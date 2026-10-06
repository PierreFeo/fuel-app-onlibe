package ru.fueltracker.app.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.Refueling
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.asString
import ru.fueltracker.app.ui.theme.FuelTrackerTheme

/** Что можно сделать в списке заправок. */
class RefuelingsListActions(
    val onAdd: () -> Unit = {},
    val onRefuelingClick: (Refueling) -> Unit = {},
    val onDeleteClick: (Refueling) -> Unit = {},
)

/**
 * Шторка «Заправки · месяц»: список заправок листа, итог из `calc`, «+ Заправка» и 🗑 в строках.
 * У закрытого листа строки не нажимаются, кнопок «+ Заправка» и 🗑 нет.
 * [error] — ошибка удаления (Snackbar ленты под шторкой не виден).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefuelingsListSheet(
    sheet: FuelSheet,
    isBusy: Boolean,
    error: UiText?,
    actions: RefuelingsListActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        RefuelingsListContent(sheet, isBusy, error, actions)
    }
}

/** Содержимое шторки отдельно — его видно в Preview (ModalBottomSheet в Preview не рисуется). */
@Composable
fun RefuelingsListContent(
    sheet: FuelSheet,
    isBusy: Boolean,
    error: UiText?,
    actions: RefuelingsListActions,
    modifier: Modifier = Modifier,
) {
    val editable = !sheet.isClosed && !isBusy
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
            .testTag(SheetsFeedTestTags.REFUELINGS_LIST),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.refuelings_title, Formatters.month(sheet.year, sheet.month)),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (sheet.refuelings.isEmpty()) {
            Text(
                text = stringResource(R.string.refuelings_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            sheet.refuelings.forEach { refueling ->
                RefuelingRow(
                    refueling = refueling,
                    showDelete = !sheet.isClosed,
                    enabled = editable,
                    onClick = { actions.onRefuelingClick(refueling) },
                    onDelete = { actions.onDeleteClick(refueling) },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(
                    R.string.refuelings_total,
                    Formatters.amount(sheet.calc.refueledL),
                    Formatters.amount(sheet.calc.refueledCost),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        error?.let {
            Text(
                text = it.asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(SheetsFeedTestTags.REFUELINGS_ERROR),
            )
        }
        if (!sheet.isClosed) {
            OutlinedButton(
                onClick = actions.onAdd,
                enabled = !isBusy,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp)
                    .testTag(SheetsFeedTestTags.addRefueling(sheet.id)),
            ) {
                Text(stringResource(R.string.sheet_add_refueling))
            }
        }
    }
}

/** Строка заправки: нажатие — форма заправки, 🗑 — удаление (только у открытого листа). */
@Composable
private fun RefuelingRow(
    refueling: Refueling,
    showDelete: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (showDelete) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            // С кнопкой 🗑 строка уже высокая (48 dp), без неё — добавляем отступы
            .padding(vertical = if (showDelete) 0.dp else 10.dp)
            .testTag(SheetsFeedTestTags.refueling(refueling.id)),
    ) {
        Text(Formatters.dayMonth(refueling.date), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = refueling.station.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                R.string.sheet_refueling_line,
                Formatters.amount(refueling.liters),
                Formatters.amount(refueling.pricePerLiter),
                Formatters.amount(refueling.totalCost),
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
        if (showDelete) {
            IconButton(
                onClick = onDelete,
                enabled = enabled,
                modifier = Modifier.testTag(SheetsFeedTestTags.deleteRefueling(refueling.id)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.refueling_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** «Удалить заправку?» — подтверждение удаления из списка. */
@Composable
fun DeleteRefuelingDialog(refueling: Refueling, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(SheetsFeedTestTags.DELETE_REFUELING_CONFIRM)) {
                Text(stringResource(R.string.sheet_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@PreviewLightDark
@Composable
private fun RefuelingsListOpenPreview() {
    FuelTrackerTheme {
        Surface {
            RefuelingsListContent(
                sheet = previewOpenSheet(),
                isBusy = false,
                error = UiText.Resource(R.string.error_no_connection),
                actions = RefuelingsListActions(),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun RefuelingsListClosedPreview() {
    FuelTrackerTheme {
        Surface {
            RefuelingsListContent(
                sheet = previewClosedSheet(),
                isBusy = false,
                error = null,
                actions = RefuelingsListActions(),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun RefuelingsListEmptyPreview() {
    FuelTrackerTheme {
        Surface {
            RefuelingsListContent(
                sheet = previewOpenSheet().copy(refuelings = emptyList()),
                isBusy = false,
                error = null,
                actions = RefuelingsListActions(),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun DeleteRefuelingDialogPreview() {
    FuelTrackerTheme {
        DeleteRefuelingDialog(refueling = previewOpenSheet().refuelings.first(), onConfirm = {}, onDismiss = {})
    }
}
