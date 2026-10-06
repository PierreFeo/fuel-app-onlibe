package ru.fueltracker.app.ui.auth

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
import ru.fueltracker.app.domain.model.CodeRequest
import ru.fueltracker.app.testutil.FakeAuthRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.httpError
import ru.fueltracker.app.testutil.networkError
import ru.fueltracker.app.ui.common.UiText

class PhoneViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeAuthRepository()
    private val viewModel = PhoneViewModel(repository)

    private fun enterPhone(value: String = "9991234567") = viewModel.onEvent(PhoneEvent.PhoneChanged(value))

    @Test
    fun `кнопка неактивна, пока номер неполный`() {
        enterPhone("999123456")
        assertFalse(viewModel.state.value.canSubmit)
        enterPhone("9991234567")
        assertTrue(viewModel.state.value.canSubmit)
    }

    @Test
    fun `неполный номер не отправляется`() = runTest {
        enterPhone("999")
        viewModel.onEvent(PhoneEvent.Submit)
        advanceUntilIdle()
        assertTrue(repository.requestCodeCalls.isEmpty())
    }

    @Test
    fun `успех — переход на экран кода с номером и таймером`() = runTest {
        repository.requestCodeResult = ApiResult.Success(CodeRequest(resendAfterSec = 45))
        enterPhone()
        viewModel.onEvent(PhoneEvent.Submit)
        assertTrue(viewModel.state.value.isLoading)
        advanceUntilIdle()

        assertEquals(listOf("+79991234567"), repository.requestCodeCalls)
        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertEquals(CodeSent("+79991234567", 45), state.codeSent)

        viewModel.onEvent(PhoneEvent.CodeSentHandled)
        assertNull(viewModel.state.value.codeSent)
    }

    @Test
    fun `403 — номер не зарегистрирован`() = runTest {
        repository.requestCodeResult = httpError(403, ErrorCodes.PHONE_NOT_ALLOWED)
        enterPhone()
        viewModel.onEvent(PhoneEvent.Submit)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(UiText.Resource(R.string.error_phone_not_allowed), state.error)
        assertNull(state.snackbar)
        assertNull(state.codeSent)
    }

    @Test
    fun `429 — повторите через N сек`() = runTest {
        repository.requestCodeResult = httpError(429, ErrorCodes.RATE_LIMITED, details = mapOf("retry_after_sec" to 42))
        enterPhone()
        viewModel.onEvent(PhoneEvent.Submit)
        advanceUntilIdle()

        assertEquals(UiText.Resource(R.string.error_retry_after_sec, listOf(42)), viewModel.state.value.error)
    }

    @Test
    fun `нет сети — Snackbar «Нет связи с сервером»`() = runTest {
        repository.requestCodeResult = networkError
        enterPhone()
        viewModel.onEvent(PhoneEvent.Submit)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.error)
        assertEquals(UiText.Resource(R.string.error_no_connection), state.snackbar)
        viewModel.onEvent(PhoneEvent.SnackbarShown)
        assertNull(viewModel.state.value.snackbar)
    }

    @Test
    fun `502 — Snackbar с текстом сервера`() = runTest {
        repository.requestCodeResult = httpError(502, ErrorCodes.SMS_SEND_FAILED, message = "Не удалось отправить SMS")
        enterPhone()
        viewModel.onEvent(PhoneEvent.Submit)
        advanceUntilIdle()

        assertEquals(UiText.Raw("Не удалось отправить SMS"), viewModel.state.value.snackbar)
    }

    @Test
    fun `правка номера убирает ошибку`() = runTest {
        repository.requestCodeResult = httpError(403, ErrorCodes.PHONE_NOT_ALLOWED)
        enterPhone()
        viewModel.onEvent(PhoneEvent.Submit)
        advanceUntilIdle()
        enterPhone("999123456")
        assertNull(viewModel.state.value.error)
    }
}
