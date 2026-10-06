package ru.fueltracker.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RequestCodeRequest(val phone: String)

@Serializable
data class RequestCodeResponse(
    @SerialName("expires_in_sec") val expiresInSec: Int,
    @SerialName("resend_after_sec") val resendAfterSec: Int,
)

@Serializable
data class VerifyCodeRequest(val phone: String, val code: String)

@Serializable
data class LoginRequest(val phone: String, val password: String)

/** Тело `/auth/refresh` и `/auth/logout`. */
@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

/** Ответ verify-code, login и refresh. */
@Serializable
data class TokensResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in_sec") val expiresInSec: Int,
    val user: UserDto,
    /** Нет в ответе `/auth/refresh`. */
    @SerialName("is_new_user") val isNewUser: Boolean? = null,
)

/** Пользователь: `user` в ответе входа и профиль `/me`. */
@Serializable
data class UserDto(
    val id: String,
    val phone: String,
    val name: String?,
    /** Задан ли пароль для входа без SMS; в старых ответах поля нет. */
    @SerialName("has_password") val hasPassword: Boolean = false,
)

@Serializable
data class UpdateMeRequest(val name: String)
