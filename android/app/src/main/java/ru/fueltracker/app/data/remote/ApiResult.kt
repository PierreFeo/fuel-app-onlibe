package ru.fueltracker.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import retrofit2.HttpException
import ru.fueltracker.app.data.remote.dto.ErrorResponse
import java.io.IOException

/** Результат запроса к API: данные или понятная ошибка вместо исключения. */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

sealed interface ApiError {

    /**
     * Сервер ответил ошибкой. [code] и [message] — из тела `{ "error": … }`;
     * null, если тело не в формате API (например, HTML-страница прокси при 502).
     */
    data class Http(
        val status: Int,
        val code: String?,
        val message: String?,
        val details: JsonObject = JsonObject(emptyMap()),
    ) : ApiError {
        /** 429: через сколько секунд можно повторить. */
        val retryAfterSec: Int? get() = detail("retry_after_sec")?.intOrNull

        /** 400 VALIDATION_ERROR: текст ошибки по имени поля из API. */
        fun fieldError(field: String): String? = detail(field)?.contentOrNull

        private fun detail(key: String): JsonPrimitive? = details[key] as? JsonPrimitive
    }

    /** Нет связи: сервер недоступен, таймаут, нет интернета. */
    data class Network(val cause: IOException) : ApiError

    /**
     * Вход другим номером, а на телефоне данные другого аккаунта (docs/05_AUTH_SMS.md):
     * вход отменён, чтобы не смешать данные. [ownerPhone] — номер владельца данных.
     */
    data class WrongAccount(val ownerPhone: String?) : ApiError

    /** Ответ пришёл, но не совпал с контрактом (ошибка в приложении или на сервере). */
    data class Unexpected(val cause: Throwable) : ApiError
}

/** Выполнить запрос и превратить исключения Retrofit/OkHttp в [ApiResult.Failure]. */
suspend fun <T> apiCall(json: Json = ApiJson, block: suspend () -> T): ApiResult<T> =
    try {
        ApiResult.Success(block())
    } catch (e: CancellationException) {
        throw e // отмену корутины не глотаем
    } catch (e: HttpException) {
        ApiResult.Failure(e.toApiError(json))
    } catch (e: IOException) {
        ApiResult.Failure(ApiError.Network(e))
    } catch (e: SerializationException) {
        ApiResult.Failure(ApiError.Unexpected(e))
    }

private fun HttpException.toApiError(json: Json): ApiError.Http {
    val status = code()
    val body = try {
        response()?.errorBody()?.string()
    } catch (_: IOException) {
        null
    }
    val error = body?.let {
        try {
            json.decodeFromString<ErrorResponse>(it).error
        } catch (_: SerializationException) {
            null
        }
    }
    return if (error != null) {
        ApiError.Http(status, error.code, error.message, error.details)
    } else {
        ApiError.Http(status, code = null, message = null)
    }
}

/** Преобразовать данные успешного ответа; ошибка проходит без изменений. */
inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}
