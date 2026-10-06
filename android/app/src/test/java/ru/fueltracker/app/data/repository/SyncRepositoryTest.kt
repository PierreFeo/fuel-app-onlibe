package ru.fueltracker.app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fueltracker.app.data.local.AppMode
import ru.fueltracker.app.data.local.AuthTokens
import ru.fueltracker.app.data.local.FakeAppStateStorage
import ru.fueltracker.app.data.local.FakeTokenStorage
import ru.fueltracker.app.data.local.LocalProfile
import ru.fueltracker.app.data.local.SyncInfo
import ru.fueltracker.app.data.local.db.CarEntity
import ru.fueltracker.app.data.local.db.RecordKey
import ru.fueltracker.app.data.local.db.RefuelingEntity
import ru.fueltracker.app.data.local.db.SheetEntity
import ru.fueltracker.app.data.local.db.SyncEntity
import ru.fueltracker.app.data.local.db.SyncRecords
import ru.fueltracker.app.data.local.db.SyncStore
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.remote.ApiTestServer
import ru.fueltracker.app.data.remote.api.SyncApi
import ru.fueltracker.app.data.remote.apiPath
import ru.fueltracker.app.data.remote.assertJsonEquals
import ru.fueltracker.app.data.remote.bodyText
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Движок синхронизации: что уходит в `POST /sync` и что делается с ответом
 * (docs/06_BUSINESS_RULES.md, «Синхронизация»). Room заменён фейком; Room-часть —
 * в androidTest (RoomSyncStoreTest).
 */
class SyncRepositoryTest {

    private val server = ApiTestServer()
    private val appState = FakeAppStateStorage(
        LocalProfile(mode = AppMode.ACCOUNT, ownerUserId = "u1", phone = "+79991234567", name = "Иван"),
    )
    private val tokens = FakeTokenStorage(initial = AuthTokens("a", "r"))
    private val store = FakeSyncStore()
    private val clock = Clock.fixed(Instant.parse("2026-10-06T18:20:00Z"), ZoneOffset.UTC)
    private val repository = DefaultSyncRepository(server.api<SyncApi>(), store, appState, tokens, clock)

    private val car = CarEntity(
        id = "c1", name = "Lada", plateNumber = null, fuelType = "AI95", tankCapacityL = "50.00",
        normLPer100km = "8.500", normWinterLPer100km = null, isArchived = false,
        createdAt = "2026-10-02T08:15:00Z", changeSeq = 3,
    )
    private val sheet = SheetEntity(
        id = "s1", carId = "c1", year = 2026, month = 10, status = "OPEN", odometerStartKm = 52_340,
        odometerEndKm = null, fuelStartL = "12.00", fuelEndActualL = null, season = "SUMMER",
        normLPer100km = "8.500", closedAt = null, createdAt = "2026-10-02T08:16:00Z",
    )
    private val deletedRefueling = RefuelingEntity(
        id = "r1", sheetId = "s1", refueledAt = "2026-10-05", liters = "40.00", pricePerLiter = "55.00",
        totalCost = "2200.00", odometerKm = null, station = null, paymentType = "PERSONAL", note = null,
        isDeleted = true, changeSeq = 2,
    )

    @After
    fun tearDown() = server.close()

    private fun response(
        cursor: Long = 1544,
        profile: String? = null,
        cars: String = "",
        rejected: String = "",
    ) = """{ "cursor": $cursor, "profile": ${profile?.let { """{"name":"$it"}""" } ?: "null"},
            "cars": [$cars], "sheets": [], "refuelings": [], "rejected": [$rejected] }"""

    private fun carJson(id: String, name: String, deleted: Boolean = false) = """
        { "id": "$id", "name": "$name", "plate_number": null, "fuel_type": "AI92", "tank_capacity_l": "43.00",
          "norm_l_per_100km": "8.500", "norm_winter_l_per_100km": null, "is_archived": false,
          "created_at": "2026-09-01T00:00:00Z", "deleted": $deleted }"""

    @Test
    fun `гость — синхронизировать некуда, запроса нет`() = runBlocking {
        appState.clear()
        appState.startGuest("Иван")

        assertEquals(SyncResult.NotSignedIn, repository.sync())
        assertEquals(0, server.server.requestCount)
    }

    @Test
    fun `сессия истекла — войти снова, запроса нет, данные целы`() = runBlocking {
        tokens.clear()

        assertEquals(SyncResult.SessionExpired, repository.sync())
        assertEquals(0, server.server.requestCount)
        assertTrue(store.applied.isEmpty())
    }

    @Test
    fun `уходят только изменённые записи, курсор и изменённое имя`() = runBlocking {
        store.pending = SyncRecords(cars = listOf(car), sheets = listOf(sheet), refuelings = listOf(deletedRefueling))
        appState.saveSync(1520, "2026-09-01T00:00:00Z")
        appState.setName("Иван Петров")
        server.enqueueJson(response())

        repository.sync()

        val request = server.takeRequest()
        assertEquals("/sync", request.apiPath)
        assertJsonEquals(
            """
            { "cursor": 1520, "profile": { "name": "Иван Петров" },
              "cars": [{ "id": "c1", "name": "Lada", "plate_number": null, "fuel_type": "AI95",
                         "tank_capacity_l": "50.00", "norm_l_per_100km": "8.500", "norm_winter_l_per_100km": null,
                         "is_archived": false, "created_at": "2026-10-02T08:15:00Z", "deleted": false }],
              "sheets": [{ "id": "s1", "car_id": "c1", "year": 2026, "month": 10, "status": "OPEN",
                           "odometer_start_km": 52340, "odometer_end_km": null, "fuel_start_l": "12.00",
                           "fuel_end_actual_l": null, "season": "SUMMER", "norm_l_per_100km": "8.500",
                           "closed_at": null, "created_at": "2026-10-02T08:16:00Z", "deleted": false }],
              "refuelings": [{ "id": "r1", "sheet_id": "s1", "refueled_at": "2026-10-05", "liters": "40.00",
                               "price_per_liter": "55.00", "total_cost": "2200.00", "odometer_km": null,
                               "station": null, "payment_type": "PERSONAL", "note": null, "deleted": true }] }
            """,
            request.bodyText,
        )
    }

    @Test
    fun `первая синхронизация — курсор null, имя не менялось — profile null`() = runBlocking {
        server.enqueueJson(response())

        repository.sync()

        assertJsonEquals(
            """{ "cursor": null, "profile": null, "cars": [], "sheets": [], "refuelings": [] }""",
            server.takeRequest().bodyText,
        )
    }

    @Test
    fun `успех — ответ в базу, новый курсор и время, счёт отправленного и полученного`() = runBlocking {
        store.pending = SyncRecords(cars = listOf(car))
        // Сервер вернул наше же авто и чужое (с другого телефона) удалённое
        server.enqueueJson(response(cars = carJson("c1", "Lada") + "," + carJson("c9", "Kia", deleted = true)))

        val result = repository.sync()

        assertEquals(SyncResult.Success(sent = 1, received = 1, rejected = emptyList()), result)
        val (sent, rejected, incoming) = store.applied.single()
        assertEquals(store.pending, sent)
        assertTrue(rejected.isEmpty())
        assertEquals(listOf("c1" to false, "c9" to true), incoming.cars.map { it.id to it.isDeleted })
        assertEquals(SyncInfo(cursor = 1544, lastSyncAt = "2026-10-06T18:20:00Z"), appState.syncInfo.first())
    }

    @Test
    fun `отклонённые записи — в результате и не помечаются отправленными`() = runBlocking {
        store.pending = SyncRecords(sheets = listOf(sheet))
        server.enqueueJson(
            response(rejected = """{ "entity": "sheet", "id": "s1", "code": "SHEET_EXISTS",
                "message": "Лист за октябрь 2026 по этому авто уже есть" }"""),
        )

        val result = repository.sync() as SyncResult.Success

        assertEquals(0, result.sent)
        assertEquals(
            listOf(RejectedRecord(SyncEntity.SHEET, "s1", "Лист за октябрь 2026 по этому авто уже есть")),
            result.rejected,
        )
        assertEquals(setOf(RecordKey(SyncEntity.SHEET, "s1")), store.applied.single().second)
    }

    @Test
    fun `имя принято сервером — больше не помечено`() = runBlocking {
        appState.setName("Иван Петров")
        server.enqueueJson(response(profile = "Иван Петров"))

        repository.sync()

        assertEquals("Иван Петров", appState.current.name)
        assertFalse(appState.current.nameDirty)
    }

    @Test
    fun `имя поменяли на телефоне во время запроса — оно уйдёт в следующий раз`() = runBlocking {
        appState.setName("Иван Петров")
        store.onApply = { appState.setName("Иван Сидоров") }
        server.enqueueJson(response(profile = "Иван Петров"))

        repository.sync()

        assertEquals("Иван Сидоров", appState.current.name)
        assertTrue(appState.current.nameDirty)
    }

    @Test
    fun `имя сменили на другом телефоне — приходит с сервера`() = runBlocking {
        server.enqueueJson(response(profile = "Иван Иванович"))

        repository.sync()

        assertEquals("Иван Иванович", appState.current.name)
    }

    @Test
    fun `нет связи — ничего не меняется`() = runBlocking {
        store.pending = SyncRecords(cars = listOf(car))
        server.close()

        val result = repository.sync()

        assertTrue(result is SyncResult.Failure && result.error is ApiError.Network)
        assertTrue(store.applied.isEmpty())
        assertNull(appState.syncInfo.first().cursor)
    }

    @Test
    fun `ошибка сервера — Failure, курсор прежний`() = runBlocking {
        appState.saveSync(7, "2026-09-01T00:00:00Z")
        server.enqueueJson("""{ "error": { "code": "INTERNAL_ERROR", "message": "Ошибка сервера", "details": {} } }""", code = 500)

        val result = repository.sync()

        assertTrue(result is SyncResult.Failure)
        assertEquals(7L, appState.syncInfo.first().cursor)
    }

    @Test
    fun `401 после обновления токена — сессия истекла`() = runBlocking {
        server.enqueueJson("""{ "error": { "code": "UNAUTHORIZED", "message": "Требуется авторизация", "details": {} } }""", code = 401)

        assertEquals(SyncResult.SessionExpired, repository.sync())
        assertTrue(store.applied.isEmpty())
    }

    @Test
    fun `статус — несохранённые изменения, включая имя, и время последней синхронизации`() = runBlocking {
        store.dirtyCount.value = 3
        appState.setName("Иван Петров")
        appState.saveSync(1, "2026-10-06T18:20:00Z")

        assertEquals(
            SyncStatus(pendingChanges = 4, lastSyncAt = Instant.parse("2026-10-06T18:20:00Z")),
            repository.observeStatus().first(),
        )
    }
}

/** Room в памяти не нужен: запоминаем, что движок просил сделать с базой. */
private class FakeSyncStore : SyncStore {

    var pending = SyncRecords()
    val dirtyCount = MutableStateFlow(0)
    val applied = mutableListOf<Triple<SyncRecords, Set<RecordKey>, SyncRecords>>()

    /** Что «случилось на телефоне» во время запроса. */
    var onApply: suspend () -> Unit = {}

    override suspend fun pendingChanges(): SyncRecords = pending

    override suspend fun applySyncResult(sent: SyncRecords, rejected: Set<RecordKey>, incoming: SyncRecords) {
        onApply()
        applied += Triple(sent, rejected, incoming)
    }

    override fun observeDirtyCount(): Flow<Int> = dirtyCount
}
