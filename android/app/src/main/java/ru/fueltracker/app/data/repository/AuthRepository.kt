package ru.fueltracker.app.data.repository

import ru.fueltracker.app.data.local.AuthTokens
import ru.fueltracker.app.data.local.TokenStorage
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.api.AuthApi
import ru.fueltracker.app.data.remote.apiCall
import ru.fueltracker.app.data.remote.dto.LoginRequest
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
}

@Singleton
class DefaultAuthRepository @Inject constructor(
    private val api: AuthApi,
    private val tokenStorage: TokenStorage,
) : AuthRepository {

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
