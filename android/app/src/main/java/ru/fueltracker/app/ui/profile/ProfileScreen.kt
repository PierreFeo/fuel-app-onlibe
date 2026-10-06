package ru.fueltracker.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fueltracker.app.BuildConfig
import ru.fueltracker.app.R
import ru.fueltracker.app.data.local.AppMode
import ru.fueltracker.app.data.local.db.SyncEntity
import ru.fueltracker.app.data.repository.RejectedRecord
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.common.LoadingView
import ru.fueltracker.app.ui.common.PhoneFormat
import ru.fueltracker.app.ui.common.SnackbarEffect
import ru.fueltracker.app.ui.theme.FuelTrackerTheme
import java.time.Instant

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    /** Гость: «Войти и сохранить данные на сервере». */
    onSignIn: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProfileContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onSignIn = onSignIn,
        appVersion = BuildConfig.VERSION_NAME,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileContent(
    state: ProfileUiState,
    onEvent: (ProfileEvent) -> Unit,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarEffect(state.snackbar, snackbarHostState) { onEvent(ProfileEvent.SnackbarShown) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (state.isLoading) LoadingView() else ProfileDetails(state, appVersion, onEvent, onSignIn)
        }
    }

    if (state.rejected.isNotEmpty()) {
        RejectedDialog(state.rejected) { onEvent(ProfileEvent.DismissRejected) }
    }

    if (state.confirmLogout) {
        AlertDialog(
            onDismissRequest = { onEvent(ProfileEvent.DismissLogout) },
            title = { Text(stringResource(R.string.profile_logout_title)) },
            text = { Text(stringResource(R.string.profile_logout_text)) },
            confirmButton = {
                TextButton(onClick = { onEvent(ProfileEvent.ConfirmLogout) }) {
                    Text(stringResource(R.string.profile_logout))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(ProfileEvent.DismissLogout) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProfileDetails(
    state: ProfileUiState,
    appVersion: String,
    onEvent: (ProfileEvent) -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = { onEvent(ProfileEvent.NameChanged(it)) },
            label = { Text(stringResource(R.string.profile_name)) },
            singleLine = true,
            enabled = !state.isLoggingOut,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onEvent(ProfileEvent.SaveName) }),
            trailingIcon = {
                if (state.canSaveName) {
                    TextButton(onClick = { onEvent(ProfileEvent.SaveName) }, modifier = Modifier.testTag(ProfileTestTags.SAVE_NAME)) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ProfileTestTags.NAME),
        )
        if (state.isGuest) {
            GuestBlock(onSignIn)
        } else {
            state.phone?.let { InfoRow(label = stringResource(R.string.profile_phone), value = PhoneFormat.display(it)) }
            HorizontalDivider()
            SyncBlock(state, onEvent, onSignIn)
        }
        HorizontalDivider()
        InfoRow(label = stringResource(R.string.profile_version), value = appVersion)
        if (!state.isGuest) {
            Spacer(Modifier.height(16.dp))
            LogoutButton(state, onEvent)
        }
    }
}

/**
 * «Синхронизация»: когда была, сколько не отправлено, кнопка. Сессия истекла — вместо кнопки
 * «Войти снова» (данные на телефоне сохранены).
 */
@Composable
private fun SyncBlock(state: ProfileUiState, onEvent: (ProfileEvent) -> Unit, onSignIn: () -> Unit) {
    Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleMedium)
    Text(
        text = state.lastSyncAt?.let { stringResource(R.string.sync_last, Formatters.dateTime(it)) }
            ?: stringResource(R.string.sync_never),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(ProfileTestTags.LAST_SYNC),
    )
    Text(
        text = stringResource(R.string.sync_pending, state.pendingChanges),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(ProfileTestTags.PENDING),
    )
    if (state.isSessionExpired) {
        Text(
            text = stringResource(R.string.sync_session_expired),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Button(
            onClick = onSignIn,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ProfileTestTags.SIGN_IN_AGAIN),
        ) { Text(stringResource(R.string.sync_sign_in_again)) }
    } else {
        Button(
            onClick = { onEvent(ProfileEvent.Sync) },
            enabled = state.canSync,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag(ProfileTestTags.SYNC),
        ) {
            if (state.isSyncing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.sync_button))
            }
        }
    }
}

/** «Не отправлено»: какие записи сервер не принял и почему (остальное синхронизировано). */
@Composable
private fun RejectedDialog(rejected: List<RejectedRecord>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_rejected_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rejected.forEach { record ->
                    val entity = stringResource(
                        when (record.entity) {
                            SyncEntity.CAR -> R.string.sync_entity_car
                            SyncEntity.SHEET -> R.string.sync_entity_sheet
                            SyncEntity.REFUELING -> R.string.sync_entity_refueling
                            null -> R.string.sync_entity_unknown
                        },
                    )
                    Text(stringResource(R.string.sync_rejected_item, entity, record.message))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

/** У гостя: данные только на телефоне — и как их сохранить. */
@Composable
private fun GuestBlock(onSignIn: () -> Unit) {
    Text(
        text = stringResource(R.string.profile_guest_info),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = onSignIn,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProfileTestTags.SIGN_IN),
    ) {
        Text(stringResource(R.string.profile_sign_in))
    }
}

@Composable
private fun LogoutButton(state: ProfileUiState, onEvent: (ProfileEvent) -> Unit) {
    OutlinedButton(
        onClick = { onEvent(ProfileEvent.RequestLogout) },
        enabled = !state.isLoggingOut,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag(ProfileTestTags.LOGOUT),
    ) {
        if (state.isLoggingOut) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.profile_logout))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

object ProfileTestTags {
    const val NAME = "profile_name"
    const val SAVE_NAME = "profile_save_name"
    const val LOGOUT = "profile_logout"
    const val SIGN_IN = "profile_sign_in"
    const val SYNC = "profile_sync"
    const val SIGN_IN_AGAIN = "profile_sign_in_again"
    const val LAST_SYNC = "profile_last_sync"
    const val PENDING = "profile_pending"
}

@PreviewLightDark
@Composable
private fun ProfileContentPreview() {
    FuelTrackerTheme {
        ProfileContent(
            state = ProfileUiState(
                isLoading = false,
                mode = AppMode.ACCOUNT,
                phone = "+79991234567",
                savedName = "Иван Петров",
                name = "Иван Петрович",
                isLoggedIn = true,
                pendingChanges = 12,
                lastSyncAt = Instant.parse("2026-10-06T15:20:00Z"),
            ),
            onEvent = {},
            onBack = {},
            onSignIn = {},
            appVersion = "1.0",
        )
    }
}

@PreviewLightDark
@Composable
private fun ProfileContentGuestPreview() {
    FuelTrackerTheme {
        ProfileContent(
            state = ProfileUiState(isLoading = false, mode = AppMode.GUEST, savedName = "Иван", name = "Иван"),
            onEvent = {},
            onBack = {},
            onSignIn = {},
            appVersion = "1.0",
        )
    }
}

@PreviewLightDark
@Composable
private fun ProfileContentSessionExpiredPreview() {
    FuelTrackerTheme {
        ProfileContent(
            state = ProfileUiState(
                isLoading = false,
                mode = AppMode.ACCOUNT,
                isLoggedIn = false,
                phone = "+79991234567",
                savedName = "Иван",
                name = "Иван",
                pendingChanges = 3,
            ),
            onEvent = {},
            onBack = {},
            onSignIn = {},
            appVersion = "1.0",
        )
    }
}
