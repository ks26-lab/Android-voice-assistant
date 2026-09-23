package com.chockXlate.teachablevoice.command.request

import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import kotlinx.serialization.Serializable

@Serializable
enum class ExecutionRequestStatus {
    READY_FOR_PERSON_2,
    REJECTED_UNKNOWN_MATCH,
    REJECTED_AMBIGUOUS_MATCH,
    REJECTED_MISSING_REQUIRED_SLOTS,
    REJECTED_CONSTANT_MISMATCH,
    REJECTED_SAFETY_BLOCKED,
    REJECTED_INVALID_WORKFLOW
}

/**
 * Structured outcome of Phase 12 Execution Request construction.
 */
@Serializable
data class ExecutionRequestBuildResult(
    val schemaVersion: String = "1.0",
    val status: ExecutionRequestStatus,
    val executionRequest: ExecutionRequest? = null,
    val skillId: String? = null,
    val version: Int? = null,
    val boundSlots: Map<String, String> = emptyMap(),
    val missingSlots: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val rejectionReason: String? = null,
    val handoffMessage: String = "HANDOFF TO PERSON 2: NOT PERFORMED"
)
