package com.chockXlate.teachablevoice.learning.intent

import kotlinx.serialization.Serializable

/**
 * High-level task/intent inferred from teaching evidence.
 * Does NOT contain variable slot bindings (which belong to Phase 5).
 */
@Serializable
data class Intent(
    val schemaVersion: String = "1.0",
    val intentId: String,
    val canonicalName: String,
    val confidence: Double = 1.0,
    val confidenceLevel: String = "HIGH", // HIGH, MEDIUM, LOW, UNKNOWN
    val sourceVoiceTranscript: String? = null,
    val sourceVoiceEventIds: List<String> = emptyList(),
    val supportingActionIds: List<String> = emptyList(),
    val supportingAppPackage: String? = null,
    val reasoning: String = ""
)

/**
 * Diagnostic container for Phase 4 intent extraction results.
 */
@Serializable
data class IntentExtractionResult(
    val schemaVersion: String = "1.0",
    val intent: Intent,
    val confidence: Double,
    val evidenceSummary: String,
    val warnings: List<String> = emptyList(),
    val status: String = "EXTRACTED"
)
