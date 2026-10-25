package ru.fueltracker.app.ui.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fueltracker.app.R
import ru.fueltracker.app.ui.common.SnackbarEffect
import ru.fueltracker.app.ui.common.asString
import ru.fueltracker.app.ui.profile.PasswordField
import ru.fueltracker.app.ui.profile.PasswordForm
import ru.fueltracker.app.ui.theme.FuelTrackerTheme

@Composable
fun SetupScreen(
    onSaved: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.onEvent(SetupEvent.SavedHandled)
            onSaved()
        }
    }
    SetupContent(state = state, onEvent = viewModel::onEvent)
}

@Composable
fun SetupContent(
    state: SetupUiState,
    onEvent: (SetupEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarEffect(state.snackbar, snackbarHostState) { onEvent(SetupEvent.SnackbarShown) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val password = state.password

    AuthLayout(
        title = stringResource(if (state.isGuest) R.string.name_title else R.string.setup_title),
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = { onEvent(SetupEvent.NameChanged(it)) },
            label = { Text(stringResource(R.string.name_label)) },
            singleLine = true,
            enabled = !state.isSaving,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = if (password == null) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onDone = { onEvent(SetupEvent.Submit) }),
            isError = state.error != null,
            supportingText = state.error?.let { { Text(it.asString()) } },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SetupTestTags.NAME_FIELD),
        )
        if (password != null) {
            Text(stringResource(R.string.setup_password_block), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.setup_password_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PasswordField(
                value = password.new,
                onValueChange = { onEvent(SetupEvent.PasswordChanged(it)) },
                label = stringResource(R.string.password_label),
                error = password.newError,
                supporting = stringResource(R.string.password_rule),
                visible = passwordVisible,
                onToggleVisible = { passwordVisible = !passwordVisible },
                enabled = !state.isSaving,
                testTag = SetupTestTags.PASSWORD,
            )
            PasswordField(
                value = password.repeat,
                onValueChange = { onEvent(SetupEvent.PasswordRepeatChanged(it)) },
                label = stringResource(R.string.password_repeat),
                error = password.repeatError,
                visible = passwordVisible,
                onToggleVisible = { passwordVisible = !passwordVisible },
                enabled = !state.isSaving,
                testTag = SetupTestTags.PASSWORD_REPEAT,
            )
            password.error?.let {
                Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        ProgressButton(
            text = stringResource(if (state.isGuest) R.string.name_continue else R.string.action_save),
            isLoading = state.isSaving,
            enabled = state.canSubmit,
            onClick = { onEvent(SetupEvent.Submit) },
            modifier = Modifier.testTag(SetupTestTags.SUBMIT),
        )
    }
}

object SetupTestTags {
    const val NAME_FIELD = "setup_name_field"
    const val PASSWORD = "setup_password"
    const val PASSWORD_REPEAT = "setup_password_repeat"
    const val SUBMIT = "setup_submit"
}

@PreviewLightDark
@Composable
private fun SetupContentPreview() {
    FuelTrackerTheme {
        SetupContent(
            state = SetupUiState(name = "Иван Петров", password = PasswordForm(needsCurrent = false, new = "12345678")),
            onEvent = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun SetupContentGuestPreview() {
    FuelTrackerTheme {
        SetupContent(state = SetupUiState(isGuest = true, name = "Иван"), onEvent = {})
    }
}
