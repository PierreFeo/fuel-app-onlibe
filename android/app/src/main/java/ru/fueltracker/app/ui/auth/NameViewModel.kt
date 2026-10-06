package ru.fueltracker.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.repository.ProfileRepository
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.toUiText
import javax.inject.Inject

data class NameUiState(
    val name: String = "",
    val isSaving: Boolean = false,
    val error: UiText? = null,
    val snackbar: UiText? = null,
    val saved: Boolean = false,
) {
    val canSubmit: Boolean get() = name.isNotBlank() && !isSaving
}

sealed interface NameEvent {
    data class NameChanged(val value: String) : NameEvent
    data object Submit : NameEvent
    data object SavedHandled : NameEvent
    data object SnackbarShown : NameEvent
}

@HiltViewModel
class NameViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NameUiState())
    val state: StateFlow<NameUiState> = _state.asStateFlow()

    fun onEvent(event: NameEvent) {
        when (event) {
            is NameEvent.NameChanged -> _state.update {
                it.copy(name = event.value.take(NAME_MAX_LENGTH), error = null)
            }
            NameEvent.Submit -> submit()
            NameEvent.SavedHandled -> _state.update { it.copy(saved = false) }
            NameEvent.SnackbarShown -> _state.update { it.copy(snackbar = null) }
        }
    }

    private fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            when (val result = profileRepository.updateName(current.name.trim())) {
                is ApiResult.Success -> _state.update { it.copy(isSaving = false, saved = true) }
                is ApiResult.Failure -> {
                    val error = result.error
                    // 400 — сервер не принял имя: его текст показываем под полем
                    val inline = (error as? ApiError.Http)
                        ?.takeIf { it.status == 400 }
                        ?.let { it.fieldError("name") ?: it.message }
                        ?.let { UiText.Raw(it) }
                    _state.update {
                        it.copy(
                            isSaving = false,
                            error = inline,
                            snackbar = if (inline == null) error.toUiText() else null,
                        )
                    }
                }
            }
        }
    }

    companion object {
        /** Ограничение `PATCH /me`: name 1..100 символов. */
        const val NAME_MAX_LENGTH = 100
    }
}
