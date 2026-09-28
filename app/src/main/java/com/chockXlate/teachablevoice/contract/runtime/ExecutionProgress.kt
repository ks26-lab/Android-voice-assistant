package com.chockXlate.teachablevoice.contract.runtime

import kotlinx.serialization.Serializable

@Serializable
enum class ProgressStatus {
    PENDING,
    ACTIVE,
    COMPLETED,
    FAILED,
    WAITING_FOR_USER,
    SKIPPED_IF_APPLICABLE
}

@Serializable
data class SubtaskProgress(
    val schemaVersion: String = "1.0",
    val subtaskId: String,
    val label: String,
    val status: ProgressStatus = ProgressStatus.PENDING,
    val stepIds: List<String> = emptyList(),
    val completedStepIds: List<String> = emptyList(),
    val evidenceSummary: String? = null,
    val failureReason: String? = null
)

@Serializable
data class ExecutionProgress(
    val schemaVersion: String = "1.0",
    val executionId: String,
    val currentSubtaskId: String? = null,
    val completedSubtaskIds: List<String> = emptyList(),
    val currentStepId: String? = null,
    val completedStepIds: List<String> = emptyList(),
    val subtasks: List<SubtaskProgress> = emptyList(),
    val overallStatus: ProgressStatus = ProgressStatus.PENDING,
    val pauseOrFailureReason: String? = null
) {
    fun isSubtaskCompleted(subtaskId: String): Boolean = subtaskId in completedSubtaskIds
    fun isStepCompleted(stepId: String): Boolean = stepId in completedStepIds
}
