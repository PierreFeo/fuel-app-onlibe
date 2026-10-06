package ru.fueltracker.app.ui.auth

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.fueltracker.app.R
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
    private val viewModel = NameViewModel(repository)

    @Test
    fun `пустое имя или пробелы не отправить`() {
        assertFalse(viewModel.state.value.canSubmit)
        viewModel.onEvent(NameEvent.NameChanged("   "))
        assertFalse(viewModel.state.value.canSubmit)
    }

    @Test
    fun `имя не длиннее 100 символов`() {
        viewModel.onEvent(NameEvent.NameChanged("я".repeat(150)))
        assertEquals(100, viewModel.state.value.name.length)
    }

    @Test
    fun `сохраняется имя без пробелов по краям`() = runTest {
        viewModel.onEvent(NameEvent.NameChanged("  Иван Петров "))
        viewModel.onEvent(NameEvent.Submit)
        assertTrue(viewModel.state.value.isSaving)
        advanceUntilIdle()

        assertEquals(listOf("Иван Петров"), repository.updateNameCalls)
        assertTrue(viewModel.state.value.saved)
        assertFalse(viewModel.state.value.isSaving)
    }

    @Test
    fun `400 — текст сервера под полем`() = runTest {
        repository.updateNameResult = httpError(
            400,
            ErrorCodes.VALIDATION_ERROR,
            message = "Неверные данные",
            details = mapOf("name" to "Слишком длинное имя"),
        )
        viewModel.onEvent(NameEvent.NameChanged("Иван"))
        viewModel.onEvent(NameEvent.Submit)
        advanceUntilIdle()

        assertEquals(UiText.Raw("Слишком длинное имя"), viewModel.state.value.error)
        assertFalse(viewModel.state.value.saved)
    }

    @Test
    fun `нет сети — Snackbar`() = runTest {
        repository.updateNameResult = networkError
        viewModel.onEvent(NameEvent.NameChanged("Иван"))
        viewModel.onEvent(NameEvent.Submit)
        advanceUntilIdle()

        assertNull(viewModel.state.value.error)
        assertEquals(UiText.Resource(R.string.error_no_connection), viewModel.state.value.snackbar)
    }
}
