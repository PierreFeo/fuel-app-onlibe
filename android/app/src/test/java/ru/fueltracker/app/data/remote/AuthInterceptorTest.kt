package ru.fueltracker.app.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.fueltracker.app.data.local.AuthTokens
import ru.fueltracker.app.data.local.FakeTokenStorage
import ru.fueltracker.app.data.remote.api.AuthApi
import ru.fueltracker.app.data.remote.api.CarsApi
import ru.fueltracker.app.data.remote.dto.RequestCodeRequest

class AuthInterceptorTest {

    private val storage = FakeTokenStorage()
    private val server = ApiTestServer { addInterceptor(AuthInterceptor(storage)) }

    @After
    fun tearDown() = server.close()

    @Test
    fun withToken_addsBearer() = runTest {
        storage.save(AuthTokens("access.jwt", "refresh.jwt"))
        server.enqueueJson("[]")

        server.api<CarsApi>().getCars()

        assertEquals("Bearer access.jwt", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun withoutToken_noHeader() = runTest {
        server.enqueueJson("[]")

        server.api<CarsApi>().getCars()

        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun authEndpoint_noHeaderEvenWithToken() = runTest {
        storage.save(AuthTokens("access.jwt", "refresh.jwt"))
        server.enqueueJson("""{ "expires_in_sec": 300, "resend_after_sec": 60 }""")

        server.api<AuthApi>().requestCode(RequestCodeRequest("+79991234567"))

        val request = server.takeRequest()
        assertEquals("/auth/request-code", request.apiPath)
        assertNull(request.headers["Authorization"])
    }
}
