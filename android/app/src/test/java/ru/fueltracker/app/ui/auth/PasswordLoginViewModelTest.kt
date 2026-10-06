package ru.fueltracker.app.ui.auth

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.fueltracker.app.R
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.domain.model.LoginResult
import ru.fueltracker.app.testutil.FakeAuthRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.httpError
import ru.fueltracker.app.testutil.networkError
import ru.fueltracker.app.ui.common.UiText

class PasswordLoginViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeAuthRepository()

    private fun createViewModel(phone: String? = null) = PasswordLoginViewModel(
        SavedStateHandle(if (phone != null) mapOf("phone" to phone) else emptyMap()),
        repository,
    )

    private fun PasswordLoginViewModel.fill(digits: String = "9991234567", password: String = "k7Fm2xQp9a") {
        onEvent(PasswordLoginEvent.PhoneChanged(digits))
        onEvent(PasswordLoginEvent.PasswordChanged(password))
    }

    @Test
    fun `номер с CodeScreen подставляется`() {
        assertEquals("9991234567", createViewModel("+79991234567").state.value.digits)
    }

    @Test
    fun `неполный номер с PhoneScreen подставляется`() {
        assertEquals("999", createViewModel("999").state.value.digits)
    }

    @Test
    fun `без номера — пустое поле`() {
        assertEquals("", createViewModel().state.value.digits)
    }

    @Test
    fun `кнопка неактивна без полного номера или пароля`() {
        val viewModel = createViewModel()
        viewModel.fill(digits = "999123456")
        assertFalse(viewModel.state.value.canSubmit)
        viewModel.fill(password = "")
        assertFalse(viewModel.state.value.canSubmit)
        viewModel.fill()
        assertTrue(viewModel.state.value.canSubmit)
    }

    @Test
    fun `показать и скрыть пароль`() {
        val viewModel = createViewModel()
        assertFalse(viewModel.state.value.isPasswordVisible)
        viewModel.onEvent(PasswordLoginEvent.TogglePasswordVisibility)
        assertTrue(viewModel.state.value.isPasswordVisible)
    }

    @Test
    fun `успешный вход`() = runTest {
        repository.loginResult = ApiResult.Success(LoginResult(isNewUser = true))
        val viewModel = createViewModel()
        viewModel.fill()
        viewModel.onEvent(PasswordLoginEvent.Submit)
        assertTrue(viewModel.state.value.isLoading)
        advanceUntilIdle()

        assertEquals(listOf("+79991234567" to "k7Fm2xQp9a"), repository.loginCalls)
        assertEquals(LoginResult(isNewUser = true), viewModel.state.value.loggedIn)
        assertEquals("", viewModel.state.value.password)
    }

    @Test
    fun `401 — неверный номер или пароль`() = runTest {
        repository.loginResult = httpError(401, ErrorCodes.INVALID_CREDENTIALS)
        val viewModel = createViewModel()
        viewModel.fill()
        viewModel.onEvent(PasswordLoginEvent.Submit)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(UiText.Resource(R.string.error_invalid_credentials), state.error)
        assertNull(state.loggedIn)
        assertFalse(state.isLoading)
    }

    @Test
    fun `400 — причина по полю, а не общее «Неверные данные»`() = runTest {
        repository.loginResult = httpError(
            400,
            ErrorCodes.VALIDATION_ERROR,
            message = "Неверные данные запроса",
            details = mapOf("phone" to "Неверный номер телефона"),
        )
        val viewModel = createViewModel()
        viewModel.fill()
        viewModel.onEvent(PasswordLoginEvent.Submit)
        advanceUntilIdle()

        assertEquals(UiText.Raw("Неверный номер телефона"), viewModel.state.value.error)
    }

    @Test
    fun `429 — минуты округляются вверх`() = runTest {
        repository.loginResult = httpError(429, ErrorCodes.RATE_LIMITED, details = mapOf("retry_after_sec" to 840))
        val viewModel = createViewModel()
        viewModel.fill()
        viewModel.onEvent(PasswordLoginEvent.Submit)
        advanceUntilIdle()
        assertEquals(UiText.Resource(R.string.error_too_many_attempts, listOf(14)), viewModel.state.value.error)

        repository.loginResult = httpError(429, ErrorCodes.RATE_LIMITED, details = mapOf("retry_after_sec" to 61))
        viewModel.onEvent(PasswordLoginEvent.Submit)
        advanceUntilIdle()
        assertEquals(UiText.Resource(R.string.error_too_many_attempts, listOf(2)), viewModel.state.value.error)
    }

    @Test
    fun `нет сети — Snackbar`() = runTest {
        repository.loginResult = networkError
        val viewModel = createViewModel()
        viewModel.fill()
        viewModel.onEvent(PasswordLoginEvent.Submit)
        advanceUntilIdle()
        assertNull(viewModel.state.value.error)
        assertEquals(UiText.Resource(R.string.error_no_connection), viewModel.state.value.snackbar)
    }
}
