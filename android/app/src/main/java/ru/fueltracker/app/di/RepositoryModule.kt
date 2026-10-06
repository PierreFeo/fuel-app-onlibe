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
import ru.fueltracker.app.data.repository.ProfileRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindAuthRepository(impl: DefaultAuthRepository): AuthRepository

    @Binds
    abstract fun bindProfileRepository(impl: DefaultProfileRepository): ProfileRepository

    @Binds
    abstract fun bindCarRepository(impl: DefaultCarRepository): CarRepository
}
