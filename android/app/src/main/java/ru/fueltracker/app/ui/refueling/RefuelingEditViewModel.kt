package ru.fueltracker.app.ui.refueling

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fueltracker.app.data.repository.LocalResult
import ru.fueltracker.app.data.repository.RefuelingRepository
import ru.fueltracker.app.domain.calc.SheetRuleViolation
import ru.fueltracker.app.domain.model.PaymentType
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.filterDecimalInput
import ru.fueltracker.app.ui.common.filterDigitsInput
import ru.fueltracker.app.ui.common.toUiText
import java.time.LocalDate
import javax.inject.Inject

data class RefuelingEditUiState(
    val target: RefuelingTarget? = null,
    val form: RefuelingForm = RefuelingForm(date = LocalDate.MIN),
    val errors: RefuelingErrors = RefuelingErrors(),
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val confirmDelete: Boolean = false,
    /** Нарушено правило ЛУТ — показывается в самой шторке (Snackbar под ней не виден). */
    val error: UiText? = null,
    /** Готово: заправка записана в базу — карточка обновится сама, шторка закроется. */
    val saved: Boolean = false,
) {
    val isNew: Boolean get() = target?.refueling == null
    val isBusy: Boolean get() = isSaving || isDeleting
}

sealed interface RefuelingEditEvent {
    data class DateChanged(val value: LocalDate) : RefuelingEditEvent
    data class LitersChanged(val value: String) : RefuelingEditEvent
    data class PriceChanged(val value: String) : RefuelingEditEvent
    data class TotalChanged(val value: String) : RefuelingEditEvent
    data class OdometerChanged(val value: String) : RefuelingEditEvent
    data class StationChanged(val value: String) : RefuelingEditEvent
    data class PaymentChanged(val value: PaymentType) : RefuelingEditEvent
    data class NoteChanged(val value: String) : RefuelingEditEvent
    data object Save : RefuelingEditEvent
    data object RequestDelete : RefuelingEditEvent
    data object ConfirmDelete : RefuelingEditEvent
    data object DismissDelete : RefuelingEditEvent
    /** Шторка закрыта (сохранили или отменили) — следующее открытие начнётся с чистой формы. */
    data object Reset : RefuelingEditEvent
}

/**
 * Форма заправки в шторке поверх ленты. Шторка — не экран навигации,
 * поэтому лист и заправку передают через [start] при каждом открытии.
 */
@HiltViewModel
class RefuelingEditViewModel @Inject constructor(
    private val repository: RefuelingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RefuelingEditUiState())
    val state: StateFlow<RefuelingEditUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun start(target: RefuelingTarget, today: LocalDate = LocalDate.now()) {
        // Та же заправка уже открыта — это поворот экрана, введённое не теряем
        if (_state.value.target == target && !_state.value.saved) return
        job?.cancel()
        val form = target.refueling?.toForm() ?: target.newForm(today)
        _state.value = RefuelingEditUiState(target = target, form = form)
    }

    fun onEvent(event: RefuelingEditEvent) {
        when (event) {
            is RefuelingEditEvent.DateChanged -> edit({ copy(date = event.value) }) { copy(date = null) }
            is RefuelingEditEvent.LitersChanged ->
                edit({ copy(liters = filterDecimalInput(event.value)).withAutoTotal() }) { copy(liters = null, totalCost = null) }
            is RefuelingEditEvent.PriceChanged ->
                edit({ copy(pricePerLiter = filterDecimalInput(event.value)).withAutoTotal() }) {
                    copy(pricePerLiter = null, totalCost = null)
                }
            is RefuelingEditEvent.TotalChanged -> edit({ changeTotal(event.value) }) { copy(totalCost = null) }
            is RefuelingEditEvent.OdometerChanged ->
                edit({ copy(odometer = filterDigitsInput(event.value)) }) { copy(odometer = null) }
            is RefuelingEditEvent.StationChanged ->
                edit({ copy(station = event.value.take(STATION_MAX)) }) { copy(station = null) }
            is RefuelingEditEvent.PaymentChanged -> edit({ copy(paymentType = event.value) }) { this }
            is RefuelingEditEvent.NoteChanged -> edit({ copy(note = event.value.take(NOTE_MAX)) }) { copy(note = null) }
            RefuelingEditEvent.Save -> save()
            RefuelingEditEvent.RequestDelete -> _state.update { it.copy(confirmDelete = true) }
            RefuelingEditEvent.DismissDelete -> _state.update { it.copy(confirmDelete = false) }
            RefuelingEditEvent.ConfirmDelete -> delete()
            RefuelingEditEvent.Reset -> _state.value = RefuelingEditUiState()
        }
    }

    private fun edit(changeForm: RefuelingForm.() -> RefuelingForm, clearError: RefuelingErrors.() -> RefuelingErrors) {
        _state.update {
            if (it.isBusy) it else it.copy(form = it.form.changeForm(), errors = it.errors.clearError(), error = null)
        }
    }

    private fun save() {
        val current = _state.value
        val target = current.target ?: return
        if (current.isBusy) return
        val (input, errors) = validateRefueling(current.form, target)
        if (input == null) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(isSaving = true, errors = errors, error = null) }
        job = viewModelScope.launch {
            val refueling = target.refueling
            val result = if (refueling == null) {
                repository.create(target.sheetId, input)
            } else {
                repository.update(refueling.id, input)
            }
            handleResult(result)
        }
    }

    private fun delete() {
        val refueling = _state.value.target?.refueling ?: return
        if (_state.value.isBusy) return
        _state.update { it.copy(confirmDelete = false, isDeleting = true, error = null) }
        job = viewModelScope.launch { handleResult(repository.delete(refueling.id)) }
    }

    private fun handleResult(result: LocalResult<Unit>) {
        when (result) {
            is LocalResult.Ok -> _state.update { it.copy(isSaving = false, isDeleting = false, saved = true) }
            is LocalResult.Rejected -> _state.update {
                val text = result.violation.toUiText()
                it.copy(
                    isSaving = false,
                    isDeleting = false,
                    // Дата вне месяца — ошибка под полем даты, остальное — общим текстом
                    errors = if (result.violation == SheetRuleViolation.REFUELING_DATE_OUTSIDE_MONTH) {
                        it.errors.copy(date = text)
                    } else {
                        it.errors
                    },
                    error = text.takeUnless { result.violation == SheetRuleViolation.REFUELING_DATE_OUTSIDE_MONTH },
                )
            }
        }
    }
}

/**
 * Сумма: ввели вручную — больше не пересчитываем; стёрли — снова считаем автоматически.
 */
private fun RefuelingForm.changeTotal(value: String): RefuelingForm {
    val text = filterDecimalInput(value)
    return if (text.isBlank()) copy(isTotalManual = false).withAutoTotal() else copy(totalCost = text, isTotalManual = true)
}
