package ru.fueltracker.app.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.asString
import ru.fueltracker.app.ui.theme.FuelTrackerTheme

/** Новый лист: поля из `next-prefill`, сезон — переключатель ❄️ Зима | ☀️ Лето. */
@Composable
fun NewSheetDialog(
    form: NewSheetForm,
    hasWinterNorm: Boolean,
    onEvent: (SheetsFeedEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onEvent(SheetsFeedEvent.DismissNewSheet) },
        title = { Text(stringResource(R.string.new_sheet_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MonthSelector(form, enabled = !form.isSaving, onEvent = onEvent)
                SeasonSelector(
                    season = form.season,
                    hasWinterNorm = hasWinterNorm,
                    enabled = !form.isSaving,
                    onSelect = { onEvent(SheetsFeedEvent.NewSheetSeason(it)) },
                )
                DialogField(
                    value = form.odometerStart,
                    onValueChange = { onEvent(SheetsFeedEvent.NewSheetOdometer(it)) },
                    label = stringResource(R.string.new_sheet_odometer),
                    error = form.odometerError,
                    enabled = !form.isSaving,
                    keyboardType = KeyboardType.Number,
                    testTag = SheetDialogTestTags.NEW_ODOMETER,
                )
                DialogField(
                    value = form.fuelStart,
                    onValueChange = { onEvent(SheetsFeedEvent.NewSheetFuel(it)) },
                    label = stringResource(R.string.new_sheet_fuel),
                    error = form.fuelError,
                    enabled = !form.isSaving,
                    keyboardType = KeyboardType.Decimal,
                    testTag = SheetDialogTestTags.NEW_FUEL,
                )
                form.error?.let { DialogError(it) }
            }
        },
        confirmButton = {
            ConfirmButton(
                text = stringResource(R.string.new_sheet_create),
                isSaving = form.isSaving,
                onClick = { onEvent(SheetsFeedEvent.ConfirmNewSheet) },
                testTag = SheetDialogTestTags.NEW_CONFIRM,
            )
        },
        dismissButton = {
            TextButton(onClick = { onEvent(SheetsFeedEvent.DismissNewSheet) }, enabled = !form.isSaving) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun MonthSelector(form: NewSheetForm, enabled: Boolean, onEvent: (SheetsFeedEvent) -> Unit) {
    val prev = stringResource(R.string.new_sheet_prev_month)
    val next = stringResource(R.string.new_sheet_next_month)
    Column {
        Text(stringResource(R.string.new_sheet_month), style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { onEvent(SheetsFeedEvent.NewSheetMonthShift(-1)) },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = prev },
            ) { Text("‹", style = MaterialTheme.typography.titleLarge) }
            Text(
                text = Formatters.month(form.year, form.month),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onEvent(SheetsFeedEvent.NewSheetMonthShift(1)) },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = next },
            ) { Text("›", style = MaterialTheme.typography.titleLarge) }
        }
    }
}

/** Без зимней нормы у авто «Зима» неактивна — подсказка объясняет почему. */
@Composable
private fun SeasonSelector(
    season: Season,
    hasWinterNorm: Boolean,
    enabled: Boolean,
    onSelect: (Season) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.new_sheet_season), style = MaterialTheme.typography.labelMedium)
        val options = listOf(Season.WINTER to R.string.sheet_season_winter, Season.SUMMER to R.string.sheet_season_summer)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = season == value,
                    onClick = { onSelect(value) },
                    enabled = enabled && (value != Season.WINTER || hasWinterNorm),
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(stringResource(label)) },
                )
            }
        }
        if (!hasWinterNorm) {
            Text(
                text = stringResource(R.string.winter_norm_not_set),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Закрыть месяц: пробег на конец обязателен, фактический остаток — по желанию. */
@Composable
fun CloseSheetDialog(
    form: CloseSheetForm,
    onEvent: (SheetsFeedEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onEvent(SheetsFeedEvent.DismissClose) },
        title = { Text(stringResource(R.string.close_sheet_title, Formatters.month(form.year, form.month))) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DialogField(
                    value = form.odometerEnd,
                    onValueChange = { onEvent(SheetsFeedEvent.CloseOdometer(it)) },
                    label = stringResource(R.string.close_sheet_odometer),
                    error = form.odometerError,
                    hint = stringResource(R.string.close_sheet_odometer_hint, Formatters.km(form.odometerStartKm)),
                    enabled = !form.isSaving,
                    keyboardType = KeyboardType.Number,
                    testTag = SheetDialogTestTags.CLOSE_ODOMETER,
                )
                DialogField(
                    value = form.fuelEndActual,
                    onValueChange = { onEvent(SheetsFeedEvent.CloseFuel(it)) },
                    label = stringResource(R.string.close_sheet_fuel),
                    error = form.fuelError,
                    hint = stringResource(R.string.close_sheet_fuel_hint),
                    enabled = !form.isSaving,
                    keyboardType = KeyboardType.Decimal,
                    testTag = SheetDialogTestTags.CLOSE_FUEL,
                )
                form.error?.let { DialogError(it) }
            }
        },
        confirmButton = {
            ConfirmButton(
                text = stringResource(R.string.close_sheet_confirm),
                isSaving = form.isSaving,
                onClick = { onEvent(SheetsFeedEvent.ConfirmClose) },
                testTag = SheetDialogTestTags.CLOSE_CONFIRM,
            )
        },
        dismissButton = {
            TextButton(onClick = { onEvent(SheetsFeedEvent.DismissClose) }, enabled = !form.isSaving) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun DialogField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: UiText?,
    enabled: Boolean,
    keyboardType: KeyboardType,
    testTag: String,
    hint: String? = null,
) {
    val supporting = error?.asString() ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    )
}

@Composable
private fun DialogError(error: UiText) {
    Text(
        text = error.asString(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ConfirmButton(text: String, isSaving: Boolean, onClick: () -> Unit, testTag: String) {
    TextButton(onClick = onClick, enabled = !isSaving, modifier = Modifier.testTag(testTag)) {
        if (isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(text)
        }
    }
}

object SheetDialogTestTags {
    const val NEW_ODOMETER = "new_sheet_odometer"
    const val NEW_FUEL = "new_sheet_fuel"
    const val NEW_CONFIRM = "new_sheet_confirm"
    const val CLOSE_ODOMETER = "close_sheet_odometer"
    const val CLOSE_FUEL = "close_sheet_fuel"
    const val CLOSE_CONFIRM = "close_sheet_confirm"
}

@PreviewLightDark
@Composable
private fun NewSheetDialogPreview() {
    FuelTrackerTheme {
        Surface {
            NewSheetDialog(
                form = NewSheetForm(2026, 11, Season.WINTER, "53340", "10"),
                hasWinterNorm = true,
                onEvent = {},
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun CloseSheetDialogPreview() {
    FuelTrackerTheme {
        Surface {
            CloseSheetDialog(
                form = CloseSheetForm(
                    sheetId = "s-10",
                    year = 2026,
                    month = 10,
                    odometerStartKm = 52_340,
                    odometerEnd = "52000",
                    fuelEndActual = "",
                    odometerError = UiText.Resource(R.string.error_odometer_end_before_start, listOf("52 340")),
                ),
                onEvent = {},
            )
        }
    }
}
