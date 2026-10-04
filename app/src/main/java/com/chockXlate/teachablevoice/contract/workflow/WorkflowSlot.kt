package com.chockXlate.teachablevoice.contract.workflow

import kotlinx.serialization.Serializable

@Serializable
enum class SlotType {
    TEXT,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    ENUM,
    ADDRESS,
    PLATFORM
}

@Serializable
data class WorkflowSlot(
    val schemaVersion: String = "1.0",
    val name: String,
    val type: SlotType,
    val required: Boolean = true,
    val exampleValue: String? = null,
    val confidence: Double = 1.0,
    val provenance: String = "demonstration",
    val role: String? = null
) {
    fun isPlatformSlot(): Boolean =
        type == SlotType.PLATFORM ||
        role in setOf("target_platform", "shopping_platform", "messaging_platform", "communication_platform", "platform") ||
        name in setOf("platform", "shopping_platform", "messaging_platform", "communication_platform", "target_platform", "app_platform")
}
