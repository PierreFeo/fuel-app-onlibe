package ru.fueltracker.app.data.repository

import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.PatchField
import ru.fueltracker.app.data.remote.api.CarsApi
import ru.fueltracker.app.data.remote.apiCall
import ru.fueltracker.app.data.remote.dto.CarCreateRequest
import ru.fueltracker.app.data.remote.dto.CarDto
import ru.fueltracker.app.data.remote.dto.CarPatchRequest
import ru.fueltracker.app.data.remote.map
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.domain.model.CarInput
import ru.fueltracker.app.domain.model.FuelType
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton
import ru.fueltracker.app.data.remote.dto.FuelType as FuelTypeDto

interface CarRepository {

    /** Без архивных, старые сверху. */
    suspend fun getCars(): ApiResult<List<Car>>

    suspend fun getCar(carId: String): ApiResult<Car>

    suspend fun createCar(input: CarInput): ApiResult<Car>

    suspend fun updateCar(carId: String, input: CarInput): ApiResult<Car>

    /** Мягкое удаление: авто уходит в архив. */
    suspend fun archiveCar(carId: String): ApiResult<Unit>
}

@Singleton
class DefaultCarRepository @Inject constructor(
    private val api: CarsApi,
) : CarRepository {

    override suspend fun getCars(): ApiResult<List<Car>> =
        apiCall { api.getCars() }.map { list -> list.map { it.toDomain() } }

    override suspend fun getCar(carId: String): ApiResult<Car> =
        apiCall { api.getCar(carId) }.map { it.toDomain() }

    override suspend fun createCar(input: CarInput): ApiResult<Car> =
        apiCall {
            api.createCar(
                CarCreateRequest(
                    name = input.name,
                    plateNumber = input.plateNumber,
                    fuelType = input.fuelType.toDto(),
                    tankCapacityL = input.tankCapacityL.toApi(),
                    normLPer100km = input.normSummer.toApi(),
                    normWinterLPer100km = input.normWinter?.toApi(),
                ),
            )
        }.map { it.toDomain() }

    // Форма редактирует все поля, поэтому отправляем все; null стирает госномер и зимнюю норму
    override suspend fun updateCar(carId: String, input: CarInput): ApiResult<Car> =
        apiCall {
            api.updateCar(
                carId,
                CarPatchRequest(
                    name = input.name,
                    plateNumber = PatchField.Present(input.plateNumber),
                    fuelType = input.fuelType.toDto(),
                    tankCapacityL = input.tankCapacityL.toApi(),
                    normLPer100km = input.normSummer.toApi(),
                    normWinterLPer100km = PatchField.Present(input.normWinter?.toApi()),
                ),
            )
        }.map { it.toDomain() }

    override suspend fun archiveCar(carId: String): ApiResult<Unit> =
        apiCall { api.deleteCar(carId) }
}

private fun CarDto.toDomain() = Car(
    id = id,
    name = name,
    plateNumber = plateNumber,
    fuelType = FuelType.valueOf(fuelType.name),
    tankCapacityL = BigDecimal(tankCapacityL),
    normSummer = BigDecimal(normLPer100km),
    normWinter = normWinterLPer100km?.let(::BigDecimal),
    isArchived = isArchived,
)

private fun FuelType.toDto() = FuelTypeDto.valueOf(name)

/** Дробные значения в API — строки с точкой: «50.5», «10.068». */
private fun BigDecimal.toApi(): String = toPlainString()
