package ru.fueltracker.app.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.ApiTestServer
import ru.fueltracker.app.data.remote.Fixtures
import ru.fueltracker.app.data.remote.api.RefuelingsApi
import ru.fueltracker.app.data.remote.apiPath
import ru.fueltracker.app.data.remote.assertJsonEquals
import ru.fueltracker.app.data.remote.bodyText
import ru.fueltracker.app.domain.model.PaymentType
import ru.fueltracker.app.domain.model.RefuelingInput
import java.math.BigDecimal
import java.time.LocalDate

class RefuelingRepositoryTest {

    private val server = ApiTestServer()
    private val repository = DefaultRefuelingRepository(server.api<RefuelingsApi>())

    @After
    fun tearDown() = server.close()

    private val minimal = RefuelingInput(
        date = LocalDate.of(2026, 10, 5),
        liters = BigDecimal("40"),
        pricePerLiter = BigDecimal("55.5"),
        totalCost = null,
        odometerKm = null,
        station = null,
        paymentType = PaymentType.PERSONAL,
        note = null,
    )

    @Test
    fun `создание — без суммы сервер посчитает сам, ответ — весь лист`() = runTest {
        server.enqueueJson(Fixtures.openSheet, code = 201)

        val result = repository.create(Fixtures.SHEET_ID, minimal)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/sheets/${Fixtures.SHEET_ID}/refuelings", request.apiPath)
        assertJsonEquals(
            """{ "refueled_at": "2026-10-05", "liters": "40", "price_per_liter": "55.5", "payment_type": "PERSONAL" }""",
            request.bodyText,
        )
        assertEquals(Fixtures.SHEET_ID, (result as ApiResult.Success).data.id)
    }

    @Test
    fun `создание со всеми полями`() = runTest {
        server.enqueueJson(Fixtures.openSheet, code = 201)

        repository.create(
            Fixtures.SHEET_ID,
            minimal.copy(
                totalCost = BigDecimal("2200"),
                odometerKm = 52_610,
                station = "Лукойл",
                paymentType = PaymentType.FUEL_CARD,
                note = "скидка",
            ),
        )

        assertJsonEquals(
            """
            {
              "refueled_at": "2026-10-05", "liters": "40", "price_per_liter": "55.5", "total_cost": "2200",
              "odometer_km": 52610, "station": "Лукойл", "payment_type": "FUEL_CARD", "note": "скидка"
            }
            """,
            server.takeRequest().bodyText,
        )
    }

    @Test
    fun `изменение — все поля, null стирает и пересчитывает сумму`() = runTest {
        server.enqueueJson(Fixtures.openSheet)

        repository.update(Fixtures.REFUELING_ID, minimal)

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/refuelings/${Fixtures.REFUELING_ID}", request.apiPath)
        assertJsonEquals(
            """
            {
              "refueled_at": "2026-10-05", "liters": "40", "price_per_liter": "55.5", "total_cost": null,
              "odometer_km": null, "station": null, "payment_type": "PERSONAL", "note": null
            }
            """,
            request.bodyText,
        )
    }

    @Test
    fun `удаление — ответ весь лист`() = runTest {
        server.enqueueJson(Fixtures.openSheet)

        val result = repository.delete(Fixtures.REFUELING_ID)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/refuelings/${Fixtures.REFUELING_ID}", request.apiPath)
        assertEquals(BigDecimal("92.00"), (result as ApiResult.Success).data.calc.fuelAvailableL)
    }
}
