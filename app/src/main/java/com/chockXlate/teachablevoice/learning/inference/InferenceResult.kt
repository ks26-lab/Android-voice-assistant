package com.chockXlate.teachablevoice.learning.inference

import com.chockXlate.teachablevoice.contract.workflow.SlotType
import kotlinx.serialization.Serializable

/**
 * Enumerates the inferred classification of a parameter across teaching demonstrations.
 */
@Serializable
enum class SlotInferenceStatus {
    CONSTANT,
    VARIABLE,
    UNKNOWN,
    CONFLICTING
}

/**
 * Detailed inference classification for a single aligned slot group across demonstrations.
 * Fully traceable to original demonstration, voice, and action provenance.
 */
@Serializable
data class AlignedSlotInference(
    val slotName: String,
    val slotType: SlotType,
    val status: SlotInferenceStatus,
    val rawValues: List<String> = emptyList(),
    val normalizedValues: List<String> = emptyList(),
    val confidence: Double,
    val confidenceLevel: String, // HIGH, MEDIUM, LOW
    val reasoning: String,
    val demonstrationIds: List<String> = emptyList(),
    val sourceSlotIds: List<String> = emptyList(),
    val sourceVoiceEventIds: List<String> = emptyList(),
    val sourceActionIds: List<String> = emptyList(),
    val conflicts: List<String> = emptyList()
)

/**
 * Container for overall Phase 6 constant/variable inference output across demonstrations.
 * Does NOT generate Workflow IR (Phase 7).
 */
@Serializable
data class InferenceResult(
    val schemaVersion: String = "1.0",
    val intentName: String,
    val demonstrationsAnalyzedCount: Int,
    val slotInferences: List<AlignedSlotInference> = emptyList(),
    val warnings: List<String> = emptyList(),
    val status: String = "INFERRED"
)
