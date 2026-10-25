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
import ru.fueltracker.app.ui.profile.PASSWORD_MAX
import ru.fueltracker.app.ui.profile.PasswordForm
import ru.fueltracker.app.ui.profile.hasErrors
import ru.fueltracker.app.ui.profile.validatePasswordForm
import ru.fueltracker.app.ui.profile.withServerError
import javax.inject.Inject

data class SetupUiState(
    val isGuest: Boolean = false,
    val name: String = "",
    /** null — пароль не нужен (уже задан, гость или вход по паролю). */
    val password: PasswordForm? = null,
    val isSaving: Boolean = false,
    val error: UiText? = null,
    val snackbar: UiText? = null,
    val saved: Boolean = false,
) {
    val canSubmit: Boolean
        get() = name.isNotBlank() && !isSaving && (password == null || password.new.isNotEmpty() && password.repeat.isNotEmpty())
}

sealed interface SetupEvent {
    data class NameChanged(val value: String) : SetupEvent
    data class PasswordChanged(val value: String) : SetupEvent
    data class PasswordRepeatChanged(val value: String) : SetupEvent
    data object Submit : SetupEvent
    data object SavedHandled : SetupEvent
    data object SnackbarShown : SetupEvent
}

/**
 * «Завершите настройку» после входа или «Продолжить без входа» (07_UI_SCREENS.md, SetupScreen).
 * Гость — имя только на телефоне. Аккаунт — сначала обязательный пароль (`PUT /me/password`,
 * без связи экран не закрывается), потом имя: на телефон + `PATCH /me`; нет связи — не беда,
 * имя уйдёт при синхронизации.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profileRepository: ProfileRepository,
    private val appState: AppStateStorage,
) : ViewModel() {

    private val isGuest: Boolean = savedStateHandle.get<Boolean>(ARG_GUEST) ?: false
    private val askPassword: Boolean = !isGuest && (savedStateHandle.get<Boolean>(ARG_ASK_PASSWORD) ?: false)

    private val _state = MutableStateFlow(
        SetupUiState(isGuest = isGuest, password = PasswordForm(needsCurrent = false).takeIf { askPassword }),
    )
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        // Имя уже есть (с сервера или от гостя) — подставляем, если пользователь ещё не начал вводить
        if (!isGuest) {
            viewModelScope.launch {
                val known = appState.get().name.orEmpty()
                _state.update { if (it.name.isEmpty()) it.copy(name = known) else it }
            }
        }
    }

    fun onEvent(event: SetupEvent) {
        when (event) {
            is SetupEvent.NameChanged -> _state.update {
                it.copy(name = event.value.take(NAME_MAX_LENGTH), error = null)
            }
            is SetupEvent.PasswordChanged -> editPassword { copy(new = event.value.take(PASSWORD_MAX), newError = null, error = null) }
            is SetupEvent.PasswordRepeatChanged -> editPassword { copy(repeat = event.value.take(PASSWORD_MAX), repeatError = null, error = null) }
            SetupEvent.Submit -> submit()
            SetupEvent.SavedHandled -> _state.update { it.copy(saved = false) }
            SetupEvent.SnackbarShown -> _state.update { it.copy(snackbar = null) }
        }
    }

    private fun editPassword(change: PasswordForm.() -> PasswordForm) {
        _state.update { s -> s.password?.takeUnless { s.isSaving }?.let { s.copy(password = it.change()) } ?: s }
    }

    private fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        val checked = current.password?.let(::validatePasswordForm)
        if (checked?.hasErrors == true) {
            _state.update { it.copy(password = checked) }
            return
        }
        val name = current.name.trim()
        _state.update { it.copy(isSaving = true, error = null, password = checked) }
        viewModelScope.launch {
            if (isGuest) {
                appState.startGuest(name)
                _state.update { it.copy(isSaving = false, saved = true) }
                return@launch
            }
            if (checked != null && !savePassword(checked)) return@launch
            saveName(name)
        }
    }

    /** false — пароль не сохранён, ошибка уже на экране. */
    private suspend fun savePassword(form: PasswordForm): Boolean =
        when (val result = profileRepository.changePassword(null, form.new)) {
            is ApiResult.Success -> {
                appState.setHasPassword(true)
                // Пароль задан — блок убираем: при ошибке имени повторно его не отправим
                _state.update { it.copy(password = null) }
                true
            }
            is ApiResult.Failure -> {
                _state.update { it.copy(isSaving = false, password = form.withServerError(result.error)) }
                false
            }
        }

    private suspend fun saveName(name: String) {
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

    companion object {
        /** Ограничение `PATCH /me`: name 1..100 символов. */
        const val NAME_MAX_LENGTH = 100

        /** Аргументы маршрута SetupRoute. */
        internal const val ARG_GUEST = "guest"
        internal const val ARG_ASK_PASSWORD = "askPassword"
    }
}
