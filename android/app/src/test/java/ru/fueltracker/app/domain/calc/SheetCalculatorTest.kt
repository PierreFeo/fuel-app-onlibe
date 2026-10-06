package ru.fueltracker.app.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.fueltracker.app.domain.model.ConsumptionStatus
import ru.fueltracker.app.domain.model.SheetCalc
import ru.fueltracker.app.domain.model.SheetWarning
import java.math.BigDecimal

/**
 * Эталонные примеры A–I из docs/06_BUSINESS_RULES.md — те же, что были у сервера
 * (backend/tests/test_sheet_calc.py), цифры обязаны совпадать до копейки.
 * BigDecimal сравниваются вместе с масштабом: "80.00" ≠ "80.0" — так проверяется и формат.
 */
class SheetCalculatorTest {

    private fun d(value: String) = BigDecimal(value)
    private val tank = d("50.00")

    private fun refueling(liters: String, cost: String, odometer: Long? = null) = RefuelingData(d(liters), d(cost), odometer)

    /** Пример A: норма 8.50; пробег 52340 → 53340; остаток 12.00; 2 × 40 л / 2200 ₽; факт. остаток 10.00. */
    private val exampleA = SheetData(
        odometerStartKm = 52_340,
        odometerEndKm = 53_340,
        fuelStartL = d("12.00"),
        fuelEndActualL = d("10.00"),
        normLPer100km = d("8.50"),
        refuelings = listOf(refueling("40.00", "2200.00"), refueling("40.00", "2200.00")),
    )

    private fun calc(sheet: SheetData, prevEnd: Long? = null) = SheetCalculator.calculate(sheet, tank, prevEnd)

    private fun expected(
        refueledL: String, refueledCost: String, fuelAvailableL: String, mileageKm: Long?,
        normConsumptionL: String?, fuelEndCalcL: String?, fuelEndL: String?, actualConsumptionL: String?,
        actualLPer100km: String?, status: ConsumptionStatus?, deviationL: String?, costPerKm: String?,
        warnings: List<SheetWarning> = emptyList(),
    ) = SheetCalc(
        d(refueledL), d(refueledCost), d(fuelAvailableL), mileageKm, normConsumptionL?.let(::d), fuelEndCalcL?.let(::d),
        fuelEndL?.let(::d), actualConsumptionL?.let(::d), actualLPer100km?.let(::d), status, deviationL?.let(::d),
        costPerKm?.let(::d), warnings,
    )

    // --- эталонные примеры ---

    @Test
    fun `пример A — закрытый месяц с фактическим остатком`() {
        assertEquals(
            expected("80.00", "4400.00", "92.00", 1000, "85.00", "7.00", "10.00", "82.00", "8.200",
                ConsumptionStatus.NORMAL, "-3.00", "4.40"),
            calc(exampleA),
        )
    }

    @Test
    fun `пример B — открытый месяц, только начало`() {
        val sheet = SheetData(53_340, null, d("10.00"), null, d("8.50"), listOf(refueling("30.00", "1650.00")))

        assertEquals(
            expected("30.00", "1650.00", "40.00", null, null, null, null, null, null, null, null, null),
            calc(sheet),
        )
    }

    @Test
    fun `пример C — пробег на конец есть, фактического остатка нет`() {
        val calc = calc(exampleA.copy(fuelEndActualL = null))

        assertEquals(d("7.00"), calc.fuelEndCalcL)
        assertEquals(d("7.00"), calc.fuelEndL)
        assertNull(calc.actualConsumptionL)
        assertNull(calc.actualLPer100km)
        assertNull(calc.deviationL)
        assertNull(calc.consumptionStatus)
        assertEquals(d("85.00"), calc.normConsumptionL)
        assertEquals(d("4.40"), calc.costPerKm)
        assertEquals(emptyList<SheetWarning>(), calc.warnings)
    }

    @Test
    fun `пример D — отрицательный остаток`() {
        val calc = calc(exampleA.copy(odometerStartKm = 10_000, odometerEndKm = 11_500, fuelEndActualL = null))

        assertEquals(1500L, calc.mileageKm)
        assertEquals(d("127.50"), calc.normConsumptionL)
        assertEquals(d("-35.50"), calc.fuelEndCalcL)
        assertEquals(listOf(SheetWarning.FUEL_END_NEGATIVE), calc.warnings)
    }

    @Test
    fun `пример E — нулевой пробег`() {
        val sheet = SheetData(53_340, 53_340, d("10.00"), null, d("8.50"), listOf(refueling("20.00", "1100.00")))
        val calc = calc(sheet)

        assertEquals(0L, calc.mileageKm)
        assertEquals(d("0.00"), calc.normConsumptionL)
        assertEquals(d("30.00"), calc.fuelEndCalcL)
        assertNull(calc.actualLPer100km)
        assertNull(calc.costPerKm)

        val withActual = calc(sheet.copy(fuelEndActualL = d("30.00")))
        assertEquals(d("0.00"), withActual.actualConsumptionL)
        assertEquals(d("0.00"), withActual.deviationL)
        assertNull(withActual.actualLPer100km) // на 0 км делить нельзя
        assertNull(withActual.consumptionStatus) // нет расхода на 100 км — нет и цвета
    }

    @Test
    fun `пример F — округление`() {
        val calc = calc(SheetData(1000, 1333, d("40.00"), null, d("7.77")))

        assertEquals(d("25.87"), calc.normConsumptionL) // 333 × 7.77 / 100 = 25.8741
        assertEquals(d("14.13"), calc.fuelEndCalcL) // от уже округлённого расхода
    }

    @Test
    fun `пример G — лето, перерасход (красный)`() {
        val sheet = SheetData(
            52_340, 53_340, d("12.00"), d("9.00"), d("10.068"),
            listOf(refueling("50.00", "2750.00"), refueling("48.50", "2667.50")),
        )

        assertEquals(
            expected("98.50", "5417.50", "110.50", 1000, "100.68", "9.82", "9.00", "101.50", "10.150",
                ConsumptionStatus.OVER, "0.82", "5.42"),
            calc(sheet),
        )
    }

    @Test
    fun `пример H — зима, в норме (зелёный)`() {
        val sheet = SheetData(
            53_340, 54_240, d("20.00"), d("19.50"), d("11.684"),
            listOf(refueling("45.00", "2520.00"), refueling("50.00", "2800.00")),
        )

        assertEquals(
            expected("95.00", "5320.00", "115.00", 900, "105.16", "9.84", "19.50", "95.50", "10.611",
                ConsumptionStatus.NORMAL, "-9.66", "5.91"),
            calc(sheet),
        )
    }

    private fun exampleI(fuelStart: String, fuelEnd: String) = SheetData(
        0, 1000, d(fuelStart), d(fuelEnd), d("10.068"),
        listOf(refueling("50.00", "2750.00"), refueling("50.00", "2750.00")),
    )

    @Test
    fun `пример I — ровно по норме зелёный, на сотую больше — красный`() {
        calc(exampleI("0.68", "0.00")).let {
            assertEquals(d("10.068"), it.actualLPer100km)
            assertEquals(ConsumptionStatus.NORMAL, it.consumptionStatus)
            assertEquals(d("0.00"), it.deviationL)
        }
        calc(exampleI("0.68", "0.01")).let {
            assertEquals(d("10.067"), it.actualLPer100km)
            assertEquals(ConsumptionStatus.NORMAL, it.consumptionStatus)
        }
        calc(exampleI("0.69", "0.00")).let {
            assertEquals(d("10.069"), it.actualLPer100km)
            assertEquals(ConsumptionStatus.OVER, it.consumptionStatus)
        }
    }

    @Test
    fun `цвет сравнивает то число, что видит пользователь`() {
        // 1006.84 л на 10 000 км → точно 10.0684 (чуть больше нормы), на экране 10.068 = норма → зелёный
        val sheet = SheetData(0, 10_000, d("6.84"), d("0.00"), d("10.068"), listOf(refueling("1000.00", "0.00")))

        assertEquals(ConsumptionStatus.NORMAL, calc(sheet).consumptionStatus)
    }

    // --- округление и мелочи ---

    @Test
    fun `round2 — половина вверх, без минус нуля, всегда 2 знака`() {
        mapOf(
            "25.8741" to "25.87", "0.005" to "0.01", "2.675" to "2.68",
            "-0.004" to "0.00", "-35.5" to "-35.50", "7" to "7.00",
        ).forEach { (value, result) ->
            assertEquals(value, result, SheetCalculator.round2(d(value)).toPlainString())
        }
    }

    @Test
    fun `без заправок — нули, а не null`() {
        val calc = calc(SheetData(0, null, d("0"), null, d("8.50")))

        assertEquals("0.00", calc.refueledL.toPlainString())
        assertEquals("0.00", calc.refueledCost.toPlainString())
        assertEquals("0.00", calc.fuelAvailableL.toPlainString())
    }

    @Test
    fun `расход на 100 км и цена км округляются`() {
        // 82.00 л на 333 км → 24.6246… → 24.625; 4400 / 333 = 13.2132… → 13.21
        val calc = calc(exampleA.copy(odometerEndKm = 52_673))

        assertEquals("24.625", calc.actualLPer100km!!.toPlainString())
        assertEquals(d("13.21"), calc.costPerKm)
    }

    @Test
    fun `знак отклонения — перерасход больше нуля, экономия меньше`() {
        assertEquals(d("5.00"), calc(exampleA.copy(fuelEndActualL = d("2.00"))).deviationL)
        assertEquals(d("-3.00"), calc(exampleA).deviationL)
    }

    @Test
    fun `сумма заправки — литры на цену до копеек`() {
        assertEquals(d("2667.50"), SheetCalculator.totalCost(d("48.50"), d("55.00")))
        assertEquals(d("0.01"), SheetCalculator.totalCost(d("0.01"), d("0.50"))) // 0.005 → 0.01
    }

    // --- предупреждения ---

    @Test
    fun `остаток больше бака — по итоговому остатку`() {
        assertEquals(listOf(SheetWarning.FUEL_END_OVER_TANK), calc(exampleA.copy(fuelEndActualL = d("50.01"))).warnings)
        assertEquals(emptyList<SheetWarning>(), calc(exampleA.copy(fuelEndActualL = d("50.00"))).warnings)
        // без фактического остатка — по расчётному: 92 − 0 = 92 > 50
        assertEquals(
            listOf(SheetWarning.FUEL_END_OVER_TANK),
            calc(exampleA.copy(odometerEndKm = 52_340, fuelEndActualL = null)).warnings,
        )
    }

    @Test
    fun `заправка больше бака — не предупреждение`() {
        val big = refueling("55.00", "3000.00")

        assertEquals(emptyList<SheetWarning>(), calc(exampleA.copy(refuelings = listOf(big, big), fuelEndActualL = d("40.00"))).warnings)
    }

    @Test
    fun `разрыв пробега с прошлым месяцем`() {
        assertEquals(emptyList<SheetWarning>(), calc(exampleA, prevEnd = 52_340).warnings)
        assertEquals(listOf(SheetWarning.ODOMETER_GAP), calc(exampleA, prevEnd = 52_300).warnings)
        assertEquals(emptyList<SheetWarning>(), calc(exampleA, prevEnd = null).warnings)
    }

    @Test
    fun `пробег заправки вне месяца`() {
        data class Case(val odometer: Long?, val end: Long?, val outOfRange: Boolean)
        listOf(
            Case(52_340, 53_340, false), // ровно на начале
            Case(53_340, 53_340, false), // ровно на конце
            Case(52_339, 53_340, true), // раньше начала
            Case(53_341, 53_340, true), // позже конца
            Case(60_000, null, false), // конца ещё нет — верхнюю границу не проверяем
            Case(52_000, null, true), // но нижнюю — проверяем
            Case(null, 53_340, false), // одометр при заправке не указан
        ).forEach { case ->
            val sheet = exampleA.copy(odometerEndKm = case.end, refuelings = listOf(refueling("40.00", "2200.00", case.odometer)))
            assertEquals(case.toString(), case.outOfRange, SheetWarning.REFUELING_ODOMETER_OUT_OF_RANGE in calc(sheet).warnings)
        }
    }

    @Test
    fun `несколько предупреждений — каждое один раз, в порядке таблицы`() {
        val sheet = SheetData(
            1000, 3000, d("0.00"), null, d("8.50"),
            listOf(refueling("60.00", "3300.00", 500), refueling("1.00", "50.00", 400)),
        )

        assertEquals(
            listOf(SheetWarning.FUEL_END_NEGATIVE, SheetWarning.ODOMETER_GAP, SheetWarning.REFUELING_ODOMETER_OUT_OF_RANGE),
            calc(sheet, prevEnd = 900).warnings,
        )
    }
}
