package ru.fueltracker.app.ui.profile

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
import ru.fueltracker.app.data.repository.ProfileRepository
import ru.fueltracker.app.domain.model.User
import ru.fueltracker.app.ui.auth.NameViewModel
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.toUiText
import javax.inject.Inject

data class ProfileUiState(
    val isLoading: Boolean = true,
    val loadError: UiText? = null,
    val user: User? = null,
    /** Имя в поле ввода; «Сохранить» активна, когда оно отличается от сохранённого. */
    val name: String = "",
    val nameError: UiText? = null,
    val isSavingName: Boolean = false,
    val confirmLogout: Boolean = false,
    val isLoggingOut: Boolean = false,
    val snackbar: UiText? = null,
) {
    val canSaveName: Boolean
        get() = user != null && name.isNotBlank() && name.trim() != user.name && !isSavingName && !isLoggingOut
}

sealed interface ProfileEvent {
    data object Retry : ProfileEvent
    data class NameChanged(val value: String) : ProfileEvent
    data object SaveName : ProfileEvent
    data object RequestLogout : ProfileEvent
    data object ConfirmLogout : ProfileEvent
    data object DismissLogout : ProfileEvent
    data object SnackbarShown : ProfileEvent
}

/**
 * Профиль: имя, телефон, «Выйти». После выхода токены стёрты — `AppViewModel` сам
 * откроет экран входа, отдельного перехода отсюда не нужно.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: ProfileEvent) {
        when (event) {
            ProfileEvent.Retry -> load()
            is ProfileEvent.NameChanged -> _state.update {
                it.copy(name = event.value.take(NameViewModel.NAME_MAX_LENGTH), nameError = null)
            }
            ProfileEvent.SaveName -> saveName()
            ProfileEvent.RequestLogout -> _state.update { it.copy(confirmLogout = true) }
            ProfileEvent.DismissLogout -> _state.update { it.copy(confirmLogout = false) }
            ProfileEvent.ConfirmLogout -> logout()
            ProfileEvent.SnackbarShown -> _state.update { it.copy(snackbar = null) }
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true, loadError = null) }
        viewModelScope.launch {
            when (val result = profileRepository.getMe()) {
                is ApiResult.Success -> _state.update {
                    it.copy(isLoading = false, user = result.data, name = result.data.name.orEmpty())
                }
                is ApiResult.Failure -> _state.update { it.copy(isLoading = false, loadError = result.error.toUiText()) }
            }
        }
    }

    private fun saveName() {
        if (!_state.value.canSaveName) return
        val name = _state.value.name.trim()
        _state.update { it.copy(isSavingName = true, nameError = null) }
        viewModelScope.launch {
            when (val result = profileRepository.updateName(name)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        isSavingName = false,
                        user = result.data,
                        name = result.data.name.orEmpty(),
                        snackbar = UiText.Resource(R.string.profile_name_saved),
                    )
                }
                is ApiResult.Failure -> {
                    val error = result.error
                    // 400 — сервер не принял имя: текст под полем
                    val inline = (error as? ApiError.Http)
                        ?.takeIf { it.status == 400 }
                        ?.let { it.fieldError("name") ?: it.message }
                        ?.let { UiText.Raw(it) }
                    _state.update {
                        it.copy(
                            isSavingName = false,
                            nameError = inline,
                            snackbar = if (inline == null) error.toUiText() else null,
                        )
                    }
                }
            }
        }
    }

    private fun logout() {
        if (_state.value.isLoggingOut) return
        _state.update { it.copy(confirmLogout = false, isLoggingOut = true) }
        viewModelScope.launch { authRepository.logout() }
    }
}
