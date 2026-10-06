package ru.fueltracker.app.ui.navigation

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import ru.fueltracker.app.data.local.AuthTokens
import ru.fueltracker.app.data.local.FakeTokenStorage
import ru.fueltracker.app.testutil.MainDispatcherRule

class AppViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `сессия повторяет наличие токенов`() = runTest {
        val storage = FakeTokenStorage(initial = AuthTokens("a", "r"))
        val viewModel = AppViewModel(storage)
        runCurrent()
        assertEquals(true, viewModel.isLoggedIn.value)

        storage.clear()
        runCurrent()
        assertEquals(false, viewModel.isLoggedIn.value)
    }

    @Test
    fun `Splash — в приложение или на вход`() {
        assertEquals(HomeRoute, sessionRedirect(ScreenKind.SPLASH, isLoggedIn = true))
        assertEquals(PhoneRoute, sessionRedirect(ScreenKind.SPLASH, isLoggedIn = false))
    }

    @Test
    fun `потеря токенов на экране приложения — на вход`() {
        assertEquals(PhoneRoute, sessionRedirect(ScreenKind.APP, isLoggedIn = false))
        assertNull(sessionRedirect(ScreenKind.APP, isLoggedIn = true))
    }

    @Test
    fun `экраны входа не перенаправляются`() {
        // Токены появляются при входе — переход делает сам экран (на NameScreen или дальше)
        assertNull(sessionRedirect(ScreenKind.AUTH, isLoggedIn = true))
        assertNull(sessionRedirect(ScreenKind.AUTH, isLoggedIn = false))
    }
}
