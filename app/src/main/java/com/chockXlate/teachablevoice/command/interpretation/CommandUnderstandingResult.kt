package com.chockXlate.teachablevoice.command.interpretation

import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.learning.intent.Intent
import kotlinx.serialization.Serializable

@Serializable
enum class CommandSlotStatus {
    EXTRACTED,
    UNRESOLVED,
    CONFLICTING,
    REFERENCE
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
    val status: CommandSlotStatus = CommandSlotStatus.EXTRACTED,
    val role: String? = null
)

/**
 * Structured output container for Phase 4.1 Command Understanding.
 * Does NOT perform Skill Matching or construct ExecutionRequest.
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
    val status: String = "UNDERSTOOD",
    val isSensitive: Boolean = false
) {
    /**
     * Preserves the original unmodified natural-language transcript.
     */
    val originalTranscript: String get() = rawCommand

    /**
     * Helper to retrieve a slot by name.
     */
    fun getSlot(name: String): CommandSlot? =
        slots.find { it.name.equals(name, ignoreCase = true) }

    val item: String?
        get() = slots.find { it.name == "item" || it.name == "query" }?.typedValue

    val platform: String?
        get() = slots.find { it.name == "platform" }?.typedValue

    val shoppingPlatform: String?
        get() = slots.find { it.name == "shopping_platform" || (it.name == "platform" && it.role == "shopping_platform") }?.typedValue
            ?: slots.find { it.name == "platform" }?.typedValue

    val messagingPlatform: String?
        get() = slots.find { it.name == "messaging_platform" }?.typedValue

    val quantity: String?
        get() = slots.find { it.name == "quantity" }?.typedValue

    val query: String?
        get() = slots.find { it.name == "query" || it.name == "item" }?.typedValue
}
