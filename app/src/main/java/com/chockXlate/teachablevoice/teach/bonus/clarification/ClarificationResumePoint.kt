package com.chockXlate.teachablevoice.teach.bonus.clarification

/**
 * Logical resume snapshot for pausing and continuing execution mid-flow without restarting.
 */
data class ClarificationResumePoint(
    val workflowId: String,
    val pausedStepIndex: Int,
    val pausedStepId: String,
    val completedStepIds: List<String>,
    val boundSlots: Map<String, String>,
    val remainingMissingSlots: List<String>
)
