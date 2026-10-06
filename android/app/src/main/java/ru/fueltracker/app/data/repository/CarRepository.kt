package ru.fueltracker.app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.fueltracker.app.data.local.db.AppDatabase
import ru.fueltracker.app.data.local.db.CarEntity
import ru.fueltracker.app.data.local.db.toDbAmount
import ru.fueltracker.app.data.local.db.toDbNorm
import ru.fueltracker.app.data.local.db.toDomain
import ru.fueltracker.app.data.local.db.touched
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.domain.model.CarInput
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Автомобили — в Room на телефоне; на сервер уходят при синхронизации. */
interface CarRepository {

    /** Без архивных, старые сверху. Обновляется сам при любом изменении. */
    fun observeCars(): Flow<List<Car>>

    /** Авто (в том числе архивное); null — нет или удалено. */
    fun observeCar(carId: String): Flow<Car?>

    suspend fun getCar(carId: String): Car?

    suspend fun createCar(input: CarInput): Car

    /** null — авто нет. */
    suspend fun updateCar(carId: String, input: CarInput): Car?

    /** Авто уходит в архив (не удаляется — его листы остаются). */
    suspend fun archiveCar(carId: String)
}

@Singleton
class DefaultCarRepository @Inject constructor(
    db: AppDatabase,
    private val clock: Clock,
) : CarRepository {

    private val cars = db.carDao()

    override fun observeCars(): Flow<List<Car>> =
        cars.observeAll().map { list -> list.filterNot { it.isArchived }.map { it.toDomain() } }

    override fun observeCar(carId: String): Flow<Car?> = cars.observe(carId).map { it?.toDomain() }

    override suspend fun getCar(carId: String): Car? = cars.get(carId)?.takeUnless { it.isDeleted }?.toDomain()

    override suspend fun createCar(input: CarInput): Car {
        val entity = CarEntity(
            id = UUID.randomUUID().toString(),
            name = input.name,
            plateNumber = input.plateNumber,
            fuelType = input.fuelType.name,
            tankCapacityL = input.tankCapacityL.toDbAmount(),
            normLPer100km = input.normSummer.toDbNorm(),
            normWinterLPer100km = input.normWinter?.toDbNorm(),
            isArchived = false,
            createdAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString(),
        )
        cars.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun updateCar(carId: String, input: CarInput): Car? {
        val current = cars.get(carId)?.takeUnless { it.isDeleted } ?: return null
        val updated = current.copy(
            name = input.name,
            plateNumber = input.plateNumber,
            fuelType = input.fuelType.name,
            tankCapacityL = input.tankCapacityL.toDbAmount(),
            normLPer100km = input.normSummer.toDbNorm(),
            normWinterLPer100km = input.normWinter?.toDbNorm(),
        ).touched()
        cars.upsert(updated)
        return updated.toDomain()
    }

    override suspend fun archiveCar(carId: String) {
        val current = cars.get(carId)?.takeUnless { it.isDeleted } ?: return
        cars.upsert(current.copy(isArchived = true).touched())
    }
}
