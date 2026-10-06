package ru.fueltracker.app.ui.sheets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fueltracker.app.R
import ru.fueltracker.app.data.local.SelectedCarStorage
import ru.fueltracker.app.data.repository.CarRepository
import ru.fueltracker.app.data.repository.LocalResult
import ru.fueltracker.app.data.repository.RefuelingRepository
import ru.fueltracker.app.data.repository.SheetRepository
import ru.fueltracker.app.domain.calc.SheetRuleViolation
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.Refueling
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.filterDecimalInput
import ru.fueltracker.app.ui.common.filterDigitsInput
import ru.fueltracker.app.ui.common.toUiText
import ru.fueltracker.app.ui.refueling.RefuelingTarget
import ru.fueltracker.app.ui.refueling.refuelingTarget
import java.math.BigDecimal
import javax.inject.Inject

/** Кнопка в Snackbar ленты. */
enum class FeedSnackbarAction {
    /** «Указать» зимнюю норму — открыть CarEditScreen авто. */
    SET_WINTER_NORM,
}

data class SheetsFeedUiState(
    val car: Car? = null,
    /** Все листы авто, новые сверху, с итогами (их считает телефон). */
    val sheets: List<FuelSheet> = emptyList(),
    /** Данные из базы ещё не пришли (доли секунды при открытии). */
    val isLoading: Boolean = true,
    /** Листы, у которых карточка раскрыта (подробности). */
    val expandedSheetIds: Set<String> = emptySet(),
    /** Листы, по которым идёт действие (сезон, переоткрытие, удаление) — их кнопки неактивны. */
    val busySheetIds: Set<String> = emptySet(),
    /** Считается подсказка `next-prefill` для нового листа. */
    val isPreparingNewSheet: Boolean = false,
    val newSheet: NewSheetForm? = null,
    val closeSheet: CloseSheetForm? = null,
    /** Лист, для которого открыт диалог «Удалить лист?». */
    val deleteCandidate: FuelSheet? = null,
    /**
     * Лист, у которого открыт список заправок. Храним id, а не лист: после сохранения заправки
     * список показывает свежие данные. Пока открыта форма заправки, список скрыт.
     */
    val refuelingsSheetId: String? = null,
    /** Заправка, для которой открыт диалог «Удалить заправку?» (из списка). */
    val refuelingToDelete: Refueling? = null,
    /** Ошибка удаления заправки — показывается в шторке списка. */
    val refuelingsError: UiText? = null,
    /** Открыта шторка заправки (новой или существующей). */
    val refuelingTarget: RefuelingTarget? = null,
    val snackbar: UiText? = null,
    val snackbarAction: FeedSnackbarAction? = null,
    /** Авто не выбрано или его больше нет — перейти в список авто. */
    val noCar: Boolean = false,
) {
    val hasContent: Boolean get() = car != null && !isLoading

    /** Лист для шторки «Заправки»; null — шторка не видна. */
    val refuelingsSheet: FuelSheet?
        get() = if (refuelingTarget != null) null else sheets.firstOrNull { it.id == refuelingsSheetId }
}

sealed interface SheetsFeedEvent {
    data class ToggleExpanded(val sheetId: String) : SheetsFeedEvent
    data class ToggleSeason(val sheet: FuelSheet) : SheetsFeedEvent
    data class Reopen(val sheet: FuelSheet) : SheetsFeedEvent
    data class RequestDelete(val sheet: FuelSheet) : SheetsFeedEvent
    data object ConfirmDelete : SheetsFeedEvent
    data object DismissDelete : SheetsFeedEvent

    data object NewSheet : SheetsFeedEvent
    data class NewSheetMonthShift(val delta: Long) : SheetsFeedEvent
    data class NewSheetSeason(val season: Season) : SheetsFeedEvent
    data class NewSheetOdometer(val value: String) : SheetsFeedEvent
    data class NewSheetFuel(val value: String) : SheetsFeedEvent
    data object ConfirmNewSheet : SheetsFeedEvent
    data object DismissNewSheet : SheetsFeedEvent

    data class Close(val sheet: FuelSheet) : SheetsFeedEvent
    data class CloseOdometer(val value: String) : SheetsFeedEvent
    data class CloseFuel(val value: String) : SheetsFeedEvent
    data object ConfirmClose : SheetsFeedEvent
    data object DismissClose : SheetsFeedEvent

    /** [≡] в карточке — список заправок листа. */
    data class OpenRefuelings(val sheetId: String) : SheetsFeedEvent
    data object DismissRefuelings : SheetsFeedEvent

    /** 🗑 в списке заправок → диалог «Удалить заправку?». */
    data class RequestDeleteRefueling(val refueling: Refueling) : SheetsFeedEvent
    data object ConfirmDeleteRefueling : SheetsFeedEvent
    data object DismissDeleteRefueling : SheetsFeedEvent

    /** «+ Заправка» ([refueling] null) или нажатие на заправку открытого листа. */
    data class OpenRefueling(val sheet: FuelSheet, val refueling: Refueling? = null) : SheetsFeedEvent
    data object DismissRefueling : SheetsFeedEvent

    /** Заправка сохранена или удалена — карточка обновится сама (лента подписана на базу). */
    data object RefuelingSaved : SheetsFeedEvent

    data object SnackbarShown : SheetsFeedEvent
}

@HiltViewModel
class SheetsFeedViewModel @Inject constructor(
    private val carRepository: CarRepository,
    private val sheetRepository: SheetRepository,
    private val refuelingRepository: RefuelingRepository,
    private val selectedCarStorage: SelectedCarStorage,
) : ViewModel() {

    private val _state = MutableStateFlow(SheetsFeedUiState())
    val state: StateFlow<SheetsFeedUiState> = _state.asStateFlow()

    private var carId: String? = null

    init {
        viewModelScope.launch { observeSelectedCar() }
    }

    /**
     * Лента = выбранное авто + его листы из базы. Любое изменение (здесь, в CarEditScreen,
     * при синхронизации) приходит само — перезагружать нечего.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeSelectedCar() {
        selectedCarStorage.selectedCarId.collectLatest { id ->
            if (id == null) {
                _state.update { it.copy(noCar = true) }
                return@collectLatest
            }
            if (id != carId) {
                carId = id
                _state.value = SheetsFeedUiState()
            }
            combine(carRepository.observeCar(id), sheetRepository.observeSheets(id)) { car, sheets -> car to sheets }
                .collect { (car, sheets) ->
                    if (car == null || car.isArchived) {
                        // Авто удалили или отправили в архив — сбрасываем выбор, откроется список авто
                        selectedCarStorage.clear()
                    } else {
                        _state.update { it.copy(car = car, sheets = sheets, isLoading = false) }
                    }
                }
        }
    }

    fun onEvent(event: SheetsFeedEvent) {
        when (event) {
            is SheetsFeedEvent.ToggleExpanded -> _state.update {
                val ids = it.expandedSheetIds
                it.copy(expandedSheetIds = if (event.sheetId in ids) ids - event.sheetId else ids + event.sheetId)
            }
            is SheetsFeedEvent.ToggleSeason -> toggleSeason(event.sheet)
            is SheetsFeedEvent.Reopen -> reopen(event.sheet)
            is SheetsFeedEvent.RequestDelete -> _state.update { it.copy(deleteCandidate = event.sheet) }
            SheetsFeedEvent.ConfirmDelete -> delete()
            SheetsFeedEvent.DismissDelete -> _state.update { it.copy(deleteCandidate = null) }

            SheetsFeedEvent.NewSheet -> prepareNewSheet()
            is SheetsFeedEvent.NewSheetMonthShift -> updateNewSheet { shiftMonth(event.delta) }
            is SheetsFeedEvent.NewSheetSeason -> updateNewSheet { copy(season = event.season, error = null) }
            is SheetsFeedEvent.NewSheetOdometer ->
                updateNewSheet { copy(odometerStart = filterDigitsInput(event.value), odometerError = null) }
            is SheetsFeedEvent.NewSheetFuel ->
                updateNewSheet { copy(fuelStart = filterDecimalInput(event.value), fuelError = null) }
            SheetsFeedEvent.ConfirmNewSheet -> createSheet()
            SheetsFeedEvent.DismissNewSheet -> _state.update {
                if (it.newSheet?.isSaving == true) it else it.copy(newSheet = null)
            }

            is SheetsFeedEvent.Close -> _state.update { it.copy(closeSheet = event.sheet.toCloseForm()) }
            is SheetsFeedEvent.CloseOdometer ->
                updateCloseSheet { copy(odometerEnd = filterDigitsInput(event.value), odometerError = null) }
            is SheetsFeedEvent.CloseFuel ->
                updateCloseSheet { copy(fuelEndActual = filterDecimalInput(event.value), fuelError = null) }
            SheetsFeedEvent.ConfirmClose -> closeSheet()
            SheetsFeedEvent.DismissClose -> _state.update {
                if (it.closeSheet?.isSaving == true) it else it.copy(closeSheet = null)
            }

            is SheetsFeedEvent.OpenRefuelings ->
                _state.update { it.copy(refuelingsSheetId = event.sheetId, refuelingsError = null) }
            SheetsFeedEvent.DismissRefuelings ->
                _state.update { it.copy(refuelingsSheetId = null, refuelingToDelete = null, refuelingsError = null) }
            is SheetsFeedEvent.RequestDeleteRefueling -> _state.update { it.copy(refuelingToDelete = event.refueling) }
            SheetsFeedEvent.ConfirmDeleteRefueling -> deleteRefueling()
            SheetsFeedEvent.DismissDeleteRefueling -> _state.update { it.copy(refuelingToDelete = null) }
            is SheetsFeedEvent.OpenRefueling -> {
                // Заправки закрытого листа не меняются — сначала переоткрыть
                if (event.sheet.isClosed) {
                    showSnackbar(UiText.Resource(R.string.sheet_closed_cannot_edit))
                } else {
                    _state.update { it.copy(refuelingTarget = event.sheet.refuelingTarget(event.refueling)) }
                }
            }
            // Форма закрылась — если она открывалась из списка заправок, список появится снова
            SheetsFeedEvent.DismissRefueling, SheetsFeedEvent.RefuelingSaved ->
                _state.update { it.copy(refuelingTarget = null) }

            SheetsFeedEvent.SnackbarShown -> _state.update { it.copy(snackbar = null, snackbarAction = null) }
        }
    }

    // --- Действия с листом в карточке ---

    private fun toggleSeason(sheet: FuelSheet) {
        if (sheet.isClosed) {
            showSnackbar(UiText.Resource(R.string.sheet_closed_cannot_edit))
            return
        }
        val newSeason = if (sheet.season == Season.SUMMER) Season.WINTER else Season.SUMMER
        runSheetAction(sheet.id, { sheetRepository.setSeason(sheet.id, newSeason) }) { norm: BigDecimal ->
            val res = if (newSeason == Season.WINTER) {
                R.string.sheet_season_switched_winter
            } else {
                R.string.sheet_season_switched_summer
            }
            showSnackbar(UiText.Resource(res, listOf(Formatters.consumption(norm))))
        }
    }

    private fun reopen(sheet: FuelSheet) {
        runSheetAction(sheet.id, { sheetRepository.reopenSheet(sheet.id) }) {
            showSnackbar(UiText.Resource(R.string.sheet_reopened))
        }
    }

    private fun delete() {
        val sheet = _state.value.deleteCandidate ?: return
        _state.update { it.copy(deleteCandidate = null) }
        runSheetAction(sheet.id, { sheetRepository.deleteSheet(sheet.id) }) {
            showSnackbar(UiText.Resource(R.string.sheet_deleted))
        }
    }

    /** Удаление заправки из списка; ошибка — в шторке (Snackbar под ней не виден). */
    private fun deleteRefueling() {
        val refueling = _state.value.refuelingToDelete ?: return
        val sheetId = _state.value.refuelingsSheetId ?: return
        _state.update { it.copy(refuelingToDelete = null, refuelingsError = null) }
        runSheetAction(
            sheetId = sheetId,
            action = { refuelingRepository.delete(refueling.id) },
            onRejected = { violation -> _state.update { it.copy(refuelingsError = violation.toUiText()) } },
            onOk = {},
        )
    }

    /** Действие с одним листом: пока идёт, его кнопки неактивны; нарушение правила — по умолчанию в Snackbar. */
    private fun <T> runSheetAction(
        sheetId: String,
        action: suspend () -> LocalResult<T>,
        onRejected: (SheetRuleViolation) -> Unit = ::showViolation,
        onOk: (T) -> Unit,
    ) {
        if (sheetId in _state.value.busySheetIds) return
        _state.update { it.copy(busySheetIds = it.busySheetIds + sheetId) }
        viewModelScope.launch {
            val result = action()
            _state.update { it.copy(busySheetIds = it.busySheetIds - sheetId) }
            when (result) {
                is LocalResult.Ok -> onOk(result.value)
                is LocalResult.Rejected -> onRejected(result.violation)
            }
        }
    }

    // --- NewSheetDialog ---

    private fun prepareNewSheet() {
        val id = carId ?: return
        if (_state.value.isPreparingNewSheet) return
        _state.update { it.copy(isPreparingNewSheet = true) }
        viewModelScope.launch {
            val prefill = sheetRepository.getNextPrefill(id)
            _state.update { it.copy(isPreparingNewSheet = false, newSheet = prefill.toForm()) }
        }
    }

    private fun createSheet() {
        val id = carId ?: return
        val form = _state.value.newSheet ?: return
        if (form.isSaving) return
        val (input, checked) = validateNewSheet(form)
        if (input == null) {
            updateNewSheet { checked }
            return
        }
        updateNewSheet { checked.copy(isSaving = true) }
        viewModelScope.launch {
            when (val result = sheetRepository.createSheet(id, input)) {
                is LocalResult.Ok -> _state.update { it.copy(newSheet = null) }
                is LocalResult.Rejected -> updateNewSheet { copy(isSaving = false, error = result.violation.toUiText()) }
            }
        }
    }

    private fun updateNewSheet(change: NewSheetForm.() -> NewSheetForm) {
        _state.update { s -> s.copy(newSheet = s.newSheet?.change()) }
    }

    // --- CloseSheetDialog ---

    private fun closeSheet() {
        val form = _state.value.closeSheet ?: return
        if (form.isSaving) return
        val (input, checked) = validateCloseSheet(form)
        if (input == null) {
            updateCloseSheet { checked }
            return
        }
        updateCloseSheet { checked.copy(isSaving = true) }
        viewModelScope.launch {
            when (val result = sheetRepository.closeSheet(form.sheetId, input.odometerEndKm, input.fuelEndActualL)) {
                is LocalResult.Ok -> _state.update { it.copy(closeSheet = null) }
                is LocalResult.Rejected -> updateCloseSheet { copy(isSaving = false, error = result.violation.toUiText()) }
            }
        }
    }

    private fun updateCloseSheet(change: CloseSheetForm.() -> CloseSheetForm) {
        _state.update { s -> s.copy(closeSheet = s.closeSheet?.change()) }
    }

    // --- Общее ---

    private fun showSnackbar(text: UiText, action: FeedSnackbarAction? = null) {
        _state.update { it.copy(snackbar = text, snackbarAction = action) }
    }

    /** «Зимняя норма не указана» — с кнопкой «Указать», остальное — просто текст. */
    private fun showViolation(violation: SheetRuleViolation) {
        if (violation == SheetRuleViolation.WINTER_NORM_NOT_SET) {
            showSnackbar(violation.toUiText(), FeedSnackbarAction.SET_WINTER_NORM)
        } else {
            showSnackbar(violation.toUiText())
        }
    }
}
