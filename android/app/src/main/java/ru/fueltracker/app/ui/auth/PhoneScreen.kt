package ru.fueltracker.app.ui.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fueltracker.app.R
import ru.fueltracker.app.ui.common.PhoneVisualTransformation
import ru.fueltracker.app.ui.common.SnackbarEffect
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.asString
import ru.fueltracker.app.ui.theme.FuelTrackerTheme

@Composable
fun PhoneScreen(
    onCodeSent: (CodeSent) -> Unit,
    onPasswordLogin: (phone: String?) -> Unit,
    viewModel: PhoneViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.codeSent) {
        state.codeSent?.let {
            viewModel.onEvent(PhoneEvent.CodeSentHandled)
            onCodeSent(it)
        }
    }
    PhoneContent(
        state = state,
        onEvent = viewModel::onEvent,
        onPasswordLogin = { onPasswordLogin(state.digits.ifEmpty { null }) },
    )
}

@Composable
fun PhoneContent(
    state: PhoneUiState,
    onEvent: (PhoneEvent) -> Unit,
    onPasswordLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarEffect(state.snackbar, snackbarHostState) { onEvent(PhoneEvent.SnackbarShown) }

    AuthLayout(
        title = stringResource(R.string.phone_title),
        subtitle = stringResource(R.string.phone_subtitle),
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        PhoneField(
            digits = state.digits,
            onValueChange = { onEvent(PhoneEvent.PhoneChanged(it)) },
            error = state.error,
            enabled = !state.isLoading,
            imeAction = ImeAction.Done,
            onImeAction = { onEvent(PhoneEvent.Submit) },
        )
        ProgressButton(
            text = stringResource(R.string.phone_get_code),
            isLoading = state.isLoading,
            enabled = state.canSubmit,
            onClick = { onEvent(PhoneEvent.Submit) },
            modifier = Modifier.testTag(PhoneTestTags.SUBMIT),
        )
        TextButton(
            onClick = onPasswordLogin,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.phone_password_login))
        }
    }
}

/** Поле номера с маской `+7 (___) ___-__-__` — общее для PhoneScreen и PasswordLoginScreen. */
@Composable
internal fun PhoneField(
    digits: String,
    onValueChange: (String) -> Unit,
    error: UiText?,
    enabled: Boolean,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = digits,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.phone_label)) },
        placeholder = { Text(stringResource(R.string.phone_placeholder)) },
        visualTransformation = PhoneVisualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { { Text(it.asString()) } },
        modifier = modifier
            .fillMaxWidth()
            .testTag(PhoneTestTags.PHONE_FIELD),
    )
}

object PhoneTestTags {
    const val PHONE_FIELD = "phone_field"
    const val SUBMIT = "phone_submit"
}

@PreviewLightDark
@Composable
private fun PhoneContentPreview() {
    FuelTrackerTheme {
        PhoneContent(state = PhoneUiState(digits = "999123"), onEvent = {}, onPasswordLogin = {})
    }
}

@PreviewLightDark
@Composable
private fun PhoneContentErrorPreview() {
    FuelTrackerTheme {
        PhoneContent(
            state = PhoneUiState(
                digits = "9991234567",
                error = UiText.Resource(R.string.error_phone_not_allowed),
            ),
            onEvent = {},
            onPasswordLogin = {},
        )
    }
}
