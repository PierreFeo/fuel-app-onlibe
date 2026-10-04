package ru.fueltracker.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class FormattersTest {

    // Неразрывный пробел между группами разрядов
    private val nbsp = " "

    @Test
    fun amount_addsTwoDecimalsWithComma() {
        assertEquals("45,50", Formatters.amount("45.5"))
        assertEquals("12,00", Formatters.amount("12"))
        assertEquals("0,00", Formatters.amount("0"))
    }

    @Test
    fun amount_groupsThousands() {
        assertEquals("5${nbsp}417,50", Formatters.amount("5417.50"))
        assertEquals("1${nbsp}234${nbsp}567,89", Formatters.amount("1234567.89"))
        assertEquals("999,99", Formatters.amount("999.99"))
    }

    @Test
    fun amount_roundsHalfUp() {
        assertEquals("0,83", Formatters.amount("0.825"))
        assertEquals("0,82", Formatters.amount("0.8249"))
        assertEquals("1${nbsp}000,00", Formatters.amount("999.995"))
    }

    @Test
    fun amount_negative() {
        assertEquals("-0,82", Formatters.amount("-0.82"))
        assertEquals("-1${nbsp}500,00", Formatters.amount("-1500"))
        assertEquals("0,00", Formatters.amount("-0.001"))
    }

    @Test
    fun consumption_hasThreeDecimals() {
        assertEquals("10,068", Formatters.consumption("10.068"))
        assertEquals("10,150", Formatters.consumption("10.15"))
        assertEquals("8,500", Formatters.consumption("8.5"))
    }

    @Test
    fun km_groupsWithoutDecimals() {
        assertEquals("52${nbsp}340", Formatters.km(52340))
        assertEquals("1${nbsp}000", Formatters.km(1000))
        assertEquals("999", Formatters.km(999))
        assertEquals("0", Formatters.km(0))
    }

    @Test
    fun month_inRussianNominative() {
        val expected = listOf(
            "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
        )
        expected.forEachIndexed { index, name ->
            assertEquals("$name 2026", Formatters.month(2026, index + 1))
        }
    }

    @Test
    fun parseDecimalInput_acceptsCommaAndDot() {
        assertEquals(BigDecimal("45.50"), Formatters.parseDecimalInput("45,50"))
        assertEquals(BigDecimal("45.50"), Formatters.parseDecimalInput("45.50"))
        assertEquals(BigDecimal("40"), Formatters.parseDecimalInput("40"))
        assertEquals(BigDecimal("45."), Formatters.parseDecimalInput("45,"))
        assertEquals(BigDecimal("0.5"), Formatters.parseDecimalInput(",5"))
    }

    @Test
    fun parseDecimalInput_ignoresSpaces() {
        assertEquals(BigDecimal("1000.5"), Formatters.parseDecimalInput(" 1 000,5 "))
        assertEquals(BigDecimal("5417.5"), Formatters.parseDecimalInput("5${nbsp}417,5"))
    }

    @Test
    fun parseDecimalInput_rejectsInvalid() {
        listOf("", " ", ",", "abc", "1,2,3", "1.2.3", "-5", "1e5", "12a").forEach { input ->
            assertNull("ввод «$input»", Formatters.parseDecimalInput(input))
        }
    }
}
