package ru.fueltracker.app.data.remote.api

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import ru.fueltracker.app.data.remote.dto.RefreshRequest
import ru.fueltracker.app.data.remote.dto.TokensResponse

/**
 * Тот же `POST /auth/refresh`, что в [AuthApi], но синхронный — только для TokenAuthenticator.
 * suspend-запросы идут через общую очередь OkHttp (не больше 5 на сервер): если 5 запросов
 * ждут обновления токена, suspend-refresh не получил бы места в очереди — приложение зависло бы.
 * `Call.execute()` очередь не использует.
 */
interface TokenRefreshApi {

    @POST("auth/refresh")
    fun refresh(@Body body: RefreshRequest): Call<TokensResponse>
}
