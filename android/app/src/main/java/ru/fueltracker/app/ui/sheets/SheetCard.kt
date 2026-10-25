package ru.fueltracker.app.ui.sheets

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.ConsumptionStatus
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetWarning
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.theme.FuelTrackerTheme
import ru.fueltracker.app.ui.theme.fuelColors

/** Что можно сделать с листом из карточки. */
class SheetCardActions(
    val onSeasonClick: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onReopen: () -> Unit = {},
    val onDelete: () -> Unit = {},
    /** [≡] — открыть шторку со списком заправок. */
    val onOpenRefuelings: () -> Unit = {},
    /** ⚠ — диалог с текстами предупреждений. */
    val onWarningsClick: () -> Unit = {},
)

/**
 * Карточка ЛУТ: компактная сводка, не раскрывается (07_UI_SCREENS.md, SheetCard).
 * Все итоги берутся из `calc`. [isBusy] — по листу идёт действие, кнопки неактивны.
 */
@Composable
fun SheetCard(
    sheet: FuelSheet,
    isBusy: Boolean,
    actions: SheetCardActions,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().testTag(SheetsFeedTestTags.card(sheet.id))) {
        // Мелкие кнопки карточки без обязательных 48 dp вокруг — иначе карточка не становится ниже
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 10.dp)) {
                SheetHeader(sheet, isBusy, actions)
                Column(modifier = Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OdometerAndConsumption(sheet, isBusy, actions)
                    HorizontalDivider()
                    SummaryRow(sheet)
                }
            }
        }
    }
}

/** Месяц — своей строкой, целиком; ниже — сезон, 🔒, «≡ N» и меню. */
@Composable
private fun SheetHeader(sheet: FuelSheet, isBusy: Boolean, actions: SheetCardActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp, end = 8.dp),
    ) {
        Text(
            text = Formatters.month(sheet.year, sheet.month),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (sheet.calc.warnings.isNotEmpty()) {
            TextButton(
                onClick = actions.onWarningsClick,
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .height(32.dp)
                    .testTag(SheetsFeedTestTags.warnings(sheet.id)),
            ) {
                val warnings = stringResource(R.string.sheet_has_warnings)
                Text(text = "⚠", modifier = Modifier.semantics { contentDescription = warnings })
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(36.dp)) {
        // Нажатие переключает сезон; у закрытого листа иконка бледная, нажатие объясняет почему нельзя.
        // Сдвиг влево — чтобы текст сезона стоял ровно под месяцем
        TextButton(
            onClick = actions.onSeasonClick,
            enabled = !isBusy,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier
                .offset(x = (-8).dp)
                .height(32.dp)
                .testTag(SheetsFeedTestTags.season(sheet.id)),
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
        Spacer(Modifier.weight(1f))
        RefuelingsButton(sheet, actions.onOpenRefuelings)
        SheetMenu(sheet, isBusy, actions)
    }
}

/**
 * «≡ N» — открывает шторку со списком заправок. Спокойный серый цвет и число текстом,
 * а не бейджем: бейдж выглядел как важное уведомление.
 */
@Composable
private fun RefuelingsButton(sheet: FuelSheet, onClick: () -> Unit) {
    val count = sheet.refuelings.size
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier
            .height(36.dp)
            .testTag(SheetsFeedTestTags.refuelingsButton(sheet.id)),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_list),
            contentDescription = stringResource(R.string.sheet_refuelings, count),
            modifier = Modifier.size(20.dp),
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                // Число уже есть в описании значка — TalkBack не повторяет его
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clearAndSetSemantics {},
            )
        }
    }
}

/** Закрытый лист — «Переоткрыть»; лист без заправок — «Удалить». */
@Composable
private fun SheetMenu(sheet: FuelSheet, isBusy: Boolean, actions: SheetCardActions) {
    val canDelete = sheet.refuelings.isEmpty()
    if (!sheet.isClosed && !canDelete) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            enabled = !isBusy,
            modifier = Modifier
                .size(36.dp)
                .testTag(SheetsFeedTestTags.menu(sheet.id)),
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

/**
 * «Начало / Конец месяца» слева, «Расход» справа — как в старом приложении.
 * У открытого листа вместо «—» в конце месяца — кнопка «Закрыть месяц».
 */
@Composable
private fun OdometerAndConsumption(sheet: FuelSheet, isBusy: Boolean, actions: SheetCardActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(IntrinsicSize.Min),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LabeledValue(stringResource(R.string.sheet_month_start)) {
                Text(Formatters.km(sheet.odometerStartKm), style = MaterialTheme.typography.bodyLarge)
            }
            LabeledValue(stringResource(R.string.sheet_month_end)) {
                val end = sheet.odometerEndKm
                if (end != null || sheet.isClosed) {
                    Text(
                        text = end?.let { Formatters.km(it) } ?: stringResource(R.string.sheet_no_value),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    FilledTonalButton(
                        onClick = actions.onClose,
                        enabled = !isBusy,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag(SheetsFeedTestTags.closeButton(sheet.id)),
                    ) {
                        Text(stringResource(R.string.sheet_close_month), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        VerticalDivider(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(min = 88.dp),
        ) {
            Text(
                text = stringResource(R.string.sheet_consumption),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            val perHundred = sheet.calc.actualLPer100km
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = perHundred?.let { Formatters.consumption(it) } ?: stringResource(R.string.sheet_no_value),
                    style = MaterialTheme.typography.titleLarge,
                    color = consumptionColor(sheet.calc.consumptionStatus),
                    modifier = Modifier.testTag(SheetsFeedTestTags.consumption(sheet.id)),
                )
                if (perHundred != null) {
                    Text(
                        text = stringResource(R.string.sheet_consumption_unit),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
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
        Text(text = value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

/** Текст предупреждения листа. */
internal val SheetWarning.textRes: Int
    @StringRes get() = when (this) {
        SheetWarning.FUEL_END_NEGATIVE -> R.string.warning_fuel_end_negative
        SheetWarning.FUEL_END_OVER_TANK -> R.string.warning_fuel_end_over_tank
        SheetWarning.ODOMETER_GAP -> R.string.warning_odometer_gap
        SheetWarning.REFUELING_ODOMETER_OUT_OF_RANGE -> R.string.warning_refueling_odometer_out_of_range
    }

/** Цвет по `consumption_status`: NORMAL — зелёный, OVER — красный, null — обычный. */
@Composable
private fun consumptionColor(status: ConsumptionStatus?): Color = when (status) {
    ConsumptionStatus.NORMAL -> MaterialTheme.fuelColors.consumptionNormal
    ConsumptionStatus.OVER -> MaterialTheme.fuelColors.consumptionOver
    null -> MaterialTheme.colorScheme.onSurface
}

@PreviewLightDark
@Composable
private fun SheetCardClosedPreview() {
    FuelTrackerTheme {
        Surface {
            SheetCard(
                // Самый длинный месяц — проверка, что заголовок не обрезается
                sheet = previewClosedSheet().copy(month = 9),
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
                isBusy = false,
                actions = SheetCardActions(),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
