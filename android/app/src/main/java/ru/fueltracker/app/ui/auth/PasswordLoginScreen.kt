package ru.fueltracker.app.ui.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.LoginResult
import ru.fueltracker.app.ui.common.SnackbarEffect
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.asString
import ru.fueltracker.app.ui.theme.FuelTrackerTheme

@Composable
fun PasswordLoginScreen(
    onLoggedIn: (LoginResult) -> Unit,
    onSmsLogin: () -> Unit,
    viewModel: PasswordLoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.loggedIn) {
        state.loggedIn?.let {
            viewModel.onEvent(PasswordLoginEvent.LoginHandled)
            onLoggedIn(it)
        }
    }
    PasswordLoginContent(state = state, onEvent = viewModel::onEvent, onSmsLogin = onSmsLogin)
}

@Composable
fun PasswordLoginContent(
    state: PasswordLoginUiState,
    onEvent: (PasswordLoginEvent) -> Unit,
    onSmsLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarEffect(state.snackbar, snackbarHostState) { onEvent(PasswordLoginEvent.SnackbarShown) }

    AuthLayout(
        title = stringResource(R.string.password_title),
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        PhoneField(
            digits = state.digits,
            onValueChange = { onEvent(PasswordLoginEvent.PhoneChanged(it)) },
            error = null,
            enabled = !state.isLoading,
            imeAction = ImeAction.Next,
            onImeAction = {},
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = { onEvent(PasswordLoginEvent.PasswordChanged(it)) },
            label = { Text(stringResource(R.string.password_label)) },
            singleLine = true,
            enabled = !state.isLoading,
            visualTransformation = if (state.isPasswordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onEvent(PasswordLoginEvent.Submit) }),
            trailingIcon = {
                IconButton(onClick = { onEvent(PasswordLoginEvent.TogglePasswordVisibility) }) {
                    Icon(
                        painter = painterResource(
                            if (state.isPasswordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                        ),
                        contentDescription = stringResource(
                            if (state.isPasswordVisible) R.string.password_hide else R.string.password_show,
                        ),
                    )
                }
            },
            isError = state.error != null,
            supportingText = {
                Text(state.error?.asString() ?: stringResource(R.string.password_hint))
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PasswordLoginTestTags.PASSWORD_FIELD),
        )
        ProgressButton(
            text = stringResource(R.string.password_login),
            isLoading = state.isLoading,
            enabled = state.canSubmit,
            onClick = { onEvent(PasswordLoginEvent.Submit) },
            modifier = Modifier.testTag(PasswordLoginTestTags.SUBMIT),
        )
        TextButton(onClick = onSmsLogin, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.password_sms_login), style = MaterialTheme.typography.labelLarge)
        }
    }
}

object PasswordLoginTestTags {
    const val PASSWORD_FIELD = "password_field"
    const val SUBMIT = "password_submit"
}

@PreviewLightDark
@Composable
private fun PasswordLoginContentPreview() {
    FuelTrackerTheme {
        PasswordLoginContent(
            state = PasswordLoginUiState(digits = "9991234567", password = "k7Fm2xQp9a"),
            onEvent = {},
            onSmsLogin = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun PasswordLoginContentErrorPreview() {
    FuelTrackerTheme {
        PasswordLoginContent(
            state = PasswordLoginUiState(
                digits = "9991234567",
                password = "wrong",
                error = UiText.Resource(R.string.error_invalid_credentials),
            ),
            onEvent = {},
            onSmsLogin = {},
        )
    }
}
