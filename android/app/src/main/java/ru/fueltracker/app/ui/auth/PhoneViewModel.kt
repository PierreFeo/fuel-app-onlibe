package ru.fueltracker.app.ui.auth

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
import ru.fueltracker.app.ui.common.PhoneFormat
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.toUiText
import javax.inject.Inject

/** Куда перейти после успешного запроса кода. */
data class CodeSent(val phone: String, val resendAfterSec: Int)

data class PhoneUiState(
    /** 10 цифр после «+7». */
    val digits: String = "",
    val isLoading: Boolean = false,
    /** Ошибка под полем: номер не разрешён, слишком часто. */
    val error: UiText? = null,
    val snackbar: UiText? = null,
    val codeSent: CodeSent? = null,
) {
    val canSubmit: Boolean get() = PhoneFormat.isComplete(digits) && !isLoading
}

sealed interface PhoneEvent {
    data class PhoneChanged(val value: String) : PhoneEvent
    data object Submit : PhoneEvent
    data object CodeSentHandled : PhoneEvent
    data object SnackbarShown : PhoneEvent
}

@HiltViewModel
class PhoneViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PhoneUiState())
    val state: StateFlow<PhoneUiState> = _state.asStateFlow()

    fun onEvent(event: PhoneEvent) {
        when (event) {
            is PhoneEvent.PhoneChanged -> _state.update {
                it.copy(digits = PhoneFormat.applyInput(it.digits, event.value), error = null)
            }
            PhoneEvent.Submit -> submit()
            PhoneEvent.CodeSentHandled -> _state.update { it.copy(codeSent = null) }
            PhoneEvent.SnackbarShown -> _state.update { it.copy(snackbar = null) }
        }
    }

    private fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        val phone = PhoneFormat.toApi(current.digits)
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = authRepository.requestCode(phone)) {
                is ApiResult.Success -> _state.update {
                    it.copy(isLoading = false, codeSent = CodeSent(phone, result.data.resendAfterSec))
                }
                is ApiResult.Failure -> _state.update { it.withError(result.error) }
            }
        }
    }

    private fun PhoneUiState.withError(error: ApiError): PhoneUiState {
        val inline = requestCodeErrorText(error)
        return copy(
            isLoading = false,
            error = inline,
            snackbar = if (inline == null) error.toUiText() else null,
        )
    }
}

/**
 * Ошибки запроса кода, которые показываются под полем (PhoneScreen и повторная отправка на CodeScreen).
 * null — ошибка общая (сеть, сервер), её показывают в Snackbar.
 */
internal fun requestCodeErrorText(error: ApiError): UiText? {
    if (error !is ApiError.Http) return null
    return when (error.status) {
        403 -> UiText.Resource(R.string.error_phone_not_allowed)
        429 -> UiText.Resource(R.string.error_retry_after_sec, listOf(error.retryAfterSec ?: DEFAULT_RETRY_SEC))
        400 -> error.message?.let { UiText.Raw(it) }
        else -> null
    }
}

internal const val DEFAULT_RETRY_SEC = 60
