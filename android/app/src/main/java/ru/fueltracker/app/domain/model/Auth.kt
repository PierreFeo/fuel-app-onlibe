package ru.fueltracker.app.domain.model

/** Код отправлен; повторить запрос можно через [resendAfterSec] секунд. */
data class CodeRequest(val resendAfterSec: Int)

/** Вход выполнен, токены сохранены. [isNewUser] — имя ещё не заполнено, нужен NameScreen. */
data class LoginResult(val isNewUser: Boolean)

data class User(
    val id: String,
    val phone: String,
    val name: String?,
)
