package ru.fueltracker.app.ui.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fueltracker.app.R
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.repository.AuthRepository
import ru.fueltracker.app.domain.model.LoginResult
import ru.fueltracker.app.ui.common.PhoneFormat
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.toUiText
import javax.inject.Inject

data class PasswordLoginUiState(
    /** 10 цифр после «+7». */
    val digits: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val error: UiText? = null,
    val snackbar: UiText? = null,
    val loggedIn: LoginResult? = null,
) {
    val canSubmit: Boolean get() = PhoneFormat.isComplete(digits) && password.isNotEmpty() && !isLoading
}

sealed interface PasswordLoginEvent {
    data class PhoneChanged(val value: String) : PasswordLoginEvent
    data class PasswordChanged(val value: String) : PasswordLoginEvent
    data object TogglePasswordVisibility : PasswordLoginEvent
    data object Submit : PasswordLoginEvent
    data object LoginHandled : PasswordLoginEvent
    data object SnackbarShown : PasswordLoginEvent
}

@HiltViewModel
class PasswordLoginViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
) : ViewModel() {

    // Номер с прошлого экрана: `+79991234567` или неполные цифры — applyInput понимает оба
    private val _state = MutableStateFlow(
        PasswordLoginUiState(
            digits = PhoneFormat.applyInput("", savedStateHandle.get<String>(ARG_PHONE).orEmpty()),
        ),
    )
    val state: StateFlow<PasswordLoginUiState> = _state.asStateFlow()

    fun onEvent(event: PasswordLoginEvent) {
        when (event) {
            is PasswordLoginEvent.PhoneChanged -> _state.update {
                it.copy(digits = PhoneFormat.applyInput(it.digits, event.value), error = null)
            }
            is PasswordLoginEvent.PasswordChanged -> _state.update {
                it.copy(password = event.value, error = null)
            }
            PasswordLoginEvent.TogglePasswordVisibility -> _state.update {
                it.copy(isPasswordVisible = !it.isPasswordVisible)
            }
            PasswordLoginEvent.Submit -> submit()
            PasswordLoginEvent.LoginHandled -> _state.update { it.copy(loggedIn = null) }
            PasswordLoginEvent.SnackbarShown -> _state.update { it.copy(snackbar = null) }
        }
    }

    private fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = authRepository.login(PhoneFormat.toApi(current.digits), current.password)) {
                is ApiResult.Success -> _state.update {
                    it.copy(isLoading = false, password = "", loggedIn = result.data)
                }
                is ApiResult.Failure -> {
                    val inline = loginErrorText(result.error)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = inline,
                            snackbar = if (inline == null) result.error.toUiText() else null,
                        )
                    }
                }
            }
        }
    }

    companion object {
        internal const val ARG_PHONE = "phone"
    }
}

private fun loginErrorText(error: ApiError): UiText? {
    if (error !is ApiError.Http) return null
    return when (error.status) {
        401 -> UiText.Resource(R.string.error_invalid_credentials)
        429 -> {
            val seconds = error.retryAfterSec ?: DEFAULT_RETRY_SEC
            // Округляем вверх: 61 сек → «2 мин», а не «1 мин»
            val minutes = ((seconds + 59) / 60).coerceAtLeast(1)
            UiText.Resource(R.string.error_too_many_attempts, listOf(minutes))
        }
        // Сервер называет поле (например, «Неверный номер телефона») — это понятнее общего «Неверные данные»
        400 -> (error.fieldError("phone") ?: error.fieldError("password") ?: error.message)?.let { UiText.Raw(it) }
        else -> null
    }
}
