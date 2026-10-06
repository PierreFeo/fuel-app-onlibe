package ru.fueltracker.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.fueltracker.app.data.repository.AuthRepository
import ru.fueltracker.app.data.repository.CarRepository
import ru.fueltracker.app.data.repository.DefaultAuthRepository
import ru.fueltracker.app.data.repository.DefaultCarRepository
import ru.fueltracker.app.data.repository.DefaultProfileRepository
import ru.fueltracker.app.data.repository.DefaultRefuelingRepository
import ru.fueltracker.app.data.repository.DefaultSheetRepository
import ru.fueltracker.app.data.repository.ProfileRepository
import ru.fueltracker.app.data.repository.RefuelingRepository
import ru.fueltracker.app.data.repository.SheetRepository
import ru.fueltracker.app.data.repository.DefaultSyncRepository
import ru.fueltracker.app.data.repository.SyncRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindAuthRepository(impl: DefaultAuthRepository): AuthRepository

    @Binds
    abstract fun bindProfileRepository(impl: DefaultProfileRepository): ProfileRepository

    @Binds
    abstract fun bindCarRepository(impl: DefaultCarRepository): CarRepository

    @Binds
    abstract fun bindSheetRepository(impl: DefaultSheetRepository): SheetRepository

    @Binds
    abstract fun bindRefuelingRepository(impl: DefaultRefuelingRepository): RefuelingRepository

    @Binds
    abstract fun bindSyncRepository(impl: DefaultSyncRepository): SyncRepository
}
