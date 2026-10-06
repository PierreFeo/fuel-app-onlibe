package ru.fueltracker.app.ui.cars

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
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.testutil.FakeCarRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.httpError
import ru.fueltracker.app.testutil.networkError
import ru.fueltracker.app.testutil.testCar
import ru.fueltracker.app.ui.common.UiText

class CarsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeCarRepository()
    private val selected = FakeSelectedCarStorage()
    private val viewModel by lazy { CarsViewModel(repository, selected) }

    private val lada = testCar(id = "car-1", name = "Lada Vesta")
    private val kia = testCar(id = "car-2", name = "Kia Rio")

    @Test
    fun `сначала загрузка, потом список`() = runTest {
        repository.cars = mutableListOf(lada, kia)
        assertTrue(viewModel.state.value.isLoading)

        viewModel.onEvent(CarsEvent.Refresh)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertEquals(listOf(lada, kia), state.cars)
    }

    @Test
    fun `пустой список`() = runTest {
        viewModel.onEvent(CarsEvent.Refresh)
        advanceUntilIdle()
        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertTrue(state.cars.isEmpty())
        assertNull(state.loadError)
    }

    @Test
    fun `ошибка первой загрузки — экран ошибки, повтор загружает`() = runTest {
        repository.getCarsResult = networkError
        viewModel.onEvent(CarsEvent.Refresh)
        advanceUntilIdle()
        assertEquals(UiText.Resource(R.string.error_no_connection), viewModel.state.value.loadError)

        repository.getCarsResult = null
        repository.cars = mutableListOf(lada)
        viewModel.onEvent(CarsEvent.Refresh)
        advanceUntilIdle()
        assertNull(viewModel.state.value.loadError)
        assertEquals(listOf(lada), viewModel.state.value.cars)
    }

    @Test
    fun `ошибка обновления при показанном списке — Snackbar, список остаётся`() = runTest {
        repository.cars = mutableListOf(lada)
        viewModel.onEvent(CarsEvent.Refresh)
        advanceUntilIdle()

        repository.getCarsResult = networkError
        viewModel.onEvent(CarsEvent.Refresh)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(lada), state.cars)
        assertNull(state.loadError)
        assertEquals(UiText.Resource(R.string.error_no_connection), state.snackbar)
    }

    @Test
    fun `выбранное авто отмечено`() = runTest {
        selected.select("car-2")
        viewModel.onEvent(CarsEvent.Refresh)
        advanceUntilIdle()
        assertEquals("car-2", viewModel.state.value.selectedCarId)
    }

    @Test
    fun `выбор авто сохраняется и открывает ленту`() = runTest {
        repository.cars = mutableListOf(lada, kia)
        viewModel.onEvent(CarsEvent.Refresh)
        advanceUntilIdle()

        viewModel.onEvent(CarsEvent.Select(kia))
        advanceUntilIdle()

        assertEquals("car-2", selected.current)
        assertTrue(viewModel.state.value.openFeed)
        viewModel.onEvent(CarsEvent.FeedOpened)
        assertFalse(viewModel.state.value.openFeed)
    }

    @Test
    fun `архив — с подтверждением, авто пропадает из списка`() = runTest {
        repository.cars = mutableListOf(lada, kia)
        viewModel.onEvent(CarsEvent.Refresh)
        advanceUntilIdle()

        viewModel.onEvent(CarsEvent.RequestArchive(lada))
        assertEquals(lada, viewModel.state.value.archiveCandidate)
        assertTrue(repository.archived.isEmpty())

        viewModel.onEvent(CarsEvent.ConfirmArchive)
        advanceUntilIdle()

        assertEquals(listOf("car-1"), repository.archived)
        val state = viewModel.state.value
        assertNull(state.archiveCandidate)
        assertEquals(listOf(kia), state.cars)
        assertEquals(UiText.Resource(R.string.cars_archived), state.snackbar)
    }

    @Test
    fun `отмена архива`() = runTest {
        viewModel.onEvent(CarsEvent.RequestArchive(lada))
        viewModel.onEvent(CarsEvent.DismissArchive)
        advanceUntilIdle()
        assertNull(viewModel.state.value.archiveCandidate)
        assertTrue(repository.archived.isEmpty())
    }

    @Test
    fun `архив выбранного авто сбрасывает выбор, другого — нет`() = runTest {
        selected.select("car-1")
        repository.cars = mutableListOf(lada, kia)
        viewModel.onEvent(CarsEvent.Refresh)
        advanceUntilIdle()

        viewModel.onEvent(CarsEvent.RequestArchive(kia))
        viewModel.onEvent(CarsEvent.ConfirmArchive)
        advanceUntilIdle()
        assertEquals("car-1", selected.current)

        viewModel.onEvent(CarsEvent.RequestArchive(lada))
        viewModel.onEvent(CarsEvent.ConfirmArchive)
        advanceUntilIdle()
        assertNull(selected.current)
    }

    @Test
    fun `ошибка архива — Snackbar, авто остаётся`() = runTest {
        repository.cars = mutableListOf(lada)
        repository.archiveResult = httpError(404, ErrorCodes.NOT_FOUND, message = "Ресурс не найден")
        viewModel.onEvent(CarsEvent.Refresh)
        advanceUntilIdle()

        viewModel.onEvent(CarsEvent.RequestArchive(lada))
        viewModel.onEvent(CarsEvent.ConfirmArchive)
        advanceUntilIdle()

        assertEquals(listOf(lada), viewModel.state.value.cars)
        assertEquals(UiText.Raw("Ресурс не найден"), viewModel.state.value.snackbar)
    }
}
