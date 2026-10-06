package ru.fueltracker.app.ui.cars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fueltracker.app.R
import ru.fueltracker.app.data.local.SelectedCarStorage
import ru.fueltracker.app.data.repository.CarRepository
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.ui.common.UiText
import javax.inject.Inject

data class CarsUiState(
    /** Список из базы ещё не пришёл (доли секунды при открытии). */
    val isLoading: Boolean = true,
    val cars: List<Car> = emptyList(),
    val selectedCarId: String? = null,
    /** Авто, для которого открыт диалог «Отправить в архив?». */
    val archiveCandidate: Car? = null,
    val snackbar: UiText? = null,
    /** Авто выбрано — перейти в ленту ЛУТ. */
    val openFeed: Boolean = false,
)

sealed interface CarsEvent {
    data class Select(val car: Car) : CarsEvent
    data class RequestArchive(val car: Car) : CarsEvent
    data object ConfirmArchive : CarsEvent
    data object DismissArchive : CarsEvent
    data object FeedOpened : CarsEvent
    data object SnackbarShown : CarsEvent
}

@HiltViewModel
class CarsViewModel @Inject constructor(
    private val carRepository: CarRepository,
    private val selectedCarStorage: SelectedCarStorage,
) : ViewModel() {

    private val _state = MutableStateFlow(CarsUiState())
    val state: StateFlow<CarsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            selectedCarStorage.selectedCarId.collect { id -> _state.update { it.copy(selectedCarId = id) } }
        }
        // Список обновляется сам — после CarEditScreen, архивации, синхронизации
        viewModelScope.launch {
            carRepository.observeCars().collect { cars -> _state.update { it.copy(isLoading = false, cars = cars) } }
        }
    }

    fun onEvent(event: CarsEvent) {
        when (event) {
            is CarsEvent.Select -> select(event.car)
            is CarsEvent.RequestArchive -> _state.update { it.copy(archiveCandidate = event.car) }
            CarsEvent.ConfirmArchive -> archive()
            CarsEvent.DismissArchive -> _state.update { it.copy(archiveCandidate = null) }
            CarsEvent.FeedOpened -> _state.update { it.copy(openFeed = false) }
            CarsEvent.SnackbarShown -> _state.update { it.copy(snackbar = null) }
        }
    }

    private fun select(car: Car) {
        viewModelScope.launch {
            selectedCarStorage.select(car.id)
            _state.update { it.copy(openFeed = true) }
        }
    }

    private fun archive() {
        val car = _state.value.archiveCandidate ?: return
        _state.update { it.copy(archiveCandidate = null) }
        viewModelScope.launch {
            carRepository.archiveCar(car.id)
            selectedCarStorage.clearIf(car.id)
            _state.update { it.copy(snackbar = UiText.Resource(R.string.cars_archived)) }
        }
    }
}
