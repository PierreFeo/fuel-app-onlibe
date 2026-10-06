package ru.fueltracker.app.data.remote.api

import retrofit2.http.Body
import retrofit2.http.POST
import ru.fueltracker.app.data.remote.dto.SyncRequest
import ru.fueltracker.app.data.remote.dto.SyncResponse

interface SyncApi {

    /** Отправить изменения телефона и получить изменения сервера после `cursor`. */
    @POST("sync")
    suspend fun sync(@Body body: SyncRequest): SyncResponse
}
