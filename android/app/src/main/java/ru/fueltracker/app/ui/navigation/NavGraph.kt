package ru.fueltracker.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.filterNotNull
import ru.fueltracker.app.domain.model.LoginResult
import ru.fueltracker.app.ui.auth.CodeScreen
import ru.fueltracker.app.ui.auth.NameScreen
import ru.fueltracker.app.ui.auth.PasswordLoginScreen
import ru.fueltracker.app.ui.auth.PhoneScreen
import ru.fueltracker.app.ui.cars.CarEditScreen
import ru.fueltracker.app.ui.cars.CarsScreen
import ru.fueltracker.app.ui.profile.ProfileScreen
import ru.fueltracker.app.ui.sheets.SheetsFeedScreen
import ru.fueltracker.app.ui.splash.SplashScreen

@Composable
fun NavGraph(
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()

    // Splash → вход или приложение; потеря токенов на экране приложения → снова вход
    LaunchedEffect(navController) {
        appViewModel.session.filterNotNull().collect { session ->
            val screen = navController.currentBackStackEntry?.destination?.kind()
            sessionRedirect(screen, session)?.let { navController.navigateClearingBackStack(it) }
        }
    }

    // После Splash сессия уже прочитана, value не null
    val goHome = { navController.navigateClearingBackStack(homeRoute(appViewModel.session.value)) }

    // После входа экраны входа убираются из истории: «Назад» закрывает приложение
    val onLoggedIn: (LoginResult) -> Unit = { result ->
        if (result.isNewUser) navController.navigateClearingBackStack(NameRoute) else goHome()
    }

    NavHost(
        navController = navController,
        startDestination = SplashRoute,
        modifier = modifier,
    ) {
        composable<SplashRoute> { SplashScreen() }
        composable<PhoneRoute> {
            PhoneScreen(
                onCodeSent = { navController.navigate(CodeRoute(it.phone, it.resendAfterSec)) },
                onPasswordLogin = { navController.navigate(PasswordLoginRoute(it)) },
            )
        }
        composable<CodeRoute> {
            CodeScreen(
                onLoggedIn = onLoggedIn,
                onChangePhone = { navController.popBackStack() },
                onPasswordLogin = { navController.navigate(PasswordLoginRoute(it)) },
            )
        }
        composable<PasswordLoginRoute> {
            PasswordLoginScreen(
                onLoggedIn = onLoggedIn,
                onSmsLogin = {
                    if (!navController.popBackStack<PhoneRoute>(inclusive = false)) {
                        navController.navigateClearingBackStack(PhoneRoute)
                    }
                },
            )
        }
        composable<NameRoute> {
            NameScreen(onSaved = goHome)
        }
        composable<CarsRoute> {
            // Корень (авто ещё не выбрано) — без стрелки «Назад»; из ленты — со стрелкой
            val canGoBack = navController.previousBackStackEntry != null
            CarsScreen(
                // Лента становится корнем: «Назад» из неё закрывает приложение
                onOpenFeed = { navController.navigateClearingBackStack(SheetsFeedRoute) },
                onAddCar = { navController.navigate(CarEditRoute()) },
                onEditCar = { navController.navigate(CarEditRoute(it)) },
                onBack = if (canGoBack) ({ navController.popBackStack() }) else null,
                onOpenProfile = if (canGoBack) null else ({ navController.navigate(ProfileRoute) }),
            )
        }
        composable<ProfileRoute> {
            // После «Выйти» переход на вход делает LaunchedEffect выше: токены стёрты → PhoneRoute
            ProfileScreen(onBack = { navController.popBackStack() })
        }
        composable<CarEditRoute> {
            CarEditScreen(onDone = { navController.popBackStack() })
        }
        composable<SheetsFeedRoute> {
            SheetsFeedScreen(
                onChangeCar = { navController.navigate(CarsRoute) },
                onOpenProfile = { navController.navigate(ProfileRoute) },
                onEditCar = { navController.navigate(CarEditRoute(it)) },
                // Авто не выбрано или его больше нет — список авто становится корнем
                onNoCar = { navController.navigateClearingBackStack(CarsRoute) },
            )
        }
    }
}

private fun NavController.navigateClearingBackStack(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

private fun NavDestination.kind(): ScreenKind = when {
    hasRoute<SplashRoute>() -> ScreenKind.SPLASH
    hasRoute<PhoneRoute>() || hasRoute<CodeRoute>() || hasRoute<PasswordLoginRoute>() -> ScreenKind.AUTH
    else -> ScreenKind.APP
}
