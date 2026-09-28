package com.chockXlate.teachablevoice.contract.runtime

import kotlinx.serialization.Serializable

@Serializable
enum class OutcomeAssessment {
    SUCCESS_SUPPORTED,
    FAILURE_SUPPORTED,
    INCOMPLETE,
    UNCERTAIN
}

@Serializable
data class OutcomeEvaluation(
    val schemaVersion: String = "1.0",
    val assessment: OutcomeAssessment,
    val reason: String,
    val details: List<String> = emptyList(),
    val subtasksEvaluated: Int = 0,
    val stepsEvaluated: Int = 0
)
