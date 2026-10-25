package ru.fueltracker.app.ui.refueling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** Заправка из версии 1.0: заполнены поля, которых в форме больше нет, сумма введена руками. */
    private val oldRefueling = Refueling(
        id = "r1",
        date = LocalDate.of(2026, 10, 5),
        liters = BigDecimal("40.00"),
        pricePerLiter = BigDecimal("55.00"),
        totalCost = BigDecimal("2100.00"),
        odometerKm = 52_610,
        station = "Лукойл",
        paymentType = PaymentType.FUEL_CARD,
        note = "Скидка",
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
    fun `сумма на экране — литры × цена с округлением до копеек`() {
        assertEquals(BigDecimal("2220.00"), previewTotal("40", "55,5"))
        assertEquals(BigDecimal("1666.65"), previewTotal("33,333", "50"))
        assertEquals(BigDecimal("0.01"), previewTotal("0,005", "1")) // половина копейки — вверх
        assertNull(previewTotal("40", ""))
        assertNull(previewTotal("abc", "55"))
    }

    @Test
    fun `новая заправка — сумма не передаётся, остальные поля пусто, оплата личные`() {
        val (input, errors) = validateRefueling(valid, october)
        assertFalse(errors.hasAny)
        assertNull(input!!.totalCost)
        assertEquals(BigDecimal("40"), input.liters)
        assertEquals(BigDecimal("55.5"), input.pricePerLiter)
        assertNull(input.odometerKm)
        assertNull(input.station)
        assertNull(input.note)
        assertEquals(PaymentType.PERSONAL, input.paymentType)
    }

    @Test
    fun `правка старой заправки — скрытые поля как были, сумма пересчитывается`() {
        val form = oldRefueling.toForm()
        assertEquals("40", form.liters)
        assertEquals("55", form.pricePerLiter)

        val (input, _) = validateRefueling(form.copy(liters = "41"), october)
        assertEquals(52_610L, input!!.odometerKm)
        assertEquals("Лукойл", input.station)
        assertEquals(PaymentType.FUEL_CARD, input.paymentType)
        assertEquals("Скидка", input.note)
        assertNull(input.totalCost) // ручная сумма 2100 не сохраняется: теперь литры × цена
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
}
