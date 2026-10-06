package ru.fueltracker.app.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.ConsumptionStatus
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.Refueling
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.asString
import ru.fueltracker.app.ui.theme.FuelTrackerTheme
import ru.fueltracker.app.ui.theme.fuelColors
import java.math.BigDecimal

/**
 * Карточка ЛУТ — только показ: все итоги берутся из `calc` сервера.
 * Кнопки действий (закрыть, заправка, меню) — в задачах 5.4–5.5.
 */
@Composable
fun SheetCard(
    sheet: FuelSheet,
    isRefuelingsExpanded: Boolean,
    onToggleRefuelings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val calc = sheet.calc
    Card(modifier = modifier.fillMaxWidth().testTag(SheetsFeedTestTags.card(sheet.id))) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SheetHeader(sheet)
            Text(
                text = stringResource(R.string.sheet_norm, Formatters.consumption(sheet.normLPer100km)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(mileageText(sheet), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.sheet_fuel_start, Formatters.amount(sheet.fuelStartL)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.sheet_refueled,
                    Formatters.amount(calc.refueledL),
                    Formatters.amount(calc.refueledCost),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (sheet.refuelings.isNotEmpty()) {
                RefuelingsSection(sheet.refuelings, isRefuelingsExpanded, onToggleRefuelings)
            }

            if (sheet.isClosed) {
                ClosedTotals(sheet)
            } else {
                Text(
                    text = stringResource(R.string.sheet_fuel_available, Formatters.amount(calc.fuelAvailableL)),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            calc.warnings.forEach { warning ->
                Text(
                    text = stringResource(R.string.sheet_warning, warning.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(sheet: FuelSheet) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = Formatters.month(sheet.year, sheet.month),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(if (sheet.season == Season.WINTER) R.string.sheet_season_winter else R.string.sheet_season_summer),
            style = MaterialTheme.typography.labelLarge,
            // Закрытый лист — бледная иконка сезона (переключать нельзя)
            color = if (sheet.isClosed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (sheet.isClosed) {
            val closed = stringResource(R.string.sheet_closed)
            Spacer(Modifier.width(8.dp))
            Text(text = "🔒", modifier = Modifier.semantics { contentDescription = closed })
        }
    }
}

@Composable
private fun mileageText(sheet: FuelSheet): String {
    val start = Formatters.km(sheet.odometerStartKm)
    val end = sheet.odometerEndKm
    val mileage = sheet.calc.mileageKm
    return if (end == null || mileage == null) {
        stringResource(R.string.sheet_mileage_open, start)
    } else {
        stringResource(R.string.sheet_mileage, start, Formatters.km(end), Formatters.km(mileage))
    }
}

@Composable
private fun RefuelingsSection(
    refuelings: List<Refueling>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Text(
        text = (if (expanded) "▾ " else "▸ ") + stringResource(R.string.sheet_refuelings, refuelings.size),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp)
            .testTag(SheetsFeedTestTags.REFUELINGS_TOGGLE),
    )
    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 12.dp)) {
            refuelings.forEach { RefuelingRow(it) }
        }
    }
}

@Composable
private fun RefuelingRow(refueling: Refueling) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Formatters.dayMonth(refueling.date), style = MaterialTheme.typography.bodySmall)
        Text(
            text = refueling.station.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
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
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ClosedTotals(sheet: FuelSheet) {
    val calc = sheet.calc
    calc.fuelEndL?.let { fuelEnd ->
        val res = if (sheet.fuelEndActualL == null) R.string.sheet_fuel_end_by_norm else R.string.sheet_fuel_end
        Text(stringResource(res, Formatters.amount(fuelEnd)), style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    val perHundred = calc.actualLPer100km
    if (perHundred == null) {
        consumptionHint(sheet)?.let {
            Text(it.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        val color = consumptionColor(calc.consumptionStatus)
        Text(stringResource(R.string.sheet_consumption), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.sheet_consumption_value, Formatters.consumption(perHundred)),
                style = MaterialTheme.typography.headlineSmall,
                color = color,
                modifier = Modifier.weight(1f),
            )
            calc.consumptionStatus?.let { status ->
                Text(
                    text = stringResource(
                        if (status == ConsumptionStatus.OVER) R.string.sheet_consumption_over else R.string.sheet_consumption_normal,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                )
            }
        }
        calc.deviationL?.let {
            Text(deviationText(it).asString(), style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
    calc.costPerKm?.let {
        Text(stringResource(R.string.sheet_cost_per_km, Formatters.amount(it)), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Цвет по `consumption_status` сервера: NORMAL — зелёный, OVER — красный, null — обычный. */
@Composable
private fun consumptionColor(status: ConsumptionStatus?): Color = when (status) {
    ConsumptionStatus.NORMAL -> MaterialTheme.fuelColors.consumptionNormal
    ConsumptionStatus.OVER -> MaterialTheme.fuelColors.consumptionOver
    null -> MaterialTheme.colorScheme.onSurface
}

/** `deviation_l` > 0 — перерасход, < 0 — экономия (число без минуса). */
internal fun deviationText(deviation: BigDecimal): UiText = when (deviation.signum()) {
    1 -> UiText.Resource(R.string.sheet_overuse, listOf(Formatters.amount(deviation)))
    -1 -> UiText.Resource(R.string.sheet_economy, listOf(Formatters.amount(deviation.abs())))
    else -> UiText.Resource(R.string.sheet_exact)
}

/** Почему расход ещё не посчитан; null — объяснять нечего (например, пробег 0 км). */
internal fun consumptionHint(sheet: FuelSheet): UiText? = when {
    sheet.calc.mileageKm == null -> UiText.Resource(R.string.sheet_consumption_pending)
    sheet.fuelEndActualL == null -> UiText.Resource(R.string.sheet_consumption_no_actual)
    else -> null
}

@PreviewLightDark
@Composable
private fun SheetCardClosedPreview() {
    FuelTrackerTheme {
        Surface {
            SheetCard(
                sheet = previewClosedSheet(),
                isRefuelingsExpanded = true,
                onToggleRefuelings = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun SheetCardOpenPreview() {
    FuelTrackerTheme {
        Surface {
            SheetCard(
                sheet = previewOpenSheet(),
                isRefuelingsExpanded = false,
                onToggleRefuelings = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
