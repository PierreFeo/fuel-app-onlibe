package ru.fueltracker.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * POST /sync — docs/04_API_CONTRACT.md, «Синхронизация».
 * У полей записей нет значений по умолчанию: ApiJson не отправляет поля со значением
 * по умолчанию, а записи должны уходить на сервер целиком (null — тоже значение).
 */

@Serializable
data class SyncRequest(
    /** null — первая синхронизация на этом телефоне. */
    val cursor: Long?,
    /** null — имя на телефоне не менялось. */
    val profile: SyncProfileDto?,
    val cars: List<CarRecordDto>,
    val sheets: List<SheetRecordDto>,
    val refuelings: List<RefuelingRecordDto>,
)

@Serializable
data class SyncResponse(
    val cursor: Long,
    val profile: SyncProfileDto? = null,
    val cars: List<CarRecordDto> = emptyList(),
    val sheets: List<SheetRecordDto> = emptyList(),
    val refuelings: List<RefuelingRecordDto> = emptyList(),
    val rejected: List<RejectedDto> = emptyList(),
)

@Serializable
data class SyncProfileDto(val name: String)

@Serializable
data class CarRecordDto(
    val id: String,
    val name: String,
    @SerialName("plate_number") val plateNumber: String?,
    @SerialName("fuel_type") val fuelType: String,
    @SerialName("tank_capacity_l") val tankCapacityL: String,
    @SerialName("norm_l_per_100km") val normLPer100km: String,
    @SerialName("norm_winter_l_per_100km") val normWinterLPer100km: String?,
    @SerialName("is_archived") val isArchived: Boolean,
    @SerialName("created_at") val createdAt: String,
    val deleted: Boolean,
)

@Serializable
data class SheetRecordDto(
    val id: String,
    @SerialName("car_id") val carId: String,
    val year: Int,
    val month: Int,
    val status: String,
    @SerialName("odometer_start_km") val odometerStartKm: Long,
    @SerialName("odometer_end_km") val odometerEndKm: Long?,
    @SerialName("fuel_start_l") val fuelStartL: String,
    @SerialName("fuel_end_actual_l") val fuelEndActualL: String?,
    val season: String,
    @SerialName("norm_l_per_100km") val normLPer100km: String,
    @SerialName("closed_at") val closedAt: String?,
    @SerialName("created_at") val createdAt: String,
    val deleted: Boolean,
)

@Serializable
data class RefuelingRecordDto(
    val id: String,
    @SerialName("sheet_id") val sheetId: String,
    @SerialName("refueled_at") val refueledAt: String,
    val liters: String,
    @SerialName("price_per_liter") val pricePerLiter: String,
    @SerialName("total_cost") val totalCost: String,
    @SerialName("odometer_km") val odometerKm: Long?,
    val station: String?,
    @SerialName("payment_type") val paymentType: String,
    val note: String?,
    val deleted: Boolean,
)

/** Запись, которую сервер не принял; остальные приняты. */
@Serializable
data class RejectedDto(
    /** car | sheet | refueling */
    val entity: String,
    val id: String,
    val code: String,
    val message: String,
)
