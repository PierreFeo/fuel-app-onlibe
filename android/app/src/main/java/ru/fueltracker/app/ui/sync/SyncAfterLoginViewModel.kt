package ru.fueltracker.app.ui.sync

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
import ru.fueltracker.app.data.repository.SyncRepository
import ru.fueltracker.app.data.repository.SyncResult
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.toUiText
import javax.inject.Inject

data class SyncAfterLoginUiState(
    val isSyncing: Boolean = true,
    /** Не удалось: «Повторить» или «Продолжить» (данные придут при следующей синхронизации). */
    val error: UiText? = null,
    /** Готово — открыть приложение. */
    val done: Boolean = false,
)

sealed interface SyncAfterLoginEvent {
    data object Retry : SyncAfterLoginEvent
    data object Continue : SyncAfterLoginEvent
}

/**
 * Первая синхронизация сразу после входа (docs/05_AUTH_SMS.md): на новом телефоне приходят
 * данные аккаунта, у бывшего гостя — его данные уходят на сервер.
 */
@HiltViewModel
class SyncAfterLoginViewModel @Inject constructor(
    private val syncRepository: SyncRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SyncAfterLoginUiState())
    val state: StateFlow<SyncAfterLoginUiState> = _state.asStateFlow()

    init {
        sync()
    }

    fun onEvent(event: SyncAfterLoginEvent) {
        when (event) {
            SyncAfterLoginEvent.Retry -> sync()
            SyncAfterLoginEvent.Continue -> _state.update { it.copy(done = true) }
        }
    }

    private fun sync() {
        _state.update { it.copy(isSyncing = true, error = null) }
        viewModelScope.launch {
            // Отклонённые записи не мешают войти: они видны в профиле как «не отправлено»
            val error = when (val result = syncRepository.sync()) {
                is SyncResult.Success, SyncResult.NotSignedIn -> null
                SyncResult.SessionExpired -> UiText.Resource(R.string.sync_session_expired)
                is SyncResult.Failure -> result.error.toSyncText()
            }
            _state.update { if (error == null) it.copy(isSyncing = false, done = true) else it.copy(isSyncing = false, error = error) }
        }
    }
}

/** Нет связи — «проверьте интернет»; ответ сервера — его текст. */
internal fun ApiError.toSyncText(): UiText =
    if (this is ApiError.Network) UiText.Resource(R.string.sync_failed) else toUiText()
