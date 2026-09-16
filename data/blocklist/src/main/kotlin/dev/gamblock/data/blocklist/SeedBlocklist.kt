package dev.gamblock.data.blocklist

import dev.gamblock.core.model.BlockStatus
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.Confidence
import dev.gamblock.core.model.RiskLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SeedBlocklistFile(
    val schema: String? = null,
    val version: Int,
    val releasedAtEpochMs: Long,
    val entries: List<SeedEntry>,
)

@Serializable
data class SeedEntry(
    val domain: String,
    val category: Category = Category.GAMBLING,
    val confidence: Confidence = Confidence.HIGH,
    val riskLevel: RiskLevel = RiskLevel.MEDIUM,
    val status: BlockStatus = BlockStatus.ACTIVE,
    val source: String = "seed",
    @SerialName("appliesToSubdomains") val appliesToSubdomains: Boolean = true,
)