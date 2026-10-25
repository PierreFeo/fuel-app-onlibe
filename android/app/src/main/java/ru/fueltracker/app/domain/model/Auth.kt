package ru.fueltracker.app.domain.model

/** Код запрошен; повторить запрос можно через [resendAfterSec] секунд. */
data class CodeRequest(val resendAfterSec: Int)

/**
 * Вход выполнен, токены сохранены. [isNewUser] — имя ещё не заполнено; [needsPassword] — вход по
 * SMS, а пароля нет. Любое из двух — нужен SetupScreen.
 */
data class LoginResult(val isNewUser: Boolean, val needsPassword: Boolean = false) {
    val needsSetup: Boolean get() = isNewUser || needsPassword
}

data class User(
    val id: String,
    val phone: String,
    val name: String?,
)
