package ru.fueltracker.app.ui.refueling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.PaymentType
import ru.fueltracker.app.domain.model.Refueling
import ru.fueltracker.app.ui.common.UiText
import java.math.BigDecimal
import java.time.LocalDate

class RefuelingFormTest {

    private val october = RefuelingTarget("s-10", 2026, 10)
    private val valid = RefuelingForm(date = LocalDate.of(2026, 10, 5), liters = "40", pricePerLiter = "55,5")

    private fun refueling(total: String) = Refueling(
        id = "r1",
        date = LocalDate.of(2026, 10, 5),
        liters = BigDecimal("40.00"),
        pricePerLiter = BigDecimal("55.00"),
        totalCost = BigDecimal(total),
        odometerKm = 52_610,
        station = "Лукойл",
        paymentType = PaymentType.FUEL_CARD,
        note = null,
    )

    @Test
    fun `дата новой заправки — сегодня или ближайший день месяца листа`() {
        assertEquals(LocalDate.of(2026, 10, 6), october.newForm(today = LocalDate.of(2026, 10, 6)).date)
        // Лист прошлого месяца — последний день месяца
        assertEquals(LocalDate.of(2026, 10, 31), october.newForm(today = LocalDate.of(2026, 11, 3)).date)
        // Лист будущего месяца — первый день
        assertEquals(LocalDate.of(2026, 10, 1), october.newForm(today = LocalDate.of(2026, 9, 28)).date)
    }

    @Test
    fun `предпросмотр суммы — литры × цена с округлением до копеек`() {
        assertEquals("2220,00", previewTotal("40", "55,5"))
        assertEquals("1666,65", previewTotal("33,333", "50"))
        assertEquals("0,01", previewTotal("0,005", "1")) // половина копейки — вверх
        assertEquals("", previewTotal("40", ""))
        assertEquals("", previewTotal("abc", "55"))
    }

    @Test
    fun `автосумма пересчитывается, пока её не ввели руками`() {
        val auto = valid.withAutoTotal()
        assertEquals("2220,00", auto.totalCost)
        val manual = auto.copy(totalCost = "2000", isTotalManual = true, liters = "50").withAutoTotal()
        assertEquals("2000", manual.totalCost)
    }

    @Test
    fun `заправка из API — сумма вручную, если не равна литры × цена`() {
        val exact = refueling("2200.00").toForm()
        assertFalse(exact.isTotalManual)
        assertEquals("2200,00", exact.totalCost)
        assertEquals("40", exact.liters)
        assertEquals("52610", exact.odometer)
        assertEquals(PaymentType.FUEL_CARD, exact.paymentType)

        assertTrue(refueling("2100.00").toForm().isTotalManual)
    }

    @Test
    fun `автосумма не отправляется — сервер посчитает сам`() {
        val (input, errors) = validateRefueling(valid.withAutoTotal(), october)
        assertFalse(errors.hasAny)
        assertNull(input!!.totalCost)
        assertEquals(BigDecimal("40"), input.liters)
        assertEquals(BigDecimal("55.5"), input.pricePerLiter)
        assertNull(input.station)
    }

    @Test
    fun `сумма вручную отправляется`() {
        val (input, _) = validateRefueling(valid.copy(totalCost = "2000,5", isTotalManual = true), october)
        assertEquals(BigDecimal("2000.5"), input!!.totalCost)
    }

    @Test
    fun `обязательные поля и литры больше нуля`() {
        val required = UiText.Resource(R.string.error_required)
        val (input, errors) = validateRefueling(RefuelingForm(date = LocalDate.of(2026, 10, 5)), october)
        assertNull(input)
        assertEquals(required, errors.liters)
        assertEquals(required, errors.pricePerLiter)

        val zero = validateRefueling(valid.copy(liters = "0"), october).second
        assertEquals(UiText.Resource(R.string.error_positive_number), zero.liters)
        // Цена 0 допустима (заправка за баллы)
        assertNull(validateRefueling(valid.copy(pricePerLiter = "0"), october).second.pricePerLiter)
    }

    @Test
    fun `дата вне месяца листа`() {
        val (_, errors) = validateRefueling(valid.copy(date = LocalDate.of(2026, 11, 1)), october)
        assertEquals(UiText.Resource(R.string.error_date_outside_month), errors.date)
    }

    @Test
    fun `необязательные поля — пробелы обрезаются, пустое — null`() {
        val (input, _) = validateRefueling(valid.copy(station = "  Лукойл ", note = "   ", odometer = "52610"), october)
        assertEquals("Лукойл", input!!.station)
        assertNull(input.note)
        assertEquals(52_610L, input.odometerKm)
    }

    @Test
    fun `слишком большой пробег`() {
        val (_, errors) = validateRefueling(valid.copy(odometer = "10000000"), october)
        assertEquals(UiText.Resource(R.string.error_too_big), errors.odometer)
    }
}
