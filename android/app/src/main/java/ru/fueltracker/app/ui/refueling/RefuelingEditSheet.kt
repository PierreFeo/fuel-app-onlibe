package ru.fueltracker.app.ui.refueling

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.PaymentType
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.asString
import ru.fueltracker.app.ui.theme.FuelTrackerTheme
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Шторка «Заправка» поверх ленты: добавить, изменить или удалить.
 * Заправка пишется в базу на телефоне — карточка листа обновится сама; [onSaved] только закрывает шторку.
 */
@Composable
fun RefuelingEditSheet(
    target: RefuelingTarget,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: RefuelingEditViewModel = hiltViewModel(),
) {
    LaunchedEffect(target) { viewModel.start(target) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.onEvent(RefuelingEditEvent.Reset)
            onSaved()
        }
    }
    // Пока state не получил target (первый кадр), рисовать нечего
    if (state.target != target) return
    RefuelingEditContent(
        state = state,
        onEvent = viewModel::onEvent,
        onDismiss = {
            if (!state.isBusy) {
                viewModel.onEvent(RefuelingEditEvent.Reset)
                onDismiss()
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefuelingEditContent(
    state: RefuelingEditUiState,
    onEvent: (RefuelingEditEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        RefuelingFormContent(state = state, onEvent = onEvent)
    }
    if (state.confirmDelete) {
        DeleteRefuelingDialog(state, onEvent)
    }
}

/** Содержимое шторки отдельно — его видно в Preview (ModalBottomSheet в Preview не рисуется). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RefuelingFormContent(
    state: RefuelingEditUiState,
    onEvent: (RefuelingEditEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    val errors = state.errors
    val enabled = !state.isBusy
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(if (state.isNew) R.string.refueling_title_new else R.string.refueling_title_edit),
            style = MaterialTheme.typography.titleLarge,
        )
        state.target?.let { target ->
            DateField(
                date = form.date,
                month = target.yearMonth,
                error = errors.date,
                enabled = enabled,
                onDateChange = { onEvent(RefuelingEditEvent.DateChanged(it)) },
            )
        }
        Field(
            value = form.liters,
            onValueChange = { onEvent(RefuelingEditEvent.LitersChanged(it)) },
            label = R.string.refueling_liters,
            error = errors.liters,
            enabled = enabled,
            keyboardType = KeyboardType.Decimal,
            testTag = RefuelingTestTags.LITERS,
        )
        Field(
            value = form.pricePerLiter,
            onValueChange = { onEvent(RefuelingEditEvent.PriceChanged(it)) },
            label = R.string.refueling_price,
            error = errors.pricePerLiter,
            enabled = enabled,
            keyboardType = KeyboardType.Decimal,
            testTag = RefuelingTestTags.PRICE,
        )
        Field(
            value = form.totalCost,
            onValueChange = { onEvent(RefuelingEditEvent.TotalChanged(it)) },
            label = R.string.refueling_total,
            error = errors.totalCost,
            hint = stringResource(if (form.isTotalManual) R.string.refueling_total_manual else R.string.refueling_total_auto),
            enabled = enabled,
            keyboardType = KeyboardType.Decimal,
            testTag = RefuelingTestTags.TOTAL,
        )
        Field(
            value = form.odometer,
            onValueChange = { onEvent(RefuelingEditEvent.OdometerChanged(it)) },
            label = R.string.refueling_odometer,
            error = errors.odometer,
            enabled = enabled,
            keyboardType = KeyboardType.Number,
            testTag = RefuelingTestTags.ODOMETER,
        )
        Field(
            value = form.station,
            onValueChange = { onEvent(RefuelingEditEvent.StationChanged(it)) },
            label = R.string.refueling_station,
            error = errors.station,
            enabled = enabled,
            keyboardType = KeyboardType.Text,
            testTag = RefuelingTestTags.STATION,
        )
        Text(stringResource(R.string.refueling_payment), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaymentType.entries.forEach { type ->
                FilterChip(
                    selected = form.paymentType == type,
                    onClick = { onEvent(RefuelingEditEvent.PaymentChanged(type)) },
                    label = { Text(stringResource(type.labelRes)) },
                    enabled = enabled,
                )
            }
        }
        Field(
            value = form.note,
            onValueChange = { onEvent(RefuelingEditEvent.NoteChanged(it)) },
            label = R.string.refueling_note,
            error = errors.note,
            enabled = enabled,
            keyboardType = KeyboardType.Text,
            testTag = RefuelingTestTags.NOTE,
            singleLine = false,
        )
        state.error?.let {
            Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = { onEvent(RefuelingEditEvent.Save) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(52.dp)
                .testTag(RefuelingTestTags.SAVE),
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.action_save))
            }
        }
        if (!state.isNew) {
            TextButton(
                onClick = { onEvent(RefuelingEditEvent.RequestDelete) },
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(RefuelingTestTags.DELETE),
            ) {
                if (state.isDeleting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.refueling_delete))
                }
            }
        }
    }
}

/** Дата — только из календаря, и только дни месяца листа (правило 4 в 06_BUSINESS_RULES.md). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    date: LocalDate,
    month: YearMonth,
    error: UiText?,
    enabled: Boolean,
    onDateChange: (LocalDate) -> Unit,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val pickDescription = stringResource(R.string.refueling_date_pick)
    Box {
        OutlinedTextField(
            value = Formatters.date(date),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.refueling_date)) },
            isError = error != null,
            supportingText = error?.let { { Text(it.asString()) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Поле только для чтения не реагирует на нажатие — ловим его поверх
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(enabled = enabled) { showPicker = true }
                .semantics { contentDescription = pickDescription }
                .testTag(RefuelingTestTags.DATE),
        )
    }
    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.toUtcMillis(),
            initialDisplayedMonthMillis = month.atDay(1).toUtcMillis(),
            yearRange = month.year..month.year,
            selectableDates = remember(month) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = YearMonth.from(utcTimeMillis.toUtcDate()) == month
                    override fun isSelectableYear(year: Int) = year == month.year
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onDateChange(it.toUtcDate()) }
                    showPicker = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

// DatePicker работает с полночью UTC — так дата не сдвигается из-за часового пояса
private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    error: UiText?,
    enabled: Boolean,
    keyboardType: KeyboardType,
    testTag: String,
    hint: String? = null,
    singleLine: Boolean = true,
) {
    val supporting = error?.asString() ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = singleLine,
        enabled = enabled,
        isError = error != null,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    )
}

@Composable
private fun DeleteRefuelingDialog(state: RefuelingEditUiState, onEvent: (RefuelingEditEvent) -> Unit) {
    val refueling = state.target?.refueling ?: return
    AlertDialog(
        onDismissRequest = { onEvent(RefuelingEditEvent.DismissDelete) },
        title = { Text(stringResource(R.string.refueling_delete_title)) },
        text = {
            Text(
                stringResource(
                    R.string.refueling_delete_text,
                    Formatters.dayMonth(refueling.date),
                    Formatters.amount(refueling.liters),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onEvent(RefuelingEditEvent.ConfirmDelete) }) {
                Text(stringResource(R.string.sheet_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(RefuelingEditEvent.DismissDelete) }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@get:StringRes
val PaymentType.labelRes: Int
    get() = when (this) {
        PaymentType.PERSONAL -> R.string.payment_personal
        PaymentType.FUEL_CARD -> R.string.payment_fuel_card
        PaymentType.COMPANY -> R.string.payment_company
    }

object RefuelingTestTags {
    const val DATE = "refueling_date"
    const val LITERS = "refueling_liters"
    const val PRICE = "refueling_price"
    const val TOTAL = "refueling_total"
    const val ODOMETER = "refueling_odometer"
    const val STATION = "refueling_station"
    const val NOTE = "refueling_note"
    const val SAVE = "refueling_save"
    const val DELETE = "refueling_delete"
}

@PreviewLightDark
@Composable
private fun RefuelingFormNewPreview() {
    FuelTrackerTheme {
        Surface {
            RefuelingFormContent(
                state = RefuelingEditUiState(
                    target = RefuelingTarget("s-10", 2026, 10),
                    form = RefuelingForm(date = LocalDate.of(2026, 10, 5), liters = "40", pricePerLiter = "55,5", totalCost = "2220,00"),
                ),
                onEvent = {},
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun RefuelingFormEditPreview() {
    FuelTrackerTheme {
        Surface {
            val refueling = ru.fueltracker.app.ui.sheets.previewClosedSheet().refuelings.first()
            RefuelingFormContent(
                state = RefuelingEditUiState(
                    target = RefuelingTarget("s-07", 2026, 7, refueling),
                    form = refueling.toForm().copy(totalCost = "2700,00", isTotalManual = true),
                    errors = RefuelingErrors(liters = UiText.Resource(R.string.error_positive_number)),
                ),
                onEvent = {},
            )
        }
    }
}
