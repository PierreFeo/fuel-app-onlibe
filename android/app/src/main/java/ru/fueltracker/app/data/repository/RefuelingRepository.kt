package ru.fueltracker.app.data.repository

import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.PatchField
import ru.fueltracker.app.data.remote.api.RefuelingsApi
import ru.fueltracker.app.data.remote.apiCall
import ru.fueltracker.app.data.remote.dto.RefuelingCreateRequest
import ru.fueltracker.app.data.remote.dto.RefuelingPatchRequest
import ru.fueltracker.app.data.remote.map
import ru.fueltracker.app.domain.model.FuelSheet
import ru.fueltracker.app.domain.model.PaymentType
import ru.fueltracker.app.domain.model.RefuelingInput
import javax.inject.Inject
import javax.inject.Singleton
import ru.fueltracker.app.data.remote.dto.PaymentType as PaymentTypeDto

/** Заправки. Каждый ответ — весь лист с пересчитанным `calc`: карточка обновляется без второго запроса. */
interface RefuelingRepository {

    suspend fun create(sheetId: String, input: RefuelingInput): ApiResult<FuelSheet>

    suspend fun update(refuelingId: String, input: RefuelingInput): ApiResult<FuelSheet>

    suspend fun delete(refuelingId: String): ApiResult<FuelSheet>
}

@Singleton
class DefaultRefuelingRepository @Inject constructor(
    private val api: RefuelingsApi,
) : RefuelingRepository {

    override suspend fun create(sheetId: String, input: RefuelingInput): ApiResult<FuelSheet> =
        apiCall {
            api.createRefueling(
                sheetId,
                RefuelingCreateRequest(
                    refueledAt = input.date.toString(),
                    liters = input.liters.toPlainString(),
                    pricePerLiter = input.pricePerLiter.toPlainString(),
                    totalCost = input.totalCost?.toPlainString(),
                    odometerKm = input.odometerKm,
                    station = input.station,
                    paymentType = input.paymentType.toDto(),
                    note = input.note,
                ),
            )
        }.map { it.toDomain() }

    // Форма редактирует все поля — отправляем все; null стирает пробег/АЗС/комментарий,
    // а у суммы означает «пересчитать литры × цена»
    override suspend fun update(refuelingId: String, input: RefuelingInput): ApiResult<FuelSheet> =
        apiCall {
            api.updateRefueling(
                refuelingId,
                RefuelingPatchRequest(
                    refueledAt = input.date.toString(),
                    liters = input.liters.toPlainString(),
                    pricePerLiter = input.pricePerLiter.toPlainString(),
                    totalCost = PatchField.Present(input.totalCost?.toPlainString()),
                    odometerKm = PatchField.Present(input.odometerKm),
                    station = PatchField.Present(input.station),
                    paymentType = input.paymentType.toDto(),
                    note = PatchField.Present(input.note),
                ),
            )
        }.map { it.toDomain() }

    override suspend fun delete(refuelingId: String): ApiResult<FuelSheet> =
        apiCall { api.deleteRefueling(refuelingId) }.map { it.toDomain() }
}

private fun PaymentType.toDto() = PaymentTypeDto.valueOf(name)
