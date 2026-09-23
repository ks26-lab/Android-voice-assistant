package com.chockXlate.teachablevoice.learning.alignment

import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.learning.intent.IntentExtractionResult
import com.chockXlate.teachablevoice.learning.slots.ExtractedSlot
import com.chockXlate.teachablevoice.learning.slots.SlotExtractionResult
import kotlinx.serialization.Serializable

/**
 * Encapsulates a teaching demonstration slot dataset for multi-demonstration alignment.
 */
@Serializable
data class DemonstrationDataset(
    val demonstrationId: String,
    val traceId: String,
    val intentResult: IntentExtractionResult,
    val slotResult: SlotExtractionResult
)

/**
 * Represents a single occurrence of a slot within a demonstration.
 */
@Serializable
data class DemonstrationSlotOccurrence(
    val demonstrationId: String,
    val traceId: String,
    val slot: ExtractedSlot
)

/**
 * Represents a group of aligned slots across demonstrations sharing the same canonical name.
 */
@Serializable
data class AlignedSlotGroup(
    val slotName: String,
    val slotType: SlotType,
    val occurrences: List<DemonstrationSlotOccurrence> = emptyList(),
    val isTypeMismatch: Boolean = false,
    val typeMismatchDetails: String? = null
)

/**
 * Diagnostic container for Phase 6 demonstration alignment results.
 */
@Serializable
data class AlignmentResult(
    val schemaVersion: String = "1.0",
    val primaryIntent: String,
    val alignedDemonstrationIds: List<String> = emptyList(),
    val incompatibleDemonstrationIds: List<String> = emptyList(),
    val alignedSlotGroups: List<AlignedSlotGroup> = emptyList(),
    val warnings: List<String> = emptyList(),
    val isCompatible: Boolean = true
)
