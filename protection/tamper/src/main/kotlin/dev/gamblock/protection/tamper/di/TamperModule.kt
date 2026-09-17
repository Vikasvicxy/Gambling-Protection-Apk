package dev.gamblock.protection.tamper.di

import android.content.Context
import android.content.pm.PackageManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.protection.tamper.AppEnvironmentProbe
import dev.gamblock.protection.tamper.DataStoreTamperEvidenceStore
import dev.gamblock.protection.tamper.EvidenceHmac
import dev.gamblock.protection.tamper.KeystoreEvidenceHmac
import dev.gamblock.protection.tamper.TamperEngine
import dev.gamblock.protection.tamper.TamperEvidenceStore
import dev.gamblock.protection.tamper.TamperRecorder
import dev.gamblock.protection.tamper.TamperSignalProbe
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TamperModule {

    @Provides
    @Singleton
    fun provideEvidenceHmac(): EvidenceHmac = KeystoreEvidenceHmac()

    @Provides
    @Singleton
    fun provideTamperEvidenceStore(
        @ApplicationContext context: Context,
        logger: ShieldLogger,
    ): TamperEvidenceStore = DataStoreTamperEvidenceStore(context, logger)

    @Provides
    @Singleton
    fun providePackageLookup(
        @ApplicationContext context: Context,
    ): dev.gamblock.protection.tamper.PackageLookup {
        val pm: PackageManager? = try {
            context.packageManager
        } catch (_: Throwable) {
            null
        }
        return dev.gamblock.protection.tamper.PackageLookup { packageName ->
            pm?.let {
                try {
                    it.getPackageInfo(packageName, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            } ?: false
        }
    }

    @Provides
    @Singleton
    fun provideTamperSignalProbe(
        packageLookup: dev.gamblock.protection.tamper.PackageLookup,
        @ApplicationContext context: Context,
    ): TamperSignalProbe = AppEnvironmentProbe(
        packageLookup = packageLookup,
        debuggableApp = AppEnvironmentProbe.isDebuggable(context.applicationInfo),
    )

    @Provides
    @Singleton
    fun provideTamperRecorder(
        store: TamperEvidenceStore,
        hmac: EvidenceHmac,
    ): TamperRecorder = TamperRecorder(
        store = store,
        hmac = hmac,
        clock = System::currentTimeMillis,
    )

    @Provides
    @Singleton
    fun provideTamperEngine(
        probe: TamperSignalProbe,
        recorder: TamperRecorder,
        logger: ShieldLogger,
    ): TamperEngine = TamperEngine(probe, recorder, logger)
}