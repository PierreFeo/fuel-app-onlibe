package ru.fueltracker.app.data.repository

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
import ru.fueltracker.app.data.local.db.AppDatabase
import ru.fueltracker.app.domain.calc.SheetRuleViolation
import ru.fueltracker.app.domain.model.CarInput
import ru.fueltracker.app.domain.model.ConsumptionStatus
import ru.fueltracker.app.domain.model.FuelType
import ru.fueltracker.app.domain.model.NewSheetInput
import ru.fueltracker.app.domain.model.PaymentType
import ru.fueltracker.app.domain.model.RefuelingInput
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetPrefill
import ru.fueltracker.app.domain.model.SheetStatus
import ru.fueltracker.app.domain.model.SheetWarning
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Репозитории на настоящей Room в памяти: правила (06_BUSINESS_RULES.md), итоги SheetCalculator
 * и пометки для синхронизации (is_dirty / is_deleted / change_seq, 03_DATA_MODEL.md).
 */
@RunWith(AndroidJUnit4::class)
class LocalRepositoriesTest {

    private lateinit var db: AppDatabase
    private lateinit var cars: DefaultCarRepository
    private lateinit var sheets: DefaultSheetRepository
    private lateinit var refuelings: DefaultRefuelingRepository

    /** «Сейчас» — 6 октября 2026. */
    private val clock = Clock.fixed(Instant.parse("2026-10-06T08:15:30Z"), ZoneOffset.UTC)

    private fun d(value: String) = BigDecimal(value)

    private val carInput = CarInput("Lada Vesta", "А123ВС77", FuelType.AI95, d("50"), d("8.5"), d("11.684"))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        cars = DefaultCarRepository(db, clock)
        sheets = DefaultSheetRepository(db, clock)
        refuelings = DefaultRefuelingRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun newCar(input: CarInput = carInput) = cars.createCar(input).id

    private suspend fun newSheet(carId: String, month: Int = 10, season: Season = Season.SUMMER): String {
        assertEquals(LocalResult.Ok(Unit), sheets.createSheet(carId, NewSheetInput(2026, month, 52_340, d("12"), season)))
        return sheets.observeSheets(carId).first().first { it.month == month }.id
    }

    private fun refueling(liters: String = "40", price: String = "55", total: String? = null, day: Int = 5) =
        RefuelingInput(LocalDate.of(2026, 10, day), d(liters), d(price), total?.let(::d), null, null, PaymentType.PERSONAL, null)

    // --- Авто ---

    @Test
    fun carIsSavedDirtyWithIdAndTimeFromPhone() = runBlocking {
        val car = cars.createCar(carInput)

        val row = db.carDao().get(car.id)!!
        assertTrue(row.isDirty)
        assertEquals(1L, row.changeSeq)
        assertEquals("2026-10-06T08:15:30Z", row.createdAt)
        assertEquals("50.00", row.tankCapacityL) // формат как в API
        assertEquals("8.500", row.normLPer100km)
        assertEquals(listOf(car), cars.observeCars().first())
    }

    @Test
    fun carUpdateAndArchiveBumpChangeSeq() = runBlocking {
        val id = newCar()
        db.carDao().markSynced(id, changeSeq = 1)

        cars.updateCar(id, carInput.copy(name = "Kia Rio", normWinter = null))
        val updated = db.carDao().get(id)!!
        assertTrue(updated.isDirty)
        assertEquals(2L, updated.changeSeq)
        assertNull(updated.normWinterLPer100km)

        cars.archiveCar(id)
        assertEquals(emptyList<Any>(), cars.observeCars().first()) // архивные в списке не видны
        assertTrue(cars.observeCar(id).first()!!.isArchived)
        assertEquals(3L, db.carDao().get(id)!!.changeSeq)
        assertNull(cars.updateCar("нет-такого", carInput))
    }

    // --- Листы: создание и правила ---

    @Test
    fun createSheetCopiesNormOfSeason() = runBlocking {
        val id = newCar()
        newSheet(id, month = 9, season = Season.SUMMER)
        newSheet(id, month = 10, season = Season.WINTER)

        val feed = sheets.observeSheets(id).first()
        assertEquals(listOf(10, 9), feed.map { it.month }) // новые сверху
        assertEquals(d("11.684"), feed[0].normLPer100km)
        assertEquals(d("8.500"), feed[1].normLPer100km)
        assertEquals(SheetStatus.OPEN, feed[0].status)
    }

    @Test
    fun createSheetRules() = runBlocking {
        val id = newCar()
        val noWinter = newCar(carInput.copy(normWinter = null))
        newSheet(id, month = 10)

        fun input(month: Int, year: Int = 2026, season: Season = Season.SUMMER) =
            NewSheetInput(year, month, 0, d("0"), season)
        assertEquals(LocalResult.Rejected(SheetRuleViolation.SHEET_EXISTS), sheets.createSheet(id, input(10)))
        assertEquals(LocalResult.Rejected(SheetRuleViolation.MONTH_TOO_FAR), sheets.createSheet(id, input(12)))
        assertEquals(LocalResult.Ok(Unit), sheets.createSheet(id, input(11))) // следующий месяц — можно
        assertEquals(
            LocalResult.Rejected(SheetRuleViolation.WINTER_NORM_NOT_SET),
            sheets.createSheet(noWinter, input(10, season = Season.WINTER)),
        )
        assertEquals(LocalResult.Rejected(SheetRuleViolation.NOT_FOUND), sheets.createSheet("нет", input(10)))
    }

    // --- Итоги и подсказка ---

    @Test
    fun feedHasCalcOfExampleA() = runBlocking {
        // Пример A из 06_BUSINESS_RULES.md целиком через репозитории
        val id = newCar()
        val sheetId = newSheet(id)
        refuelings.create(sheetId, refueling())
        refuelings.create(sheetId, refueling(day = 20))
        assertEquals(LocalResult.Ok(Unit), sheets.closeSheet(sheetId, 53_340, d("10.00")))

        val sheet = sheets.observeSheets(id).first().single()
        val calc = sheet.calc
        assertEquals(SheetStatus.CLOSED, sheet.status)
        assertEquals(d("80.00"), calc.refueledL)
        assertEquals(d("4400.00"), calc.refueledCost) // сумма посчитана телефоном: 40 × 55
        assertEquals(d("92.00"), calc.fuelAvailableL)
        assertEquals(d("85.00"), calc.normConsumptionL)
        assertEquals(d("8.200"), calc.actualLPer100km)
        assertEquals(ConsumptionStatus.NORMAL, calc.consumptionStatus)
        assertEquals(d("-3.00"), calc.deviationL)
        assertEquals(d("4.40"), calc.costPerKm)
    }

    @Test
    fun odometerGapUsesPreviousSheet() = runBlocking {
        val id = newCar()
        val september = newSheet(id, month = 9)
        sheets.closeSheet(september, 52_000 + 340, d("0"))
        val octoberInput = NewSheetInput(2026, 10, 53_000, d("0"), Season.SUMMER)
        sheets.createSheet(id, octoberInput)

        val october = sheets.observeSheets(id).first().first { it.month == 10 }
        assertEquals(listOf(SheetWarning.ODOMETER_GAP), october.calc.warnings) // 53 000 ≠ 52 340
    }

    @Test
    fun prefillFromLatestSheet() = runBlocking {
        val id = newCar()
        assertEquals(SheetPrefill(2026, 10, 0, d("0.00"), Season.SUMMER), sheets.getNextPrefill(id))

        val sheetId = newSheet(id, season = Season.WINTER)
        refuelings.create(sheetId, refueling())
        sheets.closeSheet(sheetId, 53_340, d("10.00"))

        assertEquals(SheetPrefill(2026, 11, 53_340, d("10.00"), Season.WINTER), sheets.getNextPrefill(id))
    }

    // --- Закрытие, сезон, переоткрытие ---

    @Test
    fun closeSheetRules() = runBlocking {
        val sheetId = newSheet(newCar())

        assertEquals(
            LocalResult.Rejected(SheetRuleViolation.ODOMETER_END_BEFORE_START),
            sheets.closeSheet(sheetId, 52_339, d("0")),
        )
        assertEquals(
            LocalResult.Rejected(SheetRuleViolation.FUEL_END_OVER_AVAILABLE),
            sheets.closeSheet(sheetId, 52_340, d("12.01")), // доступно 12.00
        )
        assertEquals(LocalResult.Ok(Unit), sheets.closeSheet(sheetId, 52_340, d("0")))
        assertEquals(LocalResult.Rejected(SheetRuleViolation.SHEET_CLOSED), sheets.closeSheet(sheetId, 52_340, d("0")))

        val row = db.sheetDao().get(sheetId)!!
        assertEquals("2026-10-06T08:15:30Z", row.closedAt)
        assertEquals("0.00", row.fuelEndActualL)
    }

    @Test
    fun seasonAndReopen() = runBlocking {
        val sheetId = newSheet(newCar())

        assertEquals(LocalResult.Ok(d("11.684")), sheets.setSeason(sheetId, Season.WINTER))
        sheets.closeSheet(sheetId, 52_340, d("0"))
        assertEquals(LocalResult.Rejected(SheetRuleViolation.SHEET_CLOSED), sheets.setSeason(sheetId, Season.SUMMER))

        assertEquals(LocalResult.Ok(Unit), sheets.reopenSheet(sheetId))
        val row = db.sheetDao().get(sheetId)!!
        assertEquals("OPEN", row.status)
        assertNull(row.closedAt)
        assertEquals(4L, row.changeSeq) // создан, сезон, закрыт, переоткрыт
    }

    // --- Удаление: строка-«надгробие» до синхронизации ---

    @Test
    fun deleteSheetOnlyWithoutRefuelingsAndKeepsTombstone() = runBlocking {
        val id = newCar()
        val sheetId = newSheet(id)
        refuelings.create(sheetId, refueling())
        assertEquals(LocalResult.Rejected(SheetRuleViolation.SHEET_HAS_REFUELINGS), sheets.deleteSheet(sheetId))

        val refuelingId = db.refuelingDao().aliveForSheet(sheetId).single().id
        assertEquals(LocalResult.Ok(Unit), refuelings.delete(refuelingId))
        assertEquals(LocalResult.Ok(Unit), sheets.deleteSheet(sheetId))

        assertEquals(emptyList<Any>(), sheets.observeSheets(id).first())
        val tombstone = db.sheetDao().get(sheetId)!!
        assertTrue(tombstone.isDeleted)
        assertTrue(tombstone.isDirty)
        assertTrue(db.refuelingDao().get(refuelingId)!!.isDeleted)
        // Месяц освободился — можно завести заново, пока удаление не отправлено
        assertEquals(LocalResult.Ok(Unit), sheets.createSheet(id, NewSheetInput(2026, 10, 0, d("0"), Season.SUMMER)))
    }

    // --- Заправки ---

    @Test
    fun refuelingTotalIsCountedUnlessEnteredManually() = runBlocking {
        val sheetId = newSheet(newCar())
        refuelings.create(sheetId, refueling(liters = "48.5", price = "55"))
        refuelings.create(sheetId, refueling(liters = "10", price = "55", total = "500"))

        val totals = db.refuelingDao().aliveForSheet(sheetId).map { it.totalCost }.sorted()
        assertEquals(listOf("2667.50", "500.00"), totals)
    }

    @Test
    fun refuelingRules() = runBlocking {
        val sheetId = newSheet(newCar())
        assertEquals(
            LocalResult.Rejected(SheetRuleViolation.REFUELING_DATE_OUTSIDE_MONTH),
            refuelings.create(sheetId, refueling().copy(date = LocalDate.of(2026, 11, 1))),
        )
        refuelings.create(sheetId, refueling())
        val id = db.refuelingDao().aliveForSheet(sheetId).single().id

        sheets.closeSheet(sheetId, 52_340, d("0"))
        assertEquals(LocalResult.Rejected(SheetRuleViolation.SHEET_CLOSED), refuelings.create(sheetId, refueling()))
        assertEquals(LocalResult.Rejected(SheetRuleViolation.SHEET_CLOSED), refuelings.update(id, refueling()))
        assertEquals(LocalResult.Rejected(SheetRuleViolation.SHEET_CLOSED), refuelings.delete(id))
        assertEquals(LocalResult.Rejected(SheetRuleViolation.NOT_FOUND), refuelings.delete("нет"))
    }

    @Test
    fun refuelingUpdateBumpsChangeSeqAndRecalculates() = runBlocking {
        val id = newCar()
        val sheetId = newSheet(id)
        refuelings.create(sheetId, refueling())
        val refuelingId = db.refuelingDao().aliveForSheet(sheetId).single().id
        db.refuelingDao().markSynced(refuelingId, changeSeq = 1)
        assertFalse(db.refuelingDao().get(refuelingId)!!.isDirty)

        refuelings.update(refuelingId, refueling(liters = "30"))

        val row = db.refuelingDao().get(refuelingId)!!
        assertTrue(row.isDirty)
        assertEquals(2L, row.changeSeq)
        assertEquals(d("30.00"), sheets.observeSheets(id).first().single().calc.refueledL)
    }
}
