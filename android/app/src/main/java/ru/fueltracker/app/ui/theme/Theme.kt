package ru.fueltracker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Фон экрана — светло-серый, карточки — белые (в тёмной теме — на тон светлее фона)
private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = PrimaryLight,
    onSecondary = OnPrimaryLight,
    secondaryContainer = ChipLight,
    onSecondaryContainer = OnPrimaryContainerLight,
    tertiary = SoonLight,
    tertiaryContainer = SoonContainerLight,
    background = BackgroundLight,
    onBackground = InkLight,
    surface = BackgroundLight,
    onSurface = InkLight,
    surfaceVariant = ChipLight,
    onSurfaceVariant = MutedLight,
    surfaceContainerLowest = SurfaceCardLight,
    surfaceContainerLow = SurfaceCardLight,
    surfaceContainer = SurfaceCardLight,
    surfaceContainerHigh = SurfaceCardLight,
    surfaceContainerHighest = SurfaceCardLight,
    surfaceBright = SurfaceCardLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = ErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = InkLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = PrimaryDark,
    onSecondary = OnPrimaryDark,
    secondaryContainer = ChipDark,
    onSecondaryContainer = OnPrimaryContainerDark,
    tertiary = SoonDark,
    tertiaryContainer = SoonContainerDark,
    background = BackgroundDark,
    onBackground = InkDark,
    surface = BackgroundDark,
    onSurface = InkDark,
    surfaceVariant = ChipDark,
    onSurfaceVariant = MutedDark,
    surfaceContainerLowest = BackgroundDark,
    surfaceContainerLow = SurfaceCardDark,
    surfaceContainer = SurfaceCardDark,
    surfaceContainerHigh = SurfaceDialogDark,
    surfaceContainerHighest = SurfaceCardDark,
    surfaceBright = SurfaceDialogDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = ErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = InkDark,
)

/**
 * Скругления по прототипу: поля и меню — 12, «Закрыть месяц» и мелкие кнопки — 8,
 * карточки — 22, FAB — 18, диалоги и шторки — 28. Основные кнопки — 14 ([ButtonShape]).
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Основные кнопки во всю ширину («Сохранить», «Войти»). */
val ButtonShape = RoundedCornerShape(14.dp)

/** Цвета приложения, которых нет в Material 3. */
@Immutable
data class FuelColors(
    val consumptionNormal: Color,
    val consumptionOver: Color,
    /** Дорога месяца: последние дни — пора закрыть месяц. */
    val soon: Color,
    val soonContainer: Color,
    /** Карточка и шторки — поверхность поверх фона экрана. */
    val card: Color,
    /** Госномер: фон, буквы и рамка (в тёмной теме — светлая табличка). */
    val plate: Color,
    val plateInk: Color,
    val plateLine: Color,
)

private val LightFuelColors = FuelColors(
    consumptionNormal = ConsumptionNormalLight,
    consumptionOver = ConsumptionOverLight,
    soon = SoonLight,
    soonContainer = SoonContainerLight,
    card = SurfaceCardLight,
    plate = Color.White,
    plateInk = InkLight,
    plateLine = InkLight,
)

private val DarkFuelColors = FuelColors(
    consumptionNormal = ConsumptionNormalDark,
    consumptionOver = ConsumptionOverDark,
    soon = SoonDark,
    soonContainer = SoonContainerDark,
    card = SurfaceCardDark,
    plate = InkDark,
    plateInk = BackgroundDark,
    plateLine = InkDark,
)

private val LocalFuelColors = staticCompositionLocalOf { LightFuelColors }

/** Доступ к цветам приложения: `MaterialTheme.fuelColors.consumptionOver`. */
val MaterialTheme.fuelColors: FuelColors
    @Composable get() = LocalFuelColors.current

// Динамические цвета Android 12+ не используем: приложение выглядит одинаково на всех телефонах
@Composable
fun FuelTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalFuelColors provides if (darkTheme) DarkFuelColors else LightFuelColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content,
        )
    }
}
