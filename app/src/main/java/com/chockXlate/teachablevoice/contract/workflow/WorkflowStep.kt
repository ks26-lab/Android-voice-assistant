package com.chockXlate.teachablevoice.contract.workflow

import kotlinx.serialization.Serializable

@Serializable
data class WorkflowStep(
    val schemaVersion: String = "1.0",
    val stepId: String,
    val semanticAction: String, // e.g. CLICK, INPUT_TEXT, SCROLL, LONG_PRESS
    val semanticSelector: SemanticSelector,
    val parameters: Map<String, String> = emptyMap(),
    val preconditions: Preconditions = Preconditions(),
    val expectedTransition: ExpectedTransition = ExpectedTransition(),
    val recoveryPolicy: RecoveryPolicy = RecoveryPolicy(),
    val confidence: Double = 1.0,
    val provenance: String = "demonstration"
)
