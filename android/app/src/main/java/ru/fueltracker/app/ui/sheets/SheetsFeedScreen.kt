package ru.fueltracker.app.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import ru.fueltracker.app.domain.model.FuelType
import ru.fueltracker.app.ui.common.EmptyView
import ru.fueltracker.app.ui.common.ErrorView
import ru.fueltracker.app.ui.common.LoadingView
import ru.fueltracker.app.ui.common.SnackbarEffect
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.asString
import ru.fueltracker.app.ui.theme.FuelTrackerTheme
import java.math.BigDecimal

@Composable
fun SheetsFeedScreen(
    onChangeCar: () -> Unit,
    onNoCar: () -> Unit,
    viewModel: SheetsFeedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.noCar) {
        if (state.noCar) onNoCar()
    }
    SheetsFeedContent(state = state, onEvent = viewModel::onEvent, onChangeCar = onChangeCar)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetsFeedContent(
    state: SheetsFeedUiState,
    onEvent: (SheetsFeedEvent) -> Unit,
    onChangeCar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarEffect(state.snackbar, snackbarHostState) { onEvent(SheetsFeedEvent.SnackbarShown) }

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
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                state.isLoading -> LoadingView()
                state.loadError != null -> ErrorView(
                    message = state.loadError.asString(),
                    onRetry = { onEvent(SheetsFeedEvent.Retry) },
                )
                else -> PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { onEvent(SheetsFeedEvent.Refresh) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    SheetList(state = state, onEvent = onEvent)
                }
            }
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
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, canLoadMore = state.canLoadMore) { onEvent(SheetsFeedEvent.LoadMore) }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag(SheetsFeedTestTags.LIST),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.sheets.isEmpty()) {
            // Внутри списка, чтобы и пустую ленту можно было обновить жестом вниз
            item {
                Box(modifier = Modifier.fillParentMaxSize()) {
                    EmptyView(message = stringResource(R.string.feed_empty))
                }
            }
        }
        items(state.sheets, key = { it.id }) { sheet ->
            SheetCard(
                sheet = sheet,
                isRefuelingsExpanded = sheet.id in state.expandedSheetIds,
                onToggleRefuelings = { onEvent(SheetsFeedEvent.ToggleRefuelings(sheet.id)) },
            )
        }
        if (state.isLoadingMore || state.loadMoreFailed) {
            item(key = "footer") { LoadMoreFooter(state.loadMoreFailed) { onEvent(SheetsFeedEvent.LoadMore) } }
        }
    }
}

/** Когда до конца списка остаётся пара карточек — подгрузить старые листы. */
@Composable
private fun LoadMoreEffect(listState: LazyListState, canLoadMore: Boolean, onLoadMore: () -> Unit) {
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(nearEnd, canLoadMore) {
        if (nearEnd && canLoadMore) onLoadMore()
    }
}

private const val LOAD_MORE_THRESHOLD = 3

@Composable
private fun LoadMoreFooter(failed: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (failed) {
            Text(
                text = stringResource(R.string.feed_load_more_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        } else {
            CircularProgressIndicator()
        }
    }
}

object SheetsFeedTestTags {
    const val LIST = "feed_list"
    const val CHANGE_CAR = "feed_change_car"
    const val REFUELINGS_TOGGLE = "sheet_refuelings_toggle"
    fun card(sheetId: String) = "sheet_card_$sheetId"
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
        )
    }
}

@PreviewLightDark
@Composable
private fun SheetsFeedContentErrorPreview() {
    FuelTrackerTheme {
        SheetsFeedContent(
            state = SheetsFeedUiState(isLoading = false, loadError = UiText.Resource(R.string.error_no_connection)),
            onEvent = {},
            onChangeCar = {},
        )
    }
}
