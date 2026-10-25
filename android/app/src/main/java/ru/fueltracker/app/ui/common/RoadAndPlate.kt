package ru.fueltracker.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.fueltracker.app.R
import ru.fueltracker.app.ui.theme.FuelTrackerTheme
import ru.fueltracker.app.ui.theme.fuelColors

private val CarWidth = 34.dp
private val CarHeight = 18.dp
private val FlagSpace = 14.dp

/**
 * Дорога с машинкой (дорога месяца на карточке, шаги входа, загрузка). [fraction] — положение
 * машинки от 0 (старт) до 1 (флажок). [animateFromStart] — при первом показе машинка плавно
 * доезжает от старта; если анимации в системе отключены, Compose сразу ставит её на место.
 */
@Composable
fun RoadTrack(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    animateFromStart: Boolean = true,
    description: String? = null,
) {
    // Без animateFromStart положение берётся как есть — им управляет вызывающий (шаги входа, загрузка)
    val position = remember { Animatable(0f) }
    if (animateFromStart) {
        LaunchedEffect(fraction) {
            position.animateTo(fraction.coerceIn(0f, 1f), tween(durationMillis = 900, easing = FastOutSlowInEasing))
        }
    }
    val shown = if (animateFromStart) position.value else fraction.coerceIn(0f, 1f)
    val tint by animateColorAsState(color, label = "road")
    val lane = MaterialTheme.colorScheme.outlineVariant
    val flag = MaterialTheme.colorScheme.onSurfaceVariant
    val details = MaterialTheme.fuelColors.card
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        // Центр машинки — от половины её ширины до «ширина − половина», чтобы она не вылезала за край
        val road = maxWidth - FlagSpace
        val carCenter = CarWidth / 2 + (road - CarWidth) * shown
        Canvas(modifier = Modifier.fillMaxSize()) {
            val y = size.height - 8.dp.toPx()
            val stroke = 4.dp.toPx()
            val end = size.width - FlagSpace.toPx()
            drawLine(lane, Offset(0f, y), Offset(end, y), stroke, StrokeCap.Round)
            drawLine(tint, Offset(0f, y), Offset(carCenter.toPx(), y), stroke, StrokeCap.Round)
        }
        Icon(
            painter = painterResource(R.drawable.ic_flag_finish),
            contentDescription = null,
            tint = flag,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 4.dp)
                .size(width = 16.dp, height = 22.dp)
                .offset(x = 2.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = carCenter - CarWidth / 2, y = (-7).dp)
                .size(CarWidth, CarHeight),
        ) {
            Icon(painterResource(R.drawable.ic_car_body), contentDescription = null, tint = tint)
            Icon(painterResource(R.drawable.ic_car_details), contentDescription = null, tint = details)
        }
    }
}

/**
 * Госномер как российский знак: номер и регион с RUS (07_UI_SCREENS.md, «Общие правила UI»).
 * Номер не по образцу (например, без региона) — одной частью.
 */
@Composable
fun PlateBadge(plate: String, modifier: Modifier = Modifier) {
    val (number, region) = splitPlate(plate)
    val line = MaterialTheme.fuelColors.plateLine
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.fuelColors.plate, shape)
            .border(1.5.dp, line, shape)
            .clearAndSetSemantics { contentDescription = plate },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number,
            color = MaterialTheme.fuelColors.plateInk,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (region != null) {
            Box(
                Modifier
                    .width(1.5.dp)
                    .fillMaxHeight()
                    .background(line),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(region, color = MaterialTheme.fuelColors.plateInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 11.sp)
                Text("RUS", color = MaterialTheme.fuelColors.plateInk, fontSize = 6.sp, lineHeight = 6.sp, letterSpacing = 0.5.sp)
            }
        }
    }
}

private val PlatePattern = Regex("""^(\p{L}\d{3}\p{L}{2})(\d{2,3})$""")

/** «А123ВС77» → («А123ВС», «77»); не по образцу — (номер целиком, null). */
fun splitPlate(plate: String): Pair<String, String?> {
    val clean = plate.filterNot { it.isWhitespace() }
    val match = PlatePattern.find(clean) ?: return plate.trim() to null
    return match.groupValues[1] to match.groupValues[2]
}

@PreviewLightDark
@Composable
private fun RoadAndPlatePreview() {
    FuelTrackerTheme {
        Surface(color = MaterialTheme.fuelColors.card) {
            Column(Modifier.padding(16.dp)) {
                PlateBadge("А123ВС77")
                RoadTrack(fraction = 0.8f, color = MaterialTheme.colorScheme.primary, animateFromStart = false)
                RoadTrack(fraction = 0.95f, color = MaterialTheme.fuelColors.soon, animateFromStart = false)
            }
        }
    }
}
