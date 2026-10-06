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
import ru.fueltracker.app.data.local.FakeSelectedCarStorage
import ru.fueltracker.app.testutil.FakeCarRepository
import ru.fueltracker.app.testutil.FakeRefuelingRepository
import ru.fueltracker.app.testutil.FakeSheetRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.testCar

/** Лента ЛУТ следит за базой: авто и листы приходят и обновляются сами. */
class SheetsFeedViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val cars = FakeCarRepository().apply { cars = listOf(testCar(id = "car-1"), testCar(id = "car-2", name = "Kia Rio")) }
    private val sheets = FakeSheetRepository()
    private val refuelings = FakeRefuelingRepository()
    private val selected = FakeSelectedCarStorage(initial = "car-1")

    private val october = previewOpenSheet().copy(id = "s-10", month = 10)
    private val september = previewClosedSheet().copy(id = "s-09", month = 9)
    private val kiaSheet = previewOpenSheet().copy(id = "kia-10", carId = "car-2", month = 10)

    private fun TestScope.createViewModel(): SheetsFeedViewModel {
        val viewModel = SheetsFeedViewModel(cars, sheets, refuelings, selected)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `шапка авто и все листы из базы`() = runTest {
        sheets.sheets = listOf(october, september, kiaSheet)

        val state = createViewModel().state.value

        assertFalse(state.isLoading)
        assertTrue(state.hasContent)
        assertEquals("Lada Vesta", state.car?.name)
        assertEquals(listOf(october, september), state.sheets) // листы только выбранного авто
    }

    @Test
    fun `пустая лента`() = runTest {
        val state = createViewModel().state.value
        assertTrue(state.hasContent)
        assertTrue(state.sheets.isEmpty())
    }

    @Test
    fun `изменения в базе приходят в ленту сами`() = runTest {
        sheets.sheets = listOf(september)
        val viewModel = createViewModel()

        sheets.sheets = listOf(october, september) // например, после синхронизации
        cars.cars = cars.cars.map { if (it.id == "car-1") it.copy(name = "Lada Granta") else it }
        advanceUntilIdle()

        assertEquals(listOf(october, september), viewModel.state.value.sheets)
        assertEquals("Lada Granta", viewModel.state.value.car?.name)
    }

    @Test
    fun `авто удалили — выбор сброшен, переход к списку авто`() = runTest {
        val viewModel = createViewModel()

        cars.cars = cars.cars.filterNot { it.id == "car-1" }
        advanceUntilIdle()

        assertNull(selected.current)
        assertTrue(viewModel.state.value.noCar)
    }

    @Test
    fun `авто отправили в архив — тоже к списку авто`() = runTest {
        val viewModel = createViewModel()

        cars.archiveCar("car-1")
        advanceUntilIdle()

        assertNull(selected.current)
        assertTrue(viewModel.state.value.noCar)
    }

    @Test
    fun `авто не выбрано — переход к списку авто`() = runTest {
        selected.clear()
        val viewModel = createViewModel()
        assertTrue(viewModel.state.value.noCar)
    }

    @Test
    fun `смена авто — лента другого авто, раскрытые карточки сброшены`() = runTest {
        sheets.sheets = listOf(october, kiaSheet)
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.ToggleExpanded("s-10"))

        selected.select("car-2")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("Kia Rio", state.car?.name)
        assertEquals(listOf(kiaSheet), state.sheets)
        assertTrue(state.expandedSheetIds.isEmpty())
    }

    @Test
    fun `раскрыть и свернуть карточку`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.ToggleExpanded("s-09"))
        assertEquals(setOf("s-09"), viewModel.state.value.expandedSheetIds)
        viewModel.onEvent(SheetsFeedEvent.ToggleExpanded("s-09"))
        assertTrue(viewModel.state.value.expandedSheetIds.isEmpty())
    }
}
