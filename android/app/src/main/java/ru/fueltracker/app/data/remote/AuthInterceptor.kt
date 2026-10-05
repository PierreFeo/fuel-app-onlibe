package ru.fueltracker.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import ru.fueltracker.app.data.local.TokenStorage
import javax.inject.Inject
import javax.inject.Singleton

/** Добавляет `Authorization: Bearer <access_token>` ко всем запросам, кроме `auth/…` (вход и токены). */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStorage: TokenStorage,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.isAuthEndpoint || request.header(AUTHORIZATION) != null) {
            return chain.proceed(request)
        }
        // Interceptor работает в фоновом потоке OkHttp, поэтому runBlocking здесь допустим
        val accessToken = runBlocking { tokenStorage.get() }?.accessToken
            ?: return chain.proceed(request)
        return chain.proceed(request.withBearer(accessToken))
    }
}

internal const val AUTHORIZATION = "Authorization"
private const val BEARER_PREFIX = "Bearer "

/** Вход и обновление токенов: Bearer им не нужен, а их 401 — не «просроченный токен». */
internal val Request.isAuthEndpoint: Boolean get() = "auth" in url.pathSegments

internal val Request.bearerToken: String?
    get() = header(AUTHORIZATION)?.takeIf { it.startsWith(BEARER_PREFIX) }?.removePrefix(BEARER_PREFIX)

internal fun Request.withBearer(accessToken: String): Request =
    newBuilder().header(AUTHORIZATION, BEARER_PREFIX + accessToken).build()
