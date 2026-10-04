package ru.fueltracker.app.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.fueltracker.app.data.remote.api.AuthApi
import ru.fueltracker.app.data.remote.api.CarsApi
import ru.fueltracker.app.data.remote.api.ProfileApi
import ru.fueltracker.app.data.remote.api.RefuelingsApi
import ru.fueltracker.app.data.remote.api.SheetsApi
import ru.fueltracker.app.data.remote.dto.CarCreateRequest
import ru.fueltracker.app.data.remote.dto.CarPatchRequest
import ru.fueltracker.app.data.remote.dto.FuelType
import ru.fueltracker.app.data.remote.dto.LoginRequest
import ru.fueltracker.app.data.remote.dto.PaymentType
import ru.fueltracker.app.data.remote.dto.RefreshRequest
import ru.fueltracker.app.data.remote.dto.RefuelingCreateRequest
import ru.fueltracker.app.data.remote.dto.RefuelingPatchRequest
import ru.fueltracker.app.data.remote.dto.RequestCodeRequest
import ru.fueltracker.app.data.remote.dto.Season
import ru.fueltracker.app.data.remote.dto.SheetCloseRequest
import ru.fueltracker.app.data.remote.dto.SheetCreateRequest
import ru.fueltracker.app.data.remote.dto.SheetPatchRequest
import ru.fueltracker.app.data.remote.dto.UpdateMeRequest
import ru.fueltracker.app.data.remote.dto.VerifyCodeRequest

/** Метод, путь, query и тело каждого запроса — как в 04_API_CONTRACT.md. */
class ApiRequestsTest {

    private val server = ApiTestServer()

    @After
    fun tearDown() = server.close()

    private fun assertRequest(method: String, path: String, body: String? = null) {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.apiPath)
        if (body != null) {
            assertJsonEquals(body, request.bodyText)
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(';'))
        }
    }

    // --- Авторизация и профиль ---

    @Test
    fun auth_requests() = runTest {
        val api = server.api<AuthApi>()

        server.enqueueJson("""{ "expires_in_sec": 300, "resend_after_sec": 60 }""")
        val sent = api.requestCode(RequestCodeRequest("+79991234567"))
        assertEquals(60, sent.resendAfterSec)
        assertRequest("POST", "/auth/request-code", """{ "phone": "+79991234567" }""")

        server.enqueueJson(Fixtures.tokens)
        api.verifyCode(VerifyCodeRequest("+79991234567", "123456"))
        assertRequest("POST", "/auth/verify-code", """{ "phone": "+79991234567", "code": "123456" }""")

        server.enqueueJson(Fixtures.tokens)
        api.login(LoginRequest("+79991234567", "k7Fm2xQp9a"))
        assertRequest("POST", "/auth/login", """{ "phone": "+79991234567", "password": "k7Fm2xQp9a" }""")

        server.enqueueJson(Fixtures.tokens)
        api.refresh(RefreshRequest("refresh.jwt"))
        assertRequest("POST", "/auth/refresh", """{ "refresh_token": "refresh.jwt" }""")

        server.enqueueEmpty(204)
        api.logout(RefreshRequest("refresh.jwt"))
        assertRequest("POST", "/auth/logout", """{ "refresh_token": "refresh.jwt" }""")
    }

    @Test
    fun profile_requests() = runTest {
        val api = server.api<ProfileApi>()

        server.enqueueJson(Fixtures.user)
        api.getMe()
        assertRequest("GET", "/me")

        server.enqueueJson(Fixtures.user)
        api.updateMe(UpdateMeRequest("Иван Петров"))
        assertRequest("PATCH", "/me", """{ "name": "Иван Петров" }""")
    }

    // --- Автомобили ---

    @Test
    fun cars_listQuery() = runTest {
        val api = server.api<CarsApi>()

        server.enqueueJson("[]")
        api.getCars()
        assertEquals("false", server.takeRequest().url.queryParameter("include_archived"))

        server.enqueueJson("[]")
        api.getCars(includeArchived = true)
        assertEquals("true", server.takeRequest().url.queryParameter("include_archived"))
    }

    @Test
    fun cars_crud() = runTest {
        val api = server.api<CarsApi>()
        val id = Fixtures.CAR_ID

        server.enqueueJson(Fixtures.car, code = 201)
        api.createCar(
            CarCreateRequest(
                name = "Lada Vesta",
                fuelType = FuelType.AI95,
                tankCapacityL = "50",
                normLPer100km = "8.5",
            ),
        )
        // Необязательные поля, оставленные null, не отправляются
        assertRequest(
            "POST",
            "/cars",
            """{ "name": "Lada Vesta", "fuel_type": "AI95", "tank_capacity_l": "50", "norm_l_per_100km": "8.5" }""",
        )

        server.enqueueJson(Fixtures.car)
        api.getCar(id)
        assertRequest("GET", "/cars/$id")

        server.enqueueEmpty(204)
        api.deleteCar(id)
        assertRequest("DELETE", "/cars/$id")
    }

    @Test
    fun carPatch_sendsOnlyGivenFields_andExplicitNull() = runTest {
        val api = server.api<CarsApi>()

        server.enqueueJson(Fixtures.car)
        api.updateCar(
            Fixtures.CAR_ID,
            CarPatchRequest(
                name = "Vesta SW",
                plateNumber = PatchField.Present(null), // стереть госномер
                normWinterLPer100km = PatchField.Present("11.684"),
            ),
        )
        assertRequest(
            "PATCH",
            "/cars/${Fixtures.CAR_ID}",
            """{ "name": "Vesta SW", "plate_number": null, "norm_winter_l_per_100km": "11.684" }""",
        )

        server.enqueueJson(Fixtures.car)
        api.updateCar(Fixtures.CAR_ID, CarPatchRequest(isArchived = false))
        assertRequest("PATCH", "/cars/${Fixtures.CAR_ID}", """{ "is_archived": false }""")
    }

    // --- Листы ---

    @Test
    fun sheets_pageQuery() = runTest {
        val api = server.api<SheetsApi>()

        server.enqueueJson("""{ "items": [], "next_before": null }""")
        api.getSheets(Fixtures.CAR_ID)
        val first = server.takeRequest()
        assertEquals("/cars/${Fixtures.CAR_ID}/sheets", first.apiPath)
        assertNull(first.url.query) // limit/before не заданы — не отправляются

        server.enqueueJson("""{ "items": [], "next_before": null }""")
        api.getSheets(Fixtures.CAR_ID, limit = 12, before = "2026-10")
        val next = server.takeRequest()
        assertEquals("12", next.url.queryParameter("limit"))
        assertEquals("2026-10", next.url.queryParameter("before"))
    }

    @Test
    fun sheets_requests() = runTest {
        val api = server.api<SheetsApi>()
        val carId = Fixtures.CAR_ID
        val id = Fixtures.SHEET_ID

        server.enqueueJson(
            """{ "year": 2026, "month": 11, "odometer_start_km": 1, "fuel_start_l": "1.00", "season": "SUMMER" }""",
        )
        api.getNextPrefill(carId)
        assertRequest("GET", "/cars/$carId/sheets/next-prefill")

        server.enqueueJson(Fixtures.openSheet, code = 201)
        api.createSheet(carId, SheetCreateRequest(2026, 10, odometerStartKm = 52340, fuelStartL = "12"))
        assertRequest(
            "POST",
            "/cars/$carId/sheets",
            """{ "year": 2026, "month": 10, "odometer_start_km": 52340, "fuel_start_l": "12" }""",
        )

        server.enqueueJson(Fixtures.openSheet, code = 201)
        api.createSheet(carId, SheetCreateRequest(2026, 10, 52340, "12", season = Season.WINTER))
        assertRequest(
            "POST",
            "/cars/$carId/sheets",
            """{ "year": 2026, "month": 10, "odometer_start_km": 52340, "fuel_start_l": "12", "season": "WINTER" }""",
        )

        server.enqueueJson(Fixtures.closedSheet)
        api.closeSheet(id, SheetCloseRequest(odometerEndKm = 53340, fuelEndActualL = "10"))
        assertRequest("POST", "/sheets/$id/close", """{ "odometer_end_km": 53340, "fuel_end_actual_l": "10" }""")

        server.enqueueJson(Fixtures.closedSheet)
        api.closeSheet(id, SheetCloseRequest(odometerEndKm = 53340))
        assertRequest("POST", "/sheets/$id/close", """{ "odometer_end_km": 53340 }""")

        server.enqueueJson(Fixtures.openSheet)
        api.reopenSheet(id)
        assertRequest("POST", "/sheets/$id/reopen")

        server.enqueueEmpty(204)
        api.deleteSheet(id)
        assertRequest("DELETE", "/sheets/$id")
    }

    @Test
    fun sheetPatch_seasonSwitch_andClearOdometerEnd() = runTest {
        val api = server.api<SheetsApi>()
        val id = Fixtures.SHEET_ID

        server.enqueueJson(Fixtures.openSheet)
        api.updateSheet(id, SheetPatchRequest(season = Season.WINTER))
        assertRequest("PATCH", "/sheets/$id", """{ "season": "WINTER" }""")

        server.enqueueJson(Fixtures.openSheet)
        api.updateSheet(
            id,
            SheetPatchRequest(odometerEndKm = PatchField.Present(null), fuelEndActualL = PatchField.Present("9.5")),
        )
        assertRequest("PATCH", "/sheets/$id", """{ "odometer_end_km": null, "fuel_end_actual_l": "9.5" }""")
    }

    // --- Заправки ---

    @Test
    fun refuelings_requests() = runTest {
        val api = server.api<RefuelingsApi>()
        val sheetId = Fixtures.SHEET_ID
        val id = Fixtures.REFUELING_ID

        server.enqueueJson(Fixtures.openSheet, code = 201)
        api.createRefueling(
            sheetId,
            RefuelingCreateRequest(refueledAt = "2026-10-05", liters = "40", pricePerLiter = "55"),
        )
        assertRequest(
            "POST",
            "/sheets/$sheetId/refuelings",
            """{ "refueled_at": "2026-10-05", "liters": "40", "price_per_liter": "55" }""",
        )

        server.enqueueJson(Fixtures.openSheet, code = 201)
        api.createRefueling(
            sheetId,
            RefuelingCreateRequest(
                refueledAt = "2026-10-05",
                liters = "40",
                pricePerLiter = "55",
                totalCost = "2100",
                odometerKm = 52610,
                station = "Лукойл",
                paymentType = PaymentType.FUEL_CARD,
                note = "скидка",
            ),
        )
        assertRequest(
            "POST",
            "/sheets/$sheetId/refuelings",
            """
            { "refueled_at": "2026-10-05", "liters": "40", "price_per_liter": "55", "total_cost": "2100",
              "odometer_km": 52610, "station": "Лукойл", "payment_type": "FUEL_CARD", "note": "скидка" }
            """,
        )

        server.enqueueJson(Fixtures.openSheet)
        api.updateRefueling(
            id,
            RefuelingPatchRequest(
                liters = "45",
                totalCost = PatchField.Present(null), // пересчитать сумму
                station = PatchField.Present(null), // стереть АЗС
            ),
        )
        assertRequest("PATCH", "/refuelings/$id", """{ "liters": "45", "total_cost": null, "station": null }""")

        server.enqueueJson(Fixtures.openSheet)
        api.deleteRefueling(id)
        assertRequest("DELETE", "/refuelings/$id")
    }
}
