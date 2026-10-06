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
import ru.fueltracker.app.testutil.FakeCarRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
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
    fun `сначала загрузка, потом список из базы`() = runTest {
        repository.cars = listOf(lada, kia)
        assertTrue(viewModel.state.value.isLoading)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertEquals(listOf(lada, kia), state.cars)
    }

    @Test
    fun `пустой список`() = runTest {
        viewModel
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.cars.isEmpty())
    }

    @Test
    fun `список обновляется сам — например, после CarEditScreen`() = runTest {
        repository.cars = listOf(lada)
        viewModel
        advanceUntilIdle()

        repository.cars = listOf(lada, kia)
        advanceUntilIdle()

        assertEquals(listOf(lada, kia), viewModel.state.value.cars)
    }

    @Test
    fun `выбранное авто отмечено`() = runTest {
        selected.select("car-2")
        viewModel
        advanceUntilIdle()
        assertEquals("car-2", viewModel.state.value.selectedCarId)
    }

    @Test
    fun `выбор авто сохраняется и открывает ленту`() = runTest {
        repository.cars = listOf(lada, kia)
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
        repository.cars = listOf(lada, kia)
        viewModel
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
        repository.cars = listOf(lada, kia)
        viewModel
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
}
