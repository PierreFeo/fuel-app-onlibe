package ru.fueltracker.app.data.remote.api

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import ru.fueltracker.app.data.remote.dto.FuelSheetDto
import ru.fueltracker.app.data.remote.dto.RefuelingCreateRequest
import ru.fueltracker.app.data.remote.dto.RefuelingPatchRequest

/** Заправки. Каждый запрос возвращает весь лист — карточка сразу обновляет итоги. */
interface RefuelingsApi {

    @POST("sheets/{sheet_id}/refuelings")
    suspend fun createRefueling(
        @Path("sheet_id") sheetId: String,
        @Body body: RefuelingCreateRequest,
    ): FuelSheetDto

    @PATCH("refuelings/{refueling_id}")
    suspend fun updateRefueling(
        @Path("refueling_id") refuelingId: String,
        @Body body: RefuelingPatchRequest,
    ): FuelSheetDto

    @DELETE("refuelings/{refueling_id}")
    suspend fun deleteRefueling(@Path("refueling_id") refuelingId: String): FuelSheetDto
}
