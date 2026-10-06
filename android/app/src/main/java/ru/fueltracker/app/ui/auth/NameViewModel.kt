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
import ru.fueltracker.app.data.local.AppStateStorage
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.repository.ProfileRepository
import ru.fueltracker.app.ui.common.UiText
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

/**
 * Имя после первого входа или «Продолжить без входа» (07_UI_SCREENS.md, NameScreen).
 * Гость — имя только на телефоне. Аккаунт — имя на телефоне + `PATCH /me`; нет связи — не беда,
 * имя уйдёт при синхронизации.
 */
@HiltViewModel
class NameViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profileRepository: ProfileRepository,
    private val appState: AppStateStorage,
) : ViewModel() {

    private val isGuest: Boolean = savedStateHandle.get<Boolean>(ARG_GUEST) ?: false

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
        val name = current.name.trim()
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            if (isGuest) {
                appState.startGuest(name)
                _state.update { it.copy(isSaving = false, saved = true) }
                return@launch
            }
            appState.setName(name) // отправится при синхронизации, если PATCH не дойдёт
            when (val result = profileRepository.updateName(name)) {
                is ApiResult.Success -> {
                    appState.setSyncedName(result.data.name)
                    _state.update { it.copy(isSaving = false, saved = true) }
                }
                is ApiResult.Failure -> {
                    // 400 — сервер не принял имя: его текст под полем; нет связи — идём дальше
                    val inline = (result.error as? ApiError.Http)
                        ?.takeIf { it.status == 400 }
                        ?.let { it.fieldError("name") ?: it.message }
                        ?.let { UiText.Raw(it) }
                    _state.update {
                        if (inline != null) it.copy(isSaving = false, error = inline) else it.copy(isSaving = false, saved = true)
                    }
                }
            }
        }
    }

    companion object {
        /** Ограничение `PATCH /me`: name 1..100 символов. */
        const val NAME_MAX_LENGTH = 100

        /** Аргумент маршрута NameRoute.guest. */
        internal const val ARG_GUEST = "guest"
    }
}
