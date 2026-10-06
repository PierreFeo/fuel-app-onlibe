package ru.fueltracker.app.data.local.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** DAO Room на настоящей SQLite в памяти (docs/09_TESTING.md). */
@RunWith(AndroidJUnit4::class)
class DaoTest {

    private lateinit var db: AppDatabase
    private val cars get() = db.carDao()
    private val sheets get() = db.sheetDao()
    private val refuelings get() = db.refuelingDao()

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun closeDb() = db.close()

    private fun car(id: String, createdAt: String = "2026-10-02T08:00:00Z", deleted: Boolean = false) = CarEntity(
        id = id, name = "Lada $id", plateNumber = null, fuelType = "AI95", tankCapacityL = "50.00",
        normLPer100km = "10.068", normWinterLPer100km = null, isArchived = false, createdAt = createdAt,
        isDeleted = deleted,
    )

    private fun sheet(id: String, carId: String = "c1", year: Int = 2026, month: Int = 10, deleted: Boolean = false) =
        SheetEntity(
            id = id, carId = carId, year = year, month = month, status = "OPEN", odometerStartKm = 52_340,
            odometerEndKm = null, fuelStartL = "12.00", fuelEndActualL = null, season = "SUMMER",
            normLPer100km = "10.068", closedAt = null, createdAt = "2026-10-01T07:00:00Z", isDeleted = deleted,
        )

    private fun refueling(id: String, sheetId: String = "s1", deleted: Boolean = false) = RefuelingEntity(
        id = id, sheetId = sheetId, refueledAt = "2026-10-05", liters = "40.00", pricePerLiter = "55.00",
        totalCost = "2200.00", odometerKm = null, station = null, paymentType = "PERSONAL", note = null,
        isDeleted = deleted,
    )

    @Test
    fun screensDoNotSeeDeletedRowsAndCarsAreOldestFirst() = runBlocking {
        cars.upsert(car("new", createdAt = "2026-10-03T00:00:00Z"))
        cars.upsert(car("old", createdAt = "2026-10-01T00:00:00Z"))
        cars.upsert(car("gone", deleted = true))

        assertEquals(listOf("old", "new"), cars.observeAll().first().map { it.id })
        assertNull(cars.observe("gone").first())
        assertEquals("gone", cars.get("gone")?.id) // синхронизация удалённое видит
    }

    @Test
    fun feedIsNewestFirstWithRefuelings() = runBlocking {
        cars.upsert(car("c1"))
        sheets.upsert(sheet("s9", month = 9))
        sheets.upsert(sheet("s1", month = 10))
        sheets.upsert(sheet("s-old", year = 2025, month = 12))
        sheets.upsert(sheet("s-gone", month = 8, deleted = true))
        refuelings.upsert(refueling("r1"))
        refuelings.upsert(refueling("r2"))

        val feed = sheets.observeForCar("c1").first()

        assertEquals(listOf("s1", "s9", "s-old"), feed.map { it.sheet.id })
        assertEquals(setOf("r1", "r2"), feed.first().refuelings.map { it.id }.toSet())
        assertEquals("s1", sheets.latestForCar("c1")?.sheet?.id)
    }

    @Test
    fun findMonthIgnoresDeletedSheet() = runBlocking {
        cars.upsert(car("c1"))
        sheets.upsert(sheet("old", deleted = true))
        assertNull(sheets.findMonth("c1", 2026, 10))

        sheets.upsert(sheet("new")) // тот же месяц — можно, старый удалён
        assertEquals("new", sheets.findMonth("c1", 2026, 10)?.id)
    }

    @Test
    fun markSyncedOnlyWhenChangeSeqIsTheSame() = runBlocking {
        cars.upsert(car("c1").copy(changeSeq = 2))

        cars.markSynced("c1", changeSeq = 1) // запись поменяли во время синхронизации
        assertTrue(cars.get("c1")!!.isDirty)

        cars.markSynced("c1", changeSeq = 2)
        assertEquals(false, cars.get("c1")!!.isDirty)
        assertEquals(0, db.syncDao().observeDirtyCount().first())
    }

    @Test
    fun syncedTombstoneIsErasedAndCascadesToChildren() = runBlocking {
        cars.upsert(car("c1"))
        sheets.upsert(sheet("s1").copy(isDeleted = true, changeSeq = 3))
        refuelings.upsert(refueling("r1", deleted = true))

        sheets.markSynced("s1", changeSeq = 3) // у удалённой строки флаг не сбрасывается
        assertTrue(sheets.get("s1")!!.isDirty)
        sheets.deleteSyncedTombstone("s1", changeSeq = 2)
        assertEquals("s1", sheets.get("s1")?.id)

        sheets.deleteSyncedTombstone("s1", changeSeq = 3)
        assertNull(sheets.get("s1"))
        assertNull(refuelings.get("r1")) // каскад
    }

    @Test
    fun dirtyCountSumsAllTables() = runBlocking {
        cars.upsert(car("c1"))
        sheets.upsert(sheet("s1"))
        refuelings.upsert(refueling("r1"))
        refuelings.upsert(refueling("r2").copy(isDirty = false))

        assertEquals(3, db.syncDao().observeDirtyCount().first())
        assertEquals(4, db.syncDao().totalCount())
        assertEquals(listOf("r1"), refuelings.dirty().map { it.id })
    }

    @Test(expected = SQLiteConstraintException::class)
    fun sheetNeedsExistingCar() = runBlocking {
        sheets.upsert(sheet("s1", carId = "nope"))
    }
}
