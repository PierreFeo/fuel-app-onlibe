package ru.fueltracker.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.fueltracker.app.R
import ru.fueltracker.app.ui.common.RoadTrack
import ru.fueltracker.app.ui.theme.ButtonShape

/** Шаг входа по SMS — машинка на дороге показывает, где пользователь сейчас. */
enum class AuthStep(val fraction: Float) { PHONE(0f), CODE(0.5f), SETUP(1f) }

/** Общий вид экранов входа: дорога-шаги, заголовок, подзаголовок, поля; Snackbar внизу. */
@Composable
internal fun AuthLayout(
    title: String,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    step: AuthStep? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (step != null) {
                AuthSteps(step)
            } else {
                Spacer(Modifier.height(24.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MaterialTheme.typography.headlineMedium)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

/** «Номер → Код → Настройка»: это последовательность, поэтому дорога здесь уместна. */
@Composable
private fun AuthSteps(step: AuthStep) {
    val labels = listOf(R.string.auth_step_phone, R.string.auth_step_code, R.string.auth_step_setup)
    val current = stringResource(labels[step.ordinal])
    val description = stringResource(R.string.auth_step_description, step.ordinal + 1, labels.size, current)
    Column(modifier = Modifier.padding(top = 8.dp)) {
        // Дорога не анимируется заново на каждом шаге: машинка сразу стоит на своём месте
        RoadTrack(
            fraction = step.fraction,
            color = MaterialTheme.colorScheme.primary,
            animateFromStart = false,
            description = description,
        )
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 14.dp)
                .clearAndSetSemantics {},
        ) {
            labels.forEachIndexed { index, res ->
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (index == step.ordinal) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (index == step.ordinal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Основная кнопка формы; во время запроса вместо текста — индикатор. */
@Composable
internal fun ProgressButton(
    text: String,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        shape = ButtonShape,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Text(text, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
