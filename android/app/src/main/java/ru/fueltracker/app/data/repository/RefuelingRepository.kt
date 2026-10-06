package ru.fueltracker.app.data.repository

import androidx.room.withTransaction
import ru.fueltracker.app.data.local.db.AppDatabase
import ru.fueltracker.app.data.local.db.RefuelingEntity
import ru.fueltracker.app.data.local.db.SheetEntity
import ru.fueltracker.app.data.local.db.toDbAmount
import ru.fueltracker.app.data.local.db.touched
import ru.fueltracker.app.domain.calc.SheetCalculator
import ru.fueltracker.app.domain.calc.SheetRuleViolation
import ru.fueltracker.app.domain.calc.SheetRules
import ru.fueltracker.app.domain.model.RefuelingInput
import ru.fueltracker.app.domain.model.SheetStatus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Заправки — в Room. Карточка листа обновляется сама: лента подписана на Room. */
interface RefuelingRepository {

    suspend fun create(sheetId: String, input: RefuelingInput): LocalResult<Unit>

    suspend fun update(refuelingId: String, input: RefuelingInput): LocalResult<Unit>

    suspend fun delete(refuelingId: String): LocalResult<Unit>
}

@Singleton
class DefaultRefuelingRepository @Inject constructor(
    private val db: AppDatabase,
) : RefuelingRepository {

    private val sheets = db.sheetDao()
    private val refuelings = db.refuelingDao()

    override suspend fun create(sheetId: String, input: RefuelingInput): LocalResult<Unit> = db.withTransaction {
        val sheet = editableSheet(sheetId) ?: return@withTransaction violation(sheetId)
        checkDate(sheet, input)?.let { return@withTransaction it.rejected() }
        refuelings.upsert(
            RefuelingEntity(
                id = UUID.randomUUID().toString(),
                sheetId = sheetId,
                refueledAt = "",
                liters = "",
                pricePerLiter = "",
                totalCost = "",
                odometerKm = null,
                station = null,
                paymentType = input.paymentType.name,
                note = null,
            ).with(input),
        )
        Unit.ok()
    }

    override suspend fun update(refuelingId: String, input: RefuelingInput): LocalResult<Unit> = db.withTransaction {
        val current = refuelings.get(refuelingId)?.takeUnless { it.isDeleted }
            ?: return@withTransaction SheetRuleViolation.NOT_FOUND.rejected()
        val sheet = editableSheet(current.sheetId) ?: return@withTransaction violation(current.sheetId)
        checkDate(sheet, input)?.let { return@withTransaction it.rejected() }
        refuelings.upsert(current.with(input).touched())
        Unit.ok()
    }

    override suspend fun delete(refuelingId: String): LocalResult<Unit> = db.withTransaction {
        val current = refuelings.get(refuelingId)?.takeUnless { it.isDeleted }
            ?: return@withTransaction SheetRuleViolation.NOT_FOUND.rejected()
        editableSheet(current.sheetId) ?: return@withTransaction violation(current.sheetId)
        // Строка остаётся до синхронизации: сервер должен узнать об удалении.
        refuelings.upsert(current.copy(isDeleted = true).touched())
        Unit.ok()
    }

    /** Открытый неудалённый лист; заправки закрытого не меняются (правило 8). */
    private suspend fun editableSheet(sheetId: String): SheetEntity? =
        sheets.get(sheetId)?.takeIf { !it.isDeleted && it.status == SheetStatus.OPEN.name }

    private suspend fun violation(sheetId: String): LocalResult<Nothing> {
        val sheet = sheets.get(sheetId)
        return if (sheet == null || sheet.isDeleted) {
            SheetRuleViolation.NOT_FOUND.rejected()
        } else {
            SheetRuleViolation.SHEET_CLOSED.rejected()
        }
    }

    private fun checkDate(sheet: SheetEntity, input: RefuelingInput): SheetRuleViolation? =
        SheetRuleViolation.REFUELING_DATE_OUTSIDE_MONTH.takeUnless {
            SheetRules.isDateInMonth(input.date, sheet.year, sheet.month)
        }
}

/** Поля формы → строка Room. Сумма не введена вручную — литры × цена (правило 9). */
private fun RefuelingEntity.with(input: RefuelingInput) = copy(
    refueledAt = input.date.toString(),
    liters = input.liters.toDbAmount(),
    pricePerLiter = input.pricePerLiter.toDbAmount(),
    totalCost = (input.totalCost ?: SheetCalculator.totalCost(input.liters, input.pricePerLiter)).toDbAmount(),
    odometerKm = input.odometerKm,
    station = input.station,
    paymentType = input.paymentType.name,
    note = input.note,
)
