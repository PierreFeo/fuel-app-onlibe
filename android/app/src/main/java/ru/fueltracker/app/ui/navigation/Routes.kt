package ru.fueltracker.app.ui.navigation

import kotlinx.serialization.Serializable

// Маршруты экранов (см. 07_UI_SCREENS.md). Диалоги и bottom sheet — не маршруты.

@Serializable
data object SplashRoute

@Serializable
data object PhoneRoute

/** phone — `+79991234567`; resendAfterSec — таймер повторной отправки из ответа request-code. */
@Serializable
data class CodeRoute(val phone: String, val resendAfterSec: Int = 60)

/** Запасной вход; номер подставляется, если уже введён. */
@Serializable
data class PasswordLoginRoute(val phone: String? = null)

/** guest — «Продолжить без входа»: имя сохраняется только на телефоне. */
@Serializable
data class NameRoute(val guest: Boolean = false)

@Serializable
data object CarsRoute

/** carId = null — новое авто. */
@Serializable
data class CarEditRoute(val carId: String? = null)

@Serializable
data object SheetsFeedRoute

@Serializable
data object ProfileRoute

/** «Загружаем ваши данные…» — первая синхронизация после входа. */
@Serializable
data object SyncAfterLoginRoute
