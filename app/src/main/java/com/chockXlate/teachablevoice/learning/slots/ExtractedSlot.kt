package com.chockXlate.teachablevoice.learning.slots

import com.chockXlate.teachablevoice.contract.workflow.SlotType
import kotlinx.serialization.Serializable

/**
 * Represents a concrete parameter value extracted from a single teaching demonstration.
 * Provenance-aware and typed. Does NOT indicate whether the value is a variable across demonstrations (Phase 6).
 */
@Serializable
data class ExtractedSlot(
    val schemaVersion: String = "1.0",
    val slotId: String,
    val name: String,
    val type: SlotType,
    val rawValue: String,
    val typedValue: String,
    val confidence: Double = 1.0,
    val confidenceLevel: String = "HIGH", // HIGH, MEDIUM, LOW
    val sourceVoiceEventIds: List<String> = emptyList(),
    val sourceActionIds: List<String> = emptyList(),
    val sourceTranscriptSnippet: String? = null,
    val targetRole: String? = null,
    val targetResourceId: String? = null,
    val provenanceReasoning: String = ""
)

/**
 * Represents conflicting slot evidence detected during Phase 5 extraction.
 */
@Serializable
data class SlotConflict(
    val slotName: String,
    val voiceValue: String?,
    val actionValue: String?,
    val description: String
)

/**
 * Diagnostic container for Phase 5 slot extraction results.
 */
@Serializable
data class SlotExtractionResult(
    val schemaVersion: String = "1.0",
    val intentName: String,
    val extractedSlots: List<ExtractedSlot> = emptyList(),
    val unresolvedSlots: List<String> = emptyList(),
    val conflicts: List<SlotConflict> = emptyList(),
    val warnings: List<String> = emptyList(),
    val status: String = "EXTRACTED"
)
