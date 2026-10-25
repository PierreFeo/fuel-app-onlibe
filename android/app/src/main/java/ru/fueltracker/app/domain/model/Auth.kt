package ru.fueltracker.app.domain.model

/**
 * Код запрошен; повторить запрос можно через [resendAfterSec] секунд. [isFixedCode] — SMS на сервере
 * отключены, код всегда 000000 (docs/05_AUTH_SMS.md, «SMS отключены»).
 */
data class CodeRequest(val resendAfterSec: Int, val isFixedCode: Boolean = false)

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
