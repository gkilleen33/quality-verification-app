package com.qualityverifier.domain

/** A row in the reports list. */
data class SessionSummary(
    val id: String,
    val itemType: ItemType,
    val createdAt: Long,
    val updatedAt: Long,
    val preview: String,
    val messageCount: Int,
    /** Null while the assessment is still in progress. */
    val verdictLevel: VerdictLevel? = null,
)
