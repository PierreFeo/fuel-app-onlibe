package ru.fueltracker.app.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fueltracker.app.R
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.testutil.httpError
import ru.fueltracker.app.testutil.networkError
import ru.fueltracker.app.ui.common.UiText

/** Проверки пароля на телефоне и ответы сервера (docs/04_API_CONTRACT.md, `PUT /me/password`). */
class PasswordFormTest {

    private val first = PasswordForm(needsCurrent = false)
    private val change = PasswordForm(needsCurrent = true)

    @Test
    fun `кнопка активна, когда заполнены нужные поля`() {
        assertFalse(first.canSubmit)
        assertTrue(first.copy(new = "12345678", repeat = "1").canSubmit)
        assertFalse(change.copy(new = "12345678", repeat = "12345678").canSubmit) // нет текущего
        assertTrue(change.copy(current = "x", new = "12345678", repeat = "12345678").canSubmit)
        assertFalse(first.copy(new = "12345678", repeat = "12345678", isSaving = true).canSubmit)
    }

    @Test
    fun `длина от 8 до 64`() {
        val length = UiText.Resource(R.string.error_password_length)
        assertEquals(length, validatePasswordForm(first.copy(new = "1234567", repeat = "1234567")).newError)
        assertEquals(length, validatePasswordForm(first.copy(new = "я".repeat(65), repeat = "я".repeat(65))).newError)
        assertFalse(validatePasswordForm(first.copy(new = "12345678", repeat = "12345678")).hasErrors)
        assertFalse(validatePasswordForm(first.copy(new = "я".repeat(64), repeat = "я".repeat(64))).hasErrors)
    }

    @Test
    fun `только пробелы — нельзя`() {
        val form = validatePasswordForm(first.copy(new = "         ", repeat = "         "))
        assertEquals(UiText.Resource(R.string.error_password_blank), form.newError)
    }

    @Test
    fun `пароли должны совпадать`() {
        val form = validatePasswordForm(first.copy(new = "12345678", repeat = "12345679"))
        assertNull(form.newError)
        assertEquals(UiText.Resource(R.string.error_password_mismatch), form.repeatError)
    }

    @Test
    fun `неверный текущий пароль — под полем текущего`() {
        val error = httpError(400, ErrorCodes.VALIDATION_ERROR, details = mapOf("current_password" to "Неверный пароль"))

        val form = change.copy(isSaving = true).withServerError(error.error)

        assertFalse(form.isSaving)
        assertEquals(UiText.Resource(R.string.error_password_wrong), form.currentError)
    }

    @Test
    fun `блокировка — через сколько минут повторить`() {
        val error = httpError(429, ErrorCodes.RATE_LIMITED, details = mapOf("retry_after_sec" to 840))

        assertEquals(UiText.Resource(R.string.error_too_many_attempts, listOf(14)), change.withServerError(error.error).error)
    }

    @Test
    fun `нет связи — текстом в диалоге`() {
        assertEquals(UiText.Resource(R.string.error_no_connection), change.withServerError(networkError.error).error)
    }
}
