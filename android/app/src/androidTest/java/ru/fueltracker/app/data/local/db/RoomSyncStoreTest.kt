package ru.fueltracker.app.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Что синхронизация делает с Room (docs/06_BUSINESS_RULES.md, «Синхронизация», п. 3). */
@RunWith(AndroidJUnit4::class)
class RoomSyncStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: RoomSyncStore
    private val cars get() = db.carDao()
    private val sheets get() = db.sheetDao()
    private val refuelings get() = db.refuelingDao()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        store = RoomSyncStore(db)
    }

    @After
    fun tearDown() = db.close()

    private fun car(id: String, name: String = "Lada", dirty: Boolean = true, deleted: Boolean = false, seq: Long = 1) =
        CarEntity(
            id = id, name = name, plateNumber = null, fuelType = "AI95", tankCapacityL = "50.00",
            normLPer100km = "8.500", normWinterLPer100km = null, isArchived = false,
            createdAt = "2026-10-02T08:15:00Z", isDirty = dirty, isDeleted = deleted, changeSeq = seq,
        )

    private fun sheet(id: String, carId: String = "c1", dirty: Boolean = true, deleted: Boolean = false) = SheetEntity(
        id = id, carId = carId, year = 2026, month = 10, status = "OPEN", odometerStartKm = 52_340, odometerEndKm = null,
        fuelStartL = "12.00", fuelEndActualL = null, season = "SUMMER", normLPer100km = "8.500", closedAt = null,
        createdAt = "2026-10-02T08:16:00Z", isDirty = dirty, isDeleted = deleted,
    )

    private fun refueling(id: String, sheetId: String = "s1", dirty: Boolean = true, deleted: Boolean = false) =
        RefuelingEntity(
            id = id, sheetId = sheetId, refueledAt = "2026-10-05", liters = "40.00", pricePerLiter = "55.00",
            totalCost = "2200.00", odometerKm = null, station = null, paymentType = "PERSONAL", note = null,
            isDirty = dirty, isDeleted = deleted,
        )

    @Test
    fun pendingChangesAreOnlyDirtyRows() = runBlocking {
        cars.upsert(car("c1"))
        cars.upsert(car("c2", dirty = false))
        sheets.upsert(sheet("s1", deleted = true))

        val pending = store.pendingChanges()

        assertEquals(listOf("c1"), pending.cars.map { it.id })
        assertEquals(listOf("s1"), pending.sheets.map { it.id })
        assertEquals(2, store.observeDirtyCount().first())
    }

    @Test
    fun acceptedBecomeCleanTombstonesErasedRejectedStayDirty() = runBlocking {
        cars.upsert(car("c1"))
        sheets.upsert(sheet("s1"))
        sheets.upsert(sheet("s2", deleted = true))
        refuelings.upsert(refueling("r1", sheetId = "s2", deleted = true))
        refuelings.upsert(refueling("r2"))
        val sent = store.pendingChanges()

        store.applySyncResult(sent, rejected = setOf(RecordKey(SyncEntity.REFUELING, "r2")), incoming = SyncRecords())

        assertFalse(cars.get("c1")!!.isDirty)
        assertFalse(sheets.get("s1")!!.isDirty)
        assertNull(sheets.get("s2")) // удаление отправлено — строка стёрта
        assertNull(refuelings.get("r1"))
        assertTrue(refuelings.get("r2")!!.isDirty) // отклонено — уйдёт снова
        assertEquals(1, store.observeDirtyCount().first())
    }

    @Test
    fun rowChangedDuringRequestStaysDirty() = runBlocking {
        cars.upsert(car("c1", seq = 1))
        val sent = store.pendingChanges()
        cars.upsert(car("c1", name = "Kia", seq = 2)) // поменяли, пока шёл запрос

        store.applySyncResult(sent, rejected = emptySet(), incoming = SyncRecords())

        val row = cars.get("c1")!!
        assertTrue(row.isDirty)
        assertEquals("Kia", row.name)
    }

    @Test
    fun incomingRecordsAreInsertedCleanParentsFirst() = runBlocking {
        val incoming = SyncRecords(
            cars = listOf(car("c1", dirty = false)),
            sheets = listOf(sheet("s1", dirty = false)),
            refuelings = listOf(refueling("r1", dirty = false)),
        )

        store.applySyncResult(SyncRecords(), emptySet(), incoming)

        assertFalse(cars.get("c1")!!.isDirty)
        assertEquals("c1", sheets.get("s1")!!.carId)
        assertFalse(refuelings.get("r1")!!.isDirty)
        assertEquals(0, store.observeDirtyCount().first())
    }

    @Test
    fun incomingDeletedRemovesRowWithChildren() = runBlocking {
        cars.upsert(car("c1", dirty = false))
        sheets.upsert(sheet("s1", dirty = false))
        refuelings.upsert(refueling("r1", dirty = false))

        store.applySyncResult(SyncRecords(), emptySet(), SyncRecords(sheets = listOf(sheet("s1", deleted = true))))

        assertNull(sheets.get("s1"))
        assertNull(refuelings.get("r1"))
        assertEquals("c1", cars.get("c1")?.id)
    }

    @Test
    fun incomingDoesNotOverwriteLocalUnsentChange() = runBlocking {
        cars.upsert(car("c1", name = "Моё изменение"))

        store.applySyncResult(SyncRecords(), emptySet(), SyncRecords(cars = listOf(car("c1", name = "С сервера", dirty = false))))

        val row = cars.get("c1")!!
        assertEquals("Моё изменение", row.name)
        assertTrue(row.isDirty)
    }

    @Test
    fun incomingChildWithoutParentIsSkipped() = runBlocking {
        store.applySyncResult(
            SyncRecords(),
            emptySet(),
            SyncRecords(sheets = listOf(sheet("s1", carId = "нет")), refuelings = listOf(refueling("r1", sheetId = "нет"))),
        )

        assertNull(sheets.get("s1"))
        assertNull(refuelings.get("r1"))
    }
}
