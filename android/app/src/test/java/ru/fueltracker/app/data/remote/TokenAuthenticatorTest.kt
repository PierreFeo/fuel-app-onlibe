package ru.fueltracker.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fueltracker.app.data.local.AuthTokens
import ru.fueltracker.app.data.local.FakeTokenStorage
import ru.fueltracker.app.data.remote.api.AuthApi
import ru.fueltracker.app.data.remote.api.ProfileApi
import ru.fueltracker.app.data.remote.api.TokenRefreshApi
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.data.remote.dto.VerifyCodeRequest
import ru.fueltracker.app.di.NetworkModule
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

/** Автообновление access-токена по 401 (Fixtures.tokens выдаёт access.jwt / refresh.jwt). */
class TokenAuthenticatorTest {

    private val oldTokens = AuthTokens("old.access", "old.refresh")
    private val newTokens = AuthTokens("access.jwt", "refresh.jwt")

    private val storage = FakeTokenStorage(oldTokens)
    private var refreshApi: Provider<TokenRefreshApi> = Provider { server.api<TokenRefreshApi>() }

    private val server: ApiTestServer = ApiTestServer {
        addInterceptor(AuthInterceptor(storage))
        authenticator(TokenAuthenticator(storage) { refreshApi.get() })
    }

    @After
    fun tearDown() = server.close()

    private fun enqueueUnauthorized() =
        server.enqueueJson(Fixtures.error(ErrorCodes.UNAUTHORIZED, "Требуется вход"), code = 401)

    private fun getMe() = apiCallBlocking { server.api<ProfileApi>().getMe() }

    private fun apiCallBlocking(block: suspend () -> Any) = runBlocking { apiCall { block() } }

    private fun ApiResult<*>.assertHttp(status: Int, code: String) {
        val error = (this as? ApiResult.Failure)?.error as? ApiError.Http
            ?: error("ожидалась ошибка HTTP, а пришло $this")
        assertEquals(status, error.status)
        assertEquals(code, error.code)
    }

    @Test
    fun expiredAccess_refreshesAndRetries() {
        enqueueUnauthorized()
        server.enqueueJson(Fixtures.tokens)
        server.enqueueJson(Fixtures.user)

        val result = getMe()

        assertTrue("ожидался успех, а пришло $result", result is ApiResult.Success)
        val first = server.takeRequest()
        assertEquals("/me", first.apiPath)
        assertEquals("Bearer old.access", first.headers["Authorization"])

        val refresh = server.takeRequest()
        assertEquals("/auth/refresh", refresh.apiPath)
        assertNull(refresh.headers["Authorization"])
        assertJsonEquals("""{ "refresh_token": "old.refresh" }""", refresh.bodyText)

        val retry = server.takeRequest()
        assertEquals("/me", retry.apiPath)
        assertEquals("Bearer access.jwt", retry.headers["Authorization"])

        assertEquals(newTokens, storage.current)
    }

    @Test
    fun refreshInvalid_clearsTokens() {
        enqueueUnauthorized()
        server.enqueueJson(Fixtures.error(ErrorCodes.REFRESH_INVALID, "Сессия истекла"), code = 401)

        getMe().assertHttp(401, ErrorCodes.UNAUTHORIZED)

        assertEquals(2, server.server.requestCount)
        assertNull(storage.current)
    }

    @Test
    fun refreshServerError_keepsTokens() {
        enqueueUnauthorized()
        server.enqueueJson(Fixtures.error("INTERNAL_ERROR", "Ошибка сервера"), code = 500)

        getMe().assertHttp(401, ErrorCodes.UNAUTHORIZED)

        assertEquals(2, server.server.requestCount)
        assertEquals(oldTokens, storage.current)
    }

    @Test
    fun refreshNetworkError_keepsTokens() {
        // Порт 1 закрыт — соединение не установится
        refreshApi = Provider {
            NetworkModule.createRetrofit("http://127.0.0.1:1/api/v1/", OkHttpClient(), ApiJson)
                .create(TokenRefreshApi::class.java)
        }
        enqueueUnauthorized()

        getMe().assertHttp(401, ErrorCodes.UNAUTHORIZED)

        assertEquals(oldTokens, storage.current)
    }

    @Test
    fun retryStill401_noEndlessLoop() {
        enqueueUnauthorized()
        server.enqueueJson(Fixtures.tokens)
        enqueueUnauthorized()

        getMe().assertHttp(401, ErrorCodes.UNAUTHORIZED)

        assertEquals(3, server.server.requestCount)
        assertEquals(newTokens, storage.current)
    }

    @Test
    fun noToken_noRefresh() {
        runBlocking { storage.clear() }
        enqueueUnauthorized()

        getMe().assertHttp(401, ErrorCodes.UNAUTHORIZED)

        assertEquals(1, server.server.requestCount)
    }

    @Test
    fun authEndpoint401_noRefresh() {
        server.enqueueJson(Fixtures.error(ErrorCodes.OTP_INVALID, "Неверный код"), code = 401)

        apiCallBlocking { server.api<AuthApi>().verifyCode(VerifyCodeRequest("+79991234567", "0000")) }
            .assertHttp(401, ErrorCodes.OTP_INVALID)

        assertEquals(1, server.server.requestCount)
        assertEquals(oldTokens, storage.current)
    }

    @Test
    fun alreadyRefreshedByOtherRequest_retriesWithoutRefresh() {
        runBlocking { storage.save(newTokens) }
        refreshApi = Provider { error("refresh не должен вызываться") }
        val request = Request.Builder()
            .url(server.server.url("/api/v1/me"))
            .header("Authorization", "Bearer old.access")
            .build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()

        val retry = TokenAuthenticator(storage) { refreshApi.get() }.authenticate(null, response)

        assertEquals("Bearer access.jwt", retry?.header("Authorization"))
    }

    @Test
    fun parallel401_singleRefresh() = runTest {
        val refreshCount = AtomicInteger()
        server.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.apiPath == "/auth/refresh" -> {
                    refreshCount.incrementAndGet()
                    json(200, Fixtures.tokens)
                }
                request.headers["Authorization"] == "Bearer access.jwt" -> json(200, Fixtures.user)
                else -> json(401, Fixtures.error(ErrorCodes.UNAUTHORIZED, "Требуется вход"))
            }
        }

        val results = withContext(Dispatchers.IO) {
            List(5) { async { apiCall { server.api<ProfileApi>().getMe() } } }.awaitAll()
        }

        assertTrue("все запросы должны пройти: $results", results.all { it is ApiResult.Success })
        assertEquals(1, refreshCount.get())
        assertEquals(newTokens, storage.current)
    }

    private fun json(code: Int, body: String) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()
}
