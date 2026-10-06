package ru.fueltracker.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.fueltracker.app.data.local.db.AppDatabase
import ru.fueltracker.app.data.local.db.CarDao
import ru.fueltracker.app.data.local.db.LocalData
import ru.fueltracker.app.data.local.db.RoomLocalData
import ru.fueltracker.app.data.local.db.RoomSyncStore
import ru.fueltracker.app.data.local.db.SyncStore
import ru.fueltracker.app.data.local.db.RefuelingDao
import ru.fueltracker.app.data.local.db.SheetDao
import ru.fueltracker.app.data.local.db.SyncDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** Имя файла повторяется в res/xml/backup_rules.xml и data_extraction_rules.xml. */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides
    fun provideCarDao(db: AppDatabase): CarDao = db.carDao()

    @Provides
    fun provideSheetDao(db: AppDatabase): SheetDao = db.sheetDao()

    @Provides
    fun provideRefuelingDao(db: AppDatabase): RefuelingDao = db.refuelingDao()

    @Provides
    fun provideSyncDao(db: AppDatabase): SyncDao = db.syncDao()

    @Provides
    fun provideLocalData(impl: RoomLocalData): LocalData = impl

    @Provides
    fun provideSyncStore(impl: RoomSyncStore): SyncStore = impl
}
