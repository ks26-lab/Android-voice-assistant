package com.chockXlate.teachablevoice.teach.bonus.clarification

import com.chockXlate.teachablevoice.contract.workflow.SlotType

/**
 * Structured request for user clarification mid-flow.
 */
data class ClarificationRequest(
    val requestId: String,
    val workflowId: String,
    val missingSlotName: String,
    val questionPrompt: String,
    val expectedSlotType: SlotType,
    val resumePoint: ClarificationResumePoint,
    val timestamp: Long = System.currentTimeMillis(),
    val schemaVersion: String = "1.0"
)
