package ru.fueltracker.app.data.remote.api

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import ru.fueltracker.app.data.remote.dto.CarCreateRequest
import ru.fueltracker.app.data.remote.dto.CarDto
import ru.fueltracker.app.data.remote.dto.CarPatchRequest

interface CarsApi {

    /** Старые сверху. */
    @GET("cars")
    suspend fun getCars(@Query("include_archived") includeArchived: Boolean = false): List<CarDto>

    @POST("cars")
    suspend fun createCar(@Body body: CarCreateRequest): CarDto

    @GET("cars/{car_id}")
    suspend fun getCar(@Path("car_id") carId: String): CarDto

    @PATCH("cars/{car_id}")
    suspend fun updateCar(@Path("car_id") carId: String, @Body body: CarPatchRequest): CarDto

    /** Мягкое удаление: авто уходит в архив. */
    @DELETE("cars/{car_id}")
    suspend fun deleteCar(@Path("car_id") carId: String)
}
