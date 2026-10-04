package ru.fueltracker.app.data.remote.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import ru.fueltracker.app.data.remote.dto.UpdateMeRequest
import ru.fueltracker.app.data.remote.dto.UserDto

interface ProfileApi {

    @GET("me")
    suspend fun getMe(): UserDto

    @PATCH("me")
    suspend fun updateMe(@Body body: UpdateMeRequest): UserDto
}
