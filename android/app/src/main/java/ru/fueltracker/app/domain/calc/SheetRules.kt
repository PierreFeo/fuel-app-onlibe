package ru.fueltracker.app.domain.calc

import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetPrefill
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** Нарушение блокирующего правила (docs/06_BUSINESS_RULES.md, «Валидации»): сохранять нельзя. */
enum class SheetRuleViolation {
    /** 1. Пробег на конец меньше пробега на начало. */
    ODOMETER_END_BEFORE_START,

    /** 3. Фактический остаток больше, чем было топлива (на начало + заправки). */
    FUEL_END_OVER_AVAILABLE,

    /** 4. Дата заправки вне месяца листа. */
    REFUELING_DATE_OUTSIDE_MONTH,

    /** 5. У авто уже есть лист за этот месяц. */
    SHEET_EXISTS,

    /** 6. Месяц позже следующего за текущим. */
    MONTH_TOO_FAR,

    /** 8. Лист закрыт — сначала «Переоткрыть». */
    SHEET_CLOSED,

    /** 10. Зима выбрана, а зимней нормы у авто нет. */
    WINTER_NORM_NOT_SET,

    /** 11. Лист с заправками удалить нельзя. */
    SHEET_HAS_REFUELINGS,
}

/** Самый поздний лист авто — от него считается подсказка для нового листа. */
data class LatestSheet(
    val year: Int,
    val month: Int,
    val odometerStartKm: Long,
    val odometerEndKm: Long?,
    val season: Season,
    /** `calc.fuel_end_l` этого листа. */
    val fuelEndL: BigDecimal?,
)

/**
 * Правила ЛУТ, которые раньше проверял сервер (docs/06_BUSINESS_RULES.md): сезон и норма листа,
 * подсказка `next-prefill`, блокирующие проверки. Чистые функции — их зовут репозитории.
 */
object SheetRules {

    /** Месяцы, в которые самый первый лист авто — зимний (как в MyFuel). */
    val WINTER_MONTHS: Set<Int> = setOf(11, 12, 1, 2, 3)

    private val ZERO_L = BigDecimal("0.00")

    /** Сезон нового листа, если его не выбрали явно («Сезон и норма листа», пп. 1–3). */
    fun defaultSeason(latestSeason: Season?, month: Int, hasWinterNorm: Boolean): Season {
        val season = latestSeason ?: if (month in WINTER_MONTHS) Season.WINTER else Season.SUMMER
        // Зимней нормы нет — подсказываем лето, а не ошибку.
        return if (season == Season.WINTER && !hasWinterNorm) Season.SUMMER else season
    }

    /** Норма авто для сезона; null — зимней нормы нет (правило 10). */
    fun normFor(season: Season, summerNorm: BigDecimal, winterNorm: BigDecimal?): BigDecimal? =
        if (season == Season.SUMMER) summerNorm else winterNorm

    /** Подсказка для нового листа («Перенос между месяцами»). */
    fun nextPrefill(latest: LatestSheet?, hasWinterNorm: Boolean, today: LocalDate): SheetPrefill {
        if (latest == null) {
            return SheetPrefill(
                year = today.year,
                month = today.monthValue,
                odometerStartKm = 0,
                fuelStartL = ZERO_L,
                season = defaultSeason(null, today.monthValue, hasWinterNorm),
            )
        }
        val next = YearMonth.of(latest.year, latest.month).plusMonths(1)
        return SheetPrefill(
            year = next.year,
            month = next.monthValue,
            odometerStartKm = latest.odometerEndKm ?: latest.odometerStartKm,
            fuelStartL = latest.fuelEndL ?: ZERO_L,
            season = defaultSeason(latest.season, next.monthValue, hasWinterNorm),
        )
    }

    /** Правило 6: лист — не позже следующего месяца. */
    fun isMonthTooFar(year: Int, month: Int, today: LocalDate): Boolean =
        YearMonth.of(year, month) > YearMonth.from(today).plusMonths(1)

    /** Правила 1 и 3 — при закрытии месяца и при изменении пробега/остатка. */
    fun checkEnd(
        odometerStartKm: Long,
        odometerEndKm: Long?,
        fuelEndActualL: BigDecimal?,
        fuelAvailableL: BigDecimal,
    ): SheetRuleViolation? = when {
        odometerEndKm != null && odometerEndKm < odometerStartKm -> SheetRuleViolation.ODOMETER_END_BEFORE_START
        fuelEndActualL != null && fuelEndActualL > fuelAvailableL -> SheetRuleViolation.FUEL_END_OVER_AVAILABLE
        else -> null
    }

    /** Правило 4: дата заправки внутри месяца листа. */
    fun isDateInMonth(date: LocalDate, year: Int, month: Int): Boolean =
        date.year == year && date.monthValue == month
}
