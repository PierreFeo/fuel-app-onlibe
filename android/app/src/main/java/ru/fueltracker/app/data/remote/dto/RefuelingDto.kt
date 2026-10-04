package ru.fueltracker.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.fueltracker.app.data.remote.PatchField

@Serializable
enum class PaymentType { PERSONAL, FUEL_CARD, COMPANY }

@Serializable
data class RefuelingDto(
    val id: String,
    @SerialName("sheet_id") val sheetId: String,
    /** «2026-10-05». */
    @SerialName("refueled_at") val refueledAt: String,
    val liters: String,
    @SerialName("price_per_liter") val pricePerLiter: String,
    @SerialName("total_cost") val totalCost: String,
    @SerialName("odometer_km") val odometerKm: Long?,
    val station: String?,
    @SerialName("payment_type") val paymentType: PaymentType,
    val note: String?,
)

@Serializable
data class RefuelingCreateRequest(
    @SerialName("refueled_at") val refueledAt: String,
    val liters: String,
    @SerialName("price_per_liter") val pricePerLiter: String,
    /** null — сервер посчитает литры × цена. */
    @SerialName("total_cost") val totalCost: String? = null,
    @SerialName("odometer_km") val odometerKm: Long? = null,
    val station: String? = null,
    /** null — на сервере PERSONAL. */
    @SerialName("payment_type") val paymentType: PaymentType? = null,
    val note: String? = null,
)

/**
 * PATCH /refuelings/{id}: отправляются только заданные поля.
 * `totalCost = Present(null)` — сервер пересчитает сумму (литры × цена).
 */
@Serializable
data class RefuelingPatchRequest(
    @SerialName("refueled_at") val refueledAt: String? = null,
    val liters: String? = null,
    @SerialName("price_per_liter") val pricePerLiter: String? = null,
    @SerialName("total_cost") val totalCost: PatchField<String> = PatchField.Absent,
    @SerialName("odometer_km") val odometerKm: PatchField<Long> = PatchField.Absent,
    val station: PatchField<String> = PatchField.Absent,
    @SerialName("payment_type") val paymentType: PaymentType? = null,
    val note: PatchField<String> = PatchField.Absent,
)
