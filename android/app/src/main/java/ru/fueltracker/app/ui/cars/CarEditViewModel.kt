package ru.fueltracker.app.ui.cars

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fueltracker.app.R
import ru.fueltracker.app.data.repository.CarRepository
import ru.fueltracker.app.domain.model.FuelType
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.filterDecimalInput
import javax.inject.Inject

data class CarEditUiState(
    val isNew: Boolean,
    /** Загружается авто для редактирования. */
    val isLoading: Boolean = false,
    val loadError: UiText? = null,
    val form: CarForm = CarForm(),
    val errors: CarFormErrors = CarFormErrors(),
    val isSaving: Boolean = false,
    val snackbar: UiText? = null,
    val saved: Boolean = false,
)

sealed interface CarEditEvent {
    data class NameChanged(val value: String) : CarEditEvent
    data class PlateChanged(val value: String) : CarEditEvent
    data class FuelTypeChanged(val value: FuelType) : CarEditEvent
    data class TankChanged(val value: String) : CarEditEvent
    data class NormSummerChanged(val value: String) : CarEditEvent
    data class NormWinterChanged(val value: String) : CarEditEvent
    data object Save : CarEditEvent
    data object Retry : CarEditEvent
    data object SavedHandled : CarEditEvent
    data object SnackbarShown : CarEditEvent
}

@HiltViewModel
class CarEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val carRepository: CarRepository,
) : ViewModel() {

    /** null — новое авто (CarEditRoute.carId). */
    private val carId: String? = savedStateHandle.get<String>(ARG_CAR_ID)

    private val _state = MutableStateFlow(CarEditUiState(isNew = carId == null))
    val state: StateFlow<CarEditUiState> = _state.asStateFlow()

    init {
        if (carId != null) load(carId)
    }

    fun onEvent(event: CarEditEvent) {
        when (event) {
            // Правка поля убирает его ошибку
            is CarEditEvent.NameChanged -> edit({ copy(name = event.value) }) { copy(name = null) }
            is CarEditEvent.PlateChanged -> edit({ copy(plateNumber = event.value) }) { copy(plateNumber = null) }
            is CarEditEvent.FuelTypeChanged -> edit({ copy(fuelType = event.value) }) { copy(fuelType = null) }
            is CarEditEvent.TankChanged ->
                edit({ copy(tankCapacity = filterDecimalInput(event.value)) }) { copy(tankCapacity = null) }
            is CarEditEvent.NormSummerChanged ->
                edit({ copy(normSummer = filterDecimalInput(event.value)) }) { copy(normSummer = null) }
            is CarEditEvent.NormWinterChanged ->
                edit({ copy(normWinter = filterDecimalInput(event.value)) }) { copy(normWinter = null) }
            CarEditEvent.Save -> save()
            CarEditEvent.Retry -> carId?.let(::load)
            CarEditEvent.SavedHandled -> _state.update { it.copy(saved = false) }
            CarEditEvent.SnackbarShown -> _state.update { it.copy(snackbar = null) }
        }
    }

    private fun edit(changeForm: CarForm.() -> CarForm, clearError: CarFormErrors.() -> CarFormErrors) {
        _state.update { it.copy(form = it.form.changeForm(), errors = it.errors.clearError()) }
    }

    private fun load(id: String) {
        _state.update { it.copy(isLoading = true, loadError = null) }
        viewModelScope.launch {
            val car = carRepository.getCar(id)
            _state.update {
                if (car != null) {
                    it.copy(isLoading = false, form = car.toForm())
                } else {
                    it.copy(isLoading = false, loadError = UiText.Resource(R.string.rule_not_found))
                }
            }
        }
    }

    private fun save() {
        val current = _state.value
        if (current.isSaving || current.isLoading) return
        val (input, errors) = validateCarForm(current.form)
        if (input == null) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(isSaving = true, errors = CarFormErrors()) }
        viewModelScope.launch {
            // Сохранение — в базу на телефоне: все проверки уже сделала форма
            val saved = if (carId == null) carRepository.createCar(input) else carRepository.updateCar(carId, input)
            _state.update {
                if (saved != null) {
                    it.copy(isSaving = false, saved = true)
                } else {
                    it.copy(isSaving = false, snackbar = UiText.Resource(R.string.rule_not_found))
                }
            }
        }
    }

    companion object {
        internal const val ARG_CAR_ID = "carId"
    }
}
