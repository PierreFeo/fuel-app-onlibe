package ru.fueltracker.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import ru.fueltracker.app.R

// Шрифты лежат в res/font (вариативные, OFL — лицензии в assets/licenses): работают без интернета.
// Вес вариативного шрифта задаётся FontVariation — API пока помечен экспериментальным
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Onest — весь текст интерфейса. */
val Onest = FontFamily(
    variable(R.font.onest, 400),
    variable(R.font.onest, 500),
    variable(R.font.onest, 600),
    variable(R.font.onest, 700),
)

/** Nunito — округлые цифры: пробег, расход, литры, суммы. */
val NumberFamily = FontFamily(
    variable(R.font.nunito, 600),
    variable(R.font.nunito, 700),
    variable(R.font.nunito, 800),
)

private val base = Typography()

private fun TextStyle.onest() = copy(fontFamily = Onest)

// Шкала Material 3, шрифт Onest; заголовки чуть плотнее
val Typography = Typography(
    displayLarge = base.displayLarge.onest(),
    displayMedium = base.displayMedium.onest(),
    displaySmall = base.displaySmall.onest(),
    headlineLarge = base.headlineLarge.onest().copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.onest().copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.onest().copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.onest().copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.onest().copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.onest().copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.onest(),
    bodyMedium = base.bodyMedium.onest(),
    bodySmall = base.bodySmall.onest(),
    labelLarge = base.labelLarge.onest().copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.onest().copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.onest(),
)

/** Цифры в карточке и списках: Nunito, жирный, одинаковая ширина цифр. */
val NumberStyle = TextStyle(
    fontFamily = NumberFamily,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum",
)
