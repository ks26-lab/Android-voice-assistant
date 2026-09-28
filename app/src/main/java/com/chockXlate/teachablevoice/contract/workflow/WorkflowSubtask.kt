package com.chockXlate.teachablevoice.contract.workflow

import kotlinx.serialization.Serializable

@Serializable
data class WorkflowSubtask(
    val schemaVersion: String = "1.0",
    val subtaskId: String,
    val label: String,
    val stepIds: List<String> = emptyList(),
    val preconditions: Preconditions = Preconditions(),
    val expectedOutcome: ExpectedTransition? = null,
    val confidence: Double = 1.0,
    val provenance: String = "inferred_subtask"
)
