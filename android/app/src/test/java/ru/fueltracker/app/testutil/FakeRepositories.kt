package ru.fueltracker.app.testutil

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.repository.AuthRepository
import ru.fueltracker.app.data.repository.ProfileRepository
import ru.fueltracker.app.domain.model.CodeRequest
import ru.fueltracker.app.domain.model.LoginResult
import ru.fueltracker.app.domain.model.User
import java.io.IOException

/** Отвечает заранее заданными результатами и запоминает вызовы. */
class FakeAuthRepository : AuthRepository {

    var requestCodeResult: ApiResult<CodeRequest> = ApiResult.Success(CodeRequest(resendAfterSec = 60))
    var verifyCodeResult: ApiResult<LoginResult> = ApiResult.Success(LoginResult(isNewUser = false))
    var loginResult: ApiResult<LoginResult> = ApiResult.Success(LoginResult(isNewUser = false))

    val requestCodeCalls = mutableListOf<String>()
    val verifyCodeCalls = mutableListOf<Pair<String, String>>()
    val loginCalls = mutableListOf<Pair<String, String>>()

    override suspend fun requestCode(phone: String): ApiResult<CodeRequest> {
        requestCodeCalls += phone
        return requestCodeResult
    }

    override suspend fun verifyCode(phone: String, code: String): ApiResult<LoginResult> {
        verifyCodeCalls += phone to code
        return verifyCodeResult
    }

    override suspend fun login(phone: String, password: String): ApiResult<LoginResult> {
        loginCalls += phone to password
        return loginResult
    }
}

class FakeProfileRepository : ProfileRepository {

    var updateNameResult: ApiResult<User> = ApiResult.Success(User("id-1", "+79991234567", "Иван"))
    val updateNameCalls = mutableListOf<String>()

    override suspend fun updateName(name: String): ApiResult<User> {
        updateNameCalls += name
        return updateNameResult
    }
}

/** Ошибка сервера в формате API. */
fun httpError(
    status: Int,
    code: String,
    message: String = "Ошибка с сервера",
    details: Map<String, Any> = emptyMap(),
): ApiResult.Failure = ApiResult.Failure(
    ApiError.Http(
        status = status,
        code = code,
        message = message,
        details = JsonObject(
            details.mapValues { (_, v) -> if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) },
        ),
    ),
)

val networkError: ApiResult.Failure = ApiResult.Failure(ApiError.Network(IOException("no route")))
