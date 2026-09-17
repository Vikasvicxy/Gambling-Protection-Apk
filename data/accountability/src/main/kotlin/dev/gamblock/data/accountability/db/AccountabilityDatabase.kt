package dev.gamblock.data.accountability.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dev.gamblock.data.accountability.db.dao.ApprovalRequestDao
import dev.gamblock.data.accountability.db.dao.EventGroupingDao
import dev.gamblock.data.accountability.db.dao.HeartbeatDao
import dev.gamblock.data.accountability.db.dao.PairingTokenDao
import dev.gamblock.data.accountability.db.dao.PartnerRelationshipDao
import dev.gamblock.data.accountability.db.dao.ReplacementRequestDao
import dev.gamblock.data.accountability.db.entity.ApprovalRequestEntity
import dev.gamblock.data.accountability.db.entity.EventGroupingEntity
import dev.gamblock.data.accountability.db.entity.HeartbeatEntity
import dev.gamblock.data.accountability.db.entity.PairingTokenEntity
import dev.gamblock.data.accountability.db.entity.PartnerRelationshipEntity
import dev.gamblock.data.accountability.db.entity.ReplacementRequestEntity
import dev.gamblock.core.model.AccountabilitySeverity
import dev.gamblock.core.model.ApprovalStatus
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.Confidence
import dev.gamblock.core.model.DeviceReplacementRequest
import dev.gamblock.core.model.DeviceReplacementState
import dev.gamblock.core.model.ProtectionState
import dev.gamblock.core.model.RelationshipStatus
import dev.gamblock.core.model.SensitiveChange

class AccountabilityConverters {
    @TypeConverter fun categoryToString(value: Category): String = value.name
    @TypeConverter fun stringToCategory(value: String): Category = Category.fromStorage(value)
    @TypeConverter fun relationshipStatusToString(value: RelationshipStatus): String = value.name
    @TypeConverter fun stringToRelationshipStatus(value: String): RelationshipStatus =
        if (value.isBlank()) RelationshipStatus.PENDING else RelationshipStatus.valueOf(value)
    @TypeConverter fun protectionStateToString(value: ProtectionState): String = value.name
    @TypeConverter fun stringToProtectionState(value: String): ProtectionState =
        if (value.isBlank()) ProtectionState.UNKNOWN else ProtectionState.valueOf(value)
    @TypeConverter fun approvalStatusToString(value: ApprovalStatus): String = value.name
    @TypeConverter fun stringToApprovalStatus(value: String): ApprovalStatus =
        if (value.isBlank()) ApprovalStatus.PENDING else ApprovalStatus.valueOf(value)
    @TypeConverter fun sensitiveChangeToString(value: SensitiveChange): String = value.name
    @TypeConverter fun stringToSensitiveChange(value: String): SensitiveChange =
        if (value.isBlank()) SensitiveChange.REPLACE_PROTECTED_DEVICE else SensitiveChange.valueOf(value)
    @TypeConverter fun severityToString(value: AccountabilitySeverity): String = value.name
    @TypeConverter fun stringToSeverity(value: String): AccountabilitySeverity =
        if (value.isBlank()) AccountabilitySeverity.LOW else AccountabilitySeverity.valueOf(value)
    @TypeConverter fun replacementStateToString(value: DeviceReplacementState): String = value.name
    @TypeConverter fun stringToReplacementState(value: String): DeviceReplacementState =
        if (value.isBlank()) DeviceReplacementState.PENDING
        else DeviceReplacementState.valueOf(value)
}

@Database(
    entities = [
        PairingTokenEntity::class,
        PartnerRelationshipEntity::class,
        HeartbeatEntity::class,
        EventGroupingEntity::class,
        ApprovalRequestEntity::class,
        ReplacementRequestEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(AccountabilityConverters::class)
abstract class AccountabilityDatabase : RoomDatabase() {
    abstract fun pairingTokenDao(): PairingTokenDao
    abstract fun partnerRelationshipDao(): PartnerRelationshipDao
    abstract fun heartbeatDao(): HeartbeatDao
    abstract fun eventGroupingDao(): EventGroupingDao
    abstract fun approvalRequestDao(): ApprovalRequestDao
    abstract fun replacementRequestDao(): ReplacementRequestDao

    companion object {
        @Volatile
        private var instance: AccountabilityDatabase? = null

        fun get(context: android.content.Context): AccountabilityDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext, "accountability.db").also { instance = it }
            }

        fun build(context: android.content.Context, name: String): AccountabilityDatabase =
            androidx.room.Room.databaseBuilder(context, AccountabilityDatabase::class.java, name).build()

        /** In-memory database used by tests. */
        fun inMemory(context: android.content.Context): AccountabilityDatabase =
            androidx.room.Room.inMemoryDatabaseBuilder(context, AccountabilityDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}