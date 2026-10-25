package ru.fueltracker.app.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Эталонные примеры «Прогресс месяца» из docs/06_BUSINESS_RULES.md. */
class MonthProgressTest {

    private fun october(day: Int, month: Int = 10) = MonthProgress.of(2026, 10, LocalDate.of(2026, month, day))

    @Test
    fun `25 октября — в пути, 6 дней, машинка на 0,8 дороги`() {
        val p = october(25)
        assertEquals(MonthState.ON_TRACK, p.state)
        assertEquals(6, p.daysLeft)
        assertEquals(0.8f, p.fraction, 0.0001f)
        assertEquals(25, p.day)
        assertEquals(31, p.daysInMonth)
    }

    @Test
    fun `последние 3 дня — пора закрыть`() {
        assertEquals(MonthState.ON_TRACK, october(28).state) // осталось 3 — ещё нет
        val p29 = october(29)
        assertEquals(MonthState.SOON, p29.state)
        assertEquals(2, p29.daysLeft)
        val p31 = october(31)
        assertEquals(MonthState.SOON, p31.state)
        assertEquals(0, p31.daysLeft)
        assertEquals(1f, p31.fraction, 0.0001f)
    }

    @Test
    fun `1-е число — старт`() {
        val p = october(1)
        assertEquals(MonthState.ON_TRACK, p.state)
        assertEquals(30, p.daysLeft)
        assertEquals(0f, p.fraction, 0.0001f)
    }

    @Test
    fun `месяц прошёл — опоздали, машинка у финиша`() {
        val p = october(3, month = 11)
        assertEquals(MonthState.LATE, p.state)
        assertEquals(1f, p.fraction, 0.0001f)
        assertEquals(0, p.daysLeft)
        assertNull(p.day)
    }

    @Test
    fun `месяц ещё не начался — на старте`() {
        val p = october(28, month = 9)
        assertEquals(MonthState.UPCOMING, p.state)
        assertEquals(0f, p.fraction, 0.0001f)
        assertNull(p.day)
    }

    @Test
    fun `високосный февраль — 29 дней`() {
        val p = MonthProgress.of(2028, 2, LocalDate.of(2028, 2, 27))
        assertEquals(29, p.daysInMonth)
        assertEquals(MonthState.SOON, p.state)
        assertEquals(2, p.daysLeft)
    }
}
