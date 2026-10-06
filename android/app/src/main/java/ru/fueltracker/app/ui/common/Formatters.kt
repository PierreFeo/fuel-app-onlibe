package ru.fueltracker.app.ui.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Форматирование чисел и дат для отображения.
 * Возвращают только число — единицы («л», «₽», «км») добавляются строками из strings.xml.
 * Дробные значения приходят из API строками («45.5») и не проходят через Double.
 */
object Formatters {

    private val ruLocale: Locale = Locale.forLanguageTag("ru")

    // Неразрывный пробел: «5 417,50» не разорвётся переносом строки
    private const val GROUP_SEPARATOR = ' '

    private val decimalInput = Regex("""\d+([.,]\d*)?|[.,]\d+""")

    /** «45.5» → «45,50» (литры и деньги, 2 знака). */
    fun amount(apiValue: String): String = decimal(BigDecimal(apiValue), scale = 2)

    /** «10.15» → «10,150» (нормы и расход на 100 км, 3 знака). */
    fun consumption(apiValue: String): String = decimal(BigDecimal(apiValue), scale = 3)

    /** 52340 → «52 340» (пробег, целые километры). */
    fun km(value: Long): String = decimal(BigDecimal.valueOf(value), scale = 0)

    /** (2026, 10) → «Октябрь 2026». */
    fun month(year: Int, month: Int): String {
        val name = Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, ruLocale)
        return "${name.replaceFirstChar { it.titlecase(ruLocale) }} $year"
    }

    /**
     * Ввод пользователя → BigDecimal. Принимает «,» и «.», пробелы игнорирует.
     * Пустая строка, буквы, минус, два разделителя → null.
     */
    fun parseDecimalInput(text: String): BigDecimal? {
        val cleaned = text.filterNot { it.isWhitespace() }
        if (!decimalInput.matches(cleaned)) return null
        return BigDecimal(cleaned.replace(',', '.'))
    }

    /** Округление «по-школьному», группы по 3 цифры, запятая как десятичный разделитель. */
    fun decimal(value: BigDecimal, scale: Int): String {
        val rounded = value.setScale(scale, RoundingMode.HALF_UP)
        val plain = rounded.abs().toPlainString()
        val intPart = plain.substringBefore('.')
        val fracPart = plain.substringAfter('.', missingDelimiterValue = "")
        val grouped = intPart.reversed().chunked(3).joinToString(GROUP_SEPARATOR.toString()).reversed()
        val sign = if (rounded.signum() < 0) "-" else ""
        return if (fracPart.isEmpty()) "$sign$grouped" else "$sign$grouped,$fracPart"
    }
}

/** 59 → «0:59», 125 → «2:05» (таймер повторной отправки кода). */
fun formatCountdown(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
