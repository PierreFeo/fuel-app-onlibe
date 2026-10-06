package ru.fueltracker.app.ui.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.fueltracker.app.R
import ru.fueltracker.app.ui.common.UiText
import java.math.BigDecimal

class SheetCardTextTest {

    @Test
    fun `отклонение больше нуля — перерасход`() {
        assertEquals(UiText.Resource(R.string.sheet_overuse, listOf("0,82")), deviationText(BigDecimal("0.82")))
    }

    @Test
    fun `отклонение меньше нуля — экономия без минуса`() {
        assertEquals(UiText.Resource(R.string.sheet_economy, listOf("3,00")), deviationText(BigDecimal("-3.00")))
    }

    @Test
    fun `отклонение ноль — ровно по норме`() {
        assertEquals(UiText.Resource(R.string.sheet_exact), deviationText(BigDecimal("0.00")))
    }

    @Test
    fun `подсказка вместо расхода`() {
        val open = previewOpenSheet()
        assertEquals(UiText.Resource(R.string.sheet_consumption_pending), consumptionHint(open))

        // Пример C: закрыт без фактического остатка
        val closed = previewClosedSheet()
        val noActual = closed.copy(fuelEndActualL = null, calc = closed.calc.copy(actualLPer100km = null))
        assertEquals(UiText.Resource(R.string.sheet_consumption_no_actual), consumptionHint(noActual))

        // Пример E: пробег 0 км, остаток введён — объяснять нечего
        val zeroMileage = closed.copy(calc = closed.calc.copy(mileageKm = 0, actualLPer100km = null))
        assertNull(consumptionHint(zeroMileage))
    }
}
