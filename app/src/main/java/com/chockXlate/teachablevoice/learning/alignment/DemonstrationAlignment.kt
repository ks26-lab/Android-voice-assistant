package com.chockXlate.teachablevoice.learning.alignment

import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.learning.slots.ExtractedSlot

/**
 * Deterministic component for aligning canonical parameters/slots across multiple teaching demonstrations.
 * Does NOT generate Workflow IR (Phase 7).
 */
object DemonstrationAlignment {

    fun align(datasets: List<DemonstrationDataset>): AlignmentResult {
        if (datasets.isEmpty()) {
            return AlignmentResult(
                primaryIntent = "unknown",
                isCompatible = false,
                warnings = listOf("No demonstration datasets provided for alignment.")
            )
        }

        // Sort datasets deterministically by demonstrationId
        val sortedDatasets = datasets.sortedBy { it.demonstrationId }

        // Primary intent is taken from the first demonstration
        val primaryIntent = sortedDatasets.first().intentResult.intent.canonicalName

        val alignedDemos = mutableListOf<DemonstrationDataset>()
        val incompatibleDemos = mutableListOf<String>()

        for (ds in sortedDatasets) {
            val intentName = ds.intentResult.intent.canonicalName
            if (intentName.equals(primaryIntent, ignoreCase = true)) {
                alignedDemos.add(ds)
            } else {
                incompatibleDemos.add(ds.demonstrationId)
            }
        }

        val warnings = mutableListOf<String>()
        if (incompatibleDemos.isNotEmpty()) {
            warnings.add("Excluded ${incompatibleDemos.size} demonstration(s) with incompatible intents: $incompatibleDemos")
        }

        if (alignedDemos.isEmpty()) {
            return AlignmentResult(
                primaryIntent = primaryIntent,
                alignedDemonstrationIds = emptyList(),
                incompatibleDemonstrationIds = incompatibleDemos,
                alignedSlotGroups = emptyList(),
                warnings = warnings,
                isCompatible = false
            )
        }

        // Collect all extracted slots from compatible demonstrations
        val occurrencesBySlotName = mutableMapOf<String, MutableList<DemonstrationSlotOccurrence>>()

        for (demo in alignedDemos) {
            for (slot in demo.slotResult.extractedSlots) {
                val list = occurrencesBySlotName.getOrPut(slot.name) { mutableListOf() }
                list.add(
                    DemonstrationSlotOccurrence(
                        demonstrationId = demo.demonstrationId,
                        traceId = demo.traceId,
                        slot = slot
                    )
                )
            }
        }

        // Build AlignedSlotGroup per slotName
        val alignedSlotGroups = mutableListOf<AlignedSlotGroup>()

        for ((slotName, occurrences) in occurrencesBySlotName.entries.sortedBy { it.key }) {
            val sortedOccurrences = occurrences.sortedBy { it.demonstrationId }
            val distinctTypes = sortedOccurrences.map { it.slot.type }.distinct()

            if (distinctTypes.size > 1) {
                // Type mismatch across demonstrations
                val detail = "Type mismatch for slot '$slotName': distinct types $distinctTypes across demonstrations."
                warnings.add(detail)
                alignedSlotGroups.add(
                    AlignedSlotGroup(
                        slotName = slotName,
                        slotType = distinctTypes.first(), // Fallback to first type
                        occurrences = sortedOccurrences,
                        isTypeMismatch = true,
                        typeMismatchDetails = detail
                    )
                )
            } else {
                alignedSlotGroups.add(
                    AlignedSlotGroup(
                        slotName = slotName,
                        slotType = distinctTypes.firstOrNull() ?: SlotType.TEXT,
                        occurrences = sortedOccurrences,
                        isTypeMismatch = false
                    )
                )
            }
        }

        return AlignmentResult(
            schemaVersion = "1.0",
            primaryIntent = primaryIntent,
            alignedDemonstrationIds = alignedDemos.map { it.demonstrationId },
            incompatibleDemonstrationIds = incompatibleDemos,
            alignedSlotGroups = alignedSlotGroups,
            warnings = warnings,
            isCompatible = true
        )
    }
}
