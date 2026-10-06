package ru.fueltracker.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.fueltracker.app.data.local.AppMode
import ru.fueltracker.app.data.local.AppStateStorage
import ru.fueltracker.app.data.local.SelectedCarStorage
import ru.fueltracker.app.data.local.TokenStorage
import javax.inject.Inject

/**
 * Что известно о сессии. [mode] — гость или аккаунт (null — приложение ещё не начинали:
 * экран входа). [isLoggedIn] — есть токены; у аккаунта их может не быть, если сессия истекла —
 * данные на телефоне при этом остаются (docs/05_AUTH_SMS.md).
 */
data class Session(
    val mode: AppMode?,
    val isLoggedIn: Boolean,
    val selectedCarId: String?,
)

/** Следит за режимом, токенами и выбранным авто. */
@HiltViewModel
class AppViewModel @Inject constructor(
    tokenStorage: TokenStorage,
    selectedCarStorage: SelectedCarStorage,
    appState: AppStateStorage,
) : ViewModel() {

    /** null — DataStore ещё читается (показываем Splash). */
    val session: StateFlow<Session?> =
        combine(appState.profile, tokenStorage.tokens, selectedCarStorage.selectedCarId) { profile, tokens, carId ->
            // Установка, где вошли до появления режимов: токены есть — значит, аккаунт
            val mode = profile.mode ?: if (tokens != null) AppMode.ACCOUNT else null
            Session(mode = mode, isLoggedIn = tokens != null, selectedCarId = carId)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)
}

/** Какой экран сейчас открыт — для решения, куда перенаправить. */
enum class ScreenKind {
    SPLASH,

    /** Экраны входа и знакомства (телефон, код, пароль, имя): режима может ещё не быть. */
    AUTH,

    /** Остальные экраны: нужен режим (гость или аккаунт). */
    APP,
}

/**
 * Куда перейти при изменении сессии; null — оставаться на месте.
 * Splash решает по режиму; на экранах приложения пропажа режима (выход) возвращает на вход.
 * Истёкшая сессия аккаунта (нет токенов) из приложения НЕ выкидывает — данные на телефоне.
 */
internal fun sessionRedirect(screen: ScreenKind?, session: Session): Any? = when (screen) {
    ScreenKind.SPLASH -> if (session.mode != null) homeRoute(session) else PhoneRoute
    ScreenKind.APP -> if (session.mode != null) null else PhoneRoute
    ScreenKind.AUTH, null -> null
}

/** Первый экран приложения: лента выбранного авто или список авто, если не выбрано. */
internal fun homeRoute(session: Session?): Any =
    if (session?.selectedCarId != null) SheetsFeedRoute else CarsRoute
