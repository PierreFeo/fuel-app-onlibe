package ru.fueltracker.app.ui.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import ru.fueltracker.app.ui.theme.FuelTrackerTheme

@Composable
fun NameScreen(
    onSaved: () -> Unit,
    viewModel: NameViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.onEvent(NameEvent.SavedHandled)
            onSaved()
        }
    }
    NameContent(state = state, onEvent = viewModel::onEvent)
}

@Composable
fun NameContent(
    state: NameUiState,
    onEvent: (NameEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarEffect(state.snackbar, snackbarHostState) { onEvent(NameEvent.SnackbarShown) }

    AuthLayout(
        title = stringResource(R.string.name_title),
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = { onEvent(NameEvent.NameChanged(it)) },
            label = { Text(stringResource(R.string.name_label)) },
            singleLine = true,
            enabled = !state.isSaving,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onEvent(NameEvent.Submit) }),
            isError = state.error != null,
            supportingText = state.error?.let { { Text(it.asString()) } },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(NameTestTags.NAME_FIELD),
        )
        ProgressButton(
            text = stringResource(R.string.name_continue),
            isLoading = state.isSaving,
            enabled = state.canSubmit,
            onClick = { onEvent(NameEvent.Submit) },
            modifier = Modifier.testTag(NameTestTags.SUBMIT),
        )
    }
}

object NameTestTags {
    const val NAME_FIELD = "name_field"
    const val SUBMIT = "name_submit"
}

@PreviewLightDark
@Composable
private fun NameContentPreview() {
    FuelTrackerTheme {
        NameContent(state = NameUiState(name = "Иван Петров"), onEvent = {})
    }
}
