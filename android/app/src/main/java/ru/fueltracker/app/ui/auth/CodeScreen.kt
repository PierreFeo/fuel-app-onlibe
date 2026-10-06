package ru.fueltracker.app.ui.auth

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.LoginResult
import ru.fueltracker.app.ui.common.PhoneFormat
import ru.fueltracker.app.ui.common.SnackbarEffect
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.asString
import ru.fueltracker.app.ui.common.formatCountdown
import ru.fueltracker.app.ui.theme.FuelTrackerTheme

@Composable
fun CodeScreen(
    onLoggedIn: (LoginResult) -> Unit,
    onChangePhone: () -> Unit,
    onPasswordLogin: (phone: String) -> Unit,
    viewModel: CodeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.loggedIn) {
        state.loggedIn?.let {
            viewModel.onEvent(CodeEvent.LoginHandled)
            onLoggedIn(it)
        }
    }
    CodeContent(
        state = state,
        onEvent = viewModel::onEvent,
        onChangePhone = onChangePhone,
        onPasswordLogin = { onPasswordLogin(state.phone) },
    )
}

@Composable
fun CodeContent(
    state: CodeUiState,
    onEvent: (CodeEvent) -> Unit,
    onChangePhone: () -> Unit,
    onPasswordLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarEffect(state.snackbar, snackbarHostState) { onEvent(CodeEvent.SnackbarShown) }

    AuthLayout(
        title = stringResource(R.string.code_title),
        subtitle = stringResource(R.string.code_subtitle, PhoneFormat.display(state.phone)),
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        CodeCells(
            code = state.code,
            onCodeChange = { onEvent(CodeEvent.CodeChanged(it)) },
            isError = state.error != null,
            enabled = !state.isVerifying,
        )
        Box(modifier = Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
            when {
                state.isVerifying -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                state.error != null -> Text(
                    text = state.error.asString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (state.resendSecondsLeft > 0) {
            Text(
                text = stringResource(R.string.code_resend_timer, formatCountdown(state.resendSecondsLeft)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            TextButton(
                onClick = { onEvent(CodeEvent.Resend) },
                enabled = state.canResend,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.code_resend))
            }
        }
        TextButton(onClick = onChangePhone, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.code_change_phone))
        }
        TextButton(onClick = onPasswordLogin, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.code_password_login))
        }
    }
}

/** 6 ячеек поверх одного невидимого поля: клавиатура, вставка и стирание работают как обычно. */
@Composable
private fun CodeCells(
    code: String,
    onCodeChange: (String) -> Unit,
    isError: Boolean,
    enabled: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val description = stringResource(R.string.code_input_description)

    BasicTextField(
        value = code,
        onValueChange = onCodeChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .semantics { contentDescription = description }
            .testTag(CodeTestTags.CODE_FIELD),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                repeat(CodeViewModel.CODE_LENGTH) { index ->
                    val isCurrent = enabled && index == code.length
                    val borderColor = when {
                        isError -> MaterialTheme.colorScheme.error
                        isCurrent -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .height(56.dp)
                            .border(
                                width = if (isCurrent) 2.dp else 1.dp,
                                color = borderColor,
                                shape = RoundedCornerShape(8.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = code.getOrNull(index)?.toString().orEmpty(),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
            }
        },
    )
}

object CodeTestTags {
    const val CODE_FIELD = "code_field"
}

@PreviewLightDark
@Composable
private fun CodeContentPreview() {
    FuelTrackerTheme {
        CodeContent(
            state = CodeUiState(phone = "+79991234567", code = "123", resendSecondsLeft = 42),
            onEvent = {},
            onChangePhone = {},
            onPasswordLogin = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun CodeContentErrorPreview() {
    FuelTrackerTheme {
        CodeContent(
            state = CodeUiState(phone = "+79991234567", error = UiText.Resource(R.string.error_code_invalid)),
            onEvent = {},
            onChangePhone = {},
            onPasswordLogin = {},
        )
    }
}
