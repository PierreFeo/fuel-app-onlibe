package ru.fueltracker.app.ui.cars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.FuelType
import ru.fueltracker.app.ui.common.ErrorView
import ru.fueltracker.app.ui.common.LoadingView
import ru.fueltracker.app.ui.common.SnackbarEffect
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.asString
import ru.fueltracker.app.ui.theme.FuelTrackerTheme

@Composable
fun CarEditScreen(
    onDone: () -> Unit,
    viewModel: CarEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.onEvent(CarEditEvent.SavedHandled)
            onDone()
        }
    }
    CarEditContent(state = state, onEvent = viewModel::onEvent, onBack = onDone)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarEditContent(
    state: CarEditUiState,
    onEvent: (CarEditEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarEffect(state.snackbar, snackbarHostState) { onEvent(CarEditEvent.SnackbarShown) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.isNew) R.string.car_edit_title_new else R.string.car_edit_title_edit))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
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
                    onRetry = { onEvent(CarEditEvent.Retry) },
                )
                else -> CarFormFields(state = state, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun CarFormFields(
    state: CarEditUiState,
    onEvent: (CarEditEvent) -> Unit,
) {
    val form = state.form
    val errors = state.errors
    val enabled = !state.isSaving
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FormField(
            value = form.name,
            onValueChange = { onEvent(CarEditEvent.NameChanged(it)) },
            label = stringResource(R.string.car_name_label),
            placeholder = stringResource(R.string.car_name_placeholder),
            error = errors.name,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            testTag = CarEditTestTags.NAME,
        )
        FormField(
            value = form.plateNumber,
            onValueChange = { onEvent(CarEditEvent.PlateChanged(it)) },
            label = stringResource(R.string.car_plate_label),
            placeholder = stringResource(R.string.car_plate_placeholder),
            error = errors.plateNumber,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
            testTag = CarEditTestTags.PLATE,
        )
        FuelTypeField(
            selected = form.fuelType,
            onSelect = { onEvent(CarEditEvent.FuelTypeChanged(it)) },
            error = errors.fuelType,
            enabled = enabled,
        )
        FormField(
            value = form.tankCapacity,
            onValueChange = { onEvent(CarEditEvent.TankChanged(it)) },
            label = stringResource(R.string.car_tank_label),
            error = errors.tankCapacity,
            enabled = enabled,
            keyboardOptions = decimalKeyboard,
            testTag = CarEditTestTags.TANK,
        )
        Text(
            text = stringResource(R.string.car_norms_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        FormField(
            value = form.normSummer,
            onValueChange = { onEvent(CarEditEvent.NormSummerChanged(it)) },
            label = stringResource(R.string.car_norm_summer_label),
            placeholder = "10,068",
            error = errors.normSummer,
            enabled = enabled,
            keyboardOptions = decimalKeyboard,
            testTag = CarEditTestTags.NORM_SUMMER,
        )
        FormField(
            value = form.normWinter,
            onValueChange = { onEvent(CarEditEvent.NormWinterChanged(it)) },
            label = stringResource(R.string.car_norm_winter_label),
            placeholder = "11,684",
            error = errors.normWinter,
            hint = stringResource(R.string.car_norm_winter_hint),
            enabled = enabled,
            keyboardOptions = decimalKeyboard.copy(imeAction = ImeAction.Done),
            testTag = CarEditTestTags.NORM_WINTER,
        )
        Text(
            text = stringResource(R.string.car_norms_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { onEvent(CarEditEvent.Save) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(52.dp)
                .testTag(CarEditTestTags.SAVE),
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

private val decimalKeyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)

@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: UiText?,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    testTag: String,
    placeholder: String? = null,
    hint: String? = null,
) {
    val supporting = error?.asString() ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FuelTypeField(
    selected: FuelType?,
    onSelect: (FuelType) -> Unit,
    error: UiText?,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.let { stringResource(it.labelRes) }.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.car_fuel_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = error != null,
            supportingText = error?.let { { Text(it.asString()) } },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .testTag(CarEditTestTags.FUEL),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FuelType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(stringResource(type.labelRes)) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

object CarEditTestTags {
    const val NAME = "car_name"
    const val PLATE = "car_plate"
    const val FUEL = "car_fuel"
    const val TANK = "car_tank"
    const val NORM_SUMMER = "car_norm_summer"
    const val NORM_WINTER = "car_norm_winter"
    const val SAVE = "car_save"
}

@PreviewLightDark
@Composable
private fun CarEditContentPreview() {
    FuelTrackerTheme {
        CarEditContent(
            state = CarEditUiState(
                isNew = false,
                form = CarForm("Lada Vesta", "А123ВС77", FuelType.AI95, "50", "10,068", "11,684"),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun CarEditContentErrorsPreview() {
    FuelTrackerTheme {
        CarEditContent(
            state = CarEditUiState(
                isNew = true,
                form = CarForm(tankCapacity = "0"),
                errors = CarFormErrors(
                    name = UiText.Resource(R.string.error_required),
                    fuelType = UiText.Resource(R.string.error_required),
                    tankCapacity = UiText.Resource(R.string.error_positive_number),
                    normSummer = UiText.Resource(R.string.error_required),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
