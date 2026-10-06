package ru.fueltracker.app.ui.cars

import androidx.lifecycle.SavedStateHandle
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
import ru.fueltracker.app.domain.model.FuelType
import ru.fueltracker.app.testutil.FakeCarRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.httpError
import ru.fueltracker.app.testutil.networkError
import ru.fueltracker.app.testutil.testCar
import ru.fueltracker.app.ui.common.UiText
import java.math.BigDecimal

class CarEditViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeCarRepository()

    private fun createViewModel(carId: String? = null) = CarEditViewModel(
        SavedStateHandle(if (carId != null) mapOf("carId" to carId) else emptyMap()),
        repository,
    )

    private fun CarEditViewModel.fillValid() {
        onEvent(CarEditEvent.NameChanged("Kia Rio"))
        onEvent(CarEditEvent.FuelTypeChanged(FuelType.AI92))
        onEvent(CarEditEvent.TankChanged("43"))
        onEvent(CarEditEvent.NormSummerChanged("8,5"))
    }

    @Test
    fun `новое авто — пустая форма`() {
        val state = createViewModel().state.value
        assertTrue(state.isNew)
        assertFalse(state.isLoading)
        assertEquals(CarForm(), state.form)
    }

    @Test
    fun `пустая форма не отправляется, ошибки под полями`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(CarEditEvent.Save)
        advanceUntilIdle()

        assertTrue(repository.created.isEmpty())
        val errors = viewModel.state.value.errors
        assertEquals(UiText.Resource(R.string.error_required), errors.name)
        assertEquals(UiText.Resource(R.string.error_required), errors.fuelType)
    }

    @Test
    fun `правка поля убирает его ошибку`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(CarEditEvent.Save)
        viewModel.onEvent(CarEditEvent.NameChanged("K"))
        val errors = viewModel.state.value.errors
        assertNull(errors.name)
        assertEquals(UiText.Resource(R.string.error_required), errors.fuelType)
    }

    @Test
    fun `в числовые поля попадают только цифры и разделитель`() {
        val viewModel = createViewModel()
        viewModel.onEvent(CarEditEvent.TankChanged("4a3"))
        assertEquals("43", viewModel.state.value.form.tankCapacity)
    }

    @Test
    fun `создание авто`() = runTest {
        val viewModel = createViewModel()
        viewModel.fillValid()
        viewModel.onEvent(CarEditEvent.Save)
        assertTrue(viewModel.state.value.isSaving)
        advanceUntilIdle()

        val input = repository.created.single()
        assertEquals("Kia Rio", input.name)
        assertEquals(FuelType.AI92, input.fuelType)
        assertEquals(BigDecimal("8.5"), input.normSummer)
        assertNull(input.normWinter)
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun `редактирование — форма заполняется из API и отправляется PATCH`() = runTest {
        repository.cars = mutableListOf(testCar(id = "car-1"))
        val viewModel = createViewModel("car-1")
        assertTrue(viewModel.state.value.isLoading)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isNew)
        assertEquals("Lada Vesta", state.form.name)
        assertEquals("11,684", state.form.normWinter)

        // Стираем зимнюю норму — уйдёт null
        viewModel.onEvent(CarEditEvent.NormWinterChanged(""))
        viewModel.onEvent(CarEditEvent.Save)
        advanceUntilIdle()

        val (id, input) = repository.updated.single()
        assertEquals("car-1", id)
        assertNull(input.normWinter)
        assertTrue(repository.created.isEmpty())
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun `ошибка загрузки авто — экран ошибки и повтор`() = runTest {
        repository.getCarResult = networkError
        val viewModel = createViewModel("car-1")
        advanceUntilIdle()
        assertEquals(UiText.Resource(R.string.error_no_connection), viewModel.state.value.loadError)

        repository.getCarResult = null
        repository.cars = mutableListOf(testCar(id = "car-1"))
        viewModel.onEvent(CarEditEvent.Retry)
        advanceUntilIdle()
        assertNull(viewModel.state.value.loadError)
        assertEquals("Lada Vesta", viewModel.state.value.form.name)
    }

    @Test
    fun `400 от сервера — текст под нужным полем`() = runTest {
        repository.saveResult = httpError(
            400,
            ErrorCodes.VALIDATION_ERROR,
            message = "Неверные данные запроса",
            details = mapOf("tank_capacity_l" to "Слишком большое значение"),
        )
        val viewModel = createViewModel()
        viewModel.fillValid()
        viewModel.onEvent(CarEditEvent.Save)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(UiText.Raw("Слишком большое значение"), state.errors.tankCapacity)
        assertNull(state.snackbar)
        assertFalse(state.saved)
    }

    @Test
    fun `нет сети при сохранении — Snackbar`() = runTest {
        repository.saveResult = networkError
        val viewModel = createViewModel()
        viewModel.fillValid()
        viewModel.onEvent(CarEditEvent.Save)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(UiText.Resource(R.string.error_no_connection), state.snackbar)
        assertFalse(state.isSaving)
        assertFalse(state.saved)
    }
}
