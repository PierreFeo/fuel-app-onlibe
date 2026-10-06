package ru.fueltracker.app.ui.sheets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fueltracker.app.R
import ru.fueltracker.app.data.local.SelectedCarStorage
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.data.repository.CarRepository
import ru.fueltracker.app.data.repository.RefuelingRepository
import ru.fueltracker.app.data.repository.SheetRepository
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.Refueling
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetPage
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.filterDecimalInput
import ru.fueltracker.app.ui.common.filterDigitsInput
import ru.fueltracker.app.ui.common.toUiText
import ru.fueltracker.app.ui.refueling.RefuelingTarget
import ru.fueltracker.app.ui.refueling.refuelingTarget
import javax.inject.Inject

/** Кнопка в Snackbar ленты. */
enum class FeedSnackbarAction {
    /** «Указать» зимнюю норму — открыть CarEditScreen авто. */
    SET_WINTER_NORM,
}

data class SheetsFeedUiState(
    val car: Car? = null,
    val sheets: List<FuelSheet> = emptyList(),
    /** Первая загрузка: ещё ничего не показано. */
    val isLoading: Boolean = true,
    /** Ошибка первой загрузки — экран ошибки с «Повторить». */
    val loadError: UiText? = null,
    /** Pull-to-refresh. */
    val isRefreshing: Boolean = false,
    /** null — старых листов больше нет. */
    val nextBefore: String? = null,
    val isLoadingMore: Boolean = false,
    /** Подгрузка старых листов не удалась — внизу «Повторить». */
    val loadMoreFailed: Boolean = false,
    /** Листы, у которых карточка раскрыта (подробности). */
    val expandedSheetIds: Set<String> = emptySet(),
    /** Листы, по которым идёт запрос (сезон, переоткрытие, удаление) — их кнопки неактивны. */
    val busySheetIds: Set<String> = emptySet(),
    /** Загружается подсказка `next-prefill` для нового листа. */
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
    val hasContent: Boolean get() = car != null && loadError == null && !isLoading
    val canLoadMore: Boolean get() = nextBefore != null && !isLoadingMore && !loadMoreFailed

    /** Лист для шторки «Заправки»; null — шторка не видна. */
    val refuelingsSheet: FuelSheet?
        get() = if (refuelingTarget != null) null else sheets.firstOrNull { it.id == refuelingsSheetId }
}

sealed interface SheetsFeedEvent {
    data object Refresh : SheetsFeedEvent
    data object Retry : SheetsFeedEvent
    data object LoadMore : SheetsFeedEvent

    /** Экран снова виден (например, после CarEditScreen) — обновить шапку авто. */
    data object Resume : SheetsFeedEvent
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

    /** Заправка сохранена или удалена — сервер вернул лист с пересчитанным `calc`. */
    data class RefuelingSaved(val sheet: FuelSheet) : SheetsFeedEvent

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

    /** Загрузка первой или следующей страницы; новая отменяет предыдущую. */
    private var pageJob: Job? = null

    init {
        viewModelScope.launch {
            // Авто могут сменить или отправить в архив на экране списка авто, пока лента в истории
            selectedCarStorage.selectedCarId.collect { id ->
                when {
                    id == null -> _state.update { it.copy(noCar = true) }
                    id != carId -> {
                        carId = id
                        _state.value = SheetsFeedUiState()
                        loadFirstPage(isRefresh = false)
                    }
                }
            }
        }
    }

    fun onEvent(event: SheetsFeedEvent) {
        when (event) {
            SheetsFeedEvent.Refresh -> {
                _state.update { it.copy(isRefreshing = true) }
                loadFirstPage(isRefresh = true)
            }
            SheetsFeedEvent.Retry -> {
                _state.update { it.copy(isLoading = true, loadError = null) }
                loadFirstPage(isRefresh = false)
            }
            SheetsFeedEvent.LoadMore -> loadMore()
            SheetsFeedEvent.Resume -> refreshCar()
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
                // Заправки закрытого листа не меняются (409 SHEET_CLOSED) — сначала переоткрыть
                if (event.sheet.isClosed) {
                    showSnackbar(UiText.Resource(R.string.sheet_closed_cannot_edit))
                } else {
                    _state.update { it.copy(refuelingTarget = event.sheet.refuelingTarget(event.refueling)) }
                }
            }
            // Форма закрылась — если она открывалась из списка заправок, список появится снова
            SheetsFeedEvent.DismissRefueling -> _state.update { it.copy(refuelingTarget = null) }
            is SheetsFeedEvent.RefuelingSaved -> {
                replaceSheet(event.sheet)
                _state.update { it.copy(refuelingTarget = null) }
            }

            SheetsFeedEvent.SnackbarShown -> _state.update { it.copy(snackbar = null, snackbarAction = null) }
        }
    }

    // --- Загрузка ленты ---

    private fun loadFirstPage(isRefresh: Boolean) {
        val id = carId ?: return
        pageJob?.cancel()
        // Отменённая подгрузка старых листов не должна оставить индикатор внизу
        _state.update { it.copy(isLoadingMore = false) }
        pageJob = viewModelScope.launch {
            // Шапка и лента — параллельно
            val (carResult, pageResult) = coroutineScope {
                val car = async { carRepository.getCar(id) }
                val page = async { sheetRepository.getSheets(id) }
                car.await() to page.await()
            }
            val error = (carResult as? ApiResult.Failure)?.error ?: (pageResult as? ApiResult.Failure)?.error
            when {
                error != null && error.isNotFound() -> {
                    // Авто в архиве или чужое: сбрасываем выбор, collect выше откроет список авто
                    selectedCarStorage.clear()
                }
                error != null -> _state.update {
                    if (isRefresh && it.hasContent) {
                        it.copy(isRefreshing = false, snackbar = error.toUiText())
                    } else {
                        it.copy(isLoading = false, isRefreshing = false, loadError = error.toUiText())
                    }
                }
                else -> {
                    val car = (carResult as ApiResult.Success).data
                    val page = (pageResult as ApiResult.Success).data
                    _state.update {
                        it.copy(
                            car = car,
                            sheets = page.items,
                            nextBefore = page.nextBefore,
                            isLoading = false,
                            isRefreshing = false,
                            loadError = null,
                            isLoadingMore = false,
                            loadMoreFailed = false,
                        )
                    }
                }
            }
        }
    }

    private fun loadMore() {
        val id = carId ?: return
        val current = _state.value
        val before = current.nextBefore
        if (before == null || current.isLoadingMore || current.isRefreshing || !current.hasContent) return
        _state.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
        pageJob = viewModelScope.launch {
            when (val result = sheetRepository.getSheets(id, before)) {
                is ApiResult.Success -> _state.update { it.appendPage(result.data) }
                is ApiResult.Failure -> _state.update { it.copy(isLoadingMore = false, loadMoreFailed = true) }
            }
        }
    }

    /** Тихо обновить шапку: после CarEditScreen могла появиться зимняя норма. */
    private fun refreshCar() {
        val id = carId ?: return
        if (!_state.value.hasContent) return
        viewModelScope.launch {
            val result = carRepository.getCar(id)
            if (result is ApiResult.Success) _state.update { it.copy(car = result.data) }
        }
    }

    // --- Действия с листом в карточке ---

    private fun toggleSeason(sheet: FuelSheet) {
        if (sheet.isClosed) {
            showSnackbar(UiText.Resource(R.string.sheet_closed_cannot_edit))
            return
        }
        val newSeason = if (sheet.season == Season.SUMMER) Season.WINTER else Season.SUMMER
        runSheetAction(sheet.id, { sheetRepository.setSeason(sheet.id, newSeason) }) { updated ->
            replaceSheet(updated)
            val res = if (updated.season == Season.WINTER) {
                R.string.sheet_season_switched_winter
            } else {
                R.string.sheet_season_switched_summer
            }
            showSnackbar(UiText.Resource(res, listOf(Formatters.consumption(updated.normLPer100km))))
        }
    }

    private fun reopen(sheet: FuelSheet) {
        runSheetAction(sheet.id, { sheetRepository.reopenSheet(sheet.id) }) { updated ->
            replaceSheet(updated)
            showSnackbar(UiText.Resource(R.string.sheet_reopened))
        }
    }

    private fun delete() {
        val sheet = _state.value.deleteCandidate ?: return
        _state.update { it.copy(deleteCandidate = null) }
        runSheetAction(sheet.id, { sheetRepository.deleteSheet(sheet.id) }) {
            _state.update { s -> s.copy(sheets = s.sheets.filterNot { it.id == sheet.id }) }
            showSnackbar(UiText.Resource(R.string.sheet_deleted))
        }
    }

    /** Удаление заправки из списка: ответ — весь лист; ошибка — в шторке (Snackbar под ней не виден). */
    private fun deleteRefueling() {
        val refueling = _state.value.refuelingToDelete ?: return
        val sheetId = _state.value.refuelingsSheetId ?: return
        _state.update { it.copy(refuelingToDelete = null, refuelingsError = null) }
        runSheetAction(
            sheetId = sheetId,
            request = { refuelingRepository.delete(refueling.id) },
            onFailure = { error -> _state.update { it.copy(refuelingsError = error.toUiText()) } },
            onSuccess = ::replaceSheet,
        )
    }

    /** Запрос по одному листу: пока идёт, его кнопки неактивны; ошибка — по умолчанию в Snackbar. */
    private fun <T> runSheetAction(
        sheetId: String,
        request: suspend () -> ApiResult<T>,
        onFailure: (ApiError) -> Unit = ::showError,
        onSuccess: (T) -> Unit,
    ) {
        if (sheetId in _state.value.busySheetIds) return
        _state.update { it.copy(busySheetIds = it.busySheetIds + sheetId) }
        viewModelScope.launch {
            val result = request()
            _state.update { it.copy(busySheetIds = it.busySheetIds - sheetId) }
            when (result) {
                is ApiResult.Success -> onSuccess(result.data)
                is ApiResult.Failure -> onFailure(result.error)
            }
        }
    }

    // --- NewSheetDialog ---

    private fun prepareNewSheet() {
        val id = carId ?: return
        if (_state.value.isPreparingNewSheet) return
        _state.update { it.copy(isPreparingNewSheet = true) }
        viewModelScope.launch {
            when (val result = sheetRepository.getNextPrefill(id)) {
                is ApiResult.Success -> _state.update {
                    it.copy(isPreparingNewSheet = false, newSheet = result.data.toForm())
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(isPreparingNewSheet = false) }
                    showError(result.error)
                }
            }
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
                is ApiResult.Success -> _state.update {
                    it.copy(newSheet = null, sheets = (it.sheets + result.data).sortedNewestFirst())
                }
                is ApiResult.Failure -> updateNewSheet { copy(isSaving = false, error = dialogErrorText(result.error)) }
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
                is ApiResult.Success -> {
                    _state.update { it.copy(closeSheet = null) }
                    replaceSheet(result.data)
                }
                is ApiResult.Failure -> updateCloseSheet { copy(isSaving = false, error = dialogErrorText(result.error)) }
            }
        }
    }

    private fun updateCloseSheet(change: CloseSheetForm.() -> CloseSheetForm) {
        _state.update { s -> s.copy(closeSheet = s.closeSheet?.change()) }
    }

    // --- Общее ---

    private fun replaceSheet(updated: FuelSheet) {
        _state.update { s -> s.copy(sheets = s.sheets.map { if (it.id == updated.id) updated else it }) }
    }

    private fun showSnackbar(text: UiText, action: FeedSnackbarAction? = null) {
        _state.update { it.copy(snackbar = text, snackbarAction = action) }
    }

    /** «Зимняя норма не указана» — с кнопкой «Указать», остальное — текст сервера или «Нет связи». */
    private fun showError(error: ApiError) {
        if (error.isWinterNormNotSet()) {
            showSnackbar(UiText.Resource(R.string.winter_norm_not_set), FeedSnackbarAction.SET_WINTER_NORM)
        } else {
            showSnackbar(error.toUiText())
        }
    }
}

private fun SheetsFeedUiState.appendPage(page: SheetPage): SheetsFeedUiState {
    val known = sheets.mapTo(HashSet()) { it.id }
    return copy(
        sheets = sheets + page.items.filterNot { it.id in known },
        nextBefore = page.nextBefore,
        isLoadingMore = false,
    )
}

private fun List<FuelSheet>.sortedNewestFirst() =
    sortedWith(compareByDescending<FuelSheet> { it.year }.thenByDescending { it.month })

/** Ошибка внутри диалога: Snackbar под диалогом не виден, поэтому текст — в самом диалоге. */
private fun dialogErrorText(error: ApiError): UiText =
    if (error.isWinterNormNotSet()) UiText.Resource(R.string.winter_norm_not_set) else error.toUiText()

private fun ApiError.isNotFound(): Boolean = this is ApiError.Http && status == 404

private fun ApiError.isWinterNormNotSet(): Boolean =
    this is ApiError.Http && reason == ErrorCodes.REASON_WINTER_NORM_NOT_SET
