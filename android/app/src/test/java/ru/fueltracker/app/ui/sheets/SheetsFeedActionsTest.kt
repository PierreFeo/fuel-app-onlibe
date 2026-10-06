package ru.fueltracker.app.ui.sheets

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.fueltracker.app.R
import ru.fueltracker.app.data.local.FakeSelectedCarStorage
import ru.fueltracker.app.data.repository.LocalResult
import ru.fueltracker.app.domain.calc.SheetRuleViolation
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetStatus
import ru.fueltracker.app.testutil.FakeCarRepository
import ru.fueltracker.app.testutil.FakeRefuelingRepository
import ru.fueltracker.app.testutil.FakeSheetRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.testCar
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.refueling.RefuelingTarget
import java.math.BigDecimal

/** Действия в ленте: новый лист, закрытие, переоткрытие, удаление, сезон, заправки. */
class SheetsFeedActionsTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val cars = FakeCarRepository().apply { cars = listOf(testCar(id = "car-1")) }
    private val sheets = FakeSheetRepository()
    private val refuelings = FakeRefuelingRepository()
    private val selected = FakeSelectedCarStorage(initial = "car-1")

    private val october = previewOpenSheet().copy(id = "s-10", month = 10, refuelings = emptyList())
    private val september = previewClosedSheet().copy(id = "s-09", month = 9)

    /** Открытый октябрь с одной заправкой — для удаления из списка. */
    private val octoberWithRefueling = october.copy(refuelings = previewOpenSheet().refuelings)
    private val refueling = octoberWithRefueling.refuelings.first()

    private fun TestScope.createViewModel(
        feed: List<FuelSheet> = listOf(october, september),
    ): SheetsFeedViewModel {
        sheets.sheets = feed
        val viewModel = SheetsFeedViewModel(cars, sheets, refuelings, selected)
        advanceUntilIdle()
        return viewModel
    }

    private fun SheetsFeedViewModel.sheet(id: String) = state.value.sheets.first { it.id == id }

    // --- Новый лист ---

    @Test
    fun `Новый лист — форма из подсказки next-prefill`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.NewSheet)
        assertTrue(viewModel.state.value.isPreparingNewSheet)
        advanceUntilIdle()

        val form = viewModel.state.value.newSheet!!
        assertFalse(viewModel.state.value.isPreparingNewSheet)
        assertEquals(2026 to 11, form.year to form.month)
        assertEquals("53340", form.odometerStart)
        assertEquals("10", form.fuelStart)
    }

    @Test
    fun `создание — лист встаёт в ленту по месяцу, диалог закрыт`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.NewSheet)
        advanceUntilIdle()
        viewModel.onEvent(SheetsFeedEvent.NewSheetSeason(Season.WINTER))
        viewModel.onEvent(SheetsFeedEvent.NewSheetFuel("12,5"))
        viewModel.onEvent(SheetsFeedEvent.ConfirmNewSheet)
        advanceUntilIdle()

        val (carId, input) = sheets.created.single()
        assertEquals("car-1", carId)
        assertEquals(Season.WINTER, input.season)
        assertEquals(BigDecimal("12.5"), input.fuelStartL)
        val state = viewModel.state.value
        assertNull(state.newSheet)
        assertEquals(listOf("new-2026-11", "s-10", "s-09"), state.sheets.map { it.id })
    }

    @Test
    fun `создание — ошибки полей не создают лист`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.NewSheet)
        advanceUntilIdle()
        viewModel.onEvent(SheetsFeedEvent.NewSheetOdometer(""))
        viewModel.onEvent(SheetsFeedEvent.ConfirmNewSheet)
        advanceUntilIdle()

        assertTrue(sheets.created.isEmpty())
        assertEquals(UiText.Resource(R.string.error_required), viewModel.state.value.newSheet?.odometerError)
    }

    @Test
    fun `создание — лист уже есть, текст в диалоге`() = runTest {
        sheets.violation = SheetRuleViolation.SHEET_EXISTS
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.NewSheet)
        advanceUntilIdle()
        viewModel.onEvent(SheetsFeedEvent.ConfirmNewSheet)
        advanceUntilIdle()

        val form = viewModel.state.value.newSheet!!
        assertEquals(UiText.Resource(R.string.rule_sheet_exists), form.error)
        assertFalse(form.isSaving)
    }

    @Test
    fun `в поля пробега попадают только цифры`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.NewSheet)
        advanceUntilIdle()
        viewModel.onEvent(SheetsFeedEvent.NewSheetOdometer("53 340 км"))
        assertEquals("53340", viewModel.state.value.newSheet?.odometerStart)
    }

    // --- Закрытие ---

    @Test
    fun `закрыть месяц — лист обновлён, диалог закрыт`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.Close(october))
        viewModel.onEvent(SheetsFeedEvent.CloseOdometer("54340"))
        viewModel.onEvent(SheetsFeedEvent.CloseFuel("7,5"))
        viewModel.onEvent(SheetsFeedEvent.ConfirmClose)
        advanceUntilIdle()

        assertEquals(listOf(Triple("s-10", 54_340L, BigDecimal("7.5"))), sheets.closeCalls)
        assertNull(viewModel.state.value.closeSheet)
        assertEquals(SheetStatus.CLOSED, viewModel.sheet("s-10").status)
    }

    @Test
    fun `закрыть месяц — нарушено правило, текст в диалоге`() = runTest {
        sheets.violation = SheetRuleViolation.FUEL_END_OVER_AVAILABLE
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.Close(october))
        viewModel.onEvent(SheetsFeedEvent.CloseOdometer("54340"))
        viewModel.onEvent(SheetsFeedEvent.CloseFuel("500"))
        viewModel.onEvent(SheetsFeedEvent.ConfirmClose)
        advanceUntilIdle()

        val form = viewModel.state.value.closeSheet!!
        assertEquals(UiText.Resource(R.string.rule_fuel_end_over_available), form.error)
        assertFalse(form.isSaving)
        assertEquals(SheetStatus.OPEN, viewModel.sheet("s-10").status)
    }

    @Test
    fun `отмена закрытия`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.Close(october))
        viewModel.onEvent(SheetsFeedEvent.DismissClose)
        assertNull(viewModel.state.value.closeSheet)
    }

    // --- Переоткрытие и удаление ---

    @Test
    fun `переоткрыть закрытый месяц`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.Reopen(september))
        assertTrue("s-09" in viewModel.state.value.busySheetIds)
        advanceUntilIdle()

        assertEquals(listOf("s-09"), sheets.reopenCalls)
        val state = viewModel.state.value
        assertEquals(SheetStatus.OPEN, viewModel.sheet("s-09").status)
        assertTrue(state.busySheetIds.isEmpty())
        assertEquals(UiText.Resource(R.string.sheet_reopened), state.snackbar)
    }

    @Test
    fun `удалить лист — с подтверждением`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.RequestDelete(october))
        assertEquals(october, viewModel.state.value.deleteCandidate)
        viewModel.onEvent(SheetsFeedEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(listOf("s-10"), sheets.deleteCalls)
        assertEquals(listOf("s-09"), viewModel.state.value.sheets.map { it.id })
        assertEquals(UiText.Resource(R.string.sheet_deleted), viewModel.state.value.snackbar)
    }

    @Test
    fun `лист с заправками не удалить — подсказка, лист остаётся`() = runTest {
        sheets.violation = SheetRuleViolation.SHEET_HAS_REFUELINGS
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.RequestDelete(october))
        viewModel.onEvent(SheetsFeedEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(2, viewModel.state.value.sheets.size)
        assertEquals(UiText.Resource(R.string.rule_sheet_has_refuelings), viewModel.state.value.snackbar)
    }

    // --- Сезон ---

    @Test
    fun `переключение сезона — лист обновлён, Snackbar с новой нормой`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.ToggleSeason(october))
        advanceUntilIdle()

        assertEquals(listOf("s-10" to Season.WINTER), sheets.seasonCalls)
        val state = viewModel.state.value
        assertEquals(Season.WINTER, viewModel.sheet("s-10").season)
        assertEquals(UiText.Resource(R.string.sheet_season_switched_winter, listOf("11,684")), state.snackbar)
        assertNull(state.snackbarAction)
    }

    @Test
    fun `нет зимней нормы — Snackbar с кнопкой Указать, сезон не меняется`() = runTest {
        sheets.violation = SheetRuleViolation.WINTER_NORM_NOT_SET
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.ToggleSeason(october))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(Season.SUMMER, viewModel.sheet("s-10").season)
        assertEquals(UiText.Resource(R.string.winter_norm_not_set), state.snackbar)
        assertEquals(FeedSnackbarAction.SET_WINTER_NORM, state.snackbarAction)

        viewModel.onEvent(SheetsFeedEvent.SnackbarShown)
        assertNull(viewModel.state.value.snackbarAction)
    }

    @Test
    fun `сезон закрытого листа — подсказка, без изменений`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.ToggleSeason(september))
        advanceUntilIdle()
        assertTrue(sheets.seasonCalls.isEmpty())
        assertEquals(UiText.Resource(R.string.sheet_closed_cannot_edit), viewModel.state.value.snackbar)
    }

    // --- Заправки ---

    @Test
    fun `+ Заправка открывает шторку для листа`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.OpenRefueling(october))
        assertEquals(RefuelingTarget("s-10", 2026, 10), viewModel.state.value.refuelingTarget)

        viewModel.onEvent(SheetsFeedEvent.DismissRefueling)
        assertNull(viewModel.state.value.refuelingTarget)
    }

    @Test
    fun `заправку закрытого листа не открыть`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.OpenRefueling(september, september.refuelings.first()))
        assertNull(viewModel.state.value.refuelingTarget)
        assertEquals(UiText.Resource(R.string.sheet_closed_cannot_edit), viewModel.state.value.snackbar)
    }

    @Test
    fun `сохранённая заправка — шторка закрыта, карточка обновится из базы`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.OpenRefueling(october))

        viewModel.onEvent(SheetsFeedEvent.RefuelingSaved)
        sheets.sheets = listOf(octoberWithRefueling, september) // так база сообщает о новой заправке
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.refuelingTarget)
        assertEquals(octoberWithRefueling, viewModel.sheet("s-10"))
        assertNull(state.refuelingsSheet)
    }

    // --- Список заправок (шторка по кнопке [≡]) ---

    @Test
    fun `список заправок открывается и закрывается`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.OpenRefuelings("s-09"))
        assertEquals(september, viewModel.state.value.refuelingsSheet)

        viewModel.onEvent(SheetsFeedEvent.DismissRefuelings)
        assertNull(viewModel.state.value.refuelingsSheet)
    }

    @Test
    fun `пока открыта форма заправки, список скрыт, после — снова виден со свежими данными`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.OpenRefuelings("s-10"))
        viewModel.onEvent(SheetsFeedEvent.OpenRefueling(october))
        assertNull(viewModel.state.value.refuelingsSheet)

        viewModel.onEvent(SheetsFeedEvent.RefuelingSaved)
        sheets.sheets = listOf(octoberWithRefueling, september)
        advanceUntilIdle()

        assertEquals(octoberWithRefueling, viewModel.state.value.refuelingsSheet)
    }

    // --- Удаление заправки из списка ---

    @Test
    fun `удаление заправки из списка — с подтверждением`() = runTest {
        val viewModel = createViewModel(feed = listOf(octoberWithRefueling, september))
        viewModel.onEvent(SheetsFeedEvent.OpenRefuelings("s-10"))

        viewModel.onEvent(SheetsFeedEvent.RequestDeleteRefueling(refueling))
        assertEquals(refueling, viewModel.state.value.refuelingToDelete)
        assertTrue(refuelings.deleteCalls.isEmpty()) // без подтверждения не удаляем

        viewModel.onEvent(SheetsFeedEvent.ConfirmDeleteRefueling)
        assertTrue("s-10" in viewModel.state.value.busySheetIds)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(refueling.id), refuelings.deleteCalls)
        assertNull(state.refuelingToDelete)
        assertTrue(state.busySheetIds.isEmpty())
        assertEquals("s-10", state.refuelingsSheet?.id) // шторка остаётся открытой
    }

    @Test
    fun `отмена удаления заправки — ничего не удалено`() = runTest {
        val viewModel = createViewModel(feed = listOf(octoberWithRefueling, september))
        viewModel.onEvent(SheetsFeedEvent.OpenRefuelings("s-10"))
        viewModel.onEvent(SheetsFeedEvent.RequestDeleteRefueling(refueling))

        viewModel.onEvent(SheetsFeedEvent.DismissDeleteRefueling)
        advanceUntilIdle()

        assertNull(viewModel.state.value.refuelingToDelete)
        assertTrue(refuelings.deleteCalls.isEmpty())
    }

    @Test
    fun `удаление заправки не удалось — текст в шторке, при повторном открытии его нет`() = runTest {
        refuelings.result = LocalResult.Rejected(SheetRuleViolation.SHEET_CLOSED)
        val viewModel = createViewModel(feed = listOf(octoberWithRefueling, september))
        viewModel.onEvent(SheetsFeedEvent.OpenRefuelings("s-10"))
        viewModel.onEvent(SheetsFeedEvent.RequestDeleteRefueling(refueling))

        viewModel.onEvent(SheetsFeedEvent.ConfirmDeleteRefueling)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(UiText.Resource(R.string.sheet_closed_cannot_edit), state.refuelingsError)
        assertNull(state.snackbar) // Snackbar под шторкой не виден

        viewModel.onEvent(SheetsFeedEvent.DismissRefuelings)
        viewModel.onEvent(SheetsFeedEvent.OpenRefuelings("s-10"))
        assertNull(viewModel.state.value.refuelingsError)
    }

    @Test
    fun `шапка авто обновляется сама — после CarEditScreen`() = runTest {
        val viewModel = createViewModel()
        cars.cars = listOf(testCar(id = "car-1", normWinter = null))
        advanceUntilIdle()
        assertNull(viewModel.state.value.car?.normWinter)
    }
}
