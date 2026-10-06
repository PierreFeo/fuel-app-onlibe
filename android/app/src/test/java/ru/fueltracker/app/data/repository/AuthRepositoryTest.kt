package ru.fueltracker.app.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fueltracker.app.data.local.AuthTokens
import ru.fueltracker.app.data.local.FakeTokenStorage
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.ApiTestServer
import ru.fueltracker.app.data.remote.api.AuthApi
import ru.fueltracker.app.data.remote.apiPath
import ru.fueltracker.app.domain.model.CodeRequest
import ru.fueltracker.app.domain.model.LoginResult

/** Репозиторий входа поверх настоящего Retrofit и поддельного сервера. */
class AuthRepositoryTest {

    private val server = ApiTestServer()
    private val tokenStorage = FakeTokenStorage()
    private val repository = DefaultAuthRepository(server.api<AuthApi>(), tokenStorage)

    @After
    fun tearDown() = server.close()

    private fun tokensJson(name: String?, isNewUser: Boolean) = """
        {
          "access_token": "acc-1", "refresh_token": "ref-1",
          "token_type": "bearer", "expires_in_sec": 900,
          "user": { "id": "u1", "phone": "+79991234567", "name": ${name?.let { "\"$it\"" } ?: "null"} },
          "is_new_user": $isNewUser
        }
    """.trimIndent()

    @Test
    fun `запрос кода возвращает таймер`() = runTest {
        server.enqueueJson("""{ "expires_in_sec": 300, "resend_after_sec": 60 }""")

        val result = repository.requestCode("+79991234567")

        assertEquals(ApiResult.Success(CodeRequest(resendAfterSec = 60)), result)
        assertEquals("/auth/request-code", server.takeRequest().apiPath)
    }

    @Test
    fun `verify-code сохраняет токены`() = runTest {
        server.enqueueJson(tokensJson(name = null, isNewUser = true))

        val result = repository.verifyCode("+79991234567", "123456")

        assertEquals(ApiResult.Success(LoginResult(isNewUser = true)), result)
        assertEquals(AuthTokens("acc-1", "ref-1"), tokenStorage.current)
    }

    @Test
    fun `login сохраняет токены`() = runTest {
        server.enqueueJson(tokensJson(name = "Иван", isNewUser = false))

        val result = repository.login("+79991234567", "k7Fm2xQp9a")

        assertEquals(ApiResult.Success(LoginResult(isNewUser = false)), result)
        assertEquals(AuthTokens("acc-1", "ref-1"), tokenStorage.current)
        assertEquals("/auth/login", server.takeRequest().apiPath)
    }

    @Test
    fun `ошибка входа — токены не сохраняются`() = runTest {
        server.enqueueJson(
            """{ "error": { "code": "OTP_INVALID", "message": "Неверный код", "details": {} } }""",
            code = 401,
        )

        val result = repository.verifyCode("+79991234567", "000001")

        assertTrue(result is ApiResult.Failure)
        val error = (result as ApiResult.Failure).error as ApiError.Http
        assertEquals("OTP_INVALID", error.code)
        assertNull(tokenStorage.current)
    }
}
