package ru.fueltracker.app.ui.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.NewSheetInput
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetPrefill
import ru.fueltracker.app.ui.common.UiText
import java.math.BigDecimal

class SheetFormsTest {

    private val required = UiText.Resource(R.string.error_required)

    @Test
    fun `подсказка next-prefill заполняет форму`() {
        val form = SheetPrefill(2026, 11, 53_340, BigDecimal("10.00"), Season.WINTER).toForm()
        assertEquals(NewSheetForm(2026, 11, Season.WINTER, "53340", "10"), form)
    }

    @Test
    fun `месяц листается через границу года`() {
        val december = NewSheetForm(2026, 12, Season.WINTER, "0", "0")
        assertEquals(2027 to 1, december.shiftMonth(1).let { it.year to it.month })
        assertEquals(2025 to 12, december.copy(month = 1).shiftMonth(-1).let { it.year to it.month })
    }

    @Test
    fun `новый лист — верные данные`() {
        val (input, form) = validateNewSheet(NewSheetForm(2026, 11, Season.SUMMER, "53340", "10,5"))
        assertEquals(NewSheetInput(2026, 11, 53_340, BigDecimal("10.5"), Season.SUMMER), input)
        assertNull(form.odometerError)
    }

    @Test
    fun `новый лист — остаток 0 допустим, пустые поля — ошибка`() {
        assertEquals(BigDecimal("0"), validateNewSheet(NewSheetForm(2026, 11, Season.SUMMER, "0", "0")).first?.fuelStartL)

        val (input, form) = validateNewSheet(NewSheetForm(2026, 11, Season.SUMMER, "", ""))
        assertNull(input)
        assertEquals(required, form.odometerError)
        assertEquals(required, form.fuelError)
    }

    @Test
    fun `новый лист — лишние знаки в остатке`() {
        val (_, form) = validateNewSheet(NewSheetForm(2026, 11, Season.SUMMER, "1", "10,123"))
        assertEquals(UiText.Resource(R.string.error_max_decimals, listOf(2)), form.fuelError)
    }

    @Test
    fun `закрытие — подставляются уже введённые значения`() {
        val form = previewClosedSheet().toCloseForm()
        assertEquals("53340", form.odometerEnd)
        assertEquals("9", form.fuelEndActual)
        assertEquals("", previewOpenSheet().toCloseForm().odometerEnd)
    }

    @Test
    fun `закрытие — пробег на конец не меньше начала`() {
        val base = previewOpenSheet().toCloseForm() // начало 53 340
        val (input, form) = validateCloseSheet(base.copy(odometerEnd = "53000"))
        assertNull(input)
        assertEquals(
            UiText.Resource(R.string.error_odometer_end_before_start, listOf("53 340")), // неразрывный пробел, как в Formatters
            form.odometerError,
        )
        assertEquals(53_340L, validateCloseSheet(base.copy(odometerEnd = "53340")).first?.odometerEndKm)
    }

    @Test
    fun `закрытие — остаток необязателен`() {
        val base = previewOpenSheet().toCloseForm()
        assertEquals(
            CloseSheetInput(54_000, null),
            validateCloseSheet(base.copy(odometerEnd = "54000", fuelEndActual = "")).first,
        )
        assertEquals(
            CloseSheetInput(54_000, BigDecimal("7.5")),
            validateCloseSheet(base.copy(odometerEnd = "54000", fuelEndActual = "7,50")).first,
        )
    }

    @Test
    fun `закрытие — пробег обязателен`() {
        val (_, form) = validateCloseSheet(previewOpenSheet().toCloseForm())
        assertEquals(required, form.odometerError)
    }
}
