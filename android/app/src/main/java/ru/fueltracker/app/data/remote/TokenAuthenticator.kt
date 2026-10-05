package ru.fueltracker.app.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import ru.fueltracker.app.data.local.AuthTokens
import ru.fueltracker.app.data.local.TokenStorage
import ru.fueltracker.app.data.remote.api.TokenRefreshApi
import ru.fueltracker.app.data.remote.dto.RefreshRequest
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * OkHttp вызывает его, когда сервер ответил 401. Access-токен живёт 15 минут —
 * обновляем его через `/auth/refresh` и повторяем запрос один раз.
 *
 * - refresh ответил 401 — refresh-токен недействителен: стираем токены (сессия закончилась,
 *   экраны увидят `TokenStorage.tokens == null`), запрос возвращает свой 401;
 * - нет сети или сервер упал — токены не трогаем, запрос возвращает свой 401;
 * - несколько запросов получили 401 одновременно — refresh выполняется один раз,
 *   остальные повторяются с уже новым токеном.
 *
 * [refreshApi] — через Provider: он собран на том же OkHttpClient, что и сам аутентификатор.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenStorage: TokenStorage,
    private val refreshApi: Provider<TokenRefreshApi>,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (request.isAuthEndpoint) return null
        // Без токена обновлять нечего; если уже был повтор — не зацикливаемся
        val failedAccessToken = request.bearerToken ?: return null
        if (response.priorResponse != null) return null

        synchronized(lock) {
            val current = runBlocking { tokenStorage.get() } ?: return null
            if (current.accessToken != failedAccessToken) {
                // Пока ждали, другой запрос уже обновил токен
                return request.withBearer(current.accessToken)
            }
            val fresh = refresh(current) ?: return null
            return request.withBearer(fresh.accessToken)
        }
    }

    private fun refresh(current: AuthTokens): AuthTokens? {
        val response = try {
            refreshApi.get().refresh(RefreshRequest(current.refreshToken)).execute()
        } catch (_: IOException) {
            return null
        } catch (_: SerializationException) {
            return null
        }
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            if (response.code() == 401) {
                runBlocking { tokenStorage.clearIfCurrent(current.refreshToken) }
            }
            return null
        }
        val body = response.body() ?: return null
        val fresh = AuthTokens(body.accessToken, body.refreshToken)
        val saved = runBlocking { tokenStorage.replaceIfCurrent(current.refreshToken, fresh) }
        // Не сохранили — пользователь за это время вышел: повторять запрос не нужно
        return fresh.takeIf { saved }
    }
}
