package ru.fueltracker.app.ui.profile

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.fueltracker.app.R
import ru.fueltracker.app.data.local.AppMode
import ru.fueltracker.app.data.local.FakeAppStateStorage
import ru.fueltracker.app.data.local.LocalProfile
import ru.fueltracker.app.testutil.FakeAuthRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.ui.common.UiText

/** Профиль читает всё с телефона — сеть не нужна. */
class ProfileViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val account = LocalProfile(mode = AppMode.ACCOUNT, ownerUserId = "id-1", phone = "+79991234567", name = "Иван")
    private val appState = FakeAppStateStorage(account)
    private val auth = FakeAuthRepository()

    private fun TestScope.createViewModel(): ProfileViewModel {
        val viewModel = ProfileViewModel(appState, auth)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `имя и телефон аккаунта`() = runTest {
        val state = createViewModel().state.value
        assertFalse(state.isLoading)
        assertFalse(state.isGuest)
        assertEquals("+79991234567", state.phone)
        assertEquals("Иван", state.name)
        assertFalse(state.canSaveName) // имя не меняли — сохранять нечего
    }

    @Test
    fun `аккаунт меняет имя офлайн — оно помечено для синхронизации`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.NameChanged("  Иван Петров "))
        assertTrue(viewModel.state.value.canSaveName)

        viewModel.onEvent(ProfileEvent.SaveName)
        advanceUntilIdle()

        assertEquals("Иван Петров", appState.current.name)
        assertTrue(appState.current.nameDirty)
        val state = viewModel.state.value
        assertEquals("Иван Петров", state.savedName)
        assertFalse(state.canSaveName)
        assertEquals(UiText.Resource(R.string.profile_name_saved), state.snackbar)
    }

    @Test
    fun `гость — имя только на телефоне, выйти нельзя`() = runTest {
        appState.clear()
        appState.startGuest("Иван")
        val viewModel = createViewModel()
        assertTrue(viewModel.state.value.isGuest)

        viewModel.onEvent(ProfileEvent.NameChanged("Пётр"))
        viewModel.onEvent(ProfileEvent.SaveName)
        viewModel.onEvent(ProfileEvent.ConfirmLogout)
        advanceUntilIdle()

        assertEquals("Пётр", appState.current.name)
        assertFalse(appState.current.nameDirty) // гостю отправлять некуда
        assertEquals(0, auth.logoutCalls)
    }

    @Test
    fun `пустое имя не сохранить`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.NameChanged("   "))
        assertFalse(viewModel.state.value.canSaveName)
    }

    @Test
    fun `выход — с подтверждением`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.RequestLogout)
        assertTrue(viewModel.state.value.confirmLogout)
        assertEquals(0, auth.logoutCalls)

        viewModel.onEvent(ProfileEvent.ConfirmLogout)
        advanceUntilIdle()

        assertEquals(1, auth.logoutCalls)
        assertTrue(viewModel.state.value.isLoggingOut)
    }

    @Test
    fun `отмена выхода`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.RequestLogout)
        viewModel.onEvent(ProfileEvent.DismissLogout)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.confirmLogout)
        assertEquals(0, auth.logoutCalls)
    }
}
