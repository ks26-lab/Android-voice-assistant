package com.chockXlate.teachablevoice.contract.skill

import kotlinx.serialization.Serializable

@Serializable
enum class SkillStatus {
    CREATED,
    TEACHING,
    UNTRAINED,
    TRAINED
}

/**
 * Entity representing a dynamic user-created skill before and after demonstration learning.
 * Stores user-defined identity, metadata, status, and workflow linkage.
 */
@Serializable
data class SkillRecord(
    val id: String,
    val name: String,
    val description: String = "",
    val status: SkillStatus = SkillStatus.CREATED,
    val workflowId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)
