package com.chockXlate.teachablevoice.teach.bonus.clarification

/**
 * Result output contract for Mid-Flow Clarification execution session.
 */
data class MidFlowClarificationResult(
    val schemaVersion: String = "1.0",
    val workflowId: String,
    val finalState: ClarificationState,
    val resolvedSlots: Map<String, String>,
    val resumedFromStepIndex: Int,
    val totalStepsCompleted: Int,
    val requiresUiReobservation: Boolean = true,
    val summaryReason: String
)
