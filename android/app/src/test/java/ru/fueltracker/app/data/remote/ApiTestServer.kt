package ru.fueltracker.app.data.remote

import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import retrofit2.Retrofit
import ru.fueltracker.app.di.NetworkModule
import java.util.concurrent.TimeUnit

/**
 * Поддельный сервер для тестов сетевого слоя: тот же Retrofit и Json, что в приложении,
 * но запросы уходят на MockWebServer по адресу `…/api/v1/`.
 * [configureClient] — добавить в клиент interceptor'ы и authenticator, как в NetworkModule.
 */
class ApiTestServer(
    configureClient: OkHttpClient.Builder.() -> Unit = {},
) : AutoCloseable {

    val server = MockWebServer().apply { start() }

    val retrofit: Retrofit = NetworkModule.createRetrofit(
        baseUrl = server.url("/api/v1/").toString(),
        client = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .apply(configureClient)
            .build(),
        json = ApiJson,
    )

    inline fun <reified T> api(): T = retrofit.create(T::class.java)

    fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    fun enqueueEmpty(code: Int = 204) {
        server.enqueue(MockResponse.Builder().code(code).build())
    }

    fun takeRequest(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)
        ?: error("Запрос не дошёл до сервера")

    override fun close() = server.close()
}

/** Путь без префикса `/api/v1`. */
val RecordedRequest.apiPath: String get() = url.encodedPath.removePrefix("/api/v1")

val RecordedRequest.bodyText: String get() = body?.utf8().orEmpty()

/** Сравнение JSON без учёта пробелов и порядка ключей. */
fun assertJsonEquals(expected: String, actual: String) {
    assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))
}
