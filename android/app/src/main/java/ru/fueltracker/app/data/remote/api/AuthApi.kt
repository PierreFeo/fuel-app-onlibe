package ru.fueltracker.app.data.remote.api

import retrofit2.http.Body
import retrofit2.http.POST
import ru.fueltracker.app.data.remote.dto.LoginRequest
import ru.fueltracker.app.data.remote.dto.RefreshRequest
import ru.fueltracker.app.data.remote.dto.RequestCodeRequest
import ru.fueltracker.app.data.remote.dto.RequestCodeResponse
import ru.fueltracker.app.data.remote.dto.TokensResponse
import ru.fueltracker.app.data.remote.dto.VerifyCodeRequest

/** Вход и токены (05_AUTH_SMS.md). Эти запросы — без заголовка Authorization. */
interface AuthApi {

    @POST("auth/request-code")
    suspend fun requestCode(@Body body: RequestCodeRequest): RequestCodeResponse

    @POST("auth/verify-code")
    suspend fun verifyCode(@Body body: VerifyCodeRequest): TokensResponse

    /** Запасной вход по паролю. */
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokensResponse

    /** Старый refresh-токен после вызова недействителен (ротация). */
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokensResponse

    /** 204; срабатывает и для уже недействительного токена. */
    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest)
}
