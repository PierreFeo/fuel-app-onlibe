package ru.fueltracker.app.data.local.db

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Записи для синхронизации: отправляемые (is_dirty) или пришедшие с сервера (is_deleted = удалены там). */
data class SyncRecords(
    val cars: List<CarEntity> = emptyList(),
    val sheets: List<SheetEntity> = emptyList(),
    val refuelings: List<RefuelingEntity> = emptyList(),
) {
    val size: Int get() = cars.size + sheets.size + refuelings.size
}

/** Вид записи — как в `rejected.entity` ответа сервера. */
enum class SyncEntity(val wireName: String) { CAR("car"), SHEET("sheet"), REFUELING("refueling") }

data class RecordKey(val entity: SyncEntity, val id: String)

/**
 * Синхронизация со стороны Room (docs/06_BUSINESS_RULES.md, «Синхронизация»).
 * Интерфейс — чтобы движок синхронизации тестировался без Room; Room-реализация — на эмуляторе.
 */
interface SyncStore {

    /** Все изменённые на телефоне записи (с их change_seq на момент отправки). */
    suspend fun pendingChanges(): SyncRecords

    /**
     * Ответ сервера — одной транзакцией:
     * - отправленные и не отклонённые → is_dirty = false, удаления — стираются,
     *   но только если change_seq не изменился за время запроса;
     * - пришедшие с сервера → вставить или заменить; удалённые на сервере — стереть.
     *   Запись, изменённая на телефоне после отправки (is_dirty), не перезаписывается.
     */
    suspend fun applySyncResult(sent: SyncRecords, rejected: Set<RecordKey>, incoming: SyncRecords)

    /** Сколько изменений ещё не отправлено. */
    fun observeDirtyCount(): Flow<Int>
}

@Singleton
class RoomSyncStore @Inject constructor(
    private val db: AppDatabase,
) : SyncStore {

    private val cars = db.carDao()
    private val sheets = db.sheetDao()
    private val refuelings = db.refuelingDao()

    override suspend fun pendingChanges(): SyncRecords = db.withTransaction {
        SyncRecords(cars.dirty(), sheets.dirty(), refuelings.dirty())
    }

    override fun observeDirtyCount(): Flow<Int> = db.syncDao().observeDirtyCount()

    override suspend fun applySyncResult(sent: SyncRecords, rejected: Set<RecordKey>, incoming: SyncRecords) {
        db.withTransaction {
            markSent(sent, rejected)
            applyIncoming(incoming)
        }
    }

    private suspend fun markSent(sent: SyncRecords, rejected: Set<RecordKey>) {
        fun accepted(entity: SyncEntity, id: String) = RecordKey(entity, id) !in rejected
        // Дети раньше родителей: стёртое удаление родителя стёрло бы каскадом и их
        for (r in sent.refuelings) {
            if (!accepted(SyncEntity.REFUELING, r.id)) continue
            if (r.isDeleted) refuelings.deleteSyncedTombstone(r.id, r.changeSeq) else refuelings.markSynced(r.id, r.changeSeq)
        }
        for (s in sent.sheets) {
            if (!accepted(SyncEntity.SHEET, s.id)) continue
            if (s.isDeleted) sheets.deleteSyncedTombstone(s.id, s.changeSeq) else sheets.markSynced(s.id, s.changeSeq)
        }
        for (c in sent.cars) {
            if (!accepted(SyncEntity.CAR, c.id)) continue
            if (c.isDeleted) cars.deleteSyncedTombstone(c.id, c.changeSeq) else cars.markSynced(c.id, c.changeSeq)
        }
    }

    /** Родители раньше детей: у листа должно быть авто, у заправки — лист (внешние ключи). */
    private suspend fun applyIncoming(incoming: SyncRecords) {
        for (car in incoming.cars) {
            val local = cars.get(car.id)
            if (local?.isDirty == true) continue
            if (car.isDeleted) {
                cars.delete(car.id)
            } else {
                cars.upsert(car.copy(isDirty = false, isDeleted = false, changeSeq = (local?.changeSeq ?: 0) + 1))
            }
        }
        for (sheet in incoming.sheets) {
            val local = sheets.get(sheet.id)
            if (local?.isDirty == true) continue
            if (sheet.isDeleted) {
                sheets.delete(sheet.id)
            } else if (cars.get(sheet.carId) != null) {
                sheets.upsert(sheet.copy(isDirty = false, isDeleted = false, changeSeq = (local?.changeSeq ?: 0) + 1))
            }
        }
        for (refueling in incoming.refuelings) {
            val local = refuelings.get(refueling.id)
            if (local?.isDirty == true) continue
            if (refueling.isDeleted) {
                refuelings.delete(refueling.id)
            } else if (sheets.get(refueling.sheetId) != null) {
                refuelings.upsert(
                    refueling.copy(isDirty = false, isDeleted = false, changeSeq = (local?.changeSeq ?: 0) + 1),
                )
            }
        }
    }
}
