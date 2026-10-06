package ru.fueltracker.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.fueltracker.app.R
import ru.fueltracker.app.data.remote.ApiError

/**
 * Текст для экрана, который ViewModel готовит без Context:
 * строка из strings.xml или готовый текст с сервера.
 */
sealed interface UiText {
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Resource -> stringResource(id, *args.toTypedArray())
    is UiText.Raw -> text
}

/** Ошибка запроса для Snackbar: `error.message` сервера, иначе «Нет связи с сервером». */
fun ApiError.toUiText(): UiText {
    val serverMessage = (this as? ApiError.Http)?.message
    return if (serverMessage != null) UiText.Raw(serverMessage) else UiText.Resource(R.string.error_no_connection)
}
