package dev.gamblock.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gamblock.core.common.clock.MonotonicClock
import dev.gamblock.core.common.clock.SystemWallClock
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DefaultDispatchersProvider
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.AndroidLogger
import dev.gamblock.core.common.logging.ShieldLogger
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CommonModule {

    @Provides
    @Singleton
    fun provideWallClock(): WallClock = SystemWallClock

    @Provides
    @Singleton
    fun provideMonotonicClock(): MonotonicClock = MonotonicClock.SYSTEM

    @Provides
    @Singleton
    fun provideDispatchers(): DispatchersProvider = DefaultDispatchersProvider()

    @Provides
    @Singleton
    fun provideLogger(): ShieldLogger = AndroidLogger()
}