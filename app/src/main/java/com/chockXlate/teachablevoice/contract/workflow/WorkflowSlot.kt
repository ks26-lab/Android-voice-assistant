package com.chockXlate.teachablevoice.contract.workflow

import kotlinx.serialization.Serializable

@Serializable
enum class SlotType {
    TEXT,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    ENUM,
    ADDRESS
}

@Serializable
data class WorkflowSlot(
    val schemaVersion: String = "1.0",
    val name: String,
    val type: SlotType,
    val required: Boolean = true,
    val exampleValue: String? = null,
    val confidence: Double = 1.0,
    val provenance: String = "demonstration"
)
