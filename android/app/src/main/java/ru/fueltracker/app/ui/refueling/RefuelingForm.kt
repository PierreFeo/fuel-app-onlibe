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

// Ограничения API (backend/app/schemas/refueling.py): литры и цена numeric(8,2)
private const val MONEY_SCALE = 2
private const val LITERS_DIGITS = 8
private const val PRICE_DIGITS = 8

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

/**
 * Поля формы как их ввёл пользователь: дата, литры, цена (07_UI_SCREENS.md, RefuelingEditScreen).
 * Одометр, АЗС, оплата и комментарий в форме не вводятся — у старых заправок (версия 1.0) они
 * переносятся как были, чтобы правка их не стёрла.
 */
data class RefuelingForm(
    val date: LocalDate,
    val liters: String = "",
    val pricePerLiter: String = "",
    val odometerKm: Long? = null,
    val station: String? = null,
    val paymentType: PaymentType = PaymentType.PERSONAL,
    val note: String? = null,
)

data class RefuelingErrors(
    val date: UiText? = null,
    val liters: UiText? = null,
    val pricePerLiter: UiText? = null,
) {
    val hasAny: Boolean get() = date != null || liters != null || pricePerLiter != null
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

/** Заправка из базы → форма. */
fun Refueling.toForm() = RefuelingForm(
    date = date,
    liters = liters.toInputText(),
    pricePerLiter = pricePerLiter.toInputText(),
    odometerKm = odometerKm,
    station = station,
    paymentType = paymentType,
    note = note,
)

/**
 * Сумма под полями — только показ: литры × цена, округление до копеек «по-школьному», как в
 * `SheetCalculator.totalCost`. Неполный ввод — null.
 */
fun previewTotal(liters: String, pricePerLiter: String): BigDecimal? {
    val l = Formatters.parseDecimalInput(liters) ?: return null
    val p = Formatters.parseDecimalInput(pricePerLiter) ?: return null
    return l.multiply(p).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
}

/** Проверка полей. Ошибок нет — [RefuelingInput] для записи. */
fun validateRefueling(form: RefuelingForm, target: RefuelingTarget): Pair<RefuelingInput?, RefuelingErrors> {
    val liters = parseNonNegativeDecimal(form.liters, MONEY_SCALE, LITERS_DIGITS, required = true).positive()
    val price = parseNonNegativeDecimal(form.pricePerLiter, MONEY_SCALE, PRICE_DIGITS, required = true)

    val errors = RefuelingErrors(
        date = if (YearMonth.from(form.date) != target.yearMonth) UiText.Resource(R.string.error_date_outside_month) else null,
        liters = liters.error,
        pricePerLiter = price.error,
    )
    if (errors.hasAny) return null to errors

    val input = RefuelingInput(
        date = form.date,
        liters = checkNotNull(liters.value),
        pricePerLiter = checkNotNull(price.value),
        // Сумма всегда литры × цена — посчитает репозиторий (06_BUSINESS_RULES.md, правило 9)
        totalCost = null,
        odometerKm = form.odometerKm,
        station = form.station,
        paymentType = form.paymentType,
        note = form.note,
    )
    return input to errors
}

/** Литры должны быть > 0 (ноль — тоже ошибка). */
private fun ParsedInput<BigDecimal>.positive(): ParsedInput<BigDecimal> {
    val notPositive = error == UiText.Resource(R.string.error_non_negative_number) || value?.signum() == 0
    return if (notPositive) ParsedInput(null, UiText.Resource(R.string.error_positive_number)) else this
}
