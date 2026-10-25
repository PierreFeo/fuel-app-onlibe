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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import ru.fueltracker.app.ui.theme.ButtonShape
import ru.fueltracker.app.ui.theme.NumberStyle
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
    /** Закрытый лист: «Переоткрыть месяц» прямо из списка. */
    val onReopen: () -> Unit = {},
)

/**
 * Шторка «Заправки · месяц»: список заправок листа, итог из `calc`, «+ Заправка», ✏️ и 🗑 в строках.
 * У закрытого листа строки не нажимаются, кнопок нет — вместо них подсказка и «Переоткрыть месяц».
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.refuelings_total_label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(
                        R.string.refuelings_total_value,
                        Formatters.amount(sheet.calc.refueledL),
                        Formatters.amount(sheet.calc.refueledCost),
                    ),
                    style = NumberStyle,
                    fontSize = 17.sp,
                )
            }
        }
        error?.let {
            Text(
                text = it.asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(SheetsFeedTestTags.REFUELINGS_ERROR),
            )
        }
        if (sheet.isClosed) {
            ClosedHint(isBusy = isBusy, onReopen = actions.onReopen, modifier = Modifier.testTag(SheetsFeedTestTags.reopenFromRefuelings(sheet.id)))
        } else {
            FilledTonalButton(
                onClick = actions.onAdd,
                enabled = !isBusy,
                shape = ButtonShape,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(52.dp)
                    .testTag(SheetsFeedTestTags.addRefueling(sheet.id)),
            ) {
                Text(stringResource(R.string.sheet_add_refueling), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Строка заправки: нажатие или ✏️ — форма заправки, 🗑 — удаление (только у открытого листа). */
@Composable
private fun RefuelingRow(
    refueling: Refueling,
    showDelete: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // «05.07 | 50,00 л / по 55,00 ₽, Лукойл | 2 750,00 ₽ | ✏️ 🗑» (07_UI_SCREENS.md, RefuelingsListSheet)
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (showDelete) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(vertical = if (showDelete) 4.dp else 10.dp)
            .testTag(SheetsFeedTestTags.refueling(refueling.id)),
    ) {
        Text(Formatters.dayMonth(refueling.date), style = NumberStyle, fontSize = 15.sp, modifier = Modifier.width(46.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.sheet_value_l, Formatters.amount(refueling.liters)), style = NumberStyle, fontSize = 15.sp)
            val price = Formatters.amount(refueling.pricePerLiter)
            Text(
                text = refueling.station?.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.refueling_price_station, price, it) }
                    ?: stringResource(R.string.refueling_price_only, price),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.refueling_sum, Formatters.amount(refueling.totalCost)),
            style = NumberStyle,
            fontSize = 15.sp,
            textAlign = TextAlign.End,
        )
        if (showDelete) {
            // Нажатие на строку тоже открывает форму, но без значка об этом не догадаться
            Row {
                RowIcon(R.drawable.ic_edit, R.string.refueling_edit, enabled, onClick, SheetsFeedTestTags.editRefueling(refueling.id))
                RowIcon(R.drawable.ic_delete, R.string.refueling_delete, enabled, onDelete, SheetsFeedTestTags.deleteRefueling(refueling.id))
            }
        }
    }
}

@Composable
private fun RowIcon(icon: Int, description: Int, enabled: Boolean, onClick: () -> Unit, tag: String) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp).testTag(tag)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(description),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Закрытый лист: почему нельзя менять заправки и как это исправить. */
@Composable
private fun ClosedHint(isBusy: Boolean, onReopen: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.refuelings_closed_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onReopen, enabled = !isBusy, shape = ButtonShape, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.refuelings_reopen), modifier = Modifier.padding(start = 8.dp))
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
