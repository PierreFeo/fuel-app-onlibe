package ru.fueltracker.app.ui.sync

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.fueltracker.app.R
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.repository.RejectedRecord
import ru.fueltracker.app.data.repository.SyncResult
import ru.fueltracker.app.testutil.FakeSyncRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.ui.common.UiText
import java.io.IOException

class SyncAfterLoginViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val sync = FakeSyncRepository()
    private val offline = SyncResult.Failure(ApiError.Network(IOException("no route")))

    @Test
    fun `сразу синхронизирует и открывает приложение`() = runTest {
        val viewModel = SyncAfterLoginViewModel(sync)
        assertTrue(viewModel.state.value.isSyncing)
        advanceUntilIdle()

        assertEquals(1, sync.syncCalls)
        assertTrue(viewModel.state.value.done)
    }

    @Test
    fun `отклонённые записи не мешают войти`() = runTest {
        sync.results = mutableListOf(SyncResult.Success(1, 0, listOf(RejectedRecord(null, "x", "Ошибка"))))
        val viewModel = SyncAfterLoginViewModel(sync)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.done)
    }

    @Test
    fun `нет связи — Повторить синхронизирует снова`() = runTest {
        sync.results = mutableListOf(offline, SyncResult.Success(0, 5, emptyList()))
        val viewModel = SyncAfterLoginViewModel(sync)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isSyncing)
        assertFalse(state.done)
        assertEquals(UiText.Resource(R.string.sync_failed), state.error)

        viewModel.onEvent(SyncAfterLoginEvent.Retry)
        advanceUntilIdle()
        assertEquals(2, sync.syncCalls)
        assertNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.done)
    }

    @Test
    fun `нет связи — Продолжить открывает приложение, данные придут позже`() = runTest {
        sync.results = mutableListOf(offline)
        val viewModel = SyncAfterLoginViewModel(sync)
        advanceUntilIdle()

        viewModel.onEvent(SyncAfterLoginEvent.Continue)

        assertTrue(viewModel.state.value.done)
        assertEquals(1, sync.syncCalls)
    }
}
