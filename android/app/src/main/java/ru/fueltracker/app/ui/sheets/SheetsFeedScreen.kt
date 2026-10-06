package ru.fueltracker.app.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.FuelType
import ru.fueltracker.app.ui.common.EmptyView
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.common.LoadingView
import ru.fueltracker.app.ui.common.SnackbarEffect
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.refueling.RefuelingEditSheet
import ru.fueltracker.app.ui.theme.FuelTrackerTheme
import java.math.BigDecimal

@Composable
fun SheetsFeedScreen(
    onChangeCar: () -> Unit,
    onOpenProfile: () -> Unit,
    onEditCar: (carId: String) -> Unit,
    onNoCar: () -> Unit,
    viewModel: SheetsFeedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.noCar) {
        if (state.noCar) onNoCar()
    }
    SheetsFeedContent(
        state = state,
        onEvent = viewModel::onEvent,
        onChangeCar = onChangeCar,
        onOpenProfile = onOpenProfile,
        onEditCar = { state.car?.let { onEditCar(it.id) } },
    )
    // У шторки свой ViewModel, поэтому она здесь, а не в stateless SheetsFeedContent
    state.refuelingTarget?.let { target ->
        RefuelingEditSheet(
            target = target,
            onSaved = { viewModel.onEvent(SheetsFeedEvent.RefuelingSaved) },
            onDismiss = { viewModel.onEvent(SheetsFeedEvent.DismissRefueling) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetsFeedContent(
    state: SheetsFeedUiState,
    onEvent: (SheetsFeedEvent) -> Unit,
    onChangeCar: () -> Unit,
    onOpenProfile: () -> Unit,
    onEditCar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarEffect(
        message = state.snackbar,
        hostState = snackbarHostState,
        actionLabel = state.snackbarAction?.let { UiText.Resource(R.string.winter_norm_set_action) },
        onAction = onEditCar,
        onShown = { onEvent(SheetsFeedEvent.SnackbarShown) },
    )
    state.newSheet?.let { NewSheetDialog(form = it, hasWinterNorm = state.car?.normWinter != null, onEvent = onEvent) }
    state.closeSheet?.let { CloseSheetDialog(form = it, onEvent = onEvent) }
    state.deleteCandidate?.let { DeleteSheetDialog(it, onEvent) }
    state.refuelingsSheet?.let { sheet ->
        RefuelingsListSheet(
            sheet = sheet,
            isBusy = sheet.id in state.busySheetIds,
            error = state.refuelingsError,
            actions = RefuelingsListActions(
                onAdd = { onEvent(SheetsFeedEvent.OpenRefueling(sheet)) },
                onRefuelingClick = { onEvent(SheetsFeedEvent.OpenRefueling(sheet, it)) },
                onDeleteClick = { onEvent(SheetsFeedEvent.RequestDeleteRefueling(it)) },
            ),
            onDismiss = { onEvent(SheetsFeedEvent.DismissRefuelings) },
        )
    }
    state.refuelingToDelete?.let {
        DeleteRefuelingDialog(
            refueling = it,
            onConfirm = { onEvent(SheetsFeedEvent.ConfirmDeleteRefueling) },
            onDismiss = { onEvent(SheetsFeedEvent.DismissDeleteRefueling) },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { CarTitle(state.car) },
                actions = {
                    IconButton(onClick = onChangeCar, modifier = Modifier.testTag(SheetsFeedTestTags.CHANGE_CAR)) {
                        Icon(
                            painterResource(R.drawable.ic_directions_car),
                            contentDescription = stringResource(R.string.feed_change_car),
                        )
                    }
                    IconButton(onClick = onOpenProfile, modifier = Modifier.testTag(SheetsFeedTestTags.PROFILE)) {
                        Icon(painterResource(R.drawable.ic_person), contentDescription = stringResource(R.string.profile_open))
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.hasContent) {
                ExtendedFloatingActionButton(
                    onClick = { if (!state.isPreparingNewSheet) onEvent(SheetsFeedEvent.NewSheet) },
                    icon = {
                        if (state.isPreparingNewSheet) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                        }
                    },
                    text = { Text(stringResource(R.string.feed_new_sheet)) },
                    modifier = Modifier.testTag(SheetsFeedTestTags.NEW_SHEET),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            // Данные в базе на телефоне: ни pull-to-refresh, ни ошибок загрузки здесь нет
            if (state.isLoading) LoadingView() else SheetList(state = state, onEvent = onEvent)
        }
    }
}

@Composable
private fun CarTitle(car: Car?) {
    if (car == null) return
    Column {
        Text(text = car.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        car.plateNumber?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SheetList(
    state: SheetsFeedUiState,
    onEvent: (SheetsFeedEvent) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(SheetsFeedTestTags.LIST),
        // Снизу место под FAB, чтобы он не закрывал кнопки последней карточки
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.sheets.isEmpty()) {
            item {
                Box(modifier = Modifier.fillParentMaxSize()) {
                    EmptyView(message = stringResource(R.string.feed_empty))
                }
            }
        }
        items(state.sheets, key = { it.id }) { sheet ->
            SheetCard(
                sheet = sheet,
                isExpanded = sheet.id in state.expandedSheetIds,
                isBusy = sheet.id in state.busySheetIds,
                actions = SheetCardActions(
                    onToggleExpanded = { onEvent(SheetsFeedEvent.ToggleExpanded(sheet.id)) },
                    onSeasonClick = { onEvent(SheetsFeedEvent.ToggleSeason(sheet)) },
                    onClose = { onEvent(SheetsFeedEvent.Close(sheet)) },
                    onReopen = { onEvent(SheetsFeedEvent.Reopen(sheet)) },
                    onDelete = { onEvent(SheetsFeedEvent.RequestDelete(sheet)) },
                    onOpenRefuelings = { onEvent(SheetsFeedEvent.OpenRefuelings(sheet.id)) },
                ),
            )
        }
    }
}

@Composable
private fun DeleteSheetDialog(sheet: FuelSheet, onEvent: (SheetsFeedEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(SheetsFeedEvent.DismissDelete) },
        title = { Text(stringResource(R.string.sheet_delete_title)) },
        text = { Text(stringResource(R.string.sheet_delete_text, Formatters.month(sheet.year, sheet.month))) },
        confirmButton = {
            TextButton(onClick = { onEvent(SheetsFeedEvent.ConfirmDelete) }) {
                Text(stringResource(R.string.sheet_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(SheetsFeedEvent.DismissDelete) }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

object SheetsFeedTestTags {
    const val LIST = "feed_list"
    const val CHANGE_CAR = "feed_change_car"
    const val NEW_SHEET = "feed_new_sheet"
    const val PROFILE = "feed_profile"
    const val REFUELINGS_LIST = "refuelings_list"
    const val REFUELINGS_ERROR = "refuelings_error"
    const val DELETE_REFUELING_CONFIRM = "delete_refueling_confirm"
    fun card(sheetId: String) = "sheet_card_$sheetId"
    fun details(sheetId: String) = "sheet_details_$sheetId"
    fun consumption(sheetId: String) = "sheet_consumption_$sheetId"
    fun season(sheetId: String) = "sheet_season_$sheetId"
    fun menu(sheetId: String) = "sheet_menu_$sheetId"
    fun refuelingsButton(sheetId: String) = "sheet_refuelings_$sheetId"
    fun closeButton(sheetId: String) = "sheet_close_$sheetId"
    fun addRefueling(sheetId: String) = "sheet_add_refueling_$sheetId"
    fun refueling(refuelingId: String) = "refueling_row_$refuelingId"
    fun deleteRefueling(refuelingId: String) = "refueling_delete_$refuelingId"
}

private val previewCar =
    Car("car-1", "Lada Vesta", "А123ВС77", FuelType.AI95, BigDecimal("50.00"), BigDecimal("10.068"), BigDecimal("11.684"), false)

@PreviewLightDark
@Composable
private fun SheetsFeedContentPreview() {
    FuelTrackerTheme {
        SheetsFeedContent(
            state = SheetsFeedUiState(
                car = previewCar,
                sheets = listOf(previewOpenSheet(), previewClosedSheet()),
                isLoading = false,
                expandedSheetIds = setOf("s-07"),
            ),
            onEvent = {},
            onChangeCar = {},
            onOpenProfile = {},
            onEditCar = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun SheetsFeedContentEmptyPreview() {
    FuelTrackerTheme {
        SheetsFeedContent(
            state = SheetsFeedUiState(car = previewCar, isLoading = false),
            onEvent = {},
            onChangeCar = {},
            onOpenProfile = {},
            onEditCar = {},
        )
    }
}
