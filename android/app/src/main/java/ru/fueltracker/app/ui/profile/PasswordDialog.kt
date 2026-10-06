package ru.fueltracker.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import ru.fueltracker.app.R
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.asString
import ru.fueltracker.app.ui.theme.FuelTrackerTheme

/** «Вход по паролю» в профиле аккаунта: задан ли пароль и кнопка «Задать / Сменить». */
@Composable
internal fun PasswordBlock(state: ProfileUiState, onEvent: (ProfileEvent) -> Unit) {
    Text(stringResource(R.string.password_block_title), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(if (state.hasPassword) R.string.password_status_set else R.string.password_status_not_set),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = { onEvent(ProfileEvent.OpenPassword) },
        enabled = state.canChangePassword,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProfileTestTags.PASSWORD),
    ) {
        Text(stringResource(if (state.hasPassword) R.string.password_change else R.string.password_set))
    }
}

/** Диалог пароля: ошибки — под полями и текстом в самом диалоге (Snackbar под ним не виден). */
@Composable
internal fun PasswordDialog(form: PasswordForm, onEvent: (ProfileEvent) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onEvent(ProfileEvent.DismissPassword) },
        title = { Text(stringResource(if (form.needsCurrent) R.string.password_change else R.string.password_set)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (form.needsCurrent) {
                    PasswordField(
                        value = form.current,
                        onValueChange = { onEvent(ProfileEvent.PasswordCurrentChanged(it)) },
                        label = stringResource(R.string.password_current),
                        error = form.currentError,
                        visible = visible,
                        onToggleVisible = { visible = !visible },
                        enabled = !form.isSaving,
                        testTag = ProfileTestTags.PASSWORD_CURRENT,
                    )
                }
                PasswordField(
                    value = form.new,
                    onValueChange = { onEvent(ProfileEvent.PasswordNewChanged(it)) },
                    label = stringResource(R.string.password_new),
                    error = form.newError,
                    supporting = stringResource(R.string.password_rule),
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                    enabled = !form.isSaving,
                    testTag = ProfileTestTags.PASSWORD_NEW,
                )
                PasswordField(
                    value = form.repeat,
                    onValueChange = { onEvent(ProfileEvent.PasswordRepeatChanged(it)) },
                    label = stringResource(R.string.password_repeat),
                    error = form.repeatError,
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                    enabled = !form.isSaving,
                    testTag = ProfileTestTags.PASSWORD_REPEAT,
                )
                form.error?.let {
                    Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onEvent(ProfileEvent.SavePassword) },
                enabled = form.canSubmit,
                modifier = Modifier.testTag(ProfileTestTags.PASSWORD_SAVE),
            ) {
                if (form.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(ProfileEvent.DismissPassword) }, enabled = !form.isSaving) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: UiText?,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    enabled: Boolean,
    testTag: String,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = (error?.asString() ?: supporting)?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    painter = painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = stringResource(if (visible) R.string.password_hide else R.string.password_show),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    )
}

@PreviewLightDark
@Composable
private fun PasswordDialogPreview() {
    FuelTrackerTheme {
        Surface {
            PasswordDialog(
                form = PasswordForm(
                    needsCurrent = true,
                    current = "старый",
                    new = "1234",
                    newError = UiText.Resource(R.string.error_password_length),
                ),
                onEvent = {},
            )
        }
    }
}
