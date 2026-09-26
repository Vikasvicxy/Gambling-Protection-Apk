package dev.gamblock.data.backup.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gamblock.core.security.BackupCryptoManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    /**
     * One instance for the process. [BackupCryptoManager] only holds a
     * [java.security.SecureRandom], which is itself thread-safe, and sharing it
     * keeps a single entropy source for every export.
     */
    @Provides
    @Singleton
    fun provideBackupCryptoManager(): BackupCryptoManager = BackupCryptoManager()
}
