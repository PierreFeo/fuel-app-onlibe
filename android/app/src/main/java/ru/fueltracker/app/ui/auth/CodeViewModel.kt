package ru.fueltracker.app.ui.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fueltracker.app.R
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.data.repository.AuthRepository
import ru.fueltracker.app.domain.model.LoginResult
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.toUiText
import javax.inject.Inject

data class CodeUiState(
    /** `+79991234567`. */
    val phone: String,
    val code: String = "",
    val isVerifying: Boolean = false,
    /** Через сколько секунд можно запросить код снова; 0 — кнопка «Отправить ещё раз». */
    val resendSecondsLeft: Int = 0,
    val isResending: Boolean = false,
    val error: UiText? = null,
    val snackbar: UiText? = null,
    val loggedIn: LoginResult? = null,
) {
    val canResend: Boolean get() = resendSecondsLeft == 0 && !isResending && !isVerifying
}

sealed interface CodeEvent {
    data class CodeChanged(val value: String) : CodeEvent
    data object Resend : CodeEvent
    data object LoginHandled : CodeEvent
    data object SnackbarShown : CodeEvent
}

@HiltViewModel
class CodeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
) : ViewModel() {

    // Аргументы маршрута CodeRoute лежат в SavedStateHandle под именами его полей
    private val _state = MutableStateFlow(
        CodeUiState(phone = checkNotNull(savedStateHandle.get<String>(ARG_PHONE))),
    )
    val state: StateFlow<CodeUiState> = _state.asStateFlow()

    private var timerJob: Job? = null

    init {
        startResendTimer(savedStateHandle.get<Int>(ARG_RESEND_AFTER_SEC) ?: DEFAULT_RETRY_SEC)
    }

    fun onEvent(event: CodeEvent) {
        when (event) {
            is CodeEvent.CodeChanged -> onCodeChanged(event.value)
            CodeEvent.Resend -> resend()
            CodeEvent.LoginHandled -> _state.update { it.copy(loggedIn = null) }
            CodeEvent.SnackbarShown -> _state.update { it.copy(snackbar = null) }
        }
    }

    private fun onCodeChanged(value: String) {
        if (_state.value.isVerifying) return
        val code = value.filter { it in '0'..'9' }.take(CODE_LENGTH)
        _state.update { it.copy(code = code, error = null) }
        if (code.length == CODE_LENGTH) verify(code)
    }

    private fun verify(code: String) {
        _state.update { it.copy(isVerifying = true) }
        viewModelScope.launch {
            when (val result = authRepository.verifyCode(_state.value.phone, code)) {
                is ApiResult.Success -> {
                    timerJob?.cancel()
                    _state.update { it.copy(isVerifying = false, loggedIn = result.data) }
                }
                is ApiResult.Failure -> {
                    val inline = verifyErrorText(result.error)
                    // Код стираем, чтобы сразу ввести новый
                    _state.update {
                        it.copy(
                            isVerifying = false,
                            code = "",
                            error = inline,
                            snackbar = if (inline == null) result.error.toUiText() else null,
                        )
                    }
                }
            }
        }
    }

    private fun resend() {
        if (!_state.value.canResend) return
        _state.update { it.copy(isResending = true, error = null) }
        viewModelScope.launch {
            when (val result = authRepository.requestCode(_state.value.phone)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(isResending = false, code = "", snackbar = UiText.Resource(R.string.code_resent))
                    }
                    startResendTimer(result.data.resendAfterSec)
                }
                is ApiResult.Failure -> {
                    val error = result.error
                    val inline = requestCodeErrorText(error)
                    _state.update {
                        it.copy(
                            isResending = false,
                            error = inline,
                            snackbar = if (inline == null) error.toUiText() else null,
                        )
                    }
                    // 429: кнопка вернётся, когда сервер снова разрешит запрос
                    (error as? ApiError.Http)?.retryAfterSec?.let { startResendTimer(it) }
                }
            }
        }
    }

    private fun startResendTimer(seconds: Int) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var left = seconds.coerceAtLeast(0)
            _state.update { it.copy(resendSecondsLeft = left) }
            while (left > 0) {
                delay(1_000)
                left--
                _state.update { it.copy(resendSecondsLeft = left) }
            }
        }
    }

    companion object {
        const val CODE_LENGTH = 6
        internal const val ARG_PHONE = "phone"
        internal const val ARG_RESEND_AFTER_SEC = "resendAfterSec"
    }
}

private fun verifyErrorText(error: ApiError): UiText? = when ((error as? ApiError.Http)?.code) {
    ErrorCodes.OTP_INVALID -> UiText.Resource(R.string.error_code_invalid)
    ErrorCodes.OTP_EXPIRED -> UiText.Resource(R.string.error_code_expired)
    else -> null
}
