package ru.fueltracker.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.fueltracker.app.data.local.SelectedCarStorage
import ru.fueltracker.app.data.local.TokenStorage
import javax.inject.Inject

/** Что известно о сессии: вошёл ли пользователь и какое авто выбрано. */
data class Session(
    val isLoggedIn: Boolean,
    val selectedCarId: String?,
)

/** Следит за сохранёнными токенами и выбранным авто. */
@HiltViewModel
class AppViewModel @Inject constructor(
    tokenStorage: TokenStorage,
    selectedCarStorage: SelectedCarStorage,
) : ViewModel() {

    /** null — DataStore ещё читается (показываем Splash). */
    val session: StateFlow<Session?> =
        combine(tokenStorage.tokens, selectedCarStorage.selectedCarId) { tokens, carId ->
            Session(isLoggedIn = tokens != null, selectedCarId = carId)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)
}

/** Какой экран сейчас открыт — для решения, куда перенаправить. */
enum class ScreenKind {
    SPLASH,

    /** Экраны входа: на них пользователь ещё без токенов. */
    AUTH,

    /** Остальные экраны: нужны токены. */
    APP,
}

/**
 * Куда перейти при изменении сессии; null — оставаться на месте.
 * Splash решает по наличию токена; на экранах приложения потеря токенов
 * (refresh отклонён сервером) возвращает на вход.
 */
internal fun sessionRedirect(screen: ScreenKind?, session: Session): Any? = when (screen) {
    ScreenKind.SPLASH -> if (session.isLoggedIn) homeRoute(session) else PhoneRoute
    ScreenKind.APP -> if (session.isLoggedIn) null else PhoneRoute
    ScreenKind.AUTH, null -> null
}

/** Первый экран после входа: лента выбранного авто или список авто, если не выбрано. */
internal fun homeRoute(session: Session?): Any =
    if (session?.selectedCarId != null) SheetsFeedRoute else CarsRoute
