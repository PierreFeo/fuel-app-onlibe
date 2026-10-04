package ru.fueltracker.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.fueltracker.app.data.remote.PatchField

@Serializable
enum class SheetStatus { OPEN, CLOSED }

@Serializable
enum class Season { SUMMER, WINTER }

@Serializable
enum class ConsumptionStatus { NORMAL, OVER }

@Serializable
data class FuelSheetDto(
    val id: String,
    @SerialName("car_id") val carId: String,
    val year: Int,
    val month: Int,
    val status: SheetStatus,
    @SerialName("odometer_start_km") val odometerStartKm: Long,
    @SerialName("odometer_end_km") val odometerEndKm: Long?,
    @SerialName("fuel_start_l") val fuelStartL: String,
    @SerialName("fuel_end_actual_l") val fuelEndActualL: String?,
    val season: Season,
    @SerialName("norm_l_per_100km") val normLPer100km: String,
    /** По дате по возрастанию. */
    val refuelings: List<RefuelingDto>,
    val calc: SheetCalcDto,
    @SerialName("closed_at") val closedAt: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Вычисляемые поля листа — считает только сервер (06_BUSINESS_RULES.md). */
@Serializable
data class SheetCalcDto(
    @SerialName("refueled_l") val refueledL: String,
    @SerialName("refueled_cost") val refueledCost: String,
    @SerialName("fuel_available_l") val fuelAvailableL: String,
    @SerialName("mileage_km") val mileageKm: Long?,
    @SerialName("norm_consumption_l") val normConsumptionL: String?,
    @SerialName("fuel_end_calc_l") val fuelEndCalcL: String?,
    @SerialName("fuel_end_l") val fuelEndL: String?,
    @SerialName("actual_consumption_l") val actualConsumptionL: String?,
    @SerialName("actual_l_per_100km") val actualLPer100km: String?,
    @SerialName("consumption_status") val consumptionStatus: ConsumptionStatus?,
    @SerialName("deviation_l") val deviationL: String?,
    @SerialName("cost_per_km") val costPerKm: String?,
    val warnings: List<WarningDto>,
)

/** Предупреждение: код (`FUEL_END_NEGATIVE` и др.) и готовый текст для показа. */
@Serializable
data class WarningDto(val code: String, val message: String)

@Serializable
data class SheetPageDto(
    /** Новые сверху. */
    val items: List<FuelSheetDto>,
    /** «2025-10» — передать в `before` за следующей страницей; null — листов больше нет. */
    @SerialName("next_before") val nextBefore: String?,
)

/** Подсказка для нового листа. */
@Serializable
data class SheetPrefillDto(
    val year: Int,
    val month: Int,
    @SerialName("odometer_start_km") val odometerStartKm: Long,
    @SerialName("fuel_start_l") val fuelStartL: String,
    val season: Season,
)

@Serializable
data class SheetCreateRequest(
    val year: Int,
    val month: Int,
    @SerialName("odometer_start_km") val odometerStartKm: Long,
    @SerialName("fuel_start_l") val fuelStartL: String,
    /** null — сервер возьмёт сезон как в next-prefill. */
    val season: Season? = null,
)

/** PATCH /sheets/{id}: отправляются только заданные поля. */
@Serializable
data class SheetPatchRequest(
    @SerialName("odometer_start_km") val odometerStartKm: Long? = null,
    @SerialName("odometer_end_km") val odometerEndKm: PatchField<Long> = PatchField.Absent,
    @SerialName("fuel_start_l") val fuelStartL: String? = null,
    @SerialName("fuel_end_actual_l") val fuelEndActualL: PatchField<String> = PatchField.Absent,
    /** Переключение ☀️/❄️ — норма листа заново копируется из авто. */
    val season: Season? = null,
)

@Serializable
data class SheetCloseRequest(
    @SerialName("odometer_end_km") val odometerEndKm: Long,
    /** null — не отправляется, остаток остаётся как был. */
    @SerialName("fuel_end_actual_l") val fuelEndActualL: String? = null,
)
