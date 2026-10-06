package ru.fueltracker.app.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.fueltracker.app.data.remote.api.AuthApi
import ru.fueltracker.app.data.remote.api.ProfileApi
import ru.fueltracker.app.data.remote.dto.LoginRequest
import ru.fueltracker.app.data.remote.dto.RefreshRequest
import ru.fueltracker.app.data.remote.dto.RequestCodeRequest
import ru.fueltracker.app.data.remote.dto.PasswordChangeRequest
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

        server.enqueueEmpty(204)
        api.changePassword(PasswordChangeRequest(currentPassword = null, newPassword = "мой-пароль"))
        assertRequest("PUT", "/me/password", """{ "current_password": null, "new_password": "мой-пароль" }""")
    }
}
