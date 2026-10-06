package ru.fueltracker.app.ui.common

import ru.fueltracker.app.R
import java.math.BigDecimal

// Ввод чисел в формах: фильтр символов при наборе и разбор с понятной ошибкой

/** Ввод дробного числа (литры, деньги, нормы): только цифры и разделитель «,» или «.». */
fun filterDecimalInput(text: String): String = text.filter { it.isDigit() || it == ',' || it == '.' }

/** Ввод целого числа (пробег): только цифры. */
fun filterDigitsInput(text: String): String = text.filter { it in '0'..'9' }

/** BigDecimal → текст поля: «50.00» → «50», «10.068» → «10,068». */
fun BigDecimal.toInputText(): String = stripTrailingZeros().toPlainString().replace('.', ',')

/** Значение поля или текст ошибки для него. */
data class ParsedInput<T>(val value: T?, val error: UiText?)

/** Пробег, км: целое ≥ 0, не больше int в БД. */
fun parseKmInput(text: String): ParsedInput<Long> {
    if (text.isBlank()) return ParsedInput(null, UiText.Resource(R.string.error_required))
    val value = text.trim().toLongOrNull()
        ?: return ParsedInput(null, UiText.Resource(R.string.error_whole_number))
    if (value > Int.MAX_VALUE) return ParsedInput(null, UiText.Resource(R.string.error_too_big))
    return ParsedInput(value, null)
}

/**
 * Литры/деньги: ≥ 0, не больше [scale] знаков после запятой, целая часть влезает в numeric([maxDigits], [scale]).
 * Пустое поле: обязательное — ошибка, необязательное — null без ошибки.
 */
fun parseNonNegativeDecimal(
    text: String,
    scale: Int,
    maxDigits: Int,
    required: Boolean,
): ParsedInput<BigDecimal> {
    if (text.isBlank()) {
        return ParsedInput(null, if (required) UiText.Resource(R.string.error_required) else null)
    }
    // «50,00» → 50; без setScale(0) stripTrailingZeros дал бы 5E+1
    val value = Formatters.parseDecimalInput(text)?.stripTrailingZeros()?.let { if (it.scale() < 0) it.setScale(0) else it }
    return when {
        value == null || value.signum() < 0 -> ParsedInput(null, UiText.Resource(R.string.error_non_negative_number))
        value.scale() > scale -> ParsedInput(null, UiText.Resource(R.string.error_max_decimals, listOf(scale)))
        value >= BigDecimal.TEN.pow(maxDigits - scale) -> ParsedInput(null, UiText.Resource(R.string.error_too_big))
        else -> ParsedInput(value, null)
    }
}
