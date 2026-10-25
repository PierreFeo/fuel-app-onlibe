package ru.fueltracker.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ru.fueltracker.app.R

/** Круглая тональная кнопка «•••» (меню карточки ЛУТ и авто). */
@Composable
fun MoreButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.size(30.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_more_horiz),
            contentDescription = contentDescription,
            modifier = Modifier.padding(6.dp),
        )
    }
}

/** Небольшая плашка-капсула с текстом (тип топлива, «Выбран»). */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    iconRes: Int? = null,
) {
    Surface(shape = CircleShape, color = color, contentColor = contentColor, modifier = modifier.height(26.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = if (iconRes != null) 8.dp else 10.dp, end = 10.dp),
        ) {
            if (iconRes != null) {
                Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(15.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}
