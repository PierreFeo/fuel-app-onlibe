package ru.fueltracker.app.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Единый формат ошибки: `{ "error": { "code", "message", "details" } }`. */
@Serializable
data class ErrorResponse(val error: ErrorBody)

@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    val details: JsonObject = JsonObject(emptyMap()),
)

/** Коды ошибок из 04_API_CONTRACT.md. */
object ErrorCodes {
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val OTP_INVALID = "OTP_INVALID"
    const val OTP_EXPIRED = "OTP_EXPIRED"
    const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
    const val REFRESH_INVALID = "REFRESH_INVALID"
    const val PHONE_NOT_ALLOWED = "PHONE_NOT_ALLOWED"
    const val NOT_FOUND = "NOT_FOUND"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    const val SMS_SEND_FAILED = "SMS_SEND_FAILED"
}
