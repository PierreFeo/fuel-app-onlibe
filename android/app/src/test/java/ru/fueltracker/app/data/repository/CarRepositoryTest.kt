package ru.fueltracker.app.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.ApiTestServer
import ru.fueltracker.app.data.remote.api.CarsApi
import ru.fueltracker.app.data.remote.apiPath
import ru.fueltracker.app.data.remote.assertJsonEquals
import ru.fueltracker.app.data.remote.bodyText
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.domain.model.CarInput
import ru.fueltracker.app.domain.model.FuelType
import java.math.BigDecimal

class CarRepositoryTest {

    private val server = ApiTestServer()
    private val repository = DefaultCarRepository(server.api<CarsApi>())

    @After
    fun tearDown() = server.close()

    private val carJson = """
        {
          "id": "car-1", "name": "Lada Vesta", "plate_number": "А123ВС77",
          "fuel_type": "AI95", "tank_capacity_l": "50.00",
          "norm_l_per_100km": "10.068", "norm_winter_l_per_100km": null,
          "is_archived": false, "created_at": "2026-10-02T08:15:00Z"
        }
    """.trimIndent()

    private val input = CarInput(
        name = "Lada Vesta",
        plateNumber = null,
        fuelType = FuelType.AI95,
        tankCapacityL = BigDecimal("50"),
        normSummer = BigDecimal("10.068"),
        normWinter = null,
    )

    @Test
    fun `список авто переводится в доменную модель`() = runTest {
        server.enqueueJson("[$carJson]")

        val result = repository.getCars()

        assertEquals(
            ApiResult.Success(
                listOf(
                    Car(
                        id = "car-1",
                        name = "Lada Vesta",
                        plateNumber = "А123ВС77",
                        fuelType = FuelType.AI95,
                        tankCapacityL = BigDecimal("50.00"),
                        normSummer = BigDecimal("10.068"),
                        normWinter = null,
                        isArchived = false,
                    ),
                ),
            ),
            result,
        )
        val request = server.takeRequest()
        assertEquals("/cars", request.apiPath)
        assertEquals("false", request.url.queryParameter("include_archived"))
    }

    @Test
    fun `создание — числа строками, пустые поля не отправляются`() = runTest {
        server.enqueueJson(carJson, code = 201)

        repository.createCar(input)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/cars", request.apiPath)
        assertJsonEquals(
            """{ "name": "Lada Vesta", "fuel_type": "AI95", "tank_capacity_l": "50", "norm_l_per_100km": "10.068" }""",
            request.bodyText,
        )
    }

    @Test
    fun `изменение — отправляются все поля, null стирает госномер и зимнюю норму`() = runTest {
        server.enqueueJson(carJson)

        repository.updateCar("car-1", input)

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/cars/car-1", request.apiPath)
        assertJsonEquals(
            """
            {
              "name": "Lada Vesta", "plate_number": null, "fuel_type": "AI95",
              "tank_capacity_l": "50", "norm_l_per_100km": "10.068", "norm_winter_l_per_100km": null
            }
            """,
            request.bodyText,
        )
    }

    @Test
    fun `архив — DELETE, ответ 204`() = runTest {
        server.enqueueEmpty(204)

        val result = repository.archiveCar("car-1")

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/cars/car-1", request.apiPath)
    }
}
