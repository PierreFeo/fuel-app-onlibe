package ru.fueltracker.app.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetPrefill
import java.math.BigDecimal
import java.time.LocalDate

/** Сезон, подсказка нового листа и блокирующие проверки (docs/06_BUSINESS_RULES.md). */
class SheetRulesTest {

    private fun d(value: String) = BigDecimal(value)
    private val today = LocalDate.of(2026, 10, 6)

    private fun latest(
        year: Int = 2026, month: Int = 10, end: Long? = 53_340, season: Season = Season.SUMMER, fuelEnd: String? = "10.00",
    ) = LatestSheet(year, month, odometerStartKm = 52_340, odometerEndKm = end, season = season, fuelEndL = fuelEnd?.let(::d))

    // --- сезон ---

    @Test
    fun `новый лист наследует сезон самого позднего`() {
        assertEquals(Season.WINTER, SheetRules.defaultSeason(Season.WINTER, month = 6, hasWinterNorm = true))
        assertEquals(Season.SUMMER, SheetRules.defaultSeason(Season.SUMMER, month = 12, hasWinterNorm = true))
    }

    @Test
    fun `первый лист — по месяцу, ноябрь–март зима`() {
        listOf(11, 12, 1, 2, 3).forEach { assertEquals(Season.WINTER, SheetRules.defaultSeason(null, it, hasWinterNorm = true)) }
        (4..10).forEach { assertEquals(Season.SUMMER, SheetRules.defaultSeason(null, it, hasWinterNorm = true)) }
    }

    @Test
    fun `без зимней нормы подсказка — лето`() {
        assertEquals(Season.SUMMER, SheetRules.defaultSeason(Season.WINTER, month = 1, hasWinterNorm = false))
        assertEquals(Season.SUMMER, SheetRules.defaultSeason(null, month = 1, hasWinterNorm = false))
    }

    @Test
    fun `норма для сезона`() {
        assertEquals(d("10.068"), SheetRules.normFor(Season.SUMMER, d("10.068"), d("11.684")))
        assertEquals(d("11.684"), SheetRules.normFor(Season.WINTER, d("10.068"), d("11.684")))
        assertNull(SheetRules.normFor(Season.WINTER, d("10.068"), null)) // правило 10
    }

    // --- next-prefill ---

    @Test
    fun `подсказка после прошлого листа`() {
        assertEquals(
            SheetPrefill(2026, 11, 53_340, d("10.00"), Season.SUMMER),
            SheetRules.nextPrefill(latest(), hasWinterNorm = true, today = today),
        )
    }

    @Test
    fun `подсказка через границу года и без пробега на конец`() {
        val prefill = SheetRules.nextPrefill(latest(month = 12, end = null, fuelEnd = null, season = Season.WINTER), true, today)

        assertEquals(SheetPrefill(2027, 1, 52_340, d("0.00"), Season.WINTER), prefill)
    }

    @Test
    fun `подсказка для самого первого листа — текущий месяц и нули`() {
        assertEquals(
            SheetPrefill(2026, 10, 0, d("0.00"), Season.SUMMER),
            SheetRules.nextPrefill(null, hasWinterNorm = true, today = today),
        )
        assertEquals(Season.WINTER, SheetRules.nextPrefill(null, true, LocalDate.of(2026, 12, 1)).season)
    }

    // --- блокирующие проверки ---

    @Test
    fun `лист — не дальше следующего месяца`() {
        assertFalse(SheetRules.isMonthTooFar(2026, 11, today))
        assertTrue(SheetRules.isMonthTooFar(2026, 12, today))
        assertFalse(SheetRules.isMonthTooFar(2020, 1, today)) // прошлые — можно
        assertFalse(SheetRules.isMonthTooFar(2027, 1, LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `конец месяца — пробег не меньше начала, остаток не больше доступного`() {
        assertEquals(SheetRuleViolation.ODOMETER_END_BEFORE_START, SheetRules.checkEnd(100, 99, null, d("10")))
        assertEquals(SheetRuleViolation.FUEL_END_OVER_AVAILABLE, SheetRules.checkEnd(100, 200, d("10.01"), d("10.00")))
        assertNull(SheetRules.checkEnd(100, 100, d("10.00"), d("10.00")))
        assertNull(SheetRules.checkEnd(100, null, null, d("0")))
    }

    @Test
    fun `дата заправки внутри месяца листа`() {
        assertTrue(SheetRules.isDateInMonth(LocalDate.of(2026, 10, 31), 2026, 10))
        assertFalse(SheetRules.isDateInMonth(LocalDate.of(2026, 11, 1), 2026, 10))
        assertFalse(SheetRules.isDateInMonth(LocalDate.of(2025, 10, 5), 2026, 10))
    }
}
