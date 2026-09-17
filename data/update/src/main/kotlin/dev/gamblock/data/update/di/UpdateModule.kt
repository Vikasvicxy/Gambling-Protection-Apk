package dev.gamblock.data.update.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gamblock.data.update.ReleaseDownloader
import dev.gamblock.data.update.SigningKeyProvider
import dev.gamblock.data.update.UpdateConfig
import dev.gamblock.data.update.UpdateFetcher
import dev.gamblock.data.update.SigningKeySource
import javax.inject.Singleton

/** Provides the (static, secret-free) update transport configuration and bindings. */
@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {

    @Provides
    @Singleton
    fun provideUpdateConfig(): UpdateConfig = UpdateConfig()

    @Provides
    @Singleton
    fun provideSigningKeySource(impl: SigningKeyProvider): SigningKeySource = impl

    @Provides
    @Singleton
    fun provideUpdateFetcher(impl: ReleaseDownloader): UpdateFetcher = impl
}