package com.chockXlate.teachablevoice.contract.workflow

import kotlinx.serialization.Serializable

@Serializable
data class Workflow(
    val schemaVersion: String = "1.0",
    val skillId: String,
    val name: String,
    val intent: String,
    val appContext: String,
    val slots: List<WorkflowSlot> = emptyList(),
    val steps: List<WorkflowStep> = emptyList(),
    val safetyBoundary: SafetyBoundary = SafetyBoundary()
)
