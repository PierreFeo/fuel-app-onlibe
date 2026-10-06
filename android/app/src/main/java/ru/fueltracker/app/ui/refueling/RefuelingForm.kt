package ru.fueltracker.app.ui.refueling

import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.PaymentType
import ru.fueltracker.app.domain.model.Refueling
import ru.fueltracker.app.domain.model.RefuelingInput
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.common.ParsedInput
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.parseNonNegativeDecimal
import ru.fueltracker.app.ui.common.toInputText
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

// Ограничения API (backend/app/schemas/refueling.py): литры и цена numeric(8,2),
// сумма numeric(10,2), пробег 0..9 999 999, АЗС ≤ 100 символов, комментарий ≤ 255
private const val MONEY_SCALE = 2
private const val LITERS_DIGITS = 8
private const val PRICE_DIGITS = 8
private const val TOTAL_DIGITS = 10
internal const val ODOMETER_MAX = 9_999_999L
internal const val STATION_MAX = 100
internal const val NOTE_MAX = 255

/** Для какого листа и какой заправки открыта форма; [refueling] null — новая заправка. */
data class RefuelingTarget(
    val sheetId: String,
    val year: Int,
    val month: Int,
    val refueling: Refueling? = null,
) {
    val yearMonth: YearMonth get() = YearMonth.of(year, month)
}

fun FuelSheet.refuelingTarget(refueling: Refueling? = null) = RefuelingTarget(id, year, month, refueling)

/** Поля формы как их ввёл пользователь. */
data class RefuelingForm(
    val date: LocalDate,
    val liters: String = "",
    val pricePerLiter: String = "",
    val totalCost: String = "",
    /** Сумму ввели руками (скидка) — её не пересчитываем и отправляем; иначе считает сервер. */
    val isTotalManual: Boolean = false,
    val odometer: String = "",
    val station: String = "",
    val paymentType: PaymentType = PaymentType.PERSONAL,
    val note: String = "",
)

data class RefuelingErrors(
    val date: UiText? = null,
    val liters: UiText? = null,
    val pricePerLiter: UiText? = null,
    val totalCost: UiText? = null,
    val odometer: UiText? = null,
    val station: UiText? = null,
    val note: UiText? = null,
) {
    val hasAny: Boolean
        get() = listOf(date, liters, pricePerLiter, totalCost, odometer, station, note).any { it != null }
}

/** Новая заправка: сегодня, если сегодня в месяце листа, иначе ближайший день этого месяца. */
fun RefuelingTarget.newForm(today: LocalDate): RefuelingForm {
    val month = yearMonth
    val date = when {
        YearMonth.from(today) == month -> today
        today.isBefore(month.atDay(1)) -> month.atDay(1)
        else -> month.atEndOfMonth()
    }
    return RefuelingForm(date = date)
}

/** Заправка из API → форма. Сумма, не равная литры × цена, — значит, вводили руками. */
fun Refueling.toForm() = RefuelingForm(
    date = date,
    liters = liters.toInputText(),
    pricePerLiter = pricePerLiter.toInputText(),
    totalCost = totalCost.toMoneyText(),
    isTotalManual = totalCost.compareTo(liters.multiply(pricePerLiter).setScale(MONEY_SCALE, RoundingMode.HALF_UP)) != 0,
    odometer = odometerKm?.toString().orEmpty(),
    station = station.orEmpty(),
    paymentType = paymentType,
    note = note.orEmpty(),
)

/**
 * Предпросмотр суммы — единственный расчёт на клиенте (android/CLAUDE.md):
 * литры × цена, округление до копеек «по-школьному», как на сервере. Неполный ввод — пусто.
 */
fun previewTotal(liters: String, pricePerLiter: String): String {
    val l = Formatters.parseDecimalInput(liters) ?: return ""
    val p = Formatters.parseDecimalInput(pricePerLiter) ?: return ""
    return l.multiply(p).setScale(MONEY_SCALE, RoundingMode.HALF_UP).toMoneyText()
}

/** Пересчитать сумму после правки литров или цены, если её не вводили руками. */
fun RefuelingForm.withAutoTotal(): RefuelingForm =
    if (isTotalManual) this else copy(totalCost = previewTotal(liters, pricePerLiter))

/** «2750» → «2750,00»: у денег всегда копейки. */
private fun BigDecimal.toMoneyText(): String = setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString().replace('.', ',')

/** Проверка как на сервере. Ошибки нет — [RefuelingInput] для отправки. */
fun validateRefueling(form: RefuelingForm, target: RefuelingTarget): Pair<RefuelingInput?, RefuelingErrors> {
    val liters = parseNonNegativeDecimal(form.liters, MONEY_SCALE, LITERS_DIGITS, required = true).positive()
    val price = parseNonNegativeDecimal(form.pricePerLiter, MONEY_SCALE, PRICE_DIGITS, required = true)
    // Автосумму не отправляем — сервер посчитает сам точно так же
    val total = if (form.isTotalManual) {
        parseNonNegativeDecimal(form.totalCost, MONEY_SCALE, TOTAL_DIGITS, required = true)
    } else {
        ParsedInput(null, null)
    }
    val odometer = parseOptionalOdometer(form.odometer)
    val station = form.station.trim()
    val note = form.note.trim()

    val errors = RefuelingErrors(
        date = if (YearMonth.from(form.date) != target.yearMonth) UiText.Resource(R.string.error_date_outside_month) else null,
        liters = liters.error,
        pricePerLiter = price.error,
        totalCost = total.error,
        odometer = odometer.error,
        station = if (station.length > STATION_MAX) UiText.Resource(R.string.error_too_long, listOf(STATION_MAX)) else null,
        note = if (note.length > NOTE_MAX) UiText.Resource(R.string.error_too_long, listOf(NOTE_MAX)) else null,
    )
    if (errors.hasAny) return null to errors

    val input = RefuelingInput(
        date = form.date,
        liters = checkNotNull(liters.value),
        pricePerLiter = checkNotNull(price.value),
        totalCost = total.value,
        odometerKm = odometer.value,
        station = station.ifEmpty { null },
        paymentType = form.paymentType,
        note = note.ifEmpty { null },
    )
    return input to errors
}

/** Литры должны быть > 0 (ноль — тоже ошибка). */
private fun ParsedInput<BigDecimal>.positive(): ParsedInput<BigDecimal> {
    val notPositive = error == UiText.Resource(R.string.error_non_negative_number) || value?.signum() == 0
    return if (notPositive) ParsedInput(null, UiText.Resource(R.string.error_positive_number)) else this
}

private fun parseOptionalOdometer(text: String): ParsedInput<Long> {
    if (text.isBlank()) return ParsedInput(null, null)
    val value = text.trim().toLongOrNull() ?: return ParsedInput(null, UiText.Resource(R.string.error_whole_number))
    return if (value > ODOMETER_MAX) ParsedInput(null, UiText.Resource(R.string.error_too_big)) else ParsedInput(value, null)
}
