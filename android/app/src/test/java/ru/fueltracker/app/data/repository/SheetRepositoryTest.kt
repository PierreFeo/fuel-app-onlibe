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
import ru.fueltracker.app.domain.model.ConsumptionStatus
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
            listOf(SheetWarning("FUEL_END_NEGATIVE", "Расчётный остаток отрицательный — проверьте пробег и заправки")),
            calc.warnings,
        )
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
