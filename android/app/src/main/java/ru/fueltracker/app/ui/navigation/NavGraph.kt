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
import ru.fueltracker.app.ui.cars.CarsPlaceholderScreen
import ru.fueltracker.app.ui.splash.SplashScreen

@Composable
fun NavGraph(
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()

    // Splash → вход или приложение; потеря токенов на экране приложения → снова вход
    LaunchedEffect(navController) {
        appViewModel.isLoggedIn.filterNotNull().collect { isLoggedIn ->
            val screen = navController.currentBackStackEntry?.destination?.kind()
            sessionRedirect(screen, isLoggedIn)?.let { navController.navigateClearingBackStack(it) }
        }
    }

    // После входа экраны входа убираются из истории: «Назад» закрывает приложение
    val onLoggedIn: (LoginResult) -> Unit = { result ->
        navController.navigateClearingBackStack(if (result.isNewUser) NameRoute else HomeRoute)
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
            NameScreen(onSaved = { navController.navigateClearingBackStack(HomeRoute) })
        }
        composable<CarsRoute> { CarsPlaceholderScreen() }
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
