package ru.fueltracker.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock

/** Часы приложения: «сегодня» для подсказки нового листа и время создания/закрытия записей. */
@Module
@InstallIn(SingletonComponent::class)
object TimeModule {

    @Provides
    fun provideClock(): Clock = Clock.systemDefaultZone()
}
