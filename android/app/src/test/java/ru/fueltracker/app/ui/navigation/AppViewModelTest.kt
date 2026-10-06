package ru.fueltracker.app.ui.navigation

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import ru.fueltracker.app.data.local.AuthTokens
import ru.fueltracker.app.data.local.FakeSelectedCarStorage
import ru.fueltracker.app.data.local.FakeTokenStorage
import ru.fueltracker.app.testutil.MainDispatcherRule

class AppViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val loggedInWithCar = Session(isLoggedIn = true, selectedCarId = "car-1")
    private val loggedInNoCar = Session(isLoggedIn = true, selectedCarId = null)
    private val loggedOut = Session(isLoggedIn = false, selectedCarId = null)

    @Test
    fun `сессия повторяет токены и выбранное авто`() = runTest {
        val tokens = FakeTokenStorage(initial = AuthTokens("a", "r"))
        val cars = FakeSelectedCarStorage()
        val viewModel = AppViewModel(tokens, cars)
        runCurrent()
        assertEquals(loggedInNoCar, viewModel.session.value)

        cars.select("car-1")
        runCurrent()
        assertEquals(loggedInWithCar, viewModel.session.value)

        tokens.clear()
        runCurrent()
        assertEquals(Session(isLoggedIn = false, selectedCarId = "car-1"), viewModel.session.value)
    }

    @Test
    fun `Splash — лента, список авто или вход`() {
        assertEquals(SheetsFeedRoute, sessionRedirect(ScreenKind.SPLASH, loggedInWithCar))
        assertEquals(CarsRoute, sessionRedirect(ScreenKind.SPLASH, loggedInNoCar))
        assertEquals(PhoneRoute, sessionRedirect(ScreenKind.SPLASH, loggedOut))
    }

    @Test
    fun `потеря токенов на экране приложения — на вход`() {
        assertEquals(PhoneRoute, sessionRedirect(ScreenKind.APP, loggedOut))
        assertNull(sessionRedirect(ScreenKind.APP, loggedInWithCar))
        // Выбор другого авто не перенаправляет — переход делает сам экран
        assertNull(sessionRedirect(ScreenKind.APP, loggedInNoCar))
    }

    @Test
    fun `экраны входа не перенаправляются`() {
        // Токены появляются при входе — переход делает сам экран (на NameScreen или дальше)
        assertNull(sessionRedirect(ScreenKind.AUTH, loggedInWithCar))
        assertNull(sessionRedirect(ScreenKind.AUTH, loggedOut))
    }

    @Test
    fun `после входа — лента, если авто уже выбрано`() {
        assertEquals(SheetsFeedRoute, homeRoute(loggedInWithCar))
        assertEquals(CarsRoute, homeRoute(loggedInNoCar))
        assertEquals(CarsRoute, homeRoute(null))
    }
}
