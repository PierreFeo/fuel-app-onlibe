package ru.fueltracker.app.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/** Что можно сделать с листом из карточки. */
class SheetCardActions(
    val onToggleExpanded: () -> Unit = {},
    val onSeasonClick: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onReopen: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onAddRefueling: () -> Unit = {},
    val onRefuelingClick: (Refueling) -> Unit = {},
)

/**
 * Карточка ЛУТ: компактная сводка, нажатие раскрывает подробности.
 * Все итоги берутся из `calc` сервера. [isBusy] — по листу идёт запрос, кнопки неактивны.
 */
@Composable
fun SheetCard(
    sheet: FuelSheet,
    isExpanded: Boolean,
    isBusy: Boolean,
    actions: SheetCardActions,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = actions.onToggleExpanded,
        modifier = modifier.fillMaxWidth().testTag(SheetsFeedTestTags.card(sheet.id)),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            SheetHeader(sheet, isBusy, actions)
            Column(modifier = Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OdometerAndConsumption(sheet)
                HorizontalDivider()
                SummaryRow(sheet)
            }
            AnimatedVisibility(visible = isExpanded) {
                SheetDetails(sheet, isBusy, actions, modifier = Modifier.padding(top = 8.dp, end = 8.dp))
            }
            ExpandChevron(isExpanded)
        }
    }
}

@Composable
private fun SheetHeader(sheet: FuelSheet, isBusy: Boolean, actions: SheetCardActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = Formatters.month(sheet.year, sheet.month),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (sheet.calc.warnings.isNotEmpty()) {
            val warnings = stringResource(R.string.sheet_has_warnings)
            Text(
                text = "⚠",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .semantics { contentDescription = warnings },
            )
        }
        Spacer(Modifier.weight(1f))
        // Нажатие переключает сезон; у закрытого листа иконка бледная, нажатие объясняет почему нельзя
        TextButton(
            onClick = actions.onSeasonClick,
            enabled = !isBusy,
            modifier = Modifier.testTag(SheetsFeedTestTags.season(sheet.id)),
        ) {
            val season = stringResource(
                if (sheet.season == Season.WINTER) R.string.sheet_season_winter else R.string.sheet_season_summer,
            )
            val toggle = stringResource(R.string.sheet_season_toggle)
            Text(
                text = season,
                style = MaterialTheme.typography.labelLarge,
                color = if (sheet.isClosed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = "$season. $toggle" },
            )
        }
        if (sheet.isClosed) {
            val closed = stringResource(R.string.sheet_closed)
            Text(text = "🔒", modifier = Modifier.semantics { contentDescription = closed })
        }
        SheetMenu(sheet, isBusy, actions)
    }
}

/** Закрытый лист — «Переоткрыть»; лист без заправок — «Удалить» (с заправками сервер не даст). */
@Composable
private fun SheetMenu(sheet: FuelSheet, isBusy: Boolean, actions: SheetCardActions) {
    val canDelete = sheet.refuelings.isEmpty()
    if (!sheet.isClosed && !canDelete) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            enabled = !isBusy,
            modifier = Modifier.testTag(SheetsFeedTestTags.menu(sheet.id)),
        ) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (sheet.isClosed) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sheet_reopen)) },
                    onClick = {
                        open = false
                        actions.onReopen()
                    },
                )
            }
            if (canDelete) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sheet_delete)) },
                    onClick = {
                        open = false
                        actions.onDelete()
                    },
                )
            }
        }
    }
}

/** «Начало / Конец месяца» слева, «Расход» справа — как в старом приложении. */
@Composable
private fun OdometerAndConsumption(sheet: FuelSheet) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(IntrinsicSize.Min),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LabeledValue(stringResource(R.string.sheet_month_start), Formatters.km(sheet.odometerStartKm))
            LabeledValue(
                stringResource(R.string.sheet_month_end),
                sheet.odometerEndKm?.let { Formatters.km(it) } ?: stringResource(R.string.sheet_no_value),
            )
        }
        VerticalDivider(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(min = 96.dp),
        ) {
            Text(
                text = stringResource(R.string.sheet_consumption),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            val perHundred = sheet.calc.actualLPer100km
            Text(
                text = perHundred?.let { Formatters.consumption(it) } ?: stringResource(R.string.sheet_no_value),
                style = MaterialTheme.typography.headlineSmall,
                color = consumptionColor(sheet.calc.consumptionStatus),
                modifier = Modifier.testTag(SheetsFeedTestTags.consumption(sheet.id)),
            )
            if (perHundred != null) {
                Text(
                    text = stringResource(R.string.sheet_consumption_unit),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** «Пробег · Остаток в баке (у открытого — Доступно) · Заправлено». */
@Composable
private fun SummaryRow(sheet: FuelSheet) {
    val calc = sheet.calc
    val noValue = stringResource(R.string.sheet_no_value)
    val (fuelLabel, fuelValue) = if (sheet.isClosed) {
        R.string.sheet_fuel_left_label to calc.fuelEndL
    } else {
        R.string.sheet_fuel_available_label to calc.fuelAvailableL
    }
    Row {
        SummaryCell(
            label = stringResource(R.string.sheet_mileage_label),
            value = calc.mileageKm?.let { stringResource(R.string.sheet_value_km, Formatters.km(it)) } ?: noValue,
            alignment = Alignment.Start,
            modifier = Modifier.weight(1f),
        )
        SummaryCell(
            label = stringResource(fuelLabel),
            value = fuelValue?.let { stringResource(R.string.sheet_value_l, Formatters.amount(it)) } ?: noValue,
            alignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
        )
        SummaryCell(
            label = stringResource(R.string.sheet_refueled_label),
            value = stringResource(R.string.sheet_value_l, Formatters.amount(calc.refueledL)),
            alignment = Alignment.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryCell(label: String, value: String, alignment: Alignment.Horizontal, modifier: Modifier) {
    Column(horizontalAlignment = alignment, modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
    }
}

/** Стрелка внизу карточки: подсказка, что карточку можно раскрыть. */
@Composable
private fun ExpandChevron(isExpanded: Boolean) {
    val rotation by animateFloatAsState(if (isExpanded) 180f else 0f, label = "chevron")
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_expand_more),
            contentDescription = stringResource(if (isExpanded) R.string.sheet_collapse else R.string.sheet_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(rotation),
        )
    }
}

/** Раскрытая часть: норма, деньги, экономия/перерасход, предупреждения, «Закрыть месяц». */
@Composable
private fun SheetDetails(sheet: FuelSheet, isBusy: Boolean, actions: SheetCardActions, modifier: Modifier = Modifier) {
    val calc = sheet.calc
    Column(
        modifier = modifier.testTag(SheetsFeedTestTags.details(sheet.id)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HorizontalDivider()
        DetailText(stringResource(R.string.sheet_norm, Formatters.consumption(sheet.normLPer100km)))
        DetailText(stringResource(R.string.sheet_fuel_start, Formatters.amount(sheet.fuelStartL)))
        DetailText(
            stringResource(R.string.sheet_refueled, Formatters.amount(calc.refueledL), Formatters.amount(calc.refueledCost)),
        )
        if (sheet.refuelings.isNotEmpty() || !sheet.isClosed) {
            RefuelingsSection(sheet, isBusy, actions)
        }
        if (sheet.isClosed) {
            calc.fuelEndL?.let { fuelEnd ->
                val res = if (sheet.fuelEndActualL == null) R.string.sheet_fuel_end_by_norm else R.string.sheet_fuel_end
                DetailText(stringResource(res, Formatters.amount(fuelEnd)))
            }
        }
        val deviation = calc.deviationL
        if (calc.actualLPer100km != null && deviation != null) {
            DetailText(deviationText(deviation).asString(), color = consumptionColor(calc.consumptionStatus))
        } else {
            consumptionHint(sheet)?.let { DetailText(it.asString(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        calc.costPerKm?.let { DetailText(stringResource(R.string.sheet_cost_per_km, Formatters.amount(it))) }

        calc.warnings.forEach { warning ->
            Text(
                text = stringResource(R.string.sheet_warning, warning.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (!sheet.isClosed) {
            Button(
                onClick = actions.onClose,
                enabled = !isBusy,
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag(SheetsFeedTestTags.closeButton(sheet.id)),
            ) {
                Text(stringResource(R.string.sheet_close_month))
            }
        }
    }
}

@Composable
private fun DetailText(text: String, color: Color = Color.Unspecified) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
}

/**
 * «Заправки (N)» и «+ Заправка». У открытого листа заправку можно нажать — откроется её форма;
 * у закрытого кнопки нет и строки не нажимаются.
 */
@Composable
private fun RefuelingsSection(sheet: FuelSheet, isBusy: Boolean, actions: SheetCardActions) {
    val refuelings = sheet.refuelings
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (refuelings.isNotEmpty()) {
            Text(
                text = stringResource(R.string.sheet_refuelings, refuelings.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.weight(1f))
        if (!sheet.isClosed) {
            OutlinedButton(
                onClick = actions.onAddRefueling,
                enabled = !isBusy,
                modifier = Modifier.testTag(SheetsFeedTestTags.addRefueling(sheet.id)),
            ) {
                Text(stringResource(R.string.sheet_add_refueling))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(start = 12.dp)) {
        refuelings.forEach { refueling ->
            val onClick = if (sheet.isClosed || isBusy) null else ({ actions.onRefuelingClick(refueling) })
            RefuelingRow(refueling, onClick)
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
            .padding(vertical = 6.dp)
            .testTag(SheetsFeedTestTags.refueling(refueling.id)),
    ) {
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
            textAlign = TextAlign.End,
        )
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

/**
 * Почему расход не посчитан; null — объяснять нечего (например, пробег 0 км).
 * Открытый лист — «появится после закрытия»; закрытый без фактического остатка (старые листы,
 * до того как остаток стал обязательным) — «не указан фактический остаток».
 */
internal fun consumptionHint(sheet: FuelSheet): UiText? = when {
    sheet.calc.mileageKm == null -> UiText.Resource(R.string.sheet_consumption_pending)
    sheet.fuelEndActualL == null -> UiText.Resource(
        if (sheet.isClosed) R.string.sheet_consumption_no_actual else R.string.sheet_consumption_pending,
    )
    else -> null
}

@PreviewLightDark
@Composable
private fun SheetCardClosedPreview() {
    FuelTrackerTheme {
        Surface {
            SheetCard(
                sheet = previewClosedSheet(),
                isExpanded = false,
                isBusy = false,
                actions = SheetCardActions(),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun SheetCardClosedExpandedPreview() {
    FuelTrackerTheme {
        Surface {
            SheetCard(
                sheet = previewClosedSheet(),
                isExpanded = true,
                isBusy = false,
                actions = SheetCardActions(),
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
                isExpanded = true,
                isBusy = false,
                actions = SheetCardActions(),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
