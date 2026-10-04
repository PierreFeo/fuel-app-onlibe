package ru.fueltracker.app.data.remote.api

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import ru.fueltracker.app.data.remote.dto.FuelSheetDto
import ru.fueltracker.app.data.remote.dto.SheetCloseRequest
import ru.fueltracker.app.data.remote.dto.SheetCreateRequest
import ru.fueltracker.app.data.remote.dto.SheetPageDto
import ru.fueltracker.app.data.remote.dto.SheetPatchRequest
import ru.fueltracker.app.data.remote.dto.SheetPrefillDto

/** Листы учёта топлива. Все ответы с листом содержат пересчитанный `calc`. */
interface SheetsApi {

    /**
     * Страница ленты, новые сверху.
     * @param limit 1..50, null — по умолчанию сервера (12)
     * @param before «2026-10» — листы строго раньше этого месяца; null — с самого нового
     */
    @GET("cars/{car_id}/sheets")
    suspend fun getSheets(
        @Path("car_id") carId: String,
        @Query("limit") limit: Int? = null,
        @Query("before") before: String? = null,
    ): SheetPageDto

    @GET("cars/{car_id}/sheets/next-prefill")
    suspend fun getNextPrefill(@Path("car_id") carId: String): SheetPrefillDto

    @POST("cars/{car_id}/sheets")
    suspend fun createSheet(@Path("car_id") carId: String, @Body body: SheetCreateRequest): FuelSheetDto

    @GET("sheets/{sheet_id}")
    suspend fun getSheet(@Path("sheet_id") sheetId: String): FuelSheetDto

    @PATCH("sheets/{sheet_id}")
    suspend fun updateSheet(@Path("sheet_id") sheetId: String, @Body body: SheetPatchRequest): FuelSheetDto

    @POST("sheets/{sheet_id}/close")
    suspend fun closeSheet(@Path("sheet_id") sheetId: String, @Body body: SheetCloseRequest): FuelSheetDto

    @POST("sheets/{sheet_id}/reopen")
    suspend fun reopenSheet(@Path("sheet_id") sheetId: String): FuelSheetDto

    /** Только лист без заправок, иначе 422. */
    @DELETE("sheets/{sheet_id}")
    suspend fun deleteSheet(@Path("sheet_id") sheetId: String)
}
