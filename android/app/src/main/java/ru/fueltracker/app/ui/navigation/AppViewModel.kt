package ru.fueltracker.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.fueltracker.app.data.local.TokenStorage
import javax.inject.Inject

/** Следит за сессией: есть ли сохранённые токены. */
@HiltViewModel
class AppViewModel @Inject constructor(
    tokenStorage: TokenStorage,
) : ViewModel() {

    /** null — DataStore ещё читается (показываем Splash); false — токенов нет или сессия закончилась. */
    val isLoggedIn: StateFlow<Boolean?> = tokenStorage.tokens
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)
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
internal fun sessionRedirect(screen: ScreenKind?, isLoggedIn: Boolean): Any? = when (screen) {
    ScreenKind.SPLASH -> if (isLoggedIn) HomeRoute else PhoneRoute
    ScreenKind.APP -> if (isLoggedIn) null else PhoneRoute
    ScreenKind.AUTH, null -> null
}

/**
 * Первый экран после входа. Выбор авто (`selected_car_id` → лента ЛУТ) появится в 5.2,
 * пока — всегда список авто.
 */
internal val HomeRoute: Any = CarsRoute
