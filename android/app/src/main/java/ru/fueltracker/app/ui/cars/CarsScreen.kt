package ru.fueltracker.app.ui.cars

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.domain.model.FuelType
import ru.fueltracker.app.ui.common.EmptyView
import ru.fueltracker.app.ui.common.ErrorView
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.common.LoadingView
import ru.fueltracker.app.ui.common.SnackbarEffect
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.asString
import ru.fueltracker.app.ui.theme.FuelTrackerTheme
import java.math.BigDecimal

@Composable
fun CarsScreen(
    onOpenFeed: () -> Unit,
    onAddCar: () -> Unit,
    onEditCar: (carId: String) -> Unit,
    onBack: (() -> Unit)?,
    /** Только когда список авто — первый экран (из ленты профиль открывается в её шапке). */
    onOpenProfile: (() -> Unit)? = null,
    viewModel: CarsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onEvent(CarsEvent.Refresh) }
    LaunchedEffect(state.openFeed) {
        if (state.openFeed) {
            viewModel.onEvent(CarsEvent.FeedOpened)
            onOpenFeed()
        }
    }
    CarsContent(
        state = state,
        onEvent = viewModel::onEvent,
        onAddCar = onAddCar,
        onEditCar = onEditCar,
        onBack = onBack,
        onOpenProfile = onOpenProfile,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarsContent(
    state: CarsUiState,
    onEvent: (CarsEvent) -> Unit,
    onAddCar: () -> Unit,
    onEditCar: (carId: String) -> Unit,
    onBack: (() -> Unit)?,
    /** Только когда список авто — первый экран (из ленты профиль открывается в её шапке). */
    onOpenProfile: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarEffect(state.snackbar, snackbarHostState) { onEvent(CarsEvent.SnackbarShown) }
    val hasContent = state.cars.isNotEmpty()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cars_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                actions = {
                    // Без авто нет ленты, а значит и её шапки — иначе из приложения было бы не выйти
                    if (onOpenProfile != null) {
                        IconButton(onClick = onOpenProfile) {
                            Icon(painterResource(R.drawable.ic_person), contentDescription = stringResource(R.string.profile_open))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (hasContent) {
                ExtendedFloatingActionButton(
                    onClick = onAddCar,
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.cars_add)) },
                    modifier = Modifier.testTag(CarsTestTags.ADD),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                hasContent -> CarList(
                    cars = state.cars,
                    selectedCarId = state.selectedCarId,
                    onSelect = { onEvent(CarsEvent.Select(it)) },
                    onEdit = { onEditCar(it.id) },
                    onArchive = { onEvent(CarsEvent.RequestArchive(it)) },
                )
                state.isLoading -> LoadingView()
                state.loadError != null -> ErrorView(
                    message = state.loadError.asString(),
                    onRetry = { onEvent(CarsEvent.Refresh) },
                )
                else -> EmptyView(
                    message = stringResource(R.string.cars_empty),
                    actionLabel = stringResource(R.string.cars_add),
                    onAction = onAddCar,
                )
            }
        }
    }

    state.archiveCandidate?.let { car ->
        AlertDialog(
            onDismissRequest = { onEvent(CarsEvent.DismissArchive) },
            title = { Text(stringResource(R.string.cars_archive_title)) },
            text = { Text(stringResource(R.string.cars_archive_text, car.name)) },
            confirmButton = {
                TextButton(onClick = { onEvent(CarsEvent.ConfirmArchive) }) {
                    Text(stringResource(R.string.cars_archive))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(CarsEvent.DismissArchive) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun CarList(
    cars: List<Car>,
    selectedCarId: String?,
    onSelect: (Car) -> Unit,
    onEdit: (Car) -> Unit,
    onArchive: (Car) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Снизу место под FAB, чтобы он не закрывал последнюю карточку
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cars, key = { it.id }) { car ->
            CarCard(
                car = car,
                isSelected = car.id == selectedCarId,
                onClick = { onSelect(car) },
                onEdit = { onEdit(car) },
                onArchive = { onArchive(car) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CarCard(
    car: Car,
    isSelected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CarsTestTags.card(car.id)),
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = car.name, style = MaterialTheme.typography.titleMedium)
                val fuel = stringResource(car.fuelType.labelRes)
                Text(
                    text = listOfNotNull(car.plateNumber, fuel).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.cars_norm_line, normsText(car)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (isSelected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check_circle),
                    contentDescription = stringResource(R.string.cars_selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.cars_edit)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.cars_archive)) },
                        onClick = {
                            menuOpen = false
                            onArchive()
                        },
                    )
                }
            }
        }
    }
}

/** «☀️ 10,068 · ❄️ 11,684» или только летняя. */
@Composable
private fun normsText(car: Car): String {
    val summer = stringResource(R.string.cars_norm_summer, Formatters.consumption(car.normSummer.toPlainString()))
    val winter = car.normWinter?.let {
        stringResource(R.string.cars_norm_winter, Formatters.consumption(it.toPlainString()))
    }
    return listOfNotNull(summer, winter).joinToString(" · ")
}

object CarsTestTags {
    const val ADD = "cars_add"
    fun card(carId: String) = "car_card_$carId"
}

private val previewCars = listOf(
    Car("1", "Lada Vesta", "А123ВС77", FuelType.AI95, BigDecimal("50.00"), BigDecimal("10.068"), BigDecimal("11.684"), false),
    Car("2", "Kia Rio", null, FuelType.AI92, BigDecimal("43.00"), BigDecimal("8.500"), null, false),
)

@PreviewLightDark
@Composable
private fun CarsContentPreview() {
    FuelTrackerTheme {
        CarsContent(
            state = CarsUiState(isLoading = false, cars = previewCars, selectedCarId = "1"),
            onEvent = {},
            onAddCar = {},
            onEditCar = {},
            onBack = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun CarsContentEmptyPreview() {
    FuelTrackerTheme {
        CarsContent(state = CarsUiState(isLoading = false), onEvent = {}, onAddCar = {}, onEditCar = {}, onBack = null)
    }
}

@PreviewLightDark
@Composable
private fun CarsContentErrorPreview() {
    FuelTrackerTheme {
        CarsContent(
            state = CarsUiState(isLoading = false, loadError = UiText.Resource(R.string.error_no_connection)),
            onEvent = {},
            onAddCar = {},
            onEditCar = {},
            onBack = null,
        )
    }
}
