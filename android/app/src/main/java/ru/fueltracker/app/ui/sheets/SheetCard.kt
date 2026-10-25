package ru.fueltracker.app.ui.sheets

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.calc.MonthProgress
import ru.fueltracker.app.domain.calc.MonthState
import ru.fueltracker.app.domain.model.ConsumptionStatus
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetWarning
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.common.MoreButton
import ru.fueltracker.app.ui.common.RoadTrack
import ru.fueltracker.app.ui.theme.FuelTrackerTheme
import ru.fueltracker.app.ui.theme.NumberStyle
import ru.fueltracker.app.ui.theme.fuelColors
import java.time.LocalDate
import java.time.YearMonth

/** Что можно сделать с листом из карточки. */
class SheetCardActions(
    val onSeasonClick: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onReopen: () -> Unit = {},
    val onDelete: () -> Unit = {},
    /** ⛽ — открыть шторку со списком заправок. */
    val onOpenRefuelings: () -> Unit = {},
    /** ⚠ — диалог с текстами предупреждений. */
    val onWarningsClick: () -> Unit = {},
)

/**
 * Общий размер шрифта месяца для всех карточек ленты: если «Сентябрь 2026» с кнопками не помещается
 * в строку, шрифт уменьшается у всех карточек сразу, но не меньше 13 sp (07_UI_SCREENS.md).
 */
@Stable
class MonthTitleFit {
    var size by mutableFloatStateOf(MAX_SIZE)
        private set

    fun shrink() {
        if (size > MIN_SIZE) size = (size - 0.5f).coerceAtLeast(MIN_SIZE)
    }

    private companion object {
        const val MAX_SIZE = 19f
        const val MIN_SIZE = 13f
    }
}

/**
 * Карточка ЛУТ (07_UI_SCREENS.md, SheetCard): шапка в одну строку, пробег и расход, у открытого
 * листа — дорога месяца. Все итоги берутся из `calc`. [isBusy] — по листу идёт действие.
 */
@Composable
fun SheetCard(
    sheet: FuelSheet,
    isBusy: Boolean,
    actions: SheetCardActions,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    titleFit: MonthTitleFit = remember { MonthTitleFit() },
) {
    Card(modifier = modifier.fillMaxWidth().testTag(SheetsFeedTestTags.card(sheet.id))) {
        // Мелкие кнопки карточки без обязательных 48 dp вокруг — иначе карточка не становится ниже
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 14.dp)) {
                SheetHeader(sheet, isBusy, actions, titleFit)
                Spacer(Modifier.height(8.dp))
                OdometerAndConsumption(sheet, isBusy, actions)
                if (!sheet.isClosed) {
                    MonthTrip(MonthProgress.of(sheet.year, sheet.month, today), sheet.year, sheet.month)
                }
                HorizontalDivider(modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
                SummaryRow(sheet)
            }
        }
    }
}

/** ☀️/❄️ месяц ⚠ … (⛽ N) (•••) — одна строка. */
@Composable
private fun SheetHeader(sheet: FuelSheet, isBusy: Boolean, actions: SheetCardActions, titleFit: MonthTitleFit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
    ) {
        SeasonButton(sheet, isBusy, actions.onSeasonClick)
        // Месяц и ⚠ занимают всё место до кнопок; месяц сжимается, только если не помещается
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
                text = Formatters.month(sheet.year, sheet.month),
                style = MaterialTheme.typography.titleLarge,
                fontSize = titleFit.size.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                onTextLayout = { if (it.hasVisualOverflow) titleFit.shrink() },
                modifier = Modifier.weight(1f, fill = false),
            )
            if (sheet.calc.warnings.isNotEmpty()) {
                val warnings = stringResource(R.string.sheet_has_warnings)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(role = Role.Button, onClickLabel = warnings, onClick = actions.onWarningsClick)
                        .semantics { contentDescription = warnings }
                        .testTag(SheetsFeedTestTags.warnings(sheet.id)),
                ) {
                    Text(text = "⚠", color = MaterialTheme.colorScheme.error, fontSize = 16.sp)
                }
            }
        }
        RefuelingsButton(sheet, actions.onOpenRefuelings)
        SheetMenu(sheet, isBusy, actions)
    }
}

/** Значок сезона перед месяцем: открытый лист — переключает сезон; закрытый — бледный. */
@Composable
private fun SeasonButton(sheet: FuelSheet, isBusy: Boolean, onClick: () -> Unit) {
    val winter = sheet.season == Season.WINTER
    val name = stringResource(if (winter) R.string.sheet_season_name_winter else R.string.sheet_season_name_summer)
    val toggle = stringResource(R.string.sheet_season_toggle)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clickable(enabled = !isBusy, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = "$name. $toggle" }
            .testTag(SheetsFeedTestTags.season(sheet.id)),
    ) {
        Text(
            text = stringResource(if (winter) R.string.sheet_season_icon_winter else R.string.sheet_season_icon_summer),
            fontSize = 17.sp,
            modifier = Modifier.alpha(if (sheet.isClosed) 0.5f else 1f),
        )
    }
}

/** «⛽ N» — тональная капсула; открывает шторку со списком заправок. */
@Composable
private fun RefuelingsButton(sheet: FuelSheet, onClick: () -> Unit) {
    val count = sheet.refuelings.size
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .height(30.dp)
            .testTag(SheetsFeedTestTags.refuelingsButton(sheet.id)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(start = 8.dp, end = if (count > 0) 10.dp else 8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_fuel_pump),
                contentDescription = stringResource(R.string.sheet_refuelings, count),
                modifier = Modifier.size(18.dp),
            )
            if (count > 0) {
                // Число уже есть в описании значка — TalkBack не повторяет его
                Text(
                    text = count.toString(),
                    style = NumberStyle,
                    fontSize = 14.sp,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

/** Закрытый лист — «Переоткрыть»; лист без заправок — «Удалить». */
@Composable
private fun SheetMenu(sheet: FuelSheet, isBusy: Boolean, actions: SheetCardActions) {
    val canDelete = sheet.refuelings.isEmpty()
    if (!sheet.isClosed && !canDelete) return
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(start = 2.dp)) {
        MoreButton(
            onClick = { open = true },
            enabled = !isBusy,
            contentDescription = stringResource(R.string.action_more),
            modifier = Modifier.testTag(SheetsFeedTestTags.menu(sheet.id)),
        )
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
 * «Начало / Конец» слева, «Расход» справа. Закрытый лист — 🔒 сразу за пробегом на конец;
 * открытый — вместо «—» кнопка «Закрыть месяц».
 */
@Composable
private fun OdometerAndConsumption(sheet: FuelSheet, isBusy: Boolean, actions: SheetCardActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(IntrinsicSize.Min),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OdometerRow(stringResource(R.string.sheet_month_start_short)) { Mileage(Formatters.km(sheet.odometerStartKm)) }
            OdometerRow(stringResource(R.string.sheet_month_end_short)) {
                val end = sheet.odometerEndKm
                when {
                    !sheet.isClosed && end == null -> CloseMonthButton(sheet, isBusy, actions.onClose)
                    else -> {
                        Mileage(end?.let { Formatters.km(it) } ?: stringResource(R.string.sheet_no_value))
                        if (sheet.isClosed) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lock),
                                contentDescription = stringResource(R.string.sheet_closed),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(15.dp),
                            )
                        }
                    }
                }
            }
        }
        VerticalDivider(
            thickness = 2.dp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 10.dp),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(min = 82.dp),
        ) {
            Text(
                text = stringResource(R.string.sheet_consumption),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            val perHundred = sheet.calc.actualLPer100km
            Text(
                text = perHundred?.let { Formatters.consumption(it) } ?: stringResource(R.string.sheet_no_value),
                style = MaterialTheme.typography.titleLarge,
                fontSize = 21.sp,
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
private fun OdometerRow(label: String, value: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 28.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        value()
    }
}

/** Пробег на начало и конец — округлый плотный Nunito: цифры видно сразу. */
@Composable
private fun Mileage(text: String) {
    Text(text = text, style = NumberStyle, fontSize = 17.sp)
}

@Composable
private fun CloseMonthButton(sheet: FuelSheet, isBusy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !isBusy,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier
            .height(28.dp)
            .testTag(SheetsFeedTestTags.closeButton(sheet.id)),
    ) {
        Text(stringResource(R.string.sheet_close_month), style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
    }
}

/**
 * Дорога месяца: машинка едет от 1-го числа к флажку по сегодняшней дате, за 3 дня до конца —
 * жёлтая с подсказкой, после конца месяца — красная (docs/06_BUSINESS_RULES.md, «Прогресс месяца»).
 */
@Composable
private fun MonthTrip(progress: MonthProgress, year: Int, month: Int) {
    val color = when (progress.state) {
        MonthState.UPCOMING, MonthState.ON_TRACK -> MaterialTheme.colorScheme.primary
        MonthState.SOON -> MaterialTheme.fuelColors.soon
        MonthState.LATE -> MaterialTheme.colorScheme.error
    }
    val ink = MaterialTheme.colorScheme.onSurface
    val sheetMonth = YearMonth.of(year, month)
    val nextMonth = sheetMonth.plusMonths(1)
    val headline = when (progress.state) {
        MonthState.UPCOMING -> buildAnnotatedString {
            append(stringResource(R.string.trip_upcoming, Formatters.dayMonthLong(sheetMonth.atDay(1))))
        }
        MonthState.LATE -> buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = ink)) {
                append(stringResource(R.string.trip_month_over, Formatters.monthName(month).replaceFirstChar { it.titlecase() }))
            }
        }
        else -> {
            val day = checkNotNull(progress.day)
            val rest = if (progress.daysLeft == 0) {
                stringResource(R.string.trip_last_day)
            } else {
                stringResource(R.string.trip_left, pluralStringResource(R.plurals.trip_days, progress.daysLeft, progress.daysLeft))
            }
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = ink)) {
                    append(Formatters.dayMonthLong(sheetMonth.atDay(day)))
                }
                append(", ")
                append(rest)
            }
        }
    }
    val passed = progress.day ?: if (progress.state == MonthState.LATE) progress.daysInMonth else 0
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(text = headline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RoadTrack(
            fraction = progress.fraction,
            color = color,
            description = stringResource(R.string.trip_description, passed, progress.daysInMonth),
        )
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 14.dp)
                .clearAndSetSemantics {},
        ) {
            Text(Formatters.dayMonthShort(1, month), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Formatters.dayMonthShort(progress.daysInMonth, month), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val alert = when (progress.state) {
            MonthState.SOON -> stringResource(R.string.trip_soon_alert) to MaterialTheme.fuelColors.soonContainer
            MonthState.LATE -> stringResource(R.string.trip_late_alert, Formatters.monthName(month), Formatters.monthName(nextMonth.monthValue)) to
                MaterialTheme.colorScheme.errorContainer
            else -> null
        }
        if (alert != null) {
            Surface(
                color = alert.second,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Text(
                    text = alert.first,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
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
    // Средняя колонка шире: «Остаток в баке» помещается в одну строку и на узком экране
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            modifier = Modifier.weight(1.25f),
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
            softWrap = false,
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
                today = LocalDate.of(2026, 8, 25),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun SheetCardSoonPreview() {
    FuelTrackerTheme {
        Surface {
            SheetCard(
                sheet = previewOpenSheet(),
                isBusy = false,
                actions = SheetCardActions(),
                today = LocalDate.of(2026, 8, 30),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
