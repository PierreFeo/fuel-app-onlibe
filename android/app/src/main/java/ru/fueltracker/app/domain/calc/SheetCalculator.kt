package ru.fueltracker.app.domain.calc

import ru.fueltracker.app.domain.model.ConsumptionStatus
import ru.fueltracker.app.domain.model.SheetCalc
import ru.fueltracker.app.domain.model.SheetWarning
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Данные листа, нужные для расчёта (без id, дат и прочего). */
data class SheetData(
    val odometerStartKm: Long,
    val odometerEndKm: Long?,
    val fuelStartL: BigDecimal,
    val fuelEndActualL: BigDecimal?,
    /** Копия нормы, сохранённая в листе. */
    val normLPer100km: BigDecimal,
    val refuelings: List<RefuelingData> = emptyList(),
)

data class RefuelingData(
    val liters: BigDecimal,
    val totalCost: BigDecimal,
    val odometerKm: Long? = null,
)

/**
 * Расчёты ЛУТ — чистые функции без Android, Room и сети (docs/06_BUSINESS_RULES.md).
 *
 * Каждое поле округляется HALF_UP в конце своего вычисления: литры и деньги — до 2 знаков,
 * расход на 100 км — до 3 (как нормы: 10.068). Поля, которые зависят от других, берут их уже
 * ОКРУГЛЁННЫЕ значения — так цифры в карточке сходятся «на глаз»: доступно 40.00 − по норме
 * 25.87 = остаток 14.13. Не хватает данных — null.
 */
object SheetCalculator {

    /** Деление с запасом точности, как у Decimal в Python (28+ значащих цифр), потом — округление поля. */
    private val DIVISION = MathContext.DECIMAL128
    private val HUNDRED = BigDecimal(100)

    /** До 2 знаков по правилам школьной математики (0.005 → 0.01) — литры и деньги. */
    fun round2(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)

    /** До 3 знаков (0.0005 → 0.001) — расход на 100 км, как у норм. */
    fun round3(value: BigDecimal): BigDecimal = value.setScale(3, RoundingMode.HALF_UP)

    /** Сумма заправки, если её не ввели вручную: литры × цена до копеек (правило 9). */
    fun totalCost(liters: BigDecimal, pricePerLiter: BigDecimal): BigDecimal = round2(liters * pricePerLiter)

    /**
     * Все вычисляемые поля листа.
     * [prevOdometerEndKm] — пробег на конец предыдущего листа этого авто (null — листа нет или пробег
     * на конец там не введён); нужен только для предупреждения ODOMETER_GAP.
     */
    fun calculate(sheet: SheetData, tankCapacityL: BigDecimal, prevOdometerEndKm: Long? = null): SheetCalc {
        val refueledL = round2(sheet.refuelings.sumOf { it.liters })
        val refueledCost = round2(sheet.refuelings.sumOf { it.totalCost })
        val fuelAvailableL = round2(sheet.fuelStartL + refueledL)

        val mileageKm = sheet.odometerEndKm?.let { it - sheet.odometerStartKm }
        // × норму / 100 — сдвигом запятой: точно, без округления посередине.
        val normConsumptionL = mileageKm?.let { round2((BigDecimal.valueOf(it) * sheet.normLPer100km).movePointLeft(2)) }
        val fuelEndCalcL = normConsumptionL?.let { round2(fuelAvailableL - it) }
        val fuelEndL = sheet.fuelEndActualL?.let(::round2) ?: fuelEndCalcL

        var actualConsumptionL: BigDecimal? = null
        var actualLPer100km: BigDecimal? = null
        var consumptionStatus: ConsumptionStatus? = null
        var deviationL: BigDecimal? = null
        if (mileageKm != null && normConsumptionL != null && sheet.fuelEndActualL != null) {
            val actual = round2(fuelAvailableL - sheet.fuelEndActualL)
            actualConsumptionL = actual
            deviationL = round2(actual - normConsumptionL)
            if (mileageKm > 0) {
                val perHundred = round3((actual * HUNDRED).divide(BigDecimal.valueOf(mileageKm), DIVISION))
                actualLPer100km = perHundred
                // Сравниваем то же число с 3 знаками, что видит пользователь: ровно по норме — зелёный.
                consumptionStatus =
                    if (perHundred <= sheet.normLPer100km) ConsumptionStatus.NORMAL else ConsumptionStatus.OVER
            }
        }

        val costPerKm = mileageKm?.takeIf { it > 0 }?.let {
            round2(refueledCost.divide(BigDecimal.valueOf(it), DIVISION))
        }

        return SheetCalc(
            refueledL = refueledL,
            refueledCost = refueledCost,
            fuelAvailableL = fuelAvailableL,
            mileageKm = mileageKm,
            normConsumptionL = normConsumptionL,
            fuelEndCalcL = fuelEndCalcL,
            fuelEndL = fuelEndL,
            actualConsumptionL = actualConsumptionL,
            actualLPer100km = actualLPer100km,
            consumptionStatus = consumptionStatus,
            deviationL = deviationL,
            costPerKm = costPerKm,
            warnings = warnings(sheet, tankCapacityL, prevOdometerEndKm, fuelEndCalcL, fuelEndL),
        )
    }

    /** Каждый вид предупреждения — не больше одного раза, в порядке таблицы из 06. */
    private fun warnings(
        sheet: SheetData,
        tankCapacityL: BigDecimal,
        prevOdometerEndKm: Long?,
        fuelEndCalcL: BigDecimal?,
        fuelEndL: BigDecimal?,
    ): List<SheetWarning> = buildList {
        val start = sheet.odometerStartKm
        val end = sheet.odometerEndKm
        if (fuelEndCalcL != null && fuelEndCalcL.signum() < 0) add(SheetWarning.FUEL_END_NEGATIVE)
        if (fuelEndL != null && fuelEndL > tankCapacityL) add(SheetWarning.FUEL_END_OVER_TANK)
        if (prevOdometerEndKm != null && start != prevOdometerEndKm) add(SheetWarning.ODOMETER_GAP)
        // Пока пробег на конец не введён — проверяем только нижнюю границу.
        val outOfRange = sheet.refuelings.any { r ->
            val km = r.odometerKm
            km != null && (km < start || (end != null && km > end))
        }
        if (outOfRange) add(SheetWarning.REFUELING_ODOMETER_OUT_OF_RANGE)
    }
}

