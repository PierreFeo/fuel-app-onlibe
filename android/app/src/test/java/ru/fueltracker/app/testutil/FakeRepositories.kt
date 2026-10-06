package ru.fueltracker.app.testutil

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.repository.AuthRepository
import ru.fueltracker.app.data.repository.CarRepository
import ru.fueltracker.app.data.repository.ProfileRepository
import ru.fueltracker.app.data.repository.RefuelingRepository
import ru.fueltracker.app.data.repository.SheetRepository
import ru.fueltracker.app.data.repository.SyncRepository
import ru.fueltracker.app.data.repository.SyncResult
import ru.fueltracker.app.data.repository.SyncStatus
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.NewSheetInput
import ru.fueltracker.app.domain.model.RefuelingInput
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.calc.SheetRuleViolation
import ru.fueltracker.app.data.repository.LocalResult
import ru.fueltracker.app.ui.sheets.previewOpenSheet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import ru.fueltracker.app.domain.model.SheetPrefill
import ru.fueltracker.app.domain.model.SheetStatus
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

    var logoutCalls = 0

    override suspend fun logout() {
        logoutCalls++
    }
}

class FakeProfileRepository : ProfileRepository {

    var updateNameResult: ApiResult<User> = ApiResult.Success(User("id-1", "+79991234567", "Иван"))
    val updateNameCalls = mutableListOf<String>()

    var changePasswordResult: ApiResult<Unit> = ApiResult.Success(Unit)
    val changePasswordCalls = mutableListOf<Pair<String?, String>>()

    override suspend fun changePassword(currentPassword: String?, newPassword: String): ApiResult<Unit> {
        changePasswordCalls += currentPassword to newPassword
        return changePasswordResult
    }

    override suspend fun updateName(name: String): ApiResult<User> {
        updateNameCalls += name
        return updateNameResult
    }
}

/** Авто «в базе»: список в памяти, изменения сразу приходят подписчикам — как у Room. */
class FakeCarRepository : CarRepository {

    private val carsFlow = MutableStateFlow<List<Car>>(emptyList())

    /** Все авто, включая архивные. */
    var cars: List<Car>
        get() = carsFlow.value
        set(value) {
            carsFlow.value = value
        }

    val created = mutableListOf<CarInput>()
    val updated = mutableListOf<Pair<String, CarInput>>()
    val archived = mutableListOf<String>()

    override fun observeCars(): Flow<List<Car>> = carsFlow.map { list -> list.filterNot { it.isArchived } }

    override fun observeCar(carId: String): Flow<Car?> = carsFlow.map { list -> list.firstOrNull { it.id == carId } }

    override suspend fun getCar(carId: String): Car? = cars.firstOrNull { it.id == carId }

    override suspend fun createCar(input: CarInput): Car {
        created += input
        return input.toCar("new-${created.size}").also { cars = cars + it }
    }

    override suspend fun updateCar(carId: String, input: CarInput): Car? {
        updated += carId to input
        if (cars.none { it.id == carId }) return null
        return input.toCar(carId).also { car -> cars = cars.map { if (it.id == carId) car else it } }
    }

    override suspend fun archiveCar(carId: String) {
        archived += carId
        cars = cars.map { if (it.id == carId) it.copy(isArchived = true) else it }
    }

    private fun CarInput.toCar(id: String) =
        Car(id, name, plateNumber, fuelType, tankCapacityL, normSummer, normWinter, isArchived = false)
}

/**
 * Листы «в базе». Действия по умолчанию меняют список, как настоящий репозиторий;
 * [violation] — заставить следующее действие отказать (нарушено правило).
 */
class FakeSheetRepository : SheetRepository {

    private val sheetsFlow = MutableStateFlow<List<FuelSheet>>(emptyList())

    /** Все листы всех авто, новые сверху. */
    var sheets: List<FuelSheet>
        get() = sheetsFlow.value
        set(value) {
            sheetsFlow.value = value
        }

    var prefill = SheetPrefill(2026, 11, 53_340, BigDecimal("10.00"), Season.SUMMER)

    /** Если задано — любое действие отказывает с этим нарушением. */
    var violation: SheetRuleViolation? = null

    val created = mutableListOf<Pair<String, NewSheetInput>>()
    val seasonCalls = mutableListOf<Pair<String, Season>>()
    val closeCalls = mutableListOf<Triple<String, Long, BigDecimal>>()
    val reopenCalls = mutableListOf<String>()
    val deleteCalls = mutableListOf<String>()

    override fun observeSheets(carId: String): Flow<List<FuelSheet>> =
        sheetsFlow.map { list -> list.filter { it.carId == carId } }

    override suspend fun getNextPrefill(carId: String): SheetPrefill = prefill

    override suspend fun createSheet(carId: String, input: NewSheetInput): LocalResult<Unit> {
        created += carId to input
        return act {
            val sheet = previewOpenSheet().copy(
                id = "new-${input.year}-${input.month}",
                carId = carId,
                year = input.year,
                month = input.month,
                season = input.season,
                refuelings = emptyList(),
            )
            sheets = (sheets + sheet).sortedWith(compareByDescending<FuelSheet> { it.year }.thenByDescending { it.month })
        }
    }

    override suspend fun setSeason(sheetId: String, season: Season): LocalResult<BigDecimal> {
        seasonCalls += sheetId to season
        val norm = if (season == Season.WINTER) BigDecimal("11.684") else BigDecimal("10.068")
        return violation?.let { LocalResult.Rejected(it) } ?: run {
            change(sheetId) { copy(season = season, normLPer100km = norm) }
            LocalResult.Ok(norm)
        }
    }

    override suspend fun closeSheet(sheetId: String, odometerEndKm: Long, fuelEndActualL: BigDecimal): LocalResult<Unit> {
        closeCalls += Triple(sheetId, odometerEndKm, fuelEndActualL)
        return act {
            change(sheetId) { copy(status = SheetStatus.CLOSED, odometerEndKm = odometerEndKm, fuelEndActualL = fuelEndActualL) }
        }
    }

    override suspend fun reopenSheet(sheetId: String): LocalResult<Unit> {
        reopenCalls += sheetId
        return act { change(sheetId) { copy(status = SheetStatus.OPEN) } }
    }

    override suspend fun deleteSheet(sheetId: String): LocalResult<Unit> {
        deleteCalls += sheetId
        return act { sheets = sheets.filterNot { it.id == sheetId } }
    }

    private fun act(change: () -> Unit): LocalResult<Unit> =
        violation?.let { LocalResult.Rejected(it) } ?: LocalResult.Ok(change())

    private fun change(sheetId: String, update: FuelSheet.() -> FuelSheet) {
        sheets = sheets.map { if (it.id == sheetId) it.update() else it }
    }
}

/** Заправки: запоминает вызовы и отвечает [result]. */
class FakeRefuelingRepository : RefuelingRepository {

    var result: LocalResult<Unit> = LocalResult.Ok(Unit)

    val created = mutableListOf<Pair<String, RefuelingInput>>()
    val updated = mutableListOf<Pair<String, RefuelingInput>>()
    val deleteCalls = mutableListOf<String>()

    override suspend fun create(sheetId: String, input: RefuelingInput): LocalResult<Unit> {
        created += sheetId to input
        return result
    }

    override suspend fun update(refuelingId: String, input: RefuelingInput): LocalResult<Unit> {
        updated += refuelingId to input
        return result
    }

    override suspend fun delete(refuelingId: String): LocalResult<Unit> {
        deleteCalls += refuelingId
        return result
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

/** Синхронизация: отвечает [results] по очереди (последний — повторяется) и считает вызовы. */
class FakeSyncRepository(
    var results: MutableList<SyncResult> = mutableListOf(SyncResult.Success(sent = 0, received = 0, rejected = emptyList())),
) : SyncRepository {

    val status = MutableStateFlow(SyncStatus(pendingChanges = 0, lastSyncAt = null))
    var syncCalls = 0

    override suspend fun sync(): SyncResult {
        syncCalls++
        return if (results.size > 1) results.removeAt(0) else results.first()
    }

    override fun observeStatus(): Flow<SyncStatus> = status
}
