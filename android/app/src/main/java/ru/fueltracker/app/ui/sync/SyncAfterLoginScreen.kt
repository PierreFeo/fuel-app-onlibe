package ru.fueltracker.app.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fueltracker.app.R
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.asString
import ru.fueltracker.app.ui.theme.FuelTrackerTheme

/** Полноэкранное «Загружаем ваши данные…» после входа (07_UI_SCREENS.md, SyncAfterLogin). */
@Composable
fun SyncAfterLoginScreen(
    onDone: () -> Unit,
    viewModel: SyncAfterLoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }
    SyncAfterLoginContent(state = state, onEvent = viewModel::onEvent)
}

@Composable
fun SyncAfterLoginContent(
    state: SyncAfterLoginUiState,
    onEvent: (SyncAfterLoginEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val error = state.error
            if (error == null) {
                CircularProgressIndicator()
                Text(stringResource(R.string.sync_after_login_loading), style = MaterialTheme.typography.titleMedium)
            } else {
                Text(
                    text = stringResource(R.string.sync_after_login_failed),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = error.asString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.sync_after_login_failed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = { onEvent(SyncAfterLoginEvent.Retry) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SyncAfterLoginTestTags.RETRY),
                ) { Text(stringResource(R.string.action_retry)) }
                OutlinedButton(
                    onClick = { onEvent(SyncAfterLoginEvent.Continue) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SyncAfterLoginTestTags.CONTINUE),
                ) { Text(stringResource(R.string.action_continue)) }
            }
        }
    }
}

object SyncAfterLoginTestTags {
    const val RETRY = "sync_after_login_retry"
    const val CONTINUE = "sync_after_login_continue"
}

@PreviewLightDark
@Composable
private fun SyncAfterLoginLoadingPreview() {
    FuelTrackerTheme { SyncAfterLoginContent(state = SyncAfterLoginUiState(), onEvent = {}) }
}

@PreviewLightDark
@Composable
private fun SyncAfterLoginErrorPreview() {
    FuelTrackerTheme {
        SyncAfterLoginContent(
            state = SyncAfterLoginUiState(isSyncing = false, error = UiText.Resource(R.string.sync_failed)),
            onEvent = {},
        )
    }
}
