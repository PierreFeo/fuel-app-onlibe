package ru.fueltracker.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.fueltracker.app.data.remote.PatchField

// Дробные поля — строки из API ("50.00", "10.068"), в BigDecimal переводятся при отображении

@Serializable
enum class FuelType { AI92, AI95, AI98, DIESEL, GAS }

@Serializable
data class CarDto(
    val id: String,
    val name: String,
    @SerialName("plate_number") val plateNumber: String?,
    @SerialName("fuel_type") val fuelType: FuelType,
    @SerialName("tank_capacity_l") val tankCapacityL: String,
    @SerialName("norm_l_per_100km") val normLPer100km: String,
    @SerialName("norm_winter_l_per_100km") val normWinterLPer100km: String?,
    @SerialName("is_archived") val isArchived: Boolean,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class CarCreateRequest(
    val name: String,
    @SerialName("plate_number") val plateNumber: String? = null,
    @SerialName("fuel_type") val fuelType: FuelType,
    @SerialName("tank_capacity_l") val tankCapacityL: String,
    @SerialName("norm_l_per_100km") val normLPer100km: String,
    @SerialName("norm_winter_l_per_100km") val normWinterLPer100km: String? = null,
)

/** PATCH /cars/{id}: отправляются только заданные поля. */
@Serializable
data class CarPatchRequest(
    val name: String? = null,
    @SerialName("plate_number") val plateNumber: PatchField<String> = PatchField.Absent,
    @SerialName("fuel_type") val fuelType: FuelType? = null,
    @SerialName("tank_capacity_l") val tankCapacityL: String? = null,
    @SerialName("norm_l_per_100km") val normLPer100km: String? = null,
    @SerialName("norm_winter_l_per_100km")
    val normWinterLPer100km: PatchField<String> = PatchField.Absent,
    /** false — вернуть авто из архива. */
    @SerialName("is_archived") val isArchived: Boolean? = null,
)
