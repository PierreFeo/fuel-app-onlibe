package ru.fueltracker.app.ui.sheets

import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.NewSheetInput
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetPrefill
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.parseKmInput
import ru.fueltracker.app.ui.common.parseNonNegativeDecimal
import ru.fueltracker.app.ui.common.toInputText
import java.math.BigDecimal
import java.time.YearMonth

// Остаток топлива в листе — numeric(8,2) (03_DATA_MODEL.md)
private const val FUEL_SCALE = 2
private const val FUEL_MAX_DIGITS = 8

/** Состояние NewSheetDialog; поля подставлены из `next-prefill`, пользователь может их изменить. */
data class NewSheetForm(
    val year: Int,
    val month: Int,
    val season: Season,
    val odometerStart: String,
    val fuelStart: String,
    val odometerError: UiText? = null,
    val fuelError: UiText? = null,
    /** Ошибка сервера (лист за месяц уже есть, месяц слишком далеко, нет связи). */
    val error: UiText? = null,
    val isSaving: Boolean = false,
) {
    /** Сдвинуть месяц стрелками ‹ ›. */
    fun shiftMonth(delta: Long): NewSheetForm {
        val shifted = YearMonth.of(year, month).plusMonths(delta)
        return copy(year = shifted.year, month = shifted.monthValue, error = null)
    }
}

fun SheetPrefill.toForm() = NewSheetForm(
    year = year,
    month = month,
    season = season,
    odometerStart = odometerStartKm.toString(),
    fuelStart = fuelStartL.toInputText(),
)

/** Проверка формы; null — есть ошибки, они записаны в возвращённую форму. */
fun validateNewSheet(form: NewSheetForm): Pair<NewSheetInput?, NewSheetForm> {
    val odometer = parseKmInput(form.odometerStart)
    val fuel = parseNonNegativeDecimal(form.fuelStart, FUEL_SCALE, FUEL_MAX_DIGITS, required = true)
    val checked = form.copy(odometerError = odometer.error, fuelError = fuel.error, error = null)
    if (odometer.value == null || fuel.value == null) return null to checked
    return NewSheetInput(form.year, form.month, odometer.value, fuel.value, form.season) to checked
}

/** Состояние CloseSheetDialog. */
data class CloseSheetForm(
    val sheetId: String,
    val year: Int,
    val month: Int,
    val odometerStartKm: Long,
    val odometerEnd: String,
    val fuelEndActual: String,
    val odometerError: UiText? = null,
    val fuelError: UiText? = null,
    val error: UiText? = null,
    val isSaving: Boolean = false,
)

/** Если пробег на конец или остаток уже вводили — подставляем их. */
fun FuelSheet.toCloseForm() = CloseSheetForm(
    sheetId = id,
    year = year,
    month = month,
    odometerStartKm = odometerStartKm,
    odometerEnd = odometerEndKm?.toString().orEmpty(),
    fuelEndActual = fuelEndActualL?.toInputText().orEmpty(),
)

data class CloseSheetInput(val odometerEndKm: Long, val fuelEndActualL: BigDecimal?)

/**
 * Пробег на конец обязателен и не меньше пробега на начало; остаток необязателен.
 * «Остаток не больше доступного» проверяет сервер — его текст покажется в [CloseSheetForm.error].
 */
fun validateCloseSheet(form: CloseSheetForm): Pair<CloseSheetInput?, CloseSheetForm> {
    val parsedOdometer = parseKmInput(form.odometerEnd)
    val odometerError = parsedOdometer.error ?: parsedOdometer.value?.takeIf { it < form.odometerStartKm }?.let {
        UiText.Resource(R.string.error_odometer_end_before_start, listOf(Formatters.km(form.odometerStartKm)))
    }
    val fuel = parseNonNegativeDecimal(form.fuelEndActual, FUEL_SCALE, FUEL_MAX_DIGITS, required = false)
    val checked = form.copy(odometerError = odometerError, fuelError = fuel.error, error = null)
    if (odometerError != null || fuel.error != null) return null to checked
    return CloseSheetInput(checkNotNull(parsedOdometer.value), fuel.value) to checked
}
