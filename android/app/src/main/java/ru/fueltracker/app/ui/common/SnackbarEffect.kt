package ru.fueltracker.app.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState

/** Показать [message] в Snackbar один раз и сообщить ViewModel, что оно показано. */
@Composable
fun SnackbarEffect(
    message: UiText?,
    hostState: SnackbarHostState,
    onShown: () -> Unit,
) {
    val text = message?.asString()
    val currentOnShown = rememberUpdatedState(onShown)
    LaunchedEffect(message) {
        if (text != null) {
            // Сначала показать: сброс сообщения в ViewModel перезапустил бы эффект и закрыл Snackbar
            hostState.showSnackbar(text)
            currentOnShown.value()
        }
    }
}
