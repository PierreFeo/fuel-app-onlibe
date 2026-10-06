package ru.fueltracker.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.fueltracker.app.R
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.domain.calc.SheetRuleViolation

/**
 * Текст для экрана, который ViewModel готовит без Context:
 * строка из strings.xml или готовый текст с сервера.
 */
sealed interface UiText {
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Resource -> stringResource(id, *args.toTypedArray())
    is UiText.Raw -> text
}

/** Ошибка запроса для Snackbar: `error.message` сервера, иначе «Нет связи с сервером». */
fun ApiError.toUiText(): UiText {
    val serverMessage = (this as? ApiError.Http)?.message
    return if (serverMessage != null) UiText.Raw(serverMessage) else UiText.Resource(R.string.error_no_connection)
}

/** Нарушенное правило ЛУТ (docs/06_BUSINESS_RULES.md, «Валидации») — текст для экрана. */
fun SheetRuleViolation.toUiText(): UiText = UiText.Resource(
    when (this) {
        SheetRuleViolation.ODOMETER_END_BEFORE_START -> R.string.rule_odometer_end_before_start
        SheetRuleViolation.FUEL_END_OVER_AVAILABLE -> R.string.rule_fuel_end_over_available
        SheetRuleViolation.REFUELING_DATE_OUTSIDE_MONTH -> R.string.rule_refueling_date_outside_month
        SheetRuleViolation.SHEET_EXISTS -> R.string.rule_sheet_exists
        SheetRuleViolation.MONTH_TOO_FAR -> R.string.rule_month_too_far
        SheetRuleViolation.SHEET_CLOSED -> R.string.sheet_closed_cannot_edit
        SheetRuleViolation.WINTER_NORM_NOT_SET -> R.string.winter_norm_not_set
        SheetRuleViolation.SHEET_HAS_REFUELINGS -> R.string.rule_sheet_has_refuelings
        SheetRuleViolation.NOT_FOUND -> R.string.rule_not_found
    },
)
