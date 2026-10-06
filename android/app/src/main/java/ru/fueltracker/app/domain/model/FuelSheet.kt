package ru.fueltracker.app.domain.model

import java.math.BigDecimal
import java.time.LocalDate

enum class SheetStatus { OPEN, CLOSED }

/** Сезон листа: выбирает летнюю ☀️ или зимнюю ❄️ норму авто. */
enum class Season { SUMMER, WINTER }

/** Цвет расхода: NORMAL — зелёный, OVER — красный. */
enum class ConsumptionStatus { NORMAL, OVER }

enum class PaymentType { PERSONAL, FUEL_CARD, COMPANY }

/** ЛУТ — лист учёта топлива за месяц. [calc] считает SheetCalculator (domain/calc). */
data class FuelSheet(
    val id: String,
    val carId: String,
    val year: Int,
    val month: Int,
    val status: SheetStatus,
    val odometerStartKm: Long,
    val odometerEndKm: Long?,
    val fuelStartL: BigDecimal,
    val fuelEndActualL: BigDecimal?,
    val season: Season,
    val normLPer100km: BigDecimal,
    /** По дате по возрастанию. */
    val refuelings: List<Refueling>,
    val calc: SheetCalc,
) {
    val isClosed: Boolean get() = status == SheetStatus.CLOSED
}

data class Refueling(
    val id: String,
    val date: LocalDate,
    val liters: BigDecimal,
    val pricePerLiter: BigDecimal,
    val totalCost: BigDecimal,
    val odometerKm: Long?,
    val station: String?,
    val paymentType: PaymentType,
    val note: String?,
)

/** Вычисляемые поля листа (06_BUSINESS_RULES.md); null — не хватает данных. */
data class SheetCalc(
    val refueledL: BigDecimal,
    val refueledCost: BigDecimal,
    val fuelAvailableL: BigDecimal,
    val mileageKm: Long?,
    val normConsumptionL: BigDecimal?,
    val fuelEndCalcL: BigDecimal?,
    val fuelEndL: BigDecimal?,
    val actualConsumptionL: BigDecimal?,
    val actualLPer100km: BigDecimal?,
    val consumptionStatus: ConsumptionStatus?,
    /** > 0 — перерасход, < 0 — экономия. */
    val deviationL: BigDecimal?,
    val costPerKm: BigDecimal?,
    val warnings: List<SheetWarning>,
)

/** Предупреждение листа (06_BUSINESS_RULES.md, «Предупреждения»): не блокирует сохранение. Текст — в strings.xml. */
enum class SheetWarning { FUEL_END_NEGATIVE, FUEL_END_OVER_TANK, ODOMETER_GAP, REFUELING_ODOMETER_OUT_OF_RANGE }


/** Подсказка для нового листа (`next-prefill`): следующий месяц, пробег и остаток с прошлого листа. */
data class SheetPrefill(
    val year: Int,
    val month: Int,
    val odometerStartKm: Long,
    val fuelStartL: BigDecimal,
    val season: Season,
)

/** Поля нового листа из NewSheetDialog. */
data class NewSheetInput(
    val year: Int,
    val month: Int,
    val odometerStartKm: Long,
    val fuelStartL: BigDecimal,
    val season: Season,
)

/** Поля заправки из формы. [totalCost] null — сумму посчитает приложение (литры × цена). */
data class RefuelingInput(
    val date: LocalDate,
    val liters: BigDecimal,
    val pricePerLiter: BigDecimal,
    val totalCost: BigDecimal?,
    val odometerKm: Long?,
    val station: String?,
    val paymentType: PaymentType,
    val note: String?,
)
