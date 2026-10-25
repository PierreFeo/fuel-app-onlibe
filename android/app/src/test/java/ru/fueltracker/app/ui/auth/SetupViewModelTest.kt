package ru.fueltracker.app.ui.auth

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.fueltracker.app.R
import ru.fueltracker.app.data.local.AppMode
import ru.fueltracker.app.data.local.FakeAppStateStorage
import ru.fueltracker.app.data.local.LocalProfile
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.testutil.FakeProfileRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.httpError
import ru.fueltracker.app.testutil.networkError
import ru.fueltracker.app.ui.common.UiText

class SetupViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeProfileRepository()
    private val appState = FakeAppStateStorage(LocalProfile(mode = AppMode.ACCOUNT, ownerUserId = "id-1"))

    private fun viewModel(guest: Boolean = false, askPassword: Boolean = false) =
        SetupViewModel(SavedStateHandle(mapOf("guest" to guest, "askPassword" to askPassword)), repository, appState)

    private fun SetupViewModel.fill(name: String, password: String = "", repeat: String = password) {
        onEvent(SetupEvent.NameChanged(name))
        onEvent(SetupEvent.PasswordChanged(password))
        onEvent(SetupEvent.PasswordRepeatChanged(repeat))
    }

    @Test
    fun `пустое имя или пробелы не отправить`() {
        val viewModel = viewModel()
        assertFalse(viewModel.state.value.canSubmit)
        viewModel.onEvent(SetupEvent.NameChanged("   "))
        assertFalse(viewModel.state.value.canSubmit)
    }

    @Test
    fun `имя не длиннее 100 символов`() {
        val viewModel = viewModel()
        viewModel.onEvent(SetupEvent.NameChanged("я".repeat(150)))
        assertEquals(100, viewModel.state.value.name.length)
    }

    @Test
    fun `известное имя подставляется`() = runTest {
        appState.setSyncedName("Иван Петров")
        val viewModel = viewModel(askPassword = true)
        advanceUntilIdle()

        assertEquals("Иван Петров", viewModel.state.value.name)
    }

    @Test
    fun `аккаунт — имя без пробелов по краям уходит на сервер`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(SetupEvent.NameChanged("  Иван Петров "))
        viewModel.onEvent(SetupEvent.Submit)
        assertTrue(viewModel.state.value.isSaving)
        advanceUntilIdle()

        assertEquals(listOf("Иван Петров"), repository.updateNameCalls)
        assertTrue(repository.changePasswordCalls.isEmpty())
        assertTrue(viewModel.state.value.saved)
        assertEquals("Иван", appState.current.name) // как вернул сервер (фейк отвечает «Иван»)
        assertFalse(appState.current.nameDirty)
    }

    @Test
    fun `400 — текст сервера под полем`() = runTest {
        repository.updateNameResult = httpError(
            400,
            ErrorCodes.VALIDATION_ERROR,
            message = "Неверные данные",
            details = mapOf("name" to "Слишком длинное имя"),
        )
        val viewModel = viewModel()
        viewModel.onEvent(SetupEvent.NameChanged("Иван"))
        viewModel.onEvent(SetupEvent.Submit)
        advanceUntilIdle()

        assertEquals(UiText.Raw("Слишком длинное имя"), viewModel.state.value.error)
        assertFalse(viewModel.state.value.saved)
    }

    @Test
    fun `нет сети для имени — идём дальше, имя уйдёт при синхронизации`() = runTest {
        repository.updateNameResult = networkError
        val viewModel = viewModel()
        viewModel.onEvent(SetupEvent.NameChanged("Иван"))
        viewModel.onEvent(SetupEvent.Submit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.saved)
        assertEquals("Иван", appState.current.name)
        assertTrue(appState.current.nameDirty)
    }

    @Test
    fun `пароль обязателен — без него не отправить`() {
        val viewModel = viewModel(askPassword = true)
        viewModel.fill("Иван", password = "")
        assertFalse(viewModel.state.value.canSubmit)
        viewModel.fill("Иван", password = "k7Fm2xQp9a", repeat = "")
        assertFalse(viewModel.state.value.canSubmit)
    }

    @Test
    fun `короткий или несовпадающий пароль — ошибка под полем, запросов нет`() = runTest {
        val viewModel = viewModel(askPassword = true)
        viewModel.fill("Иван", password = "1234")
        viewModel.onEvent(SetupEvent.Submit)
        assertEquals(UiText.Resource(R.string.error_password_length), viewModel.state.value.password?.newError)

        viewModel.fill("Иван", password = "k7Fm2xQp9a", repeat = "k7Fm2xQp9b")
        viewModel.onEvent(SetupEvent.Submit)
        advanceUntilIdle()

        assertEquals(UiText.Resource(R.string.error_password_mismatch), viewModel.state.value.password?.repeatError)
        assertTrue(repository.changePasswordCalls.isEmpty())
        assertTrue(repository.updateNameCalls.isEmpty())
    }

    @Test
    fun `пароль и имя сохраняются — сначала пароль`() = runTest {
        val viewModel = viewModel(askPassword = true)
        viewModel.fill("Иван", password = "k7Fm2xQp9a")
        viewModel.onEvent(SetupEvent.Submit)
        advanceUntilIdle()

        assertEquals(listOf<Pair<String?, String>>(null to "k7Fm2xQp9a"), repository.changePasswordCalls)
        assertTrue(appState.current.hasPassword)
        assertEquals(listOf("Иван"), repository.updateNameCalls)
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun `нет сети для пароля — экран не закрывается, имя не отправляем`() = runTest {
        repository.changePasswordResult = networkError
        val viewModel = viewModel(askPassword = true)
        viewModel.fill("Иван", password = "k7Fm2xQp9a")
        viewModel.onEvent(SetupEvent.Submit)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.saved)
        assertFalse(state.isSaving)
        assertNotNull(state.password?.error)
        assertFalse(appState.current.hasPassword)
        assertTrue(repository.updateNameCalls.isEmpty())
    }

    @Test
    fun `пароль сохранён, имя отклонено — повторно пароль не отправляем`() = runTest {
        repository.updateNameResult = httpError(400, ErrorCodes.VALIDATION_ERROR, message = "Неверное имя")
        val viewModel = viewModel(askPassword = true)
        viewModel.fill("Иван", password = "k7Fm2xQp9a")
        viewModel.onEvent(SetupEvent.Submit)
        advanceUntilIdle()

        assertNull(viewModel.state.value.password)
        repository.updateNameResult = networkError
        viewModel.onEvent(SetupEvent.Submit)
        advanceUntilIdle()

        assertEquals(1, repository.changePasswordCalls.size)
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun `гость — только имя на телефоне, сервер не трогаем`() = runTest {
        appState.clear()
        val viewModel = viewModel(guest = true, askPassword = true)
        assertNull(viewModel.state.value.password)
        viewModel.onEvent(SetupEvent.NameChanged(" Иван "))
        viewModel.onEvent(SetupEvent.Submit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.saved)
        assertTrue(repository.updateNameCalls.isEmpty())
        assertTrue(repository.changePasswordCalls.isEmpty())
        assertEquals(LocalProfile(mode = AppMode.GUEST, name = "Иван"), appState.current)
    }
}
