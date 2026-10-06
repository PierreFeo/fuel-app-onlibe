package ru.fueltracker.app.data.repository

import kotlinx.coroutines.withTimeoutOrNull
import ru.fueltracker.app.data.local.AuthTokens
import ru.fueltracker.app.data.local.SelectedCarStorage
import ru.fueltracker.app.data.local.TokenStorage
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.api.AuthApi
import ru.fueltracker.app.data.remote.apiCall
import ru.fueltracker.app.data.remote.dto.LoginRequest
import ru.fueltracker.app.data.remote.dto.RefreshRequest
import ru.fueltracker.app.data.remote.dto.RequestCodeRequest
import ru.fueltracker.app.data.remote.dto.TokensResponse
import ru.fueltracker.app.data.remote.dto.VerifyCodeRequest
import ru.fueltracker.app.data.remote.map
import ru.fueltracker.app.domain.model.CodeRequest
import ru.fueltracker.app.domain.model.LoginResult
import javax.inject.Inject
import javax.inject.Singleton

/** Вход по SMS-коду и по паролю. [phone] — в формате `+79991234567`. */
interface AuthRepository {

    suspend fun requestCode(phone: String): ApiResult<CodeRequest>

    /** При успехе токены уже сохранены. */
    suspend fun verifyCode(phone: String, code: String): ApiResult<LoginResult>

    /** Запасной вход. При успехе токены уже сохранены. */
    suspend fun login(phone: String, password: String): ApiResult<LoginResult>

    /**
     * Выйти: отозвать refresh-токен на сервере и стереть токены и выбранное авто.
     * Выход срабатывает всегда — даже без связи данные на телефоне стираются.
     */
    suspend fun logout()
}

@Singleton
class DefaultAuthRepository @Inject constructor(
    private val api: AuthApi,
    private val tokenStorage: TokenStorage,
    private val selectedCarStorage: SelectedCarStorage,
) : AuthRepository {

    override suspend fun logout() {
        val refreshToken = tokenStorage.get()?.refreshToken
        // Ошибку сервера или сети игнорируем: токен всё равно станет недействительным через 90 дней,
        // а пользователь должен выйти сразу
        // и не ждать таймаута OkHttp (до 30 с), если сети нет
        if (refreshToken != null) {
            withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { apiCall { api.logout(RefreshRequest(refreshToken)) } }
        }
        tokenStorage.clear()
        selectedCarStorage.clear()
    }

    private companion object {
        const val LOGOUT_TIMEOUT_MS = 5_000L
    }

    override suspend fun requestCode(phone: String): ApiResult<CodeRequest> =
        apiCall { api.requestCode(RequestCodeRequest(phone)) }
            .map { CodeRequest(resendAfterSec = it.resendAfterSec) }

    override suspend fun verifyCode(phone: String, code: String): ApiResult<LoginResult> =
        apiCall { api.verifyCode(VerifyCodeRequest(phone, code)) }.saveTokens()

    override suspend fun login(phone: String, password: String): ApiResult<LoginResult> =
        apiCall { api.login(LoginRequest(phone, password)) }.saveTokens()

    private suspend fun ApiResult<TokensResponse>.saveTokens(): ApiResult<LoginResult> {
        if (this is ApiResult.Success) {
            tokenStorage.save(AuthTokens(data.accessToken, data.refreshToken))
        }
        // is_new_user в ответе входа есть всегда; на всякий случай — «имя не заполнено»
        return map { LoginResult(isNewUser = it.isNewUser ?: it.user.name.isNullOrBlank()) }
    }
}
