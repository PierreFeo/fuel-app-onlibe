package ru.fueltracker.app.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/*
 * Локальная база — основная копия данных (docs/03_DATA_MODEL.md, «Локальная база на телефоне»).
 * Поля — как на сервере; дробные числа — строки с точкой ("45.50"), в коде — BigDecimal;
 * даты — ISO-строки. Служебные поля синхронизации:
 *  - is_dirty   — изменено на телефоне и ещё не отправлено;
 *  - is_deleted — удалено на телефоне, удаление не отправлено; на экранах не видно;
 *  - change_seq — растёт при каждом изменении строки: если запись поменяли во время
 *                 синхронизации, после неё она останется is_dirty.
 *
 * Уникальность листа на месяц (среди неудалённых) проверяет репозиторий в транзакции:
 * частичных индексов (WHERE is_deleted = 0) Room не поддерживает, а полный UNIQUE мешал бы
 * завести месяц заново, пока удаление старого листа ещё не отправлено.
 */

@Entity(tableName = "cars")
data class CarEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "plate_number") val plateNumber: String?,
    @ColumnInfo(name = "fuel_type") val fuelType: String,
    @ColumnInfo(name = "tank_capacity_l") val tankCapacityL: String,
    @ColumnInfo(name = "norm_l_per_100km") val normLPer100km: String,
    @ColumnInfo(name = "norm_winter_l_per_100km") val normWinterLPer100km: String?,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean,
    /** ISO-время UTC: «2026-10-02T08:15:00Z». */
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "is_dirty") val isDirty: Boolean = true,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean = false,
    @ColumnInfo(name = "change_seq") val changeSeq: Long = 1,
)

@Entity(
    tableName = "fuel_sheets",
    foreignKeys = [
        ForeignKey(
            entity = CarEntity::class,
            parentColumns = ["id"],
            childColumns = ["car_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("car_id")],
)
data class SheetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "car_id") val carId: String,
    val year: Int,
    val month: Int,
    val status: String,
    @ColumnInfo(name = "odometer_start_km") val odometerStartKm: Long,
    @ColumnInfo(name = "odometer_end_km") val odometerEndKm: Long?,
    @ColumnInfo(name = "fuel_start_l") val fuelStartL: String,
    @ColumnInfo(name = "fuel_end_actual_l") val fuelEndActualL: String?,
    val season: String,
    @ColumnInfo(name = "norm_l_per_100km") val normLPer100km: String,
    @ColumnInfo(name = "closed_at") val closedAt: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "is_dirty") val isDirty: Boolean = true,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean = false,
    @ColumnInfo(name = "change_seq") val changeSeq: Long = 1,
)

@Entity(
    tableName = "refuelings",
    foreignKeys = [
        ForeignKey(
            entity = SheetEntity::class,
            parentColumns = ["id"],
            childColumns = ["sheet_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sheet_id")],
)
data class RefuelingEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "sheet_id") val sheetId: String,
    /** ISO-дата: «2026-10-05». */
    @ColumnInfo(name = "refueled_at") val refueledAt: String,
    val liters: String,
    @ColumnInfo(name = "price_per_liter") val pricePerLiter: String,
    @ColumnInfo(name = "total_cost") val totalCost: String,
    @ColumnInfo(name = "odometer_km") val odometerKm: Long?,
    val station: String?,
    @ColumnInfo(name = "payment_type") val paymentType: String,
    val note: String?,
    @ColumnInfo(name = "is_dirty") val isDirty: Boolean = true,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean = false,
    @ColumnInfo(name = "change_seq") val changeSeq: Long = 1,
)

/** Лист вместе с заправками. Удалённые заправки (`is_deleted`) отфильтровывает репозиторий. */
data class SheetWithRefuelings(
    @Embedded val sheet: SheetEntity,
    @Relation(parentColumn = "id", entityColumn = "sheet_id")
    val refuelings: List<RefuelingEntity>,
)
