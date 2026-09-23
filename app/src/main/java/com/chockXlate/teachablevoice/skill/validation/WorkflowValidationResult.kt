package com.chockXlate.teachablevoice.skill.validation

import kotlinx.serialization.Serializable

@Serializable
enum class ValidationSeverity {
    WARNING,
    ERROR,
    CRITICAL_SECURITY_BLOCK
}

@Serializable
enum class ValidationCategory {
    STRUCTURE,
    SEMANTICS,
    SAFETY,
    COORDINATE_REPLAY
}

/**
 * Represents a single diagnostic issue discovered during Workflow IR validation.
 */
@Serializable
data class WorkflowValidationIssue(
    val severity: ValidationSeverity,
    val category: ValidationCategory,
    val message: String,
    val affectedStepId: String? = null,
    val affectedSlotName: String? = null
)

@Serializable
enum class ValidationStatus {
    VALID,
    INVALID,
    BLOCKED
}

/**
 * Diagnostic container for Phase 8 Workflow validation results.
 */
@Serializable
data class WorkflowValidationResult(
    val schemaVersion: String = "1.0",
    val workflowId: String?,
    val status: ValidationStatus,
    val issues: List<WorkflowValidationIssue> = emptyList(),
    val warnings: List<String> = emptyList(),
    val isStoreable: Boolean = false
)
