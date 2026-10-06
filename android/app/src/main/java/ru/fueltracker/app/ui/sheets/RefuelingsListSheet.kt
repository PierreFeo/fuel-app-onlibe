package ru.fueltracker.app.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.Refueling
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.theme.FuelTrackerTheme

/**
 * Шторка «Заправки · месяц»: список заправок листа, итог из `calc` и «+ Заправка».
 * У закрытого листа строки не нажимаются и кнопки нет.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefuelingsListSheet(
    sheet: FuelSheet,
    isBusy: Boolean,
    onAdd: () -> Unit,
    onRefuelingClick: (Refueling) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        RefuelingsListContent(sheet, isBusy, onAdd, onRefuelingClick)
    }
}

/** Содержимое шторки отдельно — его видно в Preview (ModalBottomSheet в Preview не рисуется). */
@Composable
fun RefuelingsListContent(
    sheet: FuelSheet,
    isBusy: Boolean,
    onAdd: () -> Unit,
    onRefuelingClick: (Refueling) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                val onClick = if (sheet.isClosed || isBusy) null else ({ onRefuelingClick(refueling) })
                RefuelingRow(refueling, onClick)
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
        if (!sheet.isClosed) {
            OutlinedButton(
                onClick = onAdd,
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

@Composable
private fun RefuelingRow(refueling: Refueling, onClick: (() -> Unit)?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp)
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
    }
}

@PreviewLightDark
@Composable
private fun RefuelingsListOpenPreview() {
    FuelTrackerTheme {
        Surface {
            RefuelingsListContent(sheet = previewOpenSheet(), isBusy = false, onAdd = {}, onRefuelingClick = {})
        }
    }
}

@PreviewLightDark
@Composable
private fun RefuelingsListClosedPreview() {
    FuelTrackerTheme {
        Surface {
            RefuelingsListContent(sheet = previewClosedSheet(), isBusy = false, onAdd = {}, onRefuelingClick = {})
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
                onAdd = {},
                onRefuelingClick = {},
            )
        }
    }
}
