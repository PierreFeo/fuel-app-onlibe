package ru.fueltracker.app.ui.sheets

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
import ru.fueltracker.app.data.local.FakeSelectedCarStorage
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.domain.model.SheetPage
import ru.fueltracker.app.testutil.FakeCarRepository
import ru.fueltracker.app.testutil.FakeSheetRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.httpError
import ru.fueltracker.app.testutil.networkError
import ru.fueltracker.app.testutil.testCar
import ru.fueltracker.app.ui.common.UiText

class SheetsFeedViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val cars = FakeCarRepository().apply { cars = mutableListOf(testCar(id = "car-1"), testCar(id = "car-2", name = "Kia Rio")) }
    private val sheets = FakeSheetRepository()
    private val selected = FakeSelectedCarStorage(initial = "car-1")

    private val october = previewOpenSheet().copy(id = "s-10", month = 10)
    private val september = previewClosedSheet().copy(id = "s-09", month = 9)
    private val august = previewClosedSheet().copy(id = "s-08", month = 8)

    private fun TestScope.createViewModel(): SheetsFeedViewModel {
        val viewModel = SheetsFeedViewModel(cars, sheets, selected)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `загружает шапку авто и первую страницу`() = runTest {
        sheets.pages[null] = ApiResult.Success(SheetPage(listOf(october, september), nextBefore = "2026-09"))

        val state = createViewModel().state.value

        assertFalse(state.isLoading)
        assertEquals("Lada Vesta", state.car?.name)
        assertEquals(listOf(october, september), state.sheets)
        assertEquals("2026-09", state.nextBefore)
        assertTrue(state.canLoadMore)
        assertEquals(listOf("car-1" to null), sheets.calls)
    }

    @Test
    fun `пустая лента`() = runTest {
        val state = createViewModel().state.value
        assertTrue(state.hasContent)
        assertTrue(state.sheets.isEmpty())
        assertFalse(state.canLoadMore)
    }

    @Test
    fun `подгрузка старых листов по next_before`() = runTest {
        sheets.pages[null] = ApiResult.Success(SheetPage(listOf(october), nextBefore = "2026-10"))
        sheets.pages["2026-10"] = ApiResult.Success(SheetPage(listOf(september, august), nextBefore = null))
        val viewModel = createViewModel()

        viewModel.onEvent(SheetsFeedEvent.LoadMore)
        assertTrue(viewModel.state.value.isLoadingMore)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(october, september, august), state.sheets)
        assertNull(state.nextBefore)
        assertFalse(state.isLoadingMore)

        // Больше страниц нет — запрос не уходит
        viewModel.onEvent(SheetsFeedEvent.LoadMore)
        advanceUntilIdle()
        assertEquals(2, sheets.calls.size)
    }

    @Test
    fun `ошибка подгрузки — внизу Повторить, повтор догружает`() = runTest {
        sheets.pages[null] = ApiResult.Success(SheetPage(listOf(october), nextBefore = "2026-10"))
        sheets.pages["2026-10"] = networkError
        val viewModel = createViewModel()

        viewModel.onEvent(SheetsFeedEvent.LoadMore)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.loadMoreFailed)
        // Автоподгрузка при прокрутке не долбит сервер после ошибки
        assertFalse(viewModel.state.value.canLoadMore)

        sheets.pages["2026-10"] = ApiResult.Success(SheetPage(listOf(september), nextBefore = null))
        viewModel.onEvent(SheetsFeedEvent.LoadMore)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.loadMoreFailed)
        assertEquals(listOf(october, september), viewModel.state.value.sheets)
    }

    @Test
    fun `pull-to-refresh перезагружает первую страницу`() = runTest {
        sheets.pages[null] = ApiResult.Success(SheetPage(listOf(september), nextBefore = null))
        val viewModel = createViewModel()

        sheets.pages[null] = ApiResult.Success(SheetPage(listOf(october, september), nextBefore = null))
        viewModel.onEvent(SheetsFeedEvent.Refresh)
        assertTrue(viewModel.state.value.isRefreshing)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRefreshing)
        assertEquals(listOf(october, september), viewModel.state.value.sheets)
    }

    @Test
    fun `ошибка обновления при показанной ленте — Snackbar, лента остаётся`() = runTest {
        sheets.pages[null] = ApiResult.Success(SheetPage(listOf(september), nextBefore = null))
        val viewModel = createViewModel()

        sheets.pages[null] = networkError
        viewModel.onEvent(SheetsFeedEvent.Refresh)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(september), state.sheets)
        assertNull(state.loadError)
        assertFalse(state.isRefreshing)
        assertEquals(UiText.Resource(R.string.error_no_connection), state.snackbar)
    }

    @Test
    fun `ошибка первой загрузки — экран ошибки, Повторить загружает`() = runTest {
        cars.getCarResult = networkError
        val viewModel = createViewModel()
        assertEquals(UiText.Resource(R.string.error_no_connection), viewModel.state.value.loadError)

        cars.getCarResult = null
        viewModel.onEvent(SheetsFeedEvent.Retry)
        assertTrue(viewModel.state.value.isLoading)
        advanceUntilIdle()
        assertNull(viewModel.state.value.loadError)
        assertEquals("Lada Vesta", viewModel.state.value.car?.name)
    }

    @Test
    fun `авто не найдено (404) — выбор сброшен, переход к списку авто`() = runTest {
        cars.getCarResult = httpError(404, ErrorCodes.NOT_FOUND)
        val viewModel = createViewModel()

        assertNull(selected.current)
        assertTrue(viewModel.state.value.noCar)
    }

    @Test
    fun `авто не выбрано — переход к списку авто`() = runTest {
        selected.clear()
        val viewModel = createViewModel()
        assertTrue(viewModel.state.value.noCar)
        assertTrue(sheets.calls.isEmpty())
    }

    @Test
    fun `смена авто перезагружает ленту`() = runTest {
        val viewModel = createViewModel()
        selected.select("car-2")
        advanceUntilIdle()
        assertEquals("Kia Rio", viewModel.state.value.car?.name)
        assertEquals(listOf("car-1" to null, "car-2" to null), sheets.calls)
    }

    @Test
    fun `раскрыть и свернуть заправки`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.ToggleRefuelings("s-09"))
        assertEquals(setOf("s-09"), viewModel.state.value.expandedSheetIds)
        viewModel.onEvent(SheetsFeedEvent.ToggleRefuelings("s-09"))
        assertTrue(viewModel.state.value.expandedSheetIds.isEmpty())
    }
}
