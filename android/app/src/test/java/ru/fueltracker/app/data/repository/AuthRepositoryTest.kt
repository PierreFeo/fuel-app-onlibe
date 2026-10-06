package ru.fueltracker.app.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fueltracker.app.data.local.AppMode
import ru.fueltracker.app.data.local.AuthTokens
import ru.fueltracker.app.data.local.FakeAppStateStorage
import ru.fueltracker.app.data.local.FakeLocalData
import ru.fueltracker.app.data.local.LocalProfile
import ru.fueltracker.app.data.local.FakeSelectedCarStorage
import ru.fueltracker.app.data.local.FakeTokenStorage
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.ApiTestServer
import ru.fueltracker.app.data.remote.api.AuthApi
import ru.fueltracker.app.data.remote.apiPath
import ru.fueltracker.app.data.remote.assertJsonEquals
import ru.fueltracker.app.data.remote.bodyText
import ru.fueltracker.app.domain.model.CodeRequest
import ru.fueltracker.app.domain.model.LoginResult

/** Репозиторий входа поверх настоящего Retrofit и поддельного сервера. */
class AuthRepositoryTest {

    private val server = ApiTestServer()
    private val tokenStorage = FakeTokenStorage()
    private val selectedCar = FakeSelectedCarStorage(initial = "car-1")
    private val appState = FakeAppStateStorage()
    private val localData = FakeLocalData(empty = false)
    private val repository =
        DefaultAuthRepository(server.api<AuthApi>(), tokenStorage, selectedCar, appState, localData)

    // В runTest время виртуальное: таймаут выхода (5 с) сработал бы раньше настоящего запроса
    private suspend fun logoutInRealTime() = withContext(Dispatchers.IO) { repository.logout() }

    @Test
    fun `выход — refresh-токен отзывается на сервере, с телефона стёрто всё`() = runTest {
        appState.signedIn(ru.fueltracker.app.data.local.SignedInUser("u1", "+79991234567", "Иван", false))
        tokenStorage.save(AuthTokens("acc-1", "ref-1"))
        server.enqueueEmpty(204)

        logoutInRealTime()

        val request = server.takeRequest()
        assertEquals("/auth/logout", request.apiPath)
        assertJsonEquals("""{ "refresh_token": "ref-1" }""", request.bodyText)
        assertNull(tokenStorage.current)
        assertNull(selectedCar.current)
        assertEquals(1, localData.clearCalls)
        assertEquals(LocalProfile(), appState.current)
    }

    @Test
    fun `выход без связи — всё равно стёрто`() = runTest {
        tokenStorage.save(AuthTokens("acc-1", "ref-1"))
        server.close() // сервер недоступен

        logoutInRealTime()

        assertNull(tokenStorage.current)
        assertNull(selectedCar.current)
    }

    @Test
    fun `выход с ошибкой сервера — всё равно стёрто`() = runTest {
        tokenStorage.save(AuthTokens("acc-1", "ref-1"))
        server.enqueueJson("""{ "error": { "code": "INTERNAL_ERROR", "message": "Ошибка", "details": {} } }""", code = 500)

        logoutInRealTime()

        assertNull(tokenStorage.current)
    }

    @After
    fun tearDown() = server.close()

    private fun tokensJson(name: String?, isNewUser: Boolean) = """
        {
          "access_token": "acc-1", "refresh_token": "ref-1",
          "token_type": "bearer", "expires_in_sec": 900,
          "user": { "id": "u1", "phone": "+79991234567", "name": ${name?.let { "\"$it\"" } ?: "null"}, "has_password": true },
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
    fun `вход — режим аккаунт, владелец данных и пароль запомнены`() = runTest {
        server.enqueueJson(tokensJson(name = "Иван", isNewUser = false))

        repository.verifyCode("+79991234567", "123456")

        assertEquals(
            LocalProfile(mode = AppMode.ACCOUNT, ownerUserId = "u1", phone = "+79991234567", name = "Иван", hasPassword = true),
            appState.current,
        )
    }

    @Test
    fun `гость входит в новый аккаунт — имя не спрашиваем, оно уйдёт при синхронизации`() = runTest {
        appState.startGuest("Гость Иван")
        server.enqueueJson(tokensJson(name = null, isNewUser = true))

        val result = repository.verifyCode("+79991234567", "123456")

        assertEquals(ApiResult.Success(LoginResult(isNewUser = false)), result)
        assertEquals("Гость Иван", appState.current.name)
        assertTrue(appState.current.nameDirty)
        assertEquals(AppMode.ACCOUNT, appState.current.mode)
    }

    @Test
    fun `гость входит в аккаунт с именем — берём имя аккаунта`() = runTest {
        appState.startGuest("Гость")
        server.enqueueJson(tokensJson(name = "Иван", isNewUser = false))

        repository.verifyCode("+79991234567", "123456")

        assertEquals("Иван", appState.current.name)
        assertEquals(false, appState.current.nameDirty)
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
        assertNull(appState.current.mode)
    }
}
