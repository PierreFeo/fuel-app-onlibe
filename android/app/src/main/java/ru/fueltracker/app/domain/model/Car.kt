package ru.fueltracker.app.domain.model

import java.math.BigDecimal

enum class FuelType { AI92, AI95, AI98, DIESEL, GAS }

data class Car(
    val id: String,
    val name: String,
    val plateNumber: String?,
    val fuelType: FuelType,
    val tankCapacityL: BigDecimal,
    /** Летняя (основная) норма, л/100 км. */
    val normSummer: BigDecimal,
    /** Зимняя норма; null — не задана. */
    val normWinter: BigDecimal?,
    val isArchived: Boolean,
)

/** Поля формы авто — для создания и для изменения (отправляются все). */
data class CarInput(
    val name: String,
    val plateNumber: String?,
    val fuelType: FuelType,
    val tankCapacityL: BigDecimal,
    val normSummer: BigDecimal,
    val normWinter: BigDecimal?,
)
