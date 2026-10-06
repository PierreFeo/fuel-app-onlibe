package ru.fueltracker.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.fueltracker.app.data.local.DataStoreSelectedCarStorage
import ru.fueltracker.app.data.local.DataStoreTokenStorage
import ru.fueltracker.app.data.local.SelectedCarStorage
import ru.fueltracker.app.data.local.TokenStorage
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    abstract fun bindTokenStorage(impl: DataStoreTokenStorage): TokenStorage

    @Binds
    abstract fun bindSelectedCarStorage(impl: DataStoreSelectedCarStorage): SelectedCarStorage

    companion object {

        /** Имя файла повторяется в res/xml/backup_rules.xml и data_extraction_rules.xml. */
        private const val DATASTORE_NAME = "fuel_tracker"

        /** Один DataStore на весь процесс — два экземпляра на один файл DataStore запрещает. */
        @Provides
        @Singleton
        fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                produceFile = { context.preferencesDataStoreFile(DATASTORE_NAME) },
            )
    }
}
