package ru.fueltracker.app.ui.auth

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
import ru.fueltracker.app.domain.model.LoginResult
import ru.fueltracker.app.testutil.FakeAuthRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.httpError
import ru.fueltracker.app.testutil.networkError
import ru.fueltracker.app.ui.common.UiText

class CodeViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeAuthRepository()

    private fun createViewModel(resendAfterSec: Int = 60) = CodeViewModel(
        SavedStateHandle(mapOf("phone" to PHONE, "resendAfterSec" to resendAfterSec)),
        repository,
    )

    // Таймер бесконечно не крутится, но завершаем его, чтобы runTest не ждал
    private fun TestScope.finishTimer() = advanceTimeBy(10 * 60 * 1000L)

    @Test
    fun `номер берётся из маршрута`() = runTest {
        val viewModel = createViewModel()
        assertEquals(PHONE, viewModel.state.value.phone)
        finishTimer()
    }

    @Test
    fun `код отправляется сам при вводе 6-й цифры`() = runTest {
        repository.verifyCodeResult = ApiResult.Success(LoginResult(isNewUser = true))
        val viewModel = createViewModel()

        viewModel.onEvent(CodeEvent.CodeChanged("12345"))
        runCurrent()
        assertTrue(repository.verifyCodeCalls.isEmpty())

        viewModel.onEvent(CodeEvent.CodeChanged("123456"))
        assertTrue(viewModel.state.value.isVerifying)
        runCurrent()

        assertEquals(listOf(PHONE to "123456"), repository.verifyCodeCalls)
        assertEquals(LoginResult(isNewUser = true), viewModel.state.value.loggedIn)
        assertFalse(viewModel.state.value.isVerifying)
        finishTimer()
    }

    @Test
    fun `в коде остаются только цифры, не больше 6`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(CodeEvent.CodeChanged("12a3"))
        assertEquals("123", viewModel.state.value.code)
        viewModel.onEvent(CodeEvent.CodeChanged("1234567"))
        assertEquals("123456", viewModel.state.value.code)
        finishTimer()
    }

    @Test
    fun `неверный код — сообщение и пустые ячейки`() = runTest {
        repository.verifyCodeResult = httpError(401, ErrorCodes.OTP_INVALID)
        val viewModel = createViewModel()
        viewModel.onEvent(CodeEvent.CodeChanged("111111"))
        runCurrent()

        val state = viewModel.state.value
        assertEquals(UiText.Resource(R.string.error_code_invalid), state.error)
        assertEquals("", state.code)
        assertNull(state.loggedIn)

        viewModel.onEvent(CodeEvent.CodeChanged("2"))
        assertNull(viewModel.state.value.error)
        finishTimer()
    }

    @Test
    fun `код истёк`() = runTest {
        repository.verifyCodeResult = httpError(401, ErrorCodes.OTP_EXPIRED)
        val viewModel = createViewModel()
        viewModel.onEvent(CodeEvent.CodeChanged("111111"))
        runCurrent()
        assertEquals(UiText.Resource(R.string.error_code_expired), viewModel.state.value.error)
        finishTimer()
    }

    @Test
    fun `нет сети при проверке — Snackbar`() = runTest {
        repository.verifyCodeResult = networkError
        val viewModel = createViewModel()
        viewModel.onEvent(CodeEvent.CodeChanged("111111"))
        runCurrent()
        assertNull(viewModel.state.value.error)
        assertEquals(UiText.Resource(R.string.error_no_connection), viewModel.state.value.snackbar)
        finishTimer()
    }

    @Test
    fun `таймер считает секунды до повторной отправки`() = runTest {
        val viewModel = createViewModel(resendAfterSec = 3)
        runCurrent()
        assertEquals(3, viewModel.state.value.resendSecondsLeft)
        assertFalse(viewModel.state.value.canResend)

        advanceTimeBy(1_001)
        assertEquals(2, viewModel.state.value.resendSecondsLeft)

        advanceTimeBy(2_000)
        assertEquals(0, viewModel.state.value.resendSecondsLeft)
        assertTrue(viewModel.state.value.canResend)
    }

    @Test
    fun `до конца таймера повторно не отправить`() = runTest {
        val viewModel = createViewModel(resendAfterSec = 60)
        runCurrent()
        viewModel.onEvent(CodeEvent.Resend)
        runCurrent()
        assertTrue(repository.requestCodeCalls.isEmpty())
        finishTimer()
    }

    @Test
    fun `повторная отправка перезапускает таймер`() = runTest {
        repository.requestCodeResult = ApiResult.Success(CodeRequest(resendAfterSec = 60))
        val viewModel = createViewModel(resendAfterSec = 1)
        advanceTimeBy(1_001)
        assertTrue(viewModel.state.value.canResend)

        viewModel.onEvent(CodeEvent.Resend)
        runCurrent()

        assertEquals(listOf(PHONE), repository.requestCodeCalls)
        val state = viewModel.state.value
        assertEquals(60, state.resendSecondsLeft)
        assertEquals(UiText.Resource(R.string.code_resent), state.snackbar)
        finishTimer()
    }

    @Test
    fun `повторная отправка 429 — ошибка и таймер на retry_after_sec`() = runTest {
        repository.requestCodeResult = httpError(429, ErrorCodes.RATE_LIMITED, details = mapOf("retry_after_sec" to 30))
        val viewModel = createViewModel(resendAfterSec = 0)
        runCurrent()

        viewModel.onEvent(CodeEvent.Resend)
        runCurrent()

        val state = viewModel.state.value
        assertEquals(UiText.Resource(R.string.error_retry_after_sec, listOf(30)), state.error)
        assertEquals(30, state.resendSecondsLeft)
        finishTimer()
    }

    private companion object {
        const val PHONE = "+79991234567"
    }
}
