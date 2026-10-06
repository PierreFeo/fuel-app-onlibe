package ru.fueltracker.app.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Запросы «для экранов» скрывают удалённые строки (is_deleted = 1); запросы «для синхронизации»
 * видят все. markSynced/deleteSyncedTombstone срабатывают, только если change_seq не изменился
 * с момента отправки — иначе запись поменяли во время синхронизации и она уйдёт в следующий раз.
 */

@Dao
interface CarDao {
    @Query("SELECT * FROM cars WHERE is_deleted = 0 ORDER BY created_at, id")
    fun observeAll(): Flow<List<CarEntity>>

    @Query("SELECT * FROM cars WHERE id = :id AND is_deleted = 0")
    fun observe(id: String): Flow<CarEntity?>

    /** Любая строка, в том числе удалённая (нужно синхронизации и repository). */
    @Query("SELECT * FROM cars WHERE id = :id")
    suspend fun get(id: String): CarEntity?

    @Upsert
    suspend fun upsert(car: CarEntity)

    @Query("SELECT * FROM cars WHERE is_dirty = 1")
    suspend fun dirty(): List<CarEntity>

    @Query("UPDATE cars SET is_dirty = 0 WHERE id = :id AND change_seq = :changeSeq AND is_deleted = 0")
    suspend fun markSynced(id: String, changeSeq: Long)

    @Query("DELETE FROM cars WHERE id = :id AND change_seq = :changeSeq AND is_deleted = 1")
    suspend fun deleteSyncedTombstone(id: String, changeSeq: Long)

    @Query("DELETE FROM cars WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SheetDao {
    /** Лента: листы авто, новые сверху. */
    @Transaction
    @Query("SELECT * FROM fuel_sheets WHERE car_id = :carId AND is_deleted = 0 ORDER BY year DESC, month DESC")
    fun observeForCar(carId: String): Flow<List<SheetWithRefuelings>>

    @Query("SELECT * FROM fuel_sheets WHERE id = :id")
    suspend fun get(id: String): SheetEntity?

    @Transaction
    @Query("SELECT * FROM fuel_sheets WHERE id = :id AND is_deleted = 0")
    suspend fun getWithRefuelings(id: String): SheetWithRefuelings?

    /** Самый поздний лист авто — от него считается подсказка для нового листа. */
    @Transaction
    @Query("SELECT * FROM fuel_sheets WHERE car_id = :carId AND is_deleted = 0 ORDER BY year DESC, month DESC LIMIT 1")
    suspend fun latestForCar(carId: String): SheetWithRefuelings?

    /** Неудалённый лист авто за месяц — проверка «один лист на месяц». */
    @Query("SELECT * FROM fuel_sheets WHERE car_id = :carId AND year = :year AND month = :month AND is_deleted = 0 LIMIT 1")
    suspend fun findMonth(carId: String, year: Int, month: Int): SheetEntity?

    @Query("SELECT * FROM fuel_sheets WHERE car_id = :carId AND is_deleted = 0")
    suspend fun aliveForCar(carId: String): List<SheetEntity>

    @Upsert
    suspend fun upsert(sheet: SheetEntity)

    @Query("SELECT * FROM fuel_sheets WHERE is_dirty = 1")
    suspend fun dirty(): List<SheetEntity>

    @Query("UPDATE fuel_sheets SET is_dirty = 0 WHERE id = :id AND change_seq = :changeSeq AND is_deleted = 0")
    suspend fun markSynced(id: String, changeSeq: Long)

    @Query("DELETE FROM fuel_sheets WHERE id = :id AND change_seq = :changeSeq AND is_deleted = 1")
    suspend fun deleteSyncedTombstone(id: String, changeSeq: Long)

    @Query("DELETE FROM fuel_sheets WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RefuelingDao {
    @Query("SELECT * FROM refuelings WHERE id = :id")
    suspend fun get(id: String): RefuelingEntity?

    @Query("SELECT * FROM refuelings WHERE sheet_id = :sheetId AND is_deleted = 0")
    suspend fun aliveForSheet(sheetId: String): List<RefuelingEntity>

    @Upsert
    suspend fun upsert(refueling: RefuelingEntity)

    @Query("SELECT * FROM refuelings WHERE is_dirty = 1")
    suspend fun dirty(): List<RefuelingEntity>

    @Query("UPDATE refuelings SET is_dirty = 0 WHERE id = :id AND change_seq = :changeSeq AND is_deleted = 0")
    suspend fun markSynced(id: String, changeSeq: Long)

    @Query("DELETE FROM refuelings WHERE id = :id AND change_seq = :changeSeq AND is_deleted = 1")
    suspend fun deleteSyncedTombstone(id: String, changeSeq: Long)

    @Query("DELETE FROM refuelings WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SyncDao {
    /** Сколько изменений ещё не отправлено — для профиля. */
    @Query(
        "SELECT (SELECT COUNT(*) FROM cars WHERE is_dirty = 1)" +
            " + (SELECT COUNT(*) FROM fuel_sheets WHERE is_dirty = 1)" +
            " + (SELECT COUNT(*) FROM refuelings WHERE is_dirty = 1)",
    )
    fun observeDirtyCount(): Flow<Int>

    @Query(
        "SELECT (SELECT COUNT(*) FROM cars) + (SELECT COUNT(*) FROM fuel_sheets)" +
            " + (SELECT COUNT(*) FROM refuelings)",
    )
    suspend fun totalCount(): Int
}
