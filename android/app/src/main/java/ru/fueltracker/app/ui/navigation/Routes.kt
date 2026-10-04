package ru.fueltracker.app.ui.navigation

import kotlinx.serialization.Serializable

// Маршруты экранов (см. 07_UI_SCREENS.md). Диалоги и bottom sheet — не маршруты.

@Serializable
data object SplashRoute

@Serializable
data object PhoneRoute

@Serializable
data class CodeRoute(val phone: String)

/** Запасной вход; номер подставляется, если уже введён. */
@Serializable
data class PasswordLoginRoute(val phone: String? = null)

@Serializable
data object NameRoute

@Serializable
data object CarsRoute

/** carId = null — новое авто. */
@Serializable
data class CarEditRoute(val carId: String? = null)

@Serializable
data object SheetsFeedRoute

@Serializable
data object ProfileRoute
