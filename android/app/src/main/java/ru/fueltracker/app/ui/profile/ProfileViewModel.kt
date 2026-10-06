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
import ru.fueltracker.app.data.local.AppMode
import ru.fueltracker.app.data.local.AppStateStorage
import ru.fueltracker.app.data.local.TokenStorage
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.repository.AuthRepository
import ru.fueltracker.app.data.repository.ProfileRepository
import ru.fueltracker.app.data.repository.RejectedRecord
import ru.fueltracker.app.data.repository.SyncRepository
import ru.fueltracker.app.data.repository.SyncResult
import ru.fueltracker.app.ui.auth.NameViewModel
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.sync.toSyncText
import java.time.Instant
import javax.inject.Inject

data class ProfileUiState(
    /** Профиль с телефона ещё не прочитан (доли секунды). */
    val isLoading: Boolean = true,
    val mode: AppMode? = null,
    /** Есть токены; у аккаунта без них сессия истекла — нужно «Войти снова». */
    val isLoggedIn: Boolean = false,
    val phone: String? = null,
    /** Сохранённое имя; [name] — то, что в поле ввода. */
    val savedName: String? = null,
    val name: String = "",
    val pendingChanges: Int = 0,
    /** null — синхронизации ещё не было. */
    val lastSyncAt: Instant? = null,
    val isSyncing: Boolean = false,
    /** Не пусто — диалог «Не отправлено». */
    val rejected: List<RejectedRecord> = emptyList(),
    /** Задан ли пароль для входа без SMS. */
    val hasPassword: Boolean = false,
    /** Не null — открыт диалог «Задать / Сменить пароль». */
    val passwordForm: PasswordForm? = null,
    val confirmLogout: Boolean = false,
    val isLoggingOut: Boolean = false,
    val snackbar: UiText? = null,
) {
    val isGuest: Boolean get() = mode == AppMode.GUEST
    val isSessionExpired: Boolean get() = mode == AppMode.ACCOUNT && !isLoggedIn

    /** «Сохранить» видна, когда имя изменено. */
    val canSaveName: Boolean
        get() = !isLoading && name.isNotBlank() && name.trim() != savedName && !isLoggingOut

    val canSync: Boolean get() = !isSyncing && !isLoggingOut && !isSessionExpired

    /** Пароль меняется на сервере — нужна действующая сессия. */
    val canChangePassword: Boolean get() = !isGuest && !isSessionExpired && !isLoggingOut
}

sealed interface ProfileEvent {
    data class NameChanged(val value: String) : ProfileEvent
    data object SaveName : ProfileEvent
    data object Sync : ProfileEvent
    data object DismissRejected : ProfileEvent
    data object OpenPassword : ProfileEvent
    data class PasswordCurrentChanged(val value: String) : ProfileEvent
    data class PasswordNewChanged(val value: String) : ProfileEvent
    data class PasswordRepeatChanged(val value: String) : ProfileEvent
    data object SavePassword : ProfileEvent
    data object DismissPassword : ProfileEvent
    data object RequestLogout : ProfileEvent
    data object ConfirmLogout : ProfileEvent
    data object DismissLogout : ProfileEvent
    data object SnackbarShown : ProfileEvent
}

/**
 * Профиль (07_UI_SCREENS.md, ProfileScreen): всё — с телефона; сеть нужна только кнопке
 * «Синхронизировать». Имя меняется офлайн: у аккаунта оно уйдёт на сервер при синхронизации.
 * После «Выйти» режим стёрт — `AppViewModel` сам откроет экран входа.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val appState: AppStateStorage,
    private val authRepository: AuthRepository,
    private val syncRepository: SyncRepository,
    private val profileRepository: ProfileRepository,
    tokenStorage: TokenStorage,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            appState.profile.collect { profile ->
                _state.update {
                    // Поле ввода не трогаем, если человек сейчас его правит
                    val typing = !it.isLoading && it.name != it.savedName.orEmpty()
                    it.copy(
                        isLoading = false,
                        mode = profile.mode,
                        phone = profile.phone,
                        hasPassword = profile.hasPassword,
                        savedName = profile.name,
                        name = if (typing) it.name else profile.name.orEmpty(),
                    )
                }
            }
        }
        viewModelScope.launch {
            syncRepository.observeStatus().collect { status ->
                _state.update { it.copy(pendingChanges = status.pendingChanges, lastSyncAt = status.lastSyncAt) }
            }
        }
        viewModelScope.launch {
            tokenStorage.tokens.collect { tokens -> _state.update { it.copy(isLoggedIn = tokens != null) } }
        }
    }

    fun onEvent(event: ProfileEvent) {
        when (event) {
            is ProfileEvent.NameChanged -> _state.update { it.copy(name = event.value.take(NameViewModel.NAME_MAX_LENGTH)) }
            ProfileEvent.SaveName -> saveName()
            ProfileEvent.Sync -> sync()
            ProfileEvent.DismissRejected -> _state.update { it.copy(rejected = emptyList()) }
            ProfileEvent.OpenPassword -> _state.update {
                if (it.canChangePassword) it.copy(passwordForm = PasswordForm(needsCurrent = it.hasPassword)) else it
            }
            is ProfileEvent.PasswordCurrentChanged -> editPassword { copy(current = event.value.take(PASSWORD_MAX), currentError = null, error = null) }
            is ProfileEvent.PasswordNewChanged -> editPassword { copy(new = event.value.take(PASSWORD_MAX), newError = null, error = null) }
            is ProfileEvent.PasswordRepeatChanged -> editPassword { copy(repeat = event.value.take(PASSWORD_MAX), repeatError = null, error = null) }
            ProfileEvent.SavePassword -> savePassword()
            ProfileEvent.DismissPassword -> _state.update { if (it.passwordForm?.isSaving == true) it else it.copy(passwordForm = null) }
            ProfileEvent.RequestLogout -> _state.update { it.copy(confirmLogout = true) }
            ProfileEvent.DismissLogout -> _state.update { it.copy(confirmLogout = false) }
            ProfileEvent.ConfirmLogout -> logout()
            ProfileEvent.SnackbarShown -> _state.update { it.copy(snackbar = null) }
        }
    }

    private fun saveName() {
        if (!_state.value.canSaveName) return
        val name = _state.value.name.trim()
        viewModelScope.launch {
            appState.setName(name)
            _state.update { it.copy(name = name, snackbar = UiText.Resource(R.string.profile_name_saved)) }
        }
    }

    private fun sync() {
        if (!_state.value.canSync || _state.value.isGuest) return
        _state.update { it.copy(isSyncing = true) }
        viewModelScope.launch {
            val result = syncRepository.sync()
            _state.update {
                when (result) {
                    is SyncResult.Success -> it.copy(
                        isSyncing = false,
                        rejected = result.rejected,
                        snackbar = UiText.Resource(R.string.sync_done, listOf(result.sent, result.received)),
                    )
                    SyncResult.SessionExpired ->
                        it.copy(isSyncing = false, snackbar = UiText.Resource(R.string.sync_session_expired))
                    is SyncResult.Failure -> it.copy(isSyncing = false, snackbar = result.error.toSyncText())
                    SyncResult.NotSignedIn -> it.copy(isSyncing = false)
                }
            }
        }
    }

    private fun editPassword(change: PasswordForm.() -> PasswordForm) {
        _state.update { s -> s.passwordForm?.takeUnless { it.isSaving }?.let { s.copy(passwordForm = it.change()) } ?: s }
    }

    /** `PUT /me/password`: проверки на телефоне, потом запрос (нужен интернет). */
    private fun savePassword() {
        val form = _state.value.passwordForm ?: return
        if (!form.canSubmit) return
        val checked = validatePasswordForm(form)
        if (checked.hasErrors) {
            _state.update { it.copy(passwordForm = checked) }
            return
        }
        _state.update { it.copy(passwordForm = checked.copy(isSaving = true)) }
        viewModelScope.launch {
            val current = checked.current.takeIf { checked.needsCurrent }
            when (val result = profileRepository.changePassword(current, checked.new)) {
                is ApiResult.Success -> {
                    appState.setHasPassword(true)
                    _state.update { it.copy(passwordForm = null, snackbar = UiText.Resource(R.string.password_saved)) }
                }
                is ApiResult.Failure -> _state.update { s -> s.copy(passwordForm = s.passwordForm?.withServerError(result.error)) }
            }
        }
    }

    /**
     * Выход = синхронизация + очистка телефона (docs/05_AUTH_SMS.md). Не удалось отправить всё —
     * пользователь остаётся в аккаунте, данные не трогаются: иначе они пропали бы.
     */
    private fun logout() {
        if (_state.value.isLoggingOut || _state.value.isGuest) return
        _state.update { it.copy(confirmLogout = false, isLoggingOut = true) }
        viewModelScope.launch {
            val result = syncRepository.sync()
            if (result is SyncResult.Success && result.rejected.isEmpty()) {
                // Режим стёрт — AppViewModel сам откроет экран входа
                authRepository.logout()
                return@launch
            }
            val rejected = (result as? SyncResult.Success)?.rejected.orEmpty()
            val reason = when {
                rejected.isNotEmpty() -> R.string.logout_rejected
                result == SyncResult.SessionExpired -> R.string.logout_session_expired
                else -> R.string.logout_failed
            }
            _state.update { it.copy(isLoggingOut = false, rejected = rejected, snackbar = UiText.Resource(reason)) }
        }
    }
}
