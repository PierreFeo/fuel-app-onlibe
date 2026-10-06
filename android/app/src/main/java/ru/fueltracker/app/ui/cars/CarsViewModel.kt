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
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.repository.CarRepository
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.toUiText
import javax.inject.Inject

data class CarsUiState(
    /** Первая загрузка: списка ещё нет. */
    val isLoading: Boolean = true,
    val cars: List<Car> = emptyList(),
    /** Ошибка первой загрузки — экран ошибки с «Повторить». */
    val loadError: UiText? = null,
    val selectedCarId: String? = null,
    /** Авто, для которого открыт диалог «Отправить в архив?». */
    val archiveCandidate: Car? = null,
    val snackbar: UiText? = null,
    /** Авто выбрано — перейти в ленту ЛУТ. */
    val openFeed: Boolean = false,
)

sealed interface CarsEvent {
    data object Refresh : CarsEvent
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
    }

    fun onEvent(event: CarsEvent) {
        when (event) {
            // Вызывается при каждом показе экрана — так список обновится после CarEditScreen
            CarsEvent.Refresh -> refresh()
            is CarsEvent.Select -> select(event.car)
            is CarsEvent.RequestArchive -> _state.update { it.copy(archiveCandidate = event.car) }
            CarsEvent.ConfirmArchive -> archive()
            CarsEvent.DismissArchive -> _state.update { it.copy(archiveCandidate = null) }
            CarsEvent.FeedOpened -> _state.update { it.copy(openFeed = false) }
            CarsEvent.SnackbarShown -> _state.update { it.copy(snackbar = null) }
        }
    }

    private fun refresh() {
        val hasContent = _state.value.cars.isNotEmpty()
        if (!hasContent) _state.update { it.copy(isLoading = true, loadError = null) }
        viewModelScope.launch {
            when (val result = carRepository.getCars()) {
                is ApiResult.Success -> _state.update {
                    it.copy(isLoading = false, loadError = null, cars = result.data)
                }
                is ApiResult.Failure -> _state.update {
                    // Список уже на экране — не прячем его, а сообщаем в Snackbar
                    if (it.cars.isNotEmpty()) {
                        it.copy(isLoading = false, snackbar = result.error.toUiText())
                    } else {
                        it.copy(isLoading = false, loadError = result.error.toUiText())
                    }
                }
            }
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
            when (val result = carRepository.archiveCar(car.id)) {
                is ApiResult.Success -> {
                    selectedCarStorage.clearIf(car.id)
                    _state.update {
                        it.copy(
                            cars = it.cars.filterNot { c -> c.id == car.id },
                            snackbar = UiText.Resource(R.string.cars_archived),
                        )
                    }
                }
                is ApiResult.Failure -> _state.update { it.copy(snackbar = result.error.toUiText()) }
            }
        }
    }
}
