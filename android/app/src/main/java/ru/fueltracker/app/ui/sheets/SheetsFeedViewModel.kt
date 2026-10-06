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
import ru.fueltracker.app.data.local.SelectedCarStorage
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.repository.CarRepository
import ru.fueltracker.app.data.repository.SheetRepository
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.SheetPage
import ru.fueltracker.app.ui.common.toUiText
import ru.fueltracker.app.ui.common.UiText
import javax.inject.Inject

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
    /** Листы, у которых раскрыт список заправок. */
    val expandedSheetIds: Set<String> = emptySet(),
    val snackbar: UiText? = null,
    /** Авто не выбрано или его больше нет — перейти в список авто. */
    val noCar: Boolean = false,
) {
    val hasContent: Boolean get() = car != null && loadError == null && !isLoading
    val canLoadMore: Boolean get() = nextBefore != null && !isLoadingMore && !loadMoreFailed
}

sealed interface SheetsFeedEvent {
    data object Refresh : SheetsFeedEvent
    data object Retry : SheetsFeedEvent
    data object LoadMore : SheetsFeedEvent
    data class ToggleRefuelings(val sheetId: String) : SheetsFeedEvent
    data object SnackbarShown : SheetsFeedEvent
}

@HiltViewModel
class SheetsFeedViewModel @Inject constructor(
    private val carRepository: CarRepository,
    private val sheetRepository: SheetRepository,
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
            is SheetsFeedEvent.ToggleRefuelings -> _state.update {
                val ids = it.expandedSheetIds
                it.copy(expandedSheetIds = if (event.sheetId in ids) ids - event.sheetId else ids + event.sheetId)
            }
            SheetsFeedEvent.SnackbarShown -> _state.update { it.copy(snackbar = null) }
        }
    }

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
}

private fun SheetsFeedUiState.appendPage(page: SheetPage): SheetsFeedUiState {
    val known = sheets.mapTo(HashSet()) { it.id }
    return copy(
        sheets = sheets + page.items.filterNot { it.id in known },
        nextBefore = page.nextBefore,
        isLoadingMore = false,
    )
}

private fun ApiError.isNotFound(): Boolean = this is ApiError.Http && status == 404
