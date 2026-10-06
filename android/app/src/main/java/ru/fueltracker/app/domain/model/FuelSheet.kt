package ru.fueltracker.app.domain.model

import java.math.BigDecimal
import java.time.LocalDate

enum class SheetStatus { OPEN, CLOSED }

/** Сезон листа: выбирает летнюю ☀️ или зимнюю ❄️ норму авто. */
enum class Season { SUMMER, WINTER }

/** Цвет расхода: NORMAL — зелёный, OVER — красный. */
enum class ConsumptionStatus { NORMAL, OVER }

enum class PaymentType { PERSONAL, FUEL_CARD, COMPANY }

/** ЛУТ — лист учёта топлива за месяц. [calc] считает сервер, приложение только показывает. */
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

/** Предупреждение сервера: код и готовый текст. */
data class SheetWarning(val code: String, val message: String)

/** Страница ленты; [nextBefore] — для следующей страницы, null — листов больше нет. */
data class SheetPage(
    val items: List<FuelSheet>,
    val nextBefore: String?,
)
