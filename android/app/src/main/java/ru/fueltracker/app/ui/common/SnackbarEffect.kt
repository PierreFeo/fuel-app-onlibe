package ru.fueltracker.app.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState

/** Показать [message] в Snackbar один раз и сообщить ViewModel, что оно показано. */
@Composable
fun SnackbarEffect(
    message: UiText?,
    hostState: SnackbarHostState,
    onShown: () -> Unit,
) = SnackbarEffect(message, hostState, actionLabel = null, onAction = {}, onShown = onShown)

/** То же с кнопкой действия в Snackbar (например, «Указать»). */
@Composable
fun SnackbarEffect(
    message: UiText?,
    hostState: SnackbarHostState,
    actionLabel: UiText?,
    onAction: () -> Unit,
    onShown: () -> Unit,
) {
    val text = message?.asString()
    val label = actionLabel?.asString()
    val currentOnShown = rememberUpdatedState(onShown)
    val currentOnAction = rememberUpdatedState(onAction)
    LaunchedEffect(message) {
        if (text != null) {
            // Сначала показать: сброс сообщения в ViewModel перезапустил бы эффект и закрыл Snackbar
            val result = hostState.showSnackbar(
                message = text,
                actionLabel = label,
                duration = if (label != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            currentOnShown.value()
            if (result == SnackbarResult.ActionPerformed) currentOnAction.value()
        }
    }
}
