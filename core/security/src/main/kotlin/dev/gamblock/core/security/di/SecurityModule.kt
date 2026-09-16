package dev.gamblock.core.security.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.security.InstallIdProvider
import dev.gamblock.core.security.KeystoreInstallId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideInstallIdProvider(
        @ApplicationContext context: Context,
        logger: ShieldLogger,
    ): InstallIdProvider = KeystoreInstallId(context, logger)
}