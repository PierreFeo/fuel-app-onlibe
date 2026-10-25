package ru.fueltracker.app.domain.calc

import java.time.LocalDate
import java.time.YearMonth

/** Где машинка на дороге месяца (docs/06_BUSINESS_RULES.md, «Прогресс месяца»). */
enum class MonthState {
    /** Месяц ещё не начался — машинка на старте. */
    UPCOMING,

    /** Идёт месяц, до конца больше [MonthProgress.SOON_DAYS] дней. */
    ON_TRACK,

    /** Последние дни месяца — пора закрыть. */
    SOON,

    /** Месяц прошёл, а лист открыт. */
    LATE,
}

/**
 * Прогресс месяца листа на сегодня. [fraction] — положение машинки от 0 (1-е число) до 1
 * (последний день); [day] — сегодняшнее число, если сегодня в месяце листа, иначе null.
 */
data class MonthProgress(
    val state: MonthState,
    val fraction: Float,
    val day: Int?,
    val daysInMonth: Int,
    val daysLeft: Int,
) {
    companion object {
        /** Сколько последних дней месяца считаются «пора закрыть». */
        const val SOON_DAYS = 3

        fun of(year: Int, month: Int, today: LocalDate): MonthProgress {
            val sheetMonth = YearMonth.of(year, month)
            val days = sheetMonth.lengthOfMonth()
            val current = YearMonth.from(today)
            return when {
                current.isBefore(sheetMonth) -> MonthProgress(MonthState.UPCOMING, 0f, null, days, days)
                current.isAfter(sheetMonth) -> MonthProgress(MonthState.LATE, 1f, null, days, 0)
                else -> {
                    val day = today.dayOfMonth
                    val left = days - day
                    MonthProgress(
                        state = if (left < SOON_DAYS) MonthState.SOON else MonthState.ON_TRACK,
                        fraction = (day - 1).toFloat() / (days - 1),
                        day = day,
                        daysInMonth = days,
                        daysLeft = left,
                    )
                }
            }
        }
    }
}
