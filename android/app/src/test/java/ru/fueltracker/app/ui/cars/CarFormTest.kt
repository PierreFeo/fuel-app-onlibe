package ru.fueltracker.app.ui.cars

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.CarInput
import ru.fueltracker.app.domain.model.FuelType
import ru.fueltracker.app.testutil.testCar
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.filterDecimalInput
import java.math.BigDecimal

class CarFormTest {

    private val valid = CarForm(
        name = "  Lada Vesta ",
        plateNumber = " А123ВС77 ",
        fuelType = FuelType.AI95,
        tankCapacity = "50",
        normSummer = "10,068",
        normWinter = "11.684",
    )

    private val required = UiText.Resource(R.string.error_required)

    @Test
    fun `верная форма — данные для отправки`() {
        val (input, errors) = validateCarForm(valid)
        assertFalse(errors.hasAny)
        assertEquals(
            CarInput(
                name = "Lada Vesta",
                plateNumber = "А123ВС77",
                fuelType = FuelType.AI95,
                tankCapacityL = BigDecimal("50"),
                normSummer = BigDecimal("10.068"),
                normWinter = BigDecimal("11.684"),
            ),
            input,
        )
    }

    @Test
    fun `пустые госномер и зимняя норма — null`() {
        val (input, _) = validateCarForm(valid.copy(plateNumber = "  ", normWinter = ""))
        assertNull(input!!.plateNumber)
        assertNull(input.normWinter)
    }

    @Test
    fun `обязательные поля`() {
        val (input, errors) = validateCarForm(CarForm())
        assertNull(input)
        assertEquals(required, errors.name)
        assertEquals(required, errors.fuelType)
        assertEquals(required, errors.tankCapacity)
        assertEquals(required, errors.normSummer)
        assertNull(errors.plateNumber)
        assertNull(errors.normWinter)
    }

    @Test
    fun `длина названия и госномера`() {
        val (_, errors) = validateCarForm(valid.copy(name = "я".repeat(61), plateNumber = "1".repeat(16)))
        assertEquals(UiText.Resource(R.string.error_too_long, listOf(60)), errors.name)
        assertEquals(UiText.Resource(R.string.error_too_long, listOf(15)), errors.plateNumber)
    }

    @Test
    fun `числа должны быть больше нуля`() {
        val positive = UiText.Resource(R.string.error_positive_number)
        val (_, errors) = validateCarForm(valid.copy(tankCapacity = "0", normSummer = "0,000", normWinter = "abc"))
        assertEquals(positive, errors.tankCapacity)
        assertEquals(positive, errors.normSummer)
        assertEquals(positive, errors.normWinter)
    }

    @Test
    fun `знаки после запятой — 2 у бака, 3 у норм`() {
        val (_, errors) = validateCarForm(valid.copy(tankCapacity = "50,123", normSummer = "10,0681"))
        assertEquals(UiText.Resource(R.string.error_max_decimals, listOf(2)), errors.tankCapacity)
        assertEquals(UiText.Resource(R.string.error_max_decimals, listOf(3)), errors.normSummer)
    }

    @Test
    fun `лишние нули после запятой не считаются`() {
        val (input, errors) = validateCarForm(valid.copy(tankCapacity = "50,000"))
        assertFalse(errors.hasAny)
        assertEquals(0, BigDecimal("50").compareTo(input!!.tankCapacityL))
    }

    @Test
    fun `слишком большие числа`() {
        val tooBig = UiText.Resource(R.string.error_too_big)
        val (_, errors) = validateCarForm(valid.copy(tankCapacity = "10000", normSummer = "1000"))
        assertEquals(tooBig, errors.tankCapacity)
        assertEquals(tooBig, errors.normSummer)
        assertFalse(validateCarForm(valid.copy(tankCapacity = "9999,99", normSummer = "999,999")).second.hasAny)
    }

    @Test
    fun `ввод числа — только цифры и разделитель`() {
        assertEquals("10,5", filterDecimalInput("10,5 л"))
        assertEquals("8.5", filterDecimalInput("-8.5"))
    }

    @Test
    fun `авто из API в форму`() {
        assertEquals(
            CarForm("Lada Vesta", "А123ВС77", FuelType.AI95, "50", "10,068", "11,684"),
            testCar().toForm(),
        )
        assertEquals("", testCar(normWinter = null).toForm().normWinter)
        assertEquals("8,5", testCar().copy(normSummer = BigDecimal("8.500")).toForm().normSummer)
        assertEquals("100", testCar().copy(tankCapacityL = BigDecimal("100.00")).toForm().tankCapacity)
    }
}
