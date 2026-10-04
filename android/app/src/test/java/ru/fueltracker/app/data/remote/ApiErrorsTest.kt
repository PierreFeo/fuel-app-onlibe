package ru.fueltracker.app.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fueltracker.app.data.remote.api.AuthApi
import ru.fueltracker.app.data.remote.api.CarsApi
import ru.fueltracker.app.data.remote.api.SheetsApi
import ru.fueltracker.app.data.remote.dto.CarPatchRequest
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.data.remote.dto.RequestCodeRequest
import ru.fueltracker.app.data.remote.dto.Season
import ru.fueltracker.app.data.remote.dto.SheetPatchRequest

/** apiCall превращает любые сбои в ApiResult.Failure с понятным ApiError. */
class ApiErrorsTest {

    private val server = ApiTestServer()

    @After
    fun tearDown() = server.close()

    private fun ApiResult<*>.httpError(): ApiError.Http {
        assertTrue("ожидалась ошибка HTTP, а пришло $this", this is ApiResult.Failure && error is ApiError.Http)
        return (this as ApiResult.Failure).error as ApiError.Http
    }

    @Test
    fun success_isWrapped() = runTest {
        server.enqueueJson("[${Fixtures.car}]")

        val result = apiCall { server.api<CarsApi>().getCars() }

        assertEquals("Lada Vesta", (result as ApiResult.Success).data.single().name)
    }

    @Test
    fun validationError_detailsByField() = runTest {
        server.enqueueJson(
            Fixtures.error(
                ErrorCodes.VALIDATION_ERROR,
                "Неверные данные запроса",
                """{ "name": "String should have at least 1 character" }""",
            ),
            code = 400,
        )

        val error = apiCall { server.api<CarsApi>().updateCar(Fixtures.CAR_ID, CarPatchRequest(name = "")) }
            .httpError()

        assertEquals(400, error.status)
        assertEquals(ErrorCodes.VALIDATION_ERROR, error.code)
        assertEquals("Неверные данные запроса", error.message)
        assertEquals("String should have at least 1 character", error.fieldError("name"))
        assertNull(error.fieldError("plate_number"))
    }

    @Test
    fun businessRule_reason() = runTest {
        server.enqueueJson(
            Fixtures.error(
                ErrorCodes.BUSINESS_RULE,
                "У автомобиля не задана зимняя норма",
                """{ "reason": "WINTER_NORM_NOT_SET" }""",
            ),
            code = 422,
        )

        val error = apiCall {
            server.api<SheetsApi>().updateSheet(Fixtures.SHEET_ID, SheetPatchRequest(season = Season.WINTER))
        }.httpError()

        assertEquals(422, error.status)
        assertEquals(ErrorCodes.BUSINESS_RULE, error.code)
        assertEquals(ErrorCodes.REASON_WINTER_NORM_NOT_SET, error.reason)
        assertNull(error.retryAfterSec)
    }

    @Test
    fun rateLimited_retryAfter() = runTest {
        server.enqueueJson(
            Fixtures.error(ErrorCodes.RATE_LIMITED, "Слишком часто", """{ "retry_after_sec": 45 }"""),
            code = 429,
        )

        val error = apiCall { server.api<AuthApi>().requestCode(RequestCodeRequest("+79991234567")) }
            .httpError()

        assertEquals(429, error.status)
        assertEquals(45, error.retryAfterSec)
    }

    @Test
    fun unauthorized_withoutDetails() = runTest {
        server.enqueueJson("""{ "error": { "code": "UNAUTHORIZED", "message": "Требуется вход" } }""", code = 401)

        val error = apiCall { server.api<CarsApi>().getCars() }.httpError()

        assertEquals(401, error.status)
        assertEquals(ErrorCodes.UNAUTHORIZED, error.code)
        assertTrue(error.details.isEmpty())
    }

    @Test
    fun nonApiErrorBody_keepsStatusOnly() = runTest {
        // Например, прокси отдал HTML-страницу «502 Bad Gateway»
        server.server.enqueue(
            mockwebserver3.MockResponse.Builder()
                .code(502)
                .addHeader("Content-Type", "text/html")
                .body("<html><body>Bad Gateway</body></html>")
                .build(),
        )

        val error = apiCall { server.api<CarsApi>().getCars() }.httpError()

        assertEquals(502, error.status)
        assertNull(error.code)
        assertNull(error.message)
    }

    @Test
    fun serverUnavailable_isNetworkError() = runTest {
        val api = server.api<CarsApi>()
        server.close() // сервер выключен — соединение не установится

        val result = apiCall { api.getCars() }

        assertTrue("ожидалась ошибка сети, а пришло $result", (result as ApiResult.Failure).error is ApiError.Network)
    }

    @Test
    fun malformedJson_isUnexpected() = runTest {
        server.enqueueJson("""{ "id": "only-id" }""")

        val result = apiCall { server.api<CarsApi>().getCar(Fixtures.CAR_ID) }

        assertTrue("ожидалась Unexpected, а пришло $result", (result as ApiResult.Failure).error is ApiError.Unexpected)
    }

    @Test
    fun unknownEnumValue_isUnexpected() = runTest {
        server.enqueueJson(Fixtures.car.replace("\"AI95\"", "\"HYDROGEN\""))

        val result = apiCall { server.api<CarsApi>().getCar(Fixtures.CAR_ID) }

        assertTrue((result as ApiResult.Failure).error is ApiError.Unexpected)
    }
}
