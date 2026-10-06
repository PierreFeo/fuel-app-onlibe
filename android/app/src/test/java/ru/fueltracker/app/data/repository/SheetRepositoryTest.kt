package ru.fueltracker.app.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.ApiTestServer
import ru.fueltracker.app.data.remote.Fixtures
import ru.fueltracker.app.data.remote.api.SheetsApi
import ru.fueltracker.app.data.remote.apiPath
import ru.fueltracker.app.data.remote.assertJsonEquals
import ru.fueltracker.app.data.remote.bodyText
import ru.fueltracker.app.domain.model.ConsumptionStatus
import ru.fueltracker.app.domain.model.NewSheetInput
import ru.fueltracker.app.domain.model.SheetPrefill
import ru.fueltracker.app.domain.model.PaymentType
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetStatus
import ru.fueltracker.app.domain.model.SheetWarning
import java.math.BigDecimal
import java.time.LocalDate

class SheetRepositoryTest {

    private val server = ApiTestServer()
    private val repository = DefaultSheetRepository(server.api<SheetsApi>())

    @After
    fun tearDown() = server.close()

    @Test
    fun `первая страница — без before, закрытый лист переводится целиком`() = runTest {
        server.enqueueJson("""{ "items": [ ${Fixtures.closedSheet} ], "next_before": "2026-10" }""")

        val page = (repository.getSheets(Fixtures.CAR_ID) as ApiResult.Success).data

        val request = server.takeRequest()
        assertEquals("/cars/${Fixtures.CAR_ID}/sheets", request.apiPath)
        assertNull(request.url.queryParameter("before"))
        assertEquals("2026-10", page.nextBefore)

        val sheet = page.items.single()
        assertEquals(SheetStatus.CLOSED, sheet.status)
        assertEquals(Season.WINTER, sheet.season)
        assertEquals(BigDecimal("11.684"), sheet.normLPer100km)
        assertEquals(53_340L, sheet.odometerEndKm)
        assertEquals(BigDecimal("10.00"), sheet.fuelEndActualL)

        val refueling = sheet.refuelings.single()
        assertEquals(LocalDate.of(2026, 10, 5), refueling.date)
        assertEquals(BigDecimal("2200.00"), refueling.totalCost)
        assertEquals("Лукойл, Ленина 1", refueling.station)
        assertEquals(PaymentType.PERSONAL, refueling.paymentType)

        val calc = sheet.calc
        assertEquals(1000L, calc.mileageKm)
        assertEquals(BigDecimal("4.200"), calc.actualLPer100km)
        assertEquals(ConsumptionStatus.NORMAL, calc.consumptionStatus)
        assertEquals(BigDecimal("-74.84"), calc.deviationL)
        assertEquals(BigDecimal("-64.84"), calc.fuelEndCalcL)
        assertEquals(
            listOf(SheetWarning.FUEL_END_NEGATIVE),
            calc.warnings,
        )
    }

    @Test
    fun `next-prefill`() = runTest {
        server.enqueueJson("""{ "year": 2026, "month": 11, "odometer_start_km": 53340, "fuel_start_l": "10.00", "season": "WINTER" }""")

        val prefill = (repository.getNextPrefill(Fixtures.CAR_ID) as ApiResult.Success).data

        assertEquals("/cars/${Fixtures.CAR_ID}/sheets/next-prefill", server.takeRequest().apiPath)
        assertEquals(SheetPrefill(2026, 11, 53_340, BigDecimal("10.00"), Season.WINTER), prefill)
    }

    @Test
    fun `создание листа — сезон передаётся явно`() = runTest {
        server.enqueueJson(Fixtures.openSheet, code = 201)

        repository.createSheet(Fixtures.CAR_ID, NewSheetInput(2026, 10, 52_340, BigDecimal("12"), Season.SUMMER))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/cars/${Fixtures.CAR_ID}/sheets", request.apiPath)
        assertJsonEquals(
            """{ "year": 2026, "month": 10, "odometer_start_km": 52340, "fuel_start_l": "12", "season": "SUMMER" }""",
            request.bodyText,
        )
    }

    @Test
    fun `переключение сезона — PATCH только с season`() = runTest {
        server.enqueueJson(Fixtures.openSheet)

        repository.setSeason(Fixtures.SHEET_ID, Season.WINTER)

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/sheets/${Fixtures.SHEET_ID}", request.apiPath)
        assertJsonEquals("""{ "season": "WINTER" }""", request.bodyText)
    }

    @Test
    fun `закрытие — фактический остаток отправляется всегда, пустой бак как 0`() = runTest {
        server.enqueueJson(Fixtures.closedSheet)
        server.enqueueJson(Fixtures.closedSheet)

        repository.closeSheet(Fixtures.SHEET_ID, 53_340, BigDecimal.ZERO)
        repository.closeSheet(Fixtures.SHEET_ID, 53_340, BigDecimal("10.5"))

        val first = server.takeRequest()
        assertEquals("/sheets/${Fixtures.SHEET_ID}/close", first.apiPath)
        assertJsonEquals("""{ "odometer_end_km": 53340, "fuel_end_actual_l": "0" }""", first.bodyText)
        assertJsonEquals("""{ "odometer_end_km": 53340, "fuel_end_actual_l": "10.5" }""", server.takeRequest().bodyText)
    }

    @Test
    fun `переоткрытие и удаление`() = runTest {
        server.enqueueJson(Fixtures.openSheet)
        server.enqueueEmpty(204)

        val reopened = repository.reopenSheet(Fixtures.SHEET_ID)
        val deleted = repository.deleteSheet(Fixtures.SHEET_ID)

        assertEquals(SheetStatus.OPEN, (reopened as ApiResult.Success).data.status)
        assertEquals("/sheets/${Fixtures.SHEET_ID}/reopen", server.takeRequest().apiPath)
        assertEquals(ApiResult.Success(Unit), deleted)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/sheets/${Fixtures.SHEET_ID}", delete.apiPath)
    }

    @Test
    fun `следующая страница — с before, null в calc остаются null`() = runTest {
        server.enqueueJson("""{ "items": [ ${Fixtures.openSheet} ], "next_before": null }""")

        val page = (repository.getSheets(Fixtures.CAR_ID, before = "2026-10") as ApiResult.Success).data

        assertEquals("2026-10", server.takeRequest().url.queryParameter("before"))
        assertNull(page.nextBefore)
        val sheet = page.items.single()
        assertEquals(SheetStatus.OPEN, sheet.status)
        assertNull(sheet.odometerEndKm)
        assertNull(sheet.calc.mileageKm)
        assertNull(sheet.calc.actualLPer100km)
        assertNull(sheet.calc.consumptionStatus)
        assertEquals(BigDecimal("92.00"), sheet.calc.fuelAvailableL)
    }
}
