package ru.fueltracker.app.testutil

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.repository.AuthRepository
import ru.fueltracker.app.data.repository.CarRepository
import ru.fueltracker.app.data.repository.ProfileRepository
import ru.fueltracker.app.data.repository.SheetRepository
import ru.fueltracker.app.domain.model.SheetPage
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.domain.model.CarInput
import ru.fueltracker.app.domain.model.CodeRequest
import ru.fueltracker.app.domain.model.FuelType
import ru.fueltracker.app.domain.model.LoginResult
import ru.fueltracker.app.domain.model.User
import java.io.IOException
import java.math.BigDecimal

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

class FakeCarRepository : CarRepository {

    var cars: MutableList<Car> = mutableListOf()
    var getCarsResult: ApiResult<List<Car>>? = null
    var getCarResult: ApiResult<Car>? = null
    var saveResult: ApiResult<Car>? = null
    var archiveResult: ApiResult<Unit> = ApiResult.Success(Unit)

    val created = mutableListOf<CarInput>()
    val updated = mutableListOf<Pair<String, CarInput>>()
    val archived = mutableListOf<String>()

    override suspend fun getCars(): ApiResult<List<Car>> = getCarsResult ?: ApiResult.Success(cars.toList())

    override suspend fun getCar(carId: String): ApiResult<Car> =
        getCarResult ?: ApiResult.Success(cars.first { it.id == carId })

    override suspend fun createCar(input: CarInput): ApiResult<Car> {
        created += input
        return saveResult ?: ApiResult.Success(input.toCar("new"))
    }

    override suspend fun updateCar(carId: String, input: CarInput): ApiResult<Car> {
        updated += carId to input
        return saveResult ?: ApiResult.Success(input.toCar(carId))
    }

    override suspend fun archiveCar(carId: String): ApiResult<Unit> {
        archived += carId
        return archiveResult
    }

    private fun CarInput.toCar(id: String) =
        Car(id, name, plateNumber, fuelType, tankCapacityL, normSummer, normWinter, isArchived = false)
}

/** Страницы ленты по значению `before` (null — первая страница). */
class FakeSheetRepository : SheetRepository {

    val pages = mutableMapOf<String?, ApiResult<SheetPage>>()
    val calls = mutableListOf<Pair<String, String?>>()

    override suspend fun getSheets(carId: String, before: String?): ApiResult<SheetPage> {
        calls += carId to before
        return pages[before] ?: ApiResult.Success(SheetPage(emptyList(), nextBefore = null))
    }
}

fun testCar(
    id: String = "car-1",
    name: String = "Lada Vesta",
    normWinter: String? = "11.684",
) = Car(
    id = id,
    name = name,
    plateNumber = "А123ВС77",
    fuelType = FuelType.AI95,
    tankCapacityL = BigDecimal("50.00"),
    normSummer = BigDecimal("10.068"),
    normWinter = normWinter?.let(::BigDecimal),
    isArchived = false,
)

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
