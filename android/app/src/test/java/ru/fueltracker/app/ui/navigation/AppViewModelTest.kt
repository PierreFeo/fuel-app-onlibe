package ru.fueltracker.app.ui.navigation

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import ru.fueltracker.app.data.local.AppMode
import ru.fueltracker.app.data.local.AuthTokens
import ru.fueltracker.app.data.local.FakeAppStateStorage
import ru.fueltracker.app.data.local.FakeSelectedCarStorage
import ru.fueltracker.app.data.local.FakeTokenStorage
import ru.fueltracker.app.data.local.LocalProfile
import ru.fueltracker.app.testutil.MainDispatcherRule

class AppViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val accountWithCar = Session(AppMode.ACCOUNT, isLoggedIn = true, selectedCarId = "car-1")
    private val accountNoCar = Session(AppMode.ACCOUNT, isLoggedIn = true, selectedCarId = null)
    private val guest = Session(AppMode.GUEST, isLoggedIn = false, selectedCarId = null)
    private val expiredAccount = Session(AppMode.ACCOUNT, isLoggedIn = false, selectedCarId = "car-1")
    private val nobody = Session(mode = null, isLoggedIn = false, selectedCarId = null)

    @Test
    fun `сессия повторяет режим, токены и выбранное авто`() = runTest {
        val tokens = FakeTokenStorage()
        val cars = FakeSelectedCarStorage()
        val appState = FakeAppStateStorage()
        val viewModel = AppViewModel(tokens, cars, appState)
        runCurrent()
        assertEquals(nobody, viewModel.session.value)

        appState.startGuest("Иван")
        runCurrent()
        assertEquals(guest, viewModel.session.value)

        appState.clear()
        tokens.save(AuthTokens("a", "r"))
        appState.signedIn(ru.fueltracker.app.data.local.SignedInUser("u1", "+79991234567", "Иван", false))
        cars.select("car-1")
        runCurrent()
        assertEquals(accountWithCar, viewModel.session.value)

        // Сессия истекла: токенов нет, но режим и данные остаются
        tokens.clear()
        runCurrent()
        assertEquals(expiredAccount, viewModel.session.value)
    }

    @Test
    fun `вошли до появления режимов — токены есть, значит аккаунт`() = runTest {
        val viewModel = AppViewModel(FakeTokenStorage(initial = AuthTokens("a", "r")), FakeSelectedCarStorage(), FakeAppStateStorage(LocalProfile()))
        runCurrent()
        assertEquals(accountNoCar, viewModel.session.value)
    }

    @Test
    fun `Splash — лента, список авто или вход`() {
        assertEquals(SheetsFeedRoute, sessionRedirect(ScreenKind.SPLASH, accountWithCar))
        assertEquals(CarsRoute, sessionRedirect(ScreenKind.SPLASH, accountNoCar))
        assertEquals(CarsRoute, sessionRedirect(ScreenKind.SPLASH, guest))
        assertEquals(PhoneRoute, sessionRedirect(ScreenKind.SPLASH, nobody))
    }

    @Test
    fun `выход (режим стёрт) на экране приложения — на вход`() {
        assertEquals(PhoneRoute, sessionRedirect(ScreenKind.APP, nobody))
        assertNull(sessionRedirect(ScreenKind.APP, accountWithCar))
        assertNull(sessionRedirect(ScreenKind.APP, guest))
        // Выбор другого авто не перенаправляет — переход делает сам экран
        assertNull(sessionRedirect(ScreenKind.APP, accountNoCar))
    }

    @Test
    fun `истёкшая сессия не выкидывает из приложения — данные на телефоне`() {
        assertNull(sessionRedirect(ScreenKind.APP, expiredAccount))
    }

    @Test
    fun `экраны входа не перенаправляются`() {
        // Режим появляется при входе или «без входа» — переход делает сам экран
        assertNull(sessionRedirect(ScreenKind.AUTH, accountWithCar))
        assertNull(sessionRedirect(ScreenKind.AUTH, nobody))
        assertNull(sessionRedirect(ScreenKind.AUTH, guest))
    }

    @Test
    fun `после входа — лента, если авто уже выбрано`() {
        assertEquals(SheetsFeedRoute, homeRoute(accountWithCar))
        assertEquals(CarsRoute, homeRoute(accountNoCar))
        assertEquals(CarsRoute, homeRoute(null))
    }
}
