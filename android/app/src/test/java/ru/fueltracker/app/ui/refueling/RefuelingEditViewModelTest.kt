package ru.fueltracker.app.ui.refueling

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.fueltracker.app.R
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.data.repository.RefuelingRepository
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.PaymentType
import ru.fueltracker.app.domain.model.RefuelingInput
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.httpError
import ru.fueltracker.app.testutil.networkError
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.sheets.previewOpenSheet
import java.math.BigDecimal
import java.time.LocalDate

class RefuelingEditViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val sheet = previewOpenSheet() // август 2026, одна заправка r3
    private val savedSheet = sheet.copy(id = sheet.id)

    private val repository = object : RefuelingRepository {
        var result: ApiResult<FuelSheet> = ApiResult.Success(savedSheet)
        val created = mutableListOf<Pair<String, RefuelingInput>>()
        val updated = mutableListOf<Pair<String, RefuelingInput>>()
        val deleted = mutableListOf<String>()

        override suspend fun create(sheetId: String, input: RefuelingInput): ApiResult<FuelSheet> {
            created += sheetId to input
            return result
        }

        override suspend fun update(refuelingId: String, input: RefuelingInput): ApiResult<FuelSheet> {
            updated += refuelingId to input
            return result
        }

        override suspend fun delete(refuelingId: String): ApiResult<FuelSheet> {
            deleted += refuelingId
            return result
        }
    }

    private val viewModel = RefuelingEditViewModel(repository)
    private val today = LocalDate.of(2026, 8, 10)

    private fun startNew() = viewModel.start(RefuelingTarget(sheet.id, 2026, 8), today)

    private fun startEdit() = viewModel.start(RefuelingTarget(sheet.id, 2026, 8, sheet.refuelings.first()), today)

    @Test
    fun `новая заправка — сегодняшняя дата, личные`() {
        startNew()
        val state = viewModel.state.value
        assertTrue(state.isNew)
        assertEquals(today, state.form.date)
        assertEquals(PaymentType.PERSONAL, state.form.paymentType)
    }

    @Test
    fun `сумма считается по мере ввода литров и цены`() {
        startNew()
        viewModel.onEvent(RefuelingEditEvent.LitersChanged("40"))
        assertEquals("", viewModel.state.value.form.totalCost)
        viewModel.onEvent(RefuelingEditEvent.PriceChanged("55,5"))
        assertEquals("2220,00", viewModel.state.value.form.totalCost)
        assertFalse(viewModel.state.value.form.isTotalManual)
    }

    @Test
    fun `сумма вручную не пересчитывается, стёртая — снова авто`() {
        startNew()
        viewModel.onEvent(RefuelingEditEvent.LitersChanged("40"))
        viewModel.onEvent(RefuelingEditEvent.PriceChanged("55"))
        viewModel.onEvent(RefuelingEditEvent.TotalChanged("2100"))
        viewModel.onEvent(RefuelingEditEvent.LitersChanged("41"))
        assertEquals("2100", viewModel.state.value.form.totalCost)
        assertTrue(viewModel.state.value.form.isTotalManual)

        viewModel.onEvent(RefuelingEditEvent.TotalChanged(""))
        assertEquals("2255,00", viewModel.state.value.form.totalCost)
        assertFalse(viewModel.state.value.form.isTotalManual)
    }

    @Test
    fun `сохранение новой заправки — ответ лист`() = runTest {
        startNew()
        viewModel.onEvent(RefuelingEditEvent.LitersChanged("40"))
        viewModel.onEvent(RefuelingEditEvent.PriceChanged("55"))
        viewModel.onEvent(RefuelingEditEvent.StationChanged("Лукойл"))
        viewModel.onEvent(RefuelingEditEvent.PaymentChanged(PaymentType.COMPANY))
        viewModel.onEvent(RefuelingEditEvent.Save)
        assertTrue(viewModel.state.value.isSaving)
        advanceUntilIdle()

        val (sheetId, input) = repository.created.single()
        assertEquals(sheet.id, sheetId)
        assertEquals(BigDecimal("40"), input.liters)
        assertNull(input.totalCost)
        assertEquals("Лукойл", input.station)
        assertEquals(PaymentType.COMPANY, input.paymentType)
        assertEquals(savedSheet, viewModel.state.value.savedSheet)
    }

    @Test
    fun `ошибки полей — запрос не уходит`() = runTest {
        startNew()
        viewModel.onEvent(RefuelingEditEvent.Save)
        advanceUntilIdle()
        assertTrue(repository.created.isEmpty())
        assertEquals(UiText.Resource(R.string.error_required), viewModel.state.value.errors.liters)
    }

    @Test
    fun `изменение существующей заправки — PATCH по её id`() = runTest {
        startEdit()
        val state = viewModel.state.value
        assertFalse(state.isNew)
        assertEquals("30", state.form.liters)

        viewModel.onEvent(RefuelingEditEvent.LitersChanged("35"))
        viewModel.onEvent(RefuelingEditEvent.Save)
        advanceUntilIdle()

        val (id, input) = repository.updated.single()
        assertEquals("r3", id)
        assertEquals(BigDecimal("35"), input.liters)
        assertTrue(repository.created.isEmpty())
    }

    @Test
    fun `удаление — с подтверждением`() = runTest {
        startEdit()
        viewModel.onEvent(RefuelingEditEvent.RequestDelete)
        assertTrue(viewModel.state.value.confirmDelete)
        assertTrue(repository.deleted.isEmpty())

        viewModel.onEvent(RefuelingEditEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(listOf("r3"), repository.deleted)
        assertEquals(savedSheet, viewModel.state.value.savedSheet)
    }

    @Test
    fun `ошибка сервера — текст в шторке, поле подсвечено`() = runTest {
        repository.result = httpError(
            400,
            ErrorCodes.VALIDATION_ERROR,
            message = "Неверные данные запроса",
            details = mapOf("price_per_liter" to "Не больше 2 знаков после точки"),
        )
        startNew()
        viewModel.onEvent(RefuelingEditEvent.LitersChanged("40"))
        viewModel.onEvent(RefuelingEditEvent.PriceChanged("55"))
        viewModel.onEvent(RefuelingEditEvent.Save)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isSaving)
        assertNull(state.savedSheet)
        assertEquals(UiText.Raw("Неверные данные запроса"), state.error)
        assertEquals(UiText.Raw("Не больше 2 знаков после точки"), state.errors.pricePerLiter)
    }

    @Test
    fun `нет сети — Нет связи с сервером`() = runTest {
        repository.result = networkError
        startEdit()
        viewModel.onEvent(RefuelingEditEvent.Save)
        advanceUntilIdle()
        assertEquals(UiText.Resource(R.string.error_no_connection), viewModel.state.value.error)
    }

    @Test
    fun `повторное открытие после отмены — чистая форма`() {
        startEdit()
        viewModel.onEvent(RefuelingEditEvent.LitersChanged("99"))
        viewModel.onEvent(RefuelingEditEvent.Reset)
        startEdit()
        assertEquals("30", viewModel.state.value.form.liters)
    }

    @Test
    fun `повторный start той же заправки (поворот экрана) не теряет ввод`() {
        startEdit()
        viewModel.onEvent(RefuelingEditEvent.LitersChanged("99"))
        startEdit()
        assertEquals("99", viewModel.state.value.form.liters)
    }
}
