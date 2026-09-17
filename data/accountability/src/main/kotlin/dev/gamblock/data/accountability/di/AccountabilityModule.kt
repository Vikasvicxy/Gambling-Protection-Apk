package dev.gamblock.data.accountability.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gamblock.core.accountability.DeviceIdentityService
import dev.gamblock.core.accountability.EventGrouper
import dev.gamblock.core.accountability.HeartbeatEngine
import dev.gamblock.core.accountability.NotificationPolicyEngine
import dev.gamblock.core.accountability.PairingTokenService
import dev.gamblock.core.accountability.ParentAuthorization
import dev.gamblock.core.accountability.PartnerRelationshipManager
import dev.gamblock.core.accountability.ReplacementService
import dev.gamblock.core.accountability.SeverityCalculator
import dev.gamblock.core.admin.InMemoryAuditLogger
import dev.gamblock.core.admin.ModerationEngine
import dev.gamblock.core.admin.ReportInputValidator
import dev.gamblock.core.admin.SlidingWindowRateLimiter
import dev.gamblock.core.model.GroupingConfig
import dev.gamblock.core.model.HeartbeatConfig
import dev.gamblock.core.model.ShieldBackendClient
import dev.gamblock.data.accountability.backend.BackendConfig
import dev.gamblock.data.accountability.backend.OkHttpBackendClient
import dev.gamblock.data.accountability.db.AccountabilityDatabase
import dev.gamblock.data.accountability.db.dao.ApprovalRequestDao
import dev.gamblock.data.accountability.db.dao.EventGroupingDao
import dev.gamblock.data.accountability.db.dao.HeartbeatDao
import dev.gamblock.data.accountability.db.dao.PairingTokenDao
import dev.gamblock.data.accountability.db.dao.PartnerRelationshipDao
import dev.gamblock.data.accountability.db.dao.ReplacementRequestDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AccountabilityModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AccountabilityDatabase =
        AccountabilityDatabase.get(context)

    @Provides fun providePairingTokenDao(db: AccountabilityDatabase): PairingTokenDao = db.pairingTokenDao()
    @Provides fun providePartnerRelationshipDao(db: AccountabilityDatabase): PartnerRelationshipDao = db.partnerRelationshipDao()
    @Provides fun provideHeartbeatDao(db: AccountabilityDatabase): HeartbeatDao = db.heartbeatDao()
    @Provides fun provideEventGroupingDao(db: AccountabilityDatabase): EventGroupingDao = db.eventGroupingDao()
    @Provides fun provideApprovalRequestDao(db: AccountabilityDatabase): ApprovalRequestDao = db.approvalRequestDao()
    @Provides fun provideReplacementRequestDao(db: AccountabilityDatabase): ReplacementRequestDao = db.replacementRequestDao()

    @Provides
    @Singleton
    fun providePairingTokenService(): PairingTokenService = PairingTokenService()

    @Provides
    @Singleton
    fun providePartnerRelationshipManager(): PartnerRelationshipManager = PartnerRelationshipManager()

    @Provides
    @Singleton
    fun provideHeartbeatEngine(): HeartbeatEngine = HeartbeatEngine(System::currentTimeMillis)

    @Provides
    @Singleton
    fun provideEventGrouper(): EventGrouper = EventGrouper(config = GroupingConfig(), clock = System::currentTimeMillis)

    @Provides
    @Singleton
    fun provideSeverityCalculator(): SeverityCalculator = SeverityCalculator()

    @Provides
    @Singleton
    fun provideNotificationPolicyEngine(): NotificationPolicyEngine = NotificationPolicyEngine()

    @Provides
    @Singleton
    fun provideParentAuthorization(): ParentAuthorization = ParentAuthorization()

    @Provides
    @Singleton
    fun provideDeviceIdentityService(): DeviceIdentityService = DeviceIdentityService()

    @Provides
    @Singleton
    fun provideReplacementService(): ReplacementService = ReplacementService()

    @Provides
    @Singleton
    fun provideAuditLogger(): InMemoryAuditLogger = InMemoryAuditLogger()

    @Provides
    @Singleton
    fun provideModerationEngine(logger: InMemoryAuditLogger): ModerationEngine =
        ModerationEngine(logger)

    @Provides
    @Singleton
    fun provideReportInputValidator(): ReportInputValidator = ReportInputValidator

    @Provides
    @Singleton
    fun provideRateLimiter(): SlidingWindowRateLimiter = SlidingWindowRateLimiter()

    @Provides
    @Singleton
    fun provideBackendClient(): ShieldBackendClient =
        OkHttpBackendClient(baseUrl = BackendConfig.DEFAULT_BASE_URL)
}