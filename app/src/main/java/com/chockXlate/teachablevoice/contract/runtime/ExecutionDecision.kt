package com.chockXlate.teachablevoice.contract.runtime

import kotlinx.serialization.Serializable

@Serializable
enum class DecisionType {
    EXECUTE,
    RECOVER,
    ASK_USER,
    STOP,
    HANDOFF,
    PROCEED,
    ABORT
}

@Serializable
data class ExecutionDecision(
    val schemaVersion: String = "1.0",
    val decisionId: String,
    val type: DecisionType,
    val reason: String,
    val confidence: Double = 1.0,
    val timestamp: Long = System.currentTimeMillis()
)
