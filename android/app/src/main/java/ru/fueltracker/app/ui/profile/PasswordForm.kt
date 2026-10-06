package ru.fueltracker.app.ui.profile

import ru.fueltracker.app.R
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.toUiText

/**
 * Диалог «Задать / Сменить пароль» (07_UI_SCREENS.md, ProfileScreen; `PUT /me/password`).
 * [needsCurrent] — пароль уже задан: для смены нужен текущий.
 */
data class PasswordForm(
    val needsCurrent: Boolean,
    val current: String = "",
    val new: String = "",
    val repeat: String = "",
    val currentError: UiText? = null,
    val newError: UiText? = null,
    val repeatError: UiText? = null,
    /** Ошибка запроса (нет связи, блокировка) — текстом в диалоге. */
    val error: UiText? = null,
    val isSaving: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !isSaving && new.isNotEmpty() && repeat.isNotEmpty() && (!needsCurrent || current.isNotEmpty())
}

/** Проверки на телефоне (как на сервере): 8..64 символа, не только пробелы, пароли совпадают. */
fun validatePasswordForm(form: PasswordForm): PasswordForm {
    val newError = when {
        form.new.length !in PASSWORD_MIN..PASSWORD_MAX -> UiText.Resource(R.string.error_password_length)
        form.new.isBlank() -> UiText.Resource(R.string.error_password_blank)
        else -> null
    }
    val repeatError = UiText.Resource(R.string.error_password_mismatch).takeIf { newError == null && form.repeat != form.new }
    val currentError = UiText.Resource(R.string.error_required).takeIf { form.needsCurrent && form.current.isEmpty() }
    return form.copy(currentError = currentError, newError = newError, repeatError = repeatError, error = null)
}

val PasswordForm.hasErrors: Boolean get() = currentError != null || newError != null || repeatError != null

/** Ответ сервера → ошибка под полем или общим текстом диалога. */
fun PasswordForm.withServerError(error: ApiError): PasswordForm {
    if (error is ApiError.Http) {
        when {
            error.fieldError("current_password") != null ->
                return copy(isSaving = false, currentError = UiText.Resource(R.string.error_password_wrong))
            error.fieldError("new_password") != null ->
                return copy(isSaving = false, newError = UiText.Raw(error.fieldError("new_password")!!))
            error.status == 429 -> {
                // Округляем вверх: 61 сек → «2 мин», а не «1 мин»
                val minutes = ((error.retryAfterSec ?: 60) + 59) / 60
                return copy(isSaving = false, error = UiText.Resource(R.string.error_too_many_attempts, listOf(minutes.coerceAtLeast(1))))
            }
        }
    }
    return copy(isSaving = false, error = error.toUiText())
}

const val PASSWORD_MIN = 8
const val PASSWORD_MAX = 64
