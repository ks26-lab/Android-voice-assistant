package com.chockXlate.teachablevoice.command.interpretation

import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.learning.intent.Intent
import kotlinx.serialization.Serializable

@Serializable
enum class CommandSlotStatus {
    EXTRACTED,
    UNRESOLVED,
    CONFLICTING
}

/**
 * Structured slot parameter extracted from a new natural-language user command.
 */
@Serializable
data class CommandSlot(
    val name: String,
    val type: SlotType,
    val rawValue: String,
    val typedValue: String,
    val confidence: Double = 1.0,
    val confidenceLevel: String = "HIGH",
    val provenance: String = "command_text",
    val status: CommandSlotStatus = CommandSlotStatus.EXTRACTED
)

/**
 * Structured output container for Phase 10 Command Understanding.
 * Does NOT perform Skill Matching (Phase 11) or construct ExecutionRequest.
 */
@Serializable
data class CommandUnderstandingResult(
    val schemaVersion: String = "1.0",
    val rawCommand: String,
    val normalizedCommand: String,
    val intent: Intent,
    val intentConfidence: Double,
    val intentProvenance: String = "deterministic_intent_model",
    val slots: List<CommandSlot> = emptyList(),
    val unresolvedItems: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val overallConfidence: Double = 1.0,
    val status: String = "UNDERSTOOD"
)
