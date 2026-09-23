package com.chockXlate.teachablevoice.contract.runtime

import kotlinx.serialization.Serializable

@Serializable
data class ExecutionResult(
    val schemaVersion: String = "1.0",
    val executionId: String,
    val success: Boolean,
    val finalState: ExecutionState,
    val stepsCompleted: Int,
    val totalSteps: Int,
    val errorMessage: String? = null,
    val durationMs: Long = 0L
)
