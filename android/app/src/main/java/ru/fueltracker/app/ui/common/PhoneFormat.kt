package ru.fueltracker.app.ui.common

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Номер телефона РФ. В поле ввода хранятся только 10 цифр после «+7»,
 * маску `+7 (999) 123-45-67` рисует [PhoneVisualTransformation].
 */
object PhoneFormat {

    const val DIGITS = 10
    private const val COUNTRY_PREFIX = "+7"

    /**
     * Новое значение поля → 10 цифр. Понимает вставку целого номера
     * (`8 999 123-45-67`, `+7 999…`) и не даёт ввести 11-ю цифру.
     */
    fun applyInput(previous: String, input: String): String {
        val digits = input.filter { it in '0'..'9' }
        if (digits.length <= DIGITS) return digits
        // Поле уже заполнено, а пользователь дописал цифру — оставляем как было
        if (previous.length == DIGITS && digits.startsWith(previous)) return previous
        val national = if (digits.length == DIGITS + 1 && digits[0] in "78") digits.drop(1) else digits
        return national.take(DIGITS)
    }

    fun isComplete(digits: String): Boolean = digits.length == DIGITS

    /** 10 цифр → `+79991234567` для API. */
    fun toApi(digits: String): String = COUNTRY_PREFIX + digits

    /** Сколько уже введено: `999123` → `+7 (999) 123`. Пустая строка — пусто (видна подсказка поля). */
    fun mask(digits: String): String = maskWithOffsets(digits).first

    /** `+79991234567` → `+7 (999) 123-45-67` для показа на экране. */
    fun display(apiPhone: String): String = mask(applyInput("", apiPhone))

    /** Маска и позиция каждой цифры в ней. */
    internal fun maskWithOffsets(digits: String): Pair<String, IntArray> {
        if (digits.isEmpty()) return "" to IntArray(0)
        val out = StringBuilder("$COUNTRY_PREFIX (")
        val positions = IntArray(digits.length)
        digits.forEachIndexed { i, c ->
            when (i) {
                3 -> out.append(") ")
                6, 8 -> out.append('-')
            }
            positions[i] = out.length
            out.append(c)
        }
        return out.toString() to positions
    }
}

/** Рисует маску поверх 10 цифр и правильно двигает курсор. */
object PhoneVisualTransformation : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val (masked, positions) = PhoneFormat.maskWithOffsets(text.text)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = when {
                positions.isEmpty() -> 0
                offset < positions.size -> positions[offset]
                else -> masked.length
            }

            override fun transformedToOriginal(offset: Int): Int = positions.count { it < offset }
        }
        return TransformedText(AnnotatedString(masked), mapping)
    }
}
