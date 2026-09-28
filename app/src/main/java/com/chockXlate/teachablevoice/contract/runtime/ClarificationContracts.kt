package com.chockXlate.teachablevoice.contract.runtime

import kotlinx.serialization.Serializable

@Serializable
data class ClarificationRequest(
    val schemaVersion: String = "1.0",
    val executionId: String,
    val reason: String,
    val question: String,
    val requiredSlot: String? = null,
    val candidateDescriptions: List<String> = emptyList(),
    val pausedSubtaskId: String? = null,
    val pausedStepId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ClarificationResponse(
    val schemaVersion: String = "1.0",
    val executionId: String,
    val providedSlotValue: String? = null,
    val selectedCandidateIndex: Int? = null,
    val selectedCandidateDescription: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
