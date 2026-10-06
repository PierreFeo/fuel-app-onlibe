package ru.fueltracker.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Основная копия данных на телефоне. Схема меняется только с миграцией;
 * JSON-схемы версий лежат в `app/schemas/` (android/CLAUDE.md).
 */
@Database(
    entities = [CarEntity::class, SheetEntity::class, RefuelingEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun carDao(): CarDao
    abstract fun sheetDao(): SheetDao
    abstract fun refuelingDao(): RefuelingDao
    abstract fun syncDao(): SyncDao

    companion object {
        const val NAME = "fuel_tracker.db"
    }
}
