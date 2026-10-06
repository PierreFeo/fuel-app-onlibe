package ru.fueltracker.app.ui.auth

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.fueltracker.app.data.local.AppMode
import ru.fueltracker.app.data.local.FakeAppStateStorage
import ru.fueltracker.app.data.local.LocalProfile
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.testutil.FakeProfileRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.httpError
import ru.fueltracker.app.testutil.networkError
import ru.fueltracker.app.ui.common.UiText

class NameViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeProfileRepository()
    private val appState = FakeAppStateStorage(LocalProfile(mode = AppMode.ACCOUNT, ownerUserId = "id-1"))

    private fun viewModel(guest: Boolean = false) =
        NameViewModel(SavedStateHandle(mapOf("guest" to guest)), repository, appState)

    @Test
    fun `пустое имя или пробелы не отправить`() {
        val viewModel = viewModel()
        assertFalse(viewModel.state.value.canSubmit)
        viewModel.onEvent(NameEvent.NameChanged("   "))
        assertFalse(viewModel.state.value.canSubmit)
    }

    @Test
    fun `имя не длиннее 100 символов`() {
        val viewModel = viewModel()
        viewModel.onEvent(NameEvent.NameChanged("я".repeat(150)))
        assertEquals(100, viewModel.state.value.name.length)
    }

    @Test
    fun `аккаунт — имя без пробелов по краям уходит на сервер`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(NameEvent.NameChanged("  Иван Петров "))
        viewModel.onEvent(NameEvent.Submit)
        assertTrue(viewModel.state.value.isSaving)
        advanceUntilIdle()

        assertEquals(listOf("Иван Петров"), repository.updateNameCalls)
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
        viewModel.onEvent(NameEvent.NameChanged("Иван"))
        viewModel.onEvent(NameEvent.Submit)
        advanceUntilIdle()

        assertEquals(UiText.Raw("Слишком длинное имя"), viewModel.state.value.error)
        assertFalse(viewModel.state.value.saved)
    }

    @Test
    fun `нет сети — идём дальше, имя уйдёт при синхронизации`() = runTest {
        repository.updateNameResult = networkError
        val viewModel = viewModel()
        viewModel.onEvent(NameEvent.NameChanged("Иван"))
        viewModel.onEvent(NameEvent.Submit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.saved)
        assertEquals("Иван", appState.current.name)
        assertTrue(appState.current.nameDirty)
    }

    @Test
    fun `гость — имя только на телефоне, сервер не трогаем`() = runTest {
        appState.clear()
        val viewModel = viewModel(guest = true)
        viewModel.onEvent(NameEvent.NameChanged(" Иван "))
        viewModel.onEvent(NameEvent.Submit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.saved)
        assertTrue(repository.updateNameCalls.isEmpty())
        assertEquals(LocalProfile(mode = AppMode.GUEST, name = "Иван"), appState.current)
    }
}
