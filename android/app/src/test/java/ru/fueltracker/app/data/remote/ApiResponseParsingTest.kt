package ru.fueltracker.app.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fueltracker.app.data.remote.api.AuthApi
import ru.fueltracker.app.data.remote.api.CarsApi
import ru.fueltracker.app.data.remote.api.ProfileApi
import ru.fueltracker.app.data.remote.api.RefuelingsApi
import ru.fueltracker.app.data.remote.api.SheetsApi
import ru.fueltracker.app.data.remote.dto.ConsumptionStatus
import ru.fueltracker.app.data.remote.dto.FuelType
import ru.fueltracker.app.data.remote.dto.PaymentType
import ru.fueltracker.app.data.remote.dto.RefreshRequest
import ru.fueltracker.app.data.remote.dto.Season
import ru.fueltracker.app.data.remote.dto.SheetStatus
import ru.fueltracker.app.data.remote.dto.VerifyCodeRequest

/** Ответы из 04_API_CONTRACT.md разбираются в DTO без потерь. */
class ApiResponseParsingTest {

    private val server = ApiTestServer()

    @After
    fun tearDown() = server.close()

    @Test
    fun tokens_withIsNewUser() = runTest {
        server.enqueueJson(Fixtures.tokens)

        val tokens = server.api<AuthApi>().verifyCode(VerifyCodeRequest("+79991234567", "123456"))

        assertEquals("access.jwt", tokens.accessToken)
        assertEquals("refresh.jwt", tokens.refreshToken)
        assertEquals("bearer", tokens.tokenType)
        assertEquals(900, tokens.expiresInSec)
        assertEquals("+79991234567", tokens.user.phone)
        assertNull(tokens.user.name)
        assertEquals(true, tokens.isNewUser)
    }

    @Test
    fun tokens_refreshWithoutIsNewUser() = runTest {
        server.enqueueJson(
            """
            { "access_token": "a2", "refresh_token": "r2", "token_type": "bearer",
              "expires_in_sec": 900, "user": ${Fixtures.user} }
            """,
        )

        val tokens = server.api<AuthApi>().refresh(RefreshRequest("refresh.jwt"))

        assertEquals("r2", tokens.refreshToken)
        assertNull(tokens.isNewUser)
    }

    @Test
    fun unknownFields_areIgnored() = runTest {
        server.enqueueJson("""{ "id": "u1", "phone": "+79991234567", "name": "Иван", "avatar_url": "x" }""")

        val me = server.api<ProfileApi>().getMe()

        assertEquals("Иван", me.name)
    }

    @Test
    fun car_allFields() = runTest {
        server.enqueueJson("[${Fixtures.car}]")

        val car = server.api<CarsApi>().getCars().single()

        assertEquals(Fixtures.CAR_ID, car.id)
        assertEquals("Lada Vesta", car.name)
        assertEquals("А123ВС77", car.plateNumber)
        assertEquals(FuelType.AI95, car.fuelType)
        assertEquals("50.00", car.tankCapacityL)
        assertEquals("10.068", car.normLPer100km)
        assertNull(car.normWinterLPer100km)
        assertEquals(false, car.isArchived)
        assertEquals("2026-10-02T08:15:00Z", car.createdAt)
    }

    @Test
    fun closedSheet_withCalcRefuelingsAndWarnings() = runTest {
        server.enqueueJson(Fixtures.closedSheet)

        val sheet = server.api<SheetsApi>().getSheet(Fixtures.SHEET_ID)

        assertEquals(Fixtures.CAR_ID, sheet.carId)
        assertEquals(2026, sheet.year)
        assertEquals(10, sheet.month)
        assertEquals(SheetStatus.CLOSED, sheet.status)
        assertEquals(52340L, sheet.odometerStartKm)
        assertEquals(53340L, sheet.odometerEndKm)
        assertEquals("12.00", sheet.fuelStartL)
        assertEquals("10.00", sheet.fuelEndActualL)
        assertEquals(Season.WINTER, sheet.season)
        assertEquals("11.684", sheet.normLPer100km)
        assertEquals("2026-10-31T18:00:00Z", sheet.closedAt)

        val refueling = sheet.refuelings.single()
        assertEquals(Fixtures.REFUELING_ID, refueling.id)
        assertEquals("2026-10-05", refueling.refueledAt)
        assertEquals("40.00", refueling.liters)
        assertEquals("55.00", refueling.pricePerLiter)
        assertEquals("2200.00", refueling.totalCost)
        assertEquals(52610L, refueling.odometerKm)
        assertEquals("Лукойл, Ленина 1", refueling.station)
        assertEquals(PaymentType.PERSONAL, refueling.paymentType)
        assertNull(refueling.note)

        val calc = sheet.calc
        assertEquals("40.00", calc.refueledL)
        assertEquals("2200.00", calc.refueledCost)
        assertEquals("52.00", calc.fuelAvailableL)
        assertEquals(1000L, calc.mileageKm)
        assertEquals("116.84", calc.normConsumptionL)
        assertEquals("-64.84", calc.fuelEndCalcL)
        assertEquals("10.00", calc.fuelEndL)
        assertEquals("42.00", calc.actualConsumptionL)
        assertEquals("4.200", calc.actualLPer100km)
        assertEquals(ConsumptionStatus.NORMAL, calc.consumptionStatus)
        assertEquals("-74.84", calc.deviationL)
        assertEquals("2.20", calc.costPerKm)
        assertEquals("FUEL_END_NEGATIVE", calc.warnings.single().code)
        assertTrue(calc.warnings.single().message.startsWith("Расчётный остаток"))
    }

    @Test
    fun openSheet_nullCalcFields() = runTest {
        server.enqueueJson("""{ "items": [${Fixtures.openSheet}], "next_before": null }""")

        val page = server.api<SheetsApi>().getSheets(Fixtures.CAR_ID)

        assertNull(page.nextBefore)
        val sheet = page.items.single()
        assertEquals(SheetStatus.OPEN, sheet.status)
        assertNull(sheet.odometerEndKm)
        assertNull(sheet.fuelEndActualL)
        assertNull(sheet.closedAt)
        assertTrue(sheet.refuelings.isEmpty())
        assertEquals("92.00", sheet.calc.fuelAvailableL)
        assertNull(sheet.calc.mileageKm)
        assertNull(sheet.calc.actualLPer100km)
        assertNull(sheet.calc.consumptionStatus)
        assertNull(sheet.calc.costPerKm)
        assertTrue(sheet.calc.warnings.isEmpty())
    }

    @Test
    fun sheetPage_nextBefore() = runTest {
        server.enqueueJson("""{ "items": [${Fixtures.closedSheet}], "next_before": "2026-10" }""")

        val page = server.api<SheetsApi>().getSheets(Fixtures.CAR_ID, limit = 1)

        assertEquals("2026-10", page.nextBefore)
        assertEquals(1, page.items.size)
    }

    @Test
    fun prefill() = runTest {
        server.enqueueJson(
            """{ "year": 2026, "month": 11, "odometer_start_km": 53340, "fuel_start_l": "10.00", "season": "WINTER" }""",
        )

        val prefill = server.api<SheetsApi>().getNextPrefill(Fixtures.CAR_ID)

        assertEquals(2026, prefill.year)
        assertEquals(11, prefill.month)
        assertEquals(53340L, prefill.odometerStartKm)
        assertEquals("10.00", prefill.fuelStartL)
        assertEquals(Season.WINTER, prefill.season)
    }

    @Test
    fun refuelingDelete_returnsWholeSheet() = runTest {
        server.enqueueJson(Fixtures.openSheet)

        val sheet = server.api<RefuelingsApi>().deleteRefueling(Fixtures.REFUELING_ID)

        assertEquals(Fixtures.SHEET_ID, sheet.id)
    }

    @Test
    fun noContent_204() = runTest {
        server.enqueueEmpty(204)

        server.api<AuthApi>().logout(RefreshRequest("refresh.jwt")) // не бросает исключение

        assertEquals("POST", server.takeRequest().method)
    }
}
