package ru.fueltracker.app.data.repository

import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.api.SheetsApi
import ru.fueltracker.app.data.remote.apiCall
import ru.fueltracker.app.data.remote.dto.FuelSheetDto
import ru.fueltracker.app.data.remote.dto.RefuelingDto
import ru.fueltracker.app.data.remote.dto.SheetCalcDto
import ru.fueltracker.app.data.remote.map
import ru.fueltracker.app.domain.model.ConsumptionStatus
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.PaymentType
import ru.fueltracker.app.domain.model.Refueling
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetCalc
import ru.fueltracker.app.domain.model.SheetPage
import ru.fueltracker.app.domain.model.SheetStatus
import ru.fueltracker.app.domain.model.SheetWarning
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

interface SheetRepository {

    /** Страница ленты, новые сверху. [before] — `next_before` прошлой страницы; null — первая. */
    suspend fun getSheets(carId: String, before: String? = null): ApiResult<SheetPage>
}

@Singleton
class DefaultSheetRepository @Inject constructor(
    private val api: SheetsApi,
) : SheetRepository {

    override suspend fun getSheets(carId: String, before: String?): ApiResult<SheetPage> =
        apiCall { api.getSheets(carId, before = before) }
            .map { page -> SheetPage(page.items.map { it.toDomain() }, page.nextBefore) }
}

internal fun FuelSheetDto.toDomain() = FuelSheet(
    id = id,
    carId = carId,
    year = year,
    month = month,
    status = SheetStatus.valueOf(status.name),
    odometerStartKm = odometerStartKm,
    odometerEndKm = odometerEndKm,
    fuelStartL = BigDecimal(fuelStartL),
    fuelEndActualL = fuelEndActualL?.let(::BigDecimal),
    season = Season.valueOf(season.name),
    normLPer100km = BigDecimal(normLPer100km),
    refuelings = refuelings.map { it.toDomain() },
    calc = calc.toDomain(),
)

private fun RefuelingDto.toDomain() = Refueling(
    id = id,
    date = LocalDate.parse(refueledAt),
    liters = BigDecimal(liters),
    pricePerLiter = BigDecimal(pricePerLiter),
    totalCost = BigDecimal(totalCost),
    odometerKm = odometerKm,
    station = station,
    paymentType = PaymentType.valueOf(paymentType.name),
    note = note,
)

private fun SheetCalcDto.toDomain() = SheetCalc(
    refueledL = BigDecimal(refueledL),
    refueledCost = BigDecimal(refueledCost),
    fuelAvailableL = BigDecimal(fuelAvailableL),
    mileageKm = mileageKm,
    normConsumptionL = normConsumptionL?.let(::BigDecimal),
    fuelEndCalcL = fuelEndCalcL?.let(::BigDecimal),
    fuelEndL = fuelEndL?.let(::BigDecimal),
    actualConsumptionL = actualConsumptionL?.let(::BigDecimal),
    actualLPer100km = actualLPer100km?.let(::BigDecimal),
    consumptionStatus = consumptionStatus?.let { ConsumptionStatus.valueOf(it.name) },
    deviationL = deviationL?.let(::BigDecimal),
    costPerKm = costPerKm?.let(::BigDecimal),
    warnings = warnings.map { SheetWarning(it.code, it.message) },
)
