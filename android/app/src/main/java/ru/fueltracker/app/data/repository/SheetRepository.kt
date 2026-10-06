package ru.fueltracker.app.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import ru.fueltracker.app.data.local.db.AppDatabase
import ru.fueltracker.app.data.local.db.SheetEntity
import ru.fueltracker.app.data.local.db.alive
import ru.fueltracker.app.data.local.db.sheetData
import ru.fueltracker.app.data.local.db.toDbAmount
import ru.fueltracker.app.data.local.db.toDbNorm
import ru.fueltracker.app.data.local.db.toDomain
import ru.fueltracker.app.data.local.db.touched
import ru.fueltracker.app.domain.calc.LatestSheet
import ru.fueltracker.app.domain.calc.SheetCalculator
import ru.fueltracker.app.domain.calc.SheetRuleViolation
import ru.fueltracker.app.domain.calc.SheetRules
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.NewSheetInput
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetPrefill
import ru.fueltracker.app.domain.model.SheetStatus
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** ЛУТ — в Room; итоги считает SheetCalculator, правила — SheetRules (docs/06_BUSINESS_RULES.md). */
interface SheetRepository {

    /** Все листы авто, новые сверху, с посчитанными итогами. Обновляется сам. */
    fun observeSheets(carId: String): Flow<List<FuelSheet>>

    /** Подсказка для нового листа: следующий месяц, пробег и остаток с прошлого листа, сезон. */
    suspend fun getNextPrefill(carId: String): SheetPrefill

    suspend fun createSheet(carId: String, input: NewSheetInput): LocalResult<Unit>

    /** ☀️/❄️: норма листа заново копируется из авто. Ответ — новая норма листа. */
    suspend fun setSeason(sheetId: String, season: Season): LocalResult<BigDecimal>

    /** Оба значения обязательны: без фактического остатка не посчитать расход. */
    suspend fun closeSheet(sheetId: String, odometerEndKm: Long, fuelEndActualL: BigDecimal): LocalResult<Unit>

    suspend fun reopenSheet(sheetId: String): LocalResult<Unit>

    /** Только лист без заправок. */
    suspend fun deleteSheet(sheetId: String): LocalResult<Unit>
}

@Singleton
class DefaultSheetRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
) : SheetRepository {

    private val cars = db.carDao()
    private val sheets = db.sheetDao()
    private val refuelings = db.refuelingDao()

    override fun observeSheets(carId: String): Flow<List<FuelSheet>> =
        combine(cars.observe(carId), sheets.observeForCar(carId)) { car, list ->
            if (car == null) return@combine emptyList()
            val tank = BigDecimal(car.tankCapacityL)
            // Список — новые сверху, поэтому «предыдущий месяц» — следующий элемент.
            list.mapIndexed { i, sheet -> sheet.toDomain(tank, list.getOrNull(i + 1)?.sheet?.odometerEndKm) }
        }

    override suspend fun getNextPrefill(carId: String): SheetPrefill {
        val car = cars.get(carId)
        val latest = sheets.latestForCar(carId)?.let { latest ->
            val tank = car?.tankCapacityL?.let(::BigDecimal) ?: BigDecimal.ZERO
            val calc = SheetCalculator.calculate(sheetData(latest.sheet, latest.refuelings.alive()), tank)
            LatestSheet(
                year = latest.sheet.year,
                month = latest.sheet.month,
                odometerStartKm = latest.sheet.odometerStartKm,
                odometerEndKm = latest.sheet.odometerEndKm,
                season = Season.valueOf(latest.sheet.season),
                fuelEndL = calc.fuelEndL,
            )
        }
        return SheetRules.nextPrefill(latest, hasWinterNorm = car?.normWinterLPer100km != null, today = today())
    }

    override suspend fun createSheet(carId: String, input: NewSheetInput): LocalResult<Unit> = db.withTransaction {
        val car = cars.get(carId)?.takeUnless { it.isDeleted } ?: return@withTransaction SheetRuleViolation.NOT_FOUND.rejected()
        if (SheetRules.isMonthTooFar(input.year, input.month, today())) {
            return@withTransaction SheetRuleViolation.MONTH_TOO_FAR.rejected()
        }
        if (sheets.findMonth(carId, input.year, input.month) != null) {
            return@withTransaction SheetRuleViolation.SHEET_EXISTS.rejected()
        }
        val norm = SheetRules.normFor(input.season, BigDecimal(car.normLPer100km), car.normWinterLPer100km?.let(::BigDecimal))
            ?: return@withTransaction SheetRuleViolation.WINTER_NORM_NOT_SET.rejected()
        sheets.upsert(
            SheetEntity(
                id = UUID.randomUUID().toString(),
                carId = carId,
                year = input.year,
                month = input.month,
                status = SheetStatus.OPEN.name,
                odometerStartKm = input.odometerStartKm,
                odometerEndKm = null,
                fuelStartL = input.fuelStartL.toDbAmount(),
                fuelEndActualL = null,
                season = input.season.name,
                normLPer100km = norm.toDbNorm(),
                closedAt = null,
                createdAt = now(),
            ),
        )
        Unit.ok()
    }

    override suspend fun setSeason(sheetId: String, season: Season): LocalResult<BigDecimal> = db.withTransaction {
        val sheet = aliveSheet(sheetId) ?: return@withTransaction SheetRuleViolation.NOT_FOUND.rejected()
        if (sheet.status == SheetStatus.CLOSED.name) return@withTransaction SheetRuleViolation.SHEET_CLOSED.rejected()
        val car = cars.get(sheet.carId) ?: return@withTransaction SheetRuleViolation.NOT_FOUND.rejected()
        val norm = SheetRules.normFor(season, BigDecimal(car.normLPer100km), car.normWinterLPer100km?.let(::BigDecimal))
            ?: return@withTransaction SheetRuleViolation.WINTER_NORM_NOT_SET.rejected()
        sheets.upsert(sheet.copy(season = season.name, normLPer100km = norm.toDbNorm()).touched())
        norm.ok()
    }

    override suspend fun closeSheet(
        sheetId: String,
        odometerEndKm: Long,
        fuelEndActualL: BigDecimal,
    ): LocalResult<Unit> = db.withTransaction {
        val sheet = aliveSheet(sheetId) ?: return@withTransaction SheetRuleViolation.NOT_FOUND.rejected()
        if (sheet.status == SheetStatus.CLOSED.name) return@withTransaction SheetRuleViolation.SHEET_CLOSED.rejected()
        val available = SheetCalculator.calculate(
            sheetData(sheet, refuelings.aliveForSheet(sheetId)),
            tankCapacityL = BigDecimal.ZERO, // для «доступно» бак не нужен
        ).fuelAvailableL
        SheetRules.checkEnd(sheet.odometerStartKm, odometerEndKm, fuelEndActualL, available)?.let {
            return@withTransaction it.rejected()
        }
        sheets.upsert(
            sheet.copy(
                status = SheetStatus.CLOSED.name,
                odometerEndKm = odometerEndKm,
                fuelEndActualL = fuelEndActualL.toDbAmount(),
                closedAt = now(),
            ).touched(),
        )
        Unit.ok()
    }

    override suspend fun reopenSheet(sheetId: String): LocalResult<Unit> = db.withTransaction {
        val sheet = aliveSheet(sheetId) ?: return@withTransaction SheetRuleViolation.NOT_FOUND.rejected()
        if (sheet.status != SheetStatus.OPEN.name) {
            sheets.upsert(sheet.copy(status = SheetStatus.OPEN.name, closedAt = null).touched())
        }
        Unit.ok()
    }

    override suspend fun deleteSheet(sheetId: String): LocalResult<Unit> = db.withTransaction {
        val sheet = aliveSheet(sheetId) ?: return@withTransaction SheetRuleViolation.NOT_FOUND.rejected()
        if (refuelings.aliveForSheet(sheetId).isNotEmpty()) {
            return@withTransaction SheetRuleViolation.SHEET_HAS_REFUELINGS.rejected()
        }
        // Строка остаётся до синхронизации: сервер должен узнать об удалении.
        sheets.upsert(sheet.copy(isDeleted = true).touched())
        Unit.ok()
    }

    private suspend fun aliveSheet(sheetId: String): SheetEntity? = sheets.get(sheetId)?.takeUnless { it.isDeleted }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun now(): String = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString()
}
