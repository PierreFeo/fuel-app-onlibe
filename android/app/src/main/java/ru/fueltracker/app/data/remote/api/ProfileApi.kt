package ru.fueltracker.app.data.remote.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.PUT
import ru.fueltracker.app.data.remote.dto.PasswordChangeRequest
import ru.fueltracker.app.data.remote.dto.UpdateMeRequest
import ru.fueltracker.app.data.remote.dto.UserDto

interface ProfileApi {

    @GET("me")
    suspend fun getMe(): UserDto

    @PATCH("me")
    suspend fun updateMe(@Body body: UpdateMeRequest): UserDto

    /** Задать или сменить пароль для входа без SMS; ответ 204. */
    @PUT("me/password")
    suspend fun changePassword(@Body body: PasswordChangeRequest)
}
