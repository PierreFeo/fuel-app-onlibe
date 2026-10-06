package ru.fueltracker.app.ui.profile

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.fueltracker.app.R
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.domain.model.User
import ru.fueltracker.app.testutil.FakeAuthRepository
import ru.fueltracker.app.testutil.FakeProfileRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.httpError
import ru.fueltracker.app.testutil.networkError
import ru.fueltracker.app.ui.common.UiText

class ProfileViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val profile = FakeProfileRepository()
    private val auth = FakeAuthRepository()

    private fun TestScope.createViewModel(): ProfileViewModel {
        val viewModel = ProfileViewModel(profile, auth)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `загружает имя и телефон`() = runTest {
        val state = createViewModel().state.value
        assertFalse(state.isLoading)
        assertEquals("+79991234567", state.user?.phone)
        assertEquals("Иван", state.name)
        // Имя не меняли — сохранять нечего
        assertFalse(state.canSaveName)
    }

    @Test
    fun `ошибка загрузки — Повторить`() = runTest {
        profile.getMeResult = networkError
        val viewModel = createViewModel()
        assertEquals(UiText.Resource(R.string.error_no_connection), viewModel.state.value.loadError)

        profile.getMeResult = ApiResult.Success(User("id-1", "+79991234567", "Иван"))
        viewModel.onEvent(ProfileEvent.Retry)
        advanceUntilIdle()
        assertNull(viewModel.state.value.loadError)
        assertEquals("Иван", viewModel.state.value.name)
    }

    @Test
    fun `изменить имя`() = runTest {
        profile.updateNameResult = ApiResult.Success(User("id-1", "+79991234567", "Иван Петров"))
        val viewModel = createViewModel()

        viewModel.onEvent(ProfileEvent.NameChanged(" Иван Петров "))
        assertTrue(viewModel.state.value.canSaveName)
        viewModel.onEvent(ProfileEvent.SaveName)
        advanceUntilIdle()

        assertEquals(listOf("Иван Петров"), profile.updateNameCalls)
        val state = viewModel.state.value
        assertEquals("Иван Петров", state.user?.name)
        assertFalse(state.canSaveName)
        assertEquals(UiText.Resource(R.string.profile_name_saved), state.snackbar)
    }

    @Test
    fun `пустое имя не сохранить`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.NameChanged("   "))
        assertFalse(viewModel.state.value.canSaveName)
        viewModel.onEvent(ProfileEvent.SaveName)
        advanceUntilIdle()
        assertTrue(profile.updateNameCalls.isEmpty())
    }

    @Test
    fun `сервер не принял имя (400) — текст под полем`() = runTest {
        profile.updateNameResult = httpError(400, ErrorCodes.VALIDATION_ERROR, details = mapOf("name" to "Слишком длинное"))
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.NameChanged("Пётр"))
        viewModel.onEvent(ProfileEvent.SaveName)
        advanceUntilIdle()
        assertEquals(UiText.Raw("Слишком длинное"), viewModel.state.value.nameError)
    }

    @Test
    fun `выход — с подтверждением`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.RequestLogout)
        assertTrue(viewModel.state.value.confirmLogout)
        assertEquals(0, auth.logoutCalls)

        viewModel.onEvent(ProfileEvent.ConfirmLogout)
        assertTrue(viewModel.state.value.isLoggingOut)
        advanceUntilIdle()
        assertEquals(1, auth.logoutCalls)
        assertFalse(viewModel.state.value.confirmLogout)
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
