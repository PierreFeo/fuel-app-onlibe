package ru.fueltracker.app.data.local.db

import ru.fueltracker.app.domain.calc.RefuelingData
import ru.fueltracker.app.domain.calc.SheetCalculator
import ru.fueltracker.app.domain.calc.SheetData
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.FuelType
import ru.fueltracker.app.domain.model.PaymentType
import ru.fueltracker.app.domain.model.Refueling
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// --- Room → модели для экранов ---

fun CarEntity.toDomain() = Car(
    id = id,
    name = name,
    plateNumber = plateNumber,
    fuelType = FuelType.valueOf(fuelType),
    tankCapacityL = BigDecimal(tankCapacityL),
    normSummer = BigDecimal(normLPer100km),
    normWinter = normWinterLPer100km?.let(::BigDecimal),
    isArchived = isArchived,
)

fun RefuelingEntity.toDomain() = Refueling(
    id = id,
    date = LocalDate.parse(refueledAt),
    liters = BigDecimal(liters),
    pricePerLiter = BigDecimal(pricePerLiter),
    totalCost = BigDecimal(totalCost),
    odometerKm = odometerKm,
    station = station,
    paymentType = PaymentType.valueOf(paymentType),
    note = note,
)

/** Неудалённые заправки листа — по дате, при равной дате порядок постоянный (по id). */
fun List<RefuelingEntity>.alive(): List<RefuelingEntity> =
    filterNot { it.isDeleted }.sortedWith(compareBy({ it.refueledAt }, { it.id }))

/**
 * Лист для экрана вместе с итогами, которые считает [SheetCalculator].
 * [prevOdometerEndKm] — пробег на конец предыдущего листа авто (для предупреждения ODOMETER_GAP).
 */
fun SheetWithRefuelings.toDomain(tankCapacityL: BigDecimal, prevOdometerEndKm: Long?): FuelSheet {
    val refuelings = refuelings.alive()
    return FuelSheet(
        id = sheet.id,
        carId = sheet.carId,
        year = sheet.year,
        month = sheet.month,
        status = SheetStatus.valueOf(sheet.status),
        odometerStartKm = sheet.odometerStartKm,
        odometerEndKm = sheet.odometerEndKm,
        fuelStartL = BigDecimal(sheet.fuelStartL),
        fuelEndActualL = sheet.fuelEndActualL?.let(::BigDecimal),
        season = Season.valueOf(sheet.season),
        normLPer100km = BigDecimal(sheet.normLPer100km),
        refuelings = refuelings.map { it.toDomain() },
        calc = SheetCalculator.calculate(sheetData(sheet, refuelings), tankCapacityL, prevOdometerEndKm),
    )
}

/** Данные для расчёта: только неудалённые заправки. */
fun sheetData(sheet: SheetEntity, aliveRefuelings: List<RefuelingEntity>) = SheetData(
    odometerStartKm = sheet.odometerStartKm,
    odometerEndKm = sheet.odometerEndKm,
    fuelStartL = BigDecimal(sheet.fuelStartL),
    fuelEndActualL = sheet.fuelEndActualL?.let(::BigDecimal),
    normLPer100km = BigDecimal(sheet.normLPer100km),
    refuelings = aliveRefuelings.map {
        RefuelingData(BigDecimal(it.liters), BigDecimal(it.totalCost), it.odometerKm)
    },
)

// --- модели → строки Room (как в API: "45.50", "10.068") ---

/** Литры и деньги — 2 знака. */
fun BigDecimal.toDbAmount(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()

/** Нормы — 3 знака. */
fun BigDecimal.toDbNorm(): String = setScale(3, RoundingMode.HALF_UP).toPlainString()

// --- изменение строки на телефоне: уйдёт на сервер при следующей синхронизации ---

fun CarEntity.touched() = copy(isDirty = true, changeSeq = changeSeq + 1)

fun SheetEntity.touched() = copy(isDirty = true, changeSeq = changeSeq + 1)

fun RefuelingEntity.touched() = copy(isDirty = true, changeSeq = changeSeq + 1)
