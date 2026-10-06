package ru.fueltracker.app.data.repository

import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.api.SheetsApi
import ru.fueltracker.app.data.remote.apiCall
import ru.fueltracker.app.data.remote.dto.FuelSheetDto
import ru.fueltracker.app.data.remote.dto.RefuelingDto
import ru.fueltracker.app.data.remote.dto.SheetCalcDto
import ru.fueltracker.app.data.remote.dto.SheetCloseRequest
import ru.fueltracker.app.data.remote.dto.SheetCreateRequest
import ru.fueltracker.app.data.remote.dto.SheetPatchRequest
import ru.fueltracker.app.data.remote.map
import ru.fueltracker.app.domain.model.ConsumptionStatus
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.NewSheetInput
import ru.fueltracker.app.domain.model.SheetPrefill
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
import ru.fueltracker.app.data.remote.dto.Season as SeasonDto

interface SheetRepository {

    /** Страница ленты, новые сверху. [before] — `next_before` прошлой страницы; null — первая. */
    suspend fun getSheets(carId: String, before: String? = null): ApiResult<SheetPage>

    suspend fun getNextPrefill(carId: String): ApiResult<SheetPrefill>

    suspend fun createSheet(carId: String, input: NewSheetInput): ApiResult<FuelSheet>

    /** ☀️/❄️: норма листа заново копируется из авто. 422 `WINTER_NORM_NOT_SET` — нет зимней нормы. */
    suspend fun setSeason(sheetId: String, season: Season): ApiResult<FuelSheet>

    /** [fuelEndActualL] null — фактический остаток не вводили, сервер посчитает по норме. */
    suspend fun closeSheet(sheetId: String, odometerEndKm: Long, fuelEndActualL: BigDecimal?): ApiResult<FuelSheet>

    suspend fun reopenSheet(sheetId: String): ApiResult<FuelSheet>

    /** Только лист без заправок, иначе 422. */
    suspend fun deleteSheet(sheetId: String): ApiResult<Unit>
}

@Singleton
class DefaultSheetRepository @Inject constructor(
    private val api: SheetsApi,
) : SheetRepository {

    override suspend fun getSheets(carId: String, before: String?): ApiResult<SheetPage> =
        apiCall { api.getSheets(carId, before = before) }
            .map { page -> SheetPage(page.items.map { it.toDomain() }, page.nextBefore) }

    override suspend fun getNextPrefill(carId: String): ApiResult<SheetPrefill> =
        apiCall { api.getNextPrefill(carId) }.map {
            SheetPrefill(
                year = it.year,
                month = it.month,
                odometerStartKm = it.odometerStartKm,
                fuelStartL = BigDecimal(it.fuelStartL),
                season = Season.valueOf(it.season.name),
            )
        }

    override suspend fun createSheet(carId: String, input: NewSheetInput): ApiResult<FuelSheet> =
        apiCall {
            api.createSheet(
                carId,
                SheetCreateRequest(
                    year = input.year,
                    month = input.month,
                    odometerStartKm = input.odometerStartKm,
                    fuelStartL = input.fuelStartL.toPlainString(),
                    season = input.season.toDto(),
                ),
            )
        }.map { it.toDomain() }

    override suspend fun setSeason(sheetId: String, season: Season): ApiResult<FuelSheet> =
        apiCall { api.updateSheet(sheetId, SheetPatchRequest(season = season.toDto())) }.map { it.toDomain() }

    override suspend fun closeSheet(
        sheetId: String,
        odometerEndKm: Long,
        fuelEndActualL: BigDecimal?,
    ): ApiResult<FuelSheet> =
        apiCall {
            api.closeSheet(sheetId, SheetCloseRequest(odometerEndKm, fuelEndActualL?.toPlainString()))
        }.map { it.toDomain() }

    override suspend fun reopenSheet(sheetId: String): ApiResult<FuelSheet> =
        apiCall { api.reopenSheet(sheetId) }.map { it.toDomain() }

    override suspend fun deleteSheet(sheetId: String): ApiResult<Unit> =
        apiCall { api.deleteSheet(sheetId) }
}

private fun Season.toDto() = SeasonDto.valueOf(name)

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
