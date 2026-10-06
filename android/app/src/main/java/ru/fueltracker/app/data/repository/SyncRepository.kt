package ru.fueltracker.app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.fueltracker.app.data.local.AppMode
import ru.fueltracker.app.data.local.AppStateStorage
import ru.fueltracker.app.data.local.TokenStorage
import ru.fueltracker.app.data.local.db.CarEntity
import ru.fueltracker.app.data.local.db.RecordKey
import ru.fueltracker.app.data.local.db.RefuelingEntity
import ru.fueltracker.app.data.local.db.SheetEntity
import ru.fueltracker.app.data.local.db.SyncEntity
import ru.fueltracker.app.data.local.db.SyncRecords
import ru.fueltracker.app.data.local.db.SyncStore
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.api.SyncApi
import ru.fueltracker.app.data.remote.apiCall
import ru.fueltracker.app.data.remote.dto.CarRecordDto
import ru.fueltracker.app.data.remote.dto.RefuelingRecordDto
import ru.fueltracker.app.data.remote.dto.SheetRecordDto
import ru.fueltracker.app.data.remote.dto.SyncProfileDto
import ru.fueltracker.app.data.remote.dto.SyncRequest
import ru.fueltracker.app.data.remote.dto.SyncResponse
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Запись, которую сервер не принял: что это и почему (текст сервера). */
data class RejectedRecord(val entity: SyncEntity?, val id: String, val message: String)

sealed interface SyncResult {
    /** [sent] — отправлено изменений, [received] — пришло новых с сервера, [rejected] — не принято. */
    data class Success(val sent: Int, val received: Int, val rejected: List<RejectedRecord>) : SyncResult

    /** Гость: синхронизировать некуда. */
    data object NotSignedIn : SyncResult

    /** Сессия истекла (refresh-токен недействителен) — нужно войти снова, данные на телефоне целы. */
    data object SessionExpired : SyncResult

    /** Нет связи или сервер ответил ошибкой — на телефоне ничего не изменилось. */
    data class Failure(val error: ApiError) : SyncResult
}

/** Для профиля: сколько не отправлено и когда была последняя синхронизация. */
data class SyncStatus(val pendingChanges: Int, val lastSyncAt: Instant?)

/**
 * Синхронизация по кнопке (docs/04_API_CONTRACT.md и docs/06_BUSINESS_RULES.md, «Синхронизация»):
 * изменённые записи → `POST /sync` → ответ сервера в Room одной транзакцией → новый курсор.
 */
interface SyncRepository {

    suspend fun sync(): SyncResult

    fun observeStatus(): Flow<SyncStatus>
}

@Singleton
class DefaultSyncRepository @Inject constructor(
    private val api: SyncApi,
    private val store: SyncStore,
    private val appState: AppStateStorage,
    private val tokenStorage: TokenStorage,
    private val clock: Clock,
) : SyncRepository {

    /** Две синхронизации одновременно (кнопка + выход) отправили бы одно и то же дважды. */
    private val mutex = Mutex()

    override fun observeStatus(): Flow<SyncStatus> =
        combine(store.observeDirtyCount(), appState.profile, appState.syncInfo) { dirty, profile, info ->
            SyncStatus(
                pendingChanges = dirty + if (profile.nameDirty) 1 else 0,
                lastSyncAt = info.lastSyncAt?.let(Instant::parse),
            )
        }

    override suspend fun sync(): SyncResult = mutex.withLock {
        val profile = appState.get()
        if (profile.mode != AppMode.ACCOUNT) return SyncResult.NotSignedIn
        if (tokenStorage.get() == null) return SyncResult.SessionExpired

        val pending = store.pendingChanges()
        val nameToSend = profile.name?.takeIf { profile.nameDirty }
        val request = SyncRequest(
            cursor = appState.getSyncInfo().cursor,
            profile = nameToSend?.let(::SyncProfileDto),
            cars = pending.cars.map { it.toDto() },
            sheets = pending.sheets.map { it.toDto() },
            refuelings = pending.refuelings.map { it.toDto() },
        )

        val response = when (val result = apiCall { api.sync(request) }) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> {
                // 401 после попытки обновить токен (TokenAuthenticator) — сессия закончилась
                val expired = (result.error as? ApiError.Http)?.status == 401 || tokenStorage.get() == null
                return if (expired) SyncResult.SessionExpired else SyncResult.Failure(result.error)
            }
        }
        return applyResponse(response, pending, nameToSend)
    }

    private suspend fun applyResponse(response: SyncResponse, pending: SyncRecords, nameToSend: String?): SyncResult {
        val rejected = response.rejected.map { r ->
            RejectedRecord(SyncEntity.entries.firstOrNull { it.wireName == r.entity }, r.id, r.message)
        }
        val rejectedKeys = rejected.mapNotNullTo(HashSet()) { r -> r.entity?.let { RecordKey(it, r.id) } }
        val incoming = SyncRecords(
            cars = response.cars.map { it.toEntity() },
            sheets = response.sheets.map { it.toEntity() },
            refuelings = response.refuelings.map { it.toEntity() },
        )
        store.applySyncResult(pending, rejectedKeys, incoming)

        // Имя: отправленное приняли; пришло другое с сервера — берём его, если на телефоне его не меняли заново
        val current = appState.get()
        val nameUnchangedSinceSend = !current.nameDirty || (nameToSend != null && current.name == nameToSend)
        if (nameUnchangedSinceSend && (nameToSend != null || response.profile != null)) {
            appState.setSyncedName(response.profile?.name ?: nameToSend)
        }

        appState.saveSync(response.cursor, Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString())

        // Свои же записи сервер присылает обратно — «получено» считаем только чужие изменения
        val sentIds = pending.cars.map { it.id } + pending.sheets.map { it.id } + pending.refuelings.map { it.id }
        val received = incoming.cars.count { it.id !in sentIds } +
            incoming.sheets.count { it.id !in sentIds } +
            incoming.refuelings.count { it.id !in sentIds }
        val sent = pending.size + (if (nameToSend != null) 1 else 0) - rejected.size
        return SyncResult.Success(sent = sent, received = received, rejected = rejected)
    }
}

// --- Room ↔ JSON синхронизации: строки чисел и дат одинаковые («50.00», «2026-10-05») ---

private fun CarEntity.toDto() = CarRecordDto(
    id, name, plateNumber, fuelType, tankCapacityL, normLPer100km, normWinterLPer100km, isArchived, createdAt, isDeleted,
)

private fun SheetEntity.toDto() = SheetRecordDto(
    id, carId, year, month, status, odometerStartKm, odometerEndKm, fuelStartL, fuelEndActualL, season,
    normLPer100km, closedAt, createdAt, isDeleted,
)

private fun RefuelingEntity.toDto() = RefuelingRecordDto(
    id, sheetId, refueledAt, liters, pricePerLiter, totalCost, odometerKm, station, paymentType, note, isDeleted,
)

/** Пришедшая запись: is_dirty / change_seq выставит SyncStore; is_deleted = удалена на сервере. */
private fun CarRecordDto.toEntity() = CarEntity(
    id, name, plateNumber, fuelType, tankCapacityL, normLPer100km, normWinterLPer100km, isArchived, createdAt,
    isDirty = false, isDeleted = deleted,
)

private fun SheetRecordDto.toEntity() = SheetEntity(
    id, carId, year, month, status, odometerStartKm, odometerEndKm, fuelStartL, fuelEndActualL, season,
    normLPer100km, closedAt, createdAt, isDirty = false, isDeleted = deleted,
)

private fun RefuelingRecordDto.toEntity() = RefuelingEntity(
    id, sheetId, refueledAt, liters, pricePerLiter, totalCost, odometerKm, station, paymentType, note,
    isDirty = false, isDeleted = deleted,
)
