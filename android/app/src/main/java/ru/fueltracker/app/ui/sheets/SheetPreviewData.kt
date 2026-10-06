package ru.fueltracker.app.ui.sheets

import ru.fueltracker.app.domain.model.ConsumptionStatus
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.PaymentType
import ru.fueltracker.app.domain.model.Refueling
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetCalc
import ru.fueltracker.app.domain.model.SheetStatus
import java.math.BigDecimal
import java.time.LocalDate

// Данные для @Preview: пример G из 06_BUSINESS_RULES.md (закрыт, перерасход) и пример B (открыт)

private fun d(value: String) = BigDecimal(value)

internal fun previewClosedSheet() = FuelSheet(
    id = "s-07",
    carId = "car-1",
    year = 2026,
    month = 7,
    status = SheetStatus.CLOSED,
    odometerStartKm = 52_340,
    odometerEndKm = 53_340,
    fuelStartL = d("12.00"),
    fuelEndActualL = d("9.00"),
    season = Season.SUMMER,
    normLPer100km = d("10.068"),
    refuelings = listOf(
        Refueling("r1", LocalDate.of(2026, 7, 5), d("50.00"), d("55.00"), d("2750.00"), null, "Лукойл", PaymentType.PERSONAL, null),
        Refueling("r2", LocalDate.of(2026, 7, 20), d("48.50"), d("55.00"), d("2667.50"), null, null, PaymentType.PERSONAL, null),
    ),
    calc = SheetCalc(
        refueledL = d("98.50"),
        refueledCost = d("5417.50"),
        fuelAvailableL = d("110.50"),
        mileageKm = 1000,
        normConsumptionL = d("100.68"),
        fuelEndCalcL = d("9.82"),
        fuelEndL = d("9.00"),
        actualConsumptionL = d("101.50"),
        actualLPer100km = d("10.150"),
        consumptionStatus = ConsumptionStatus.OVER,
        deviationL = d("0.82"),
        costPerKm = d("5.42"),
        warnings = emptyList(),
    ),
)

internal fun previewOpenSheet() = FuelSheet(
    id = "s-08",
    carId = "car-1",
    year = 2026,
    month = 8,
    status = SheetStatus.OPEN,
    odometerStartKm = 53_340,
    odometerEndKm = null,
    fuelStartL = d("10.00"),
    fuelEndActualL = null,
    season = Season.SUMMER,
    normLPer100km = d("10.068"),
    refuelings = listOf(
        Refueling("r3", LocalDate.of(2026, 8, 3), d("30.00"), d("55.00"), d("1650.00"), null, null, PaymentType.PERSONAL, null),
    ),
    calc = SheetCalc(
        refueledL = d("30.00"),
        refueledCost = d("1650.00"),
        fuelAvailableL = d("40.00"),
        mileageKm = null,
        normConsumptionL = null,
        fuelEndCalcL = null,
        fuelEndL = null,
        actualConsumptionL = null,
        actualLPer100km = null,
        consumptionStatus = null,
        deviationL = null,
        costPerKm = null,
        warnings = emptyList(),
    ),
)
