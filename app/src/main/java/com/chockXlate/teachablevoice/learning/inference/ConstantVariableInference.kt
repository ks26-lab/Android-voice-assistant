package com.chockXlate.teachablevoice.learning.inference

import com.chockXlate.teachablevoice.learning.alignment.AlignmentResult
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationAlignment
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset

/**
 * Deterministic engine for inferring parameter CONSTANT, VARIABLE, UNKNOWN, or CONFLICTING status
 * across multiple aligned teaching demonstrations.
 * Does NOT generate Workflow IR (Phase 7).
 */
object ConstantVariableInference {

    fun infer(datasets: List<DemonstrationDataset>): InferenceResult {
        val alignmentResult = DemonstrationAlignment.align(datasets)
        return infer(alignmentResult)
    }

    fun infer(alignmentResult: AlignmentResult): InferenceResult {
        val warnings = mutableListOf<String>()
        warnings.addAll(alignmentResult.warnings)

        val totalAlignedDemos = alignmentResult.alignedDemonstrationIds.size
        val inferences = mutableListOf<AlignedSlotInference>()

        for (group in alignmentResult.alignedSlotGroups) {
            val slotName = group.slotName
            val occurrences = group.occurrences.sortedBy { it.demonstrationId }
            val rawValues = occurrences.map { it.slot.rawValue }
            val normalizedValues = rawValues.map { normalizeValue(it) }

            val demoIds = occurrences.map { it.demonstrationId }.distinct()
            val slotIds = occurrences.map { it.slot.slotId }.distinct()
            val vIds = occurrences.flatMap { it.slot.sourceVoiceEventIds }.distinct()
            val aIds = occurrences.flatMap { it.slot.sourceActionIds }.distinct()

            // Check internal Phase 5 conflicts
            val internalConflictOccurrence = occurrences.find {
                it.slot.confidenceLevel == "MEDIUM" || it.slot.provenanceReasoning.contains("Conflict", ignoreCase = true)
            }

            val inference: AlignedSlotInference = when {
                group.isTypeMismatch -> {
                    val detail = group.typeMismatchDetails ?: "Type mismatch across demonstrations for slot '$slotName'."
                    AlignedSlotInference(
                        slotName = slotName,
                        slotType = group.slotType,
                        status = SlotInferenceStatus.CONFLICTING,
                        rawValues = rawValues,
                        normalizedValues = normalizedValues,
                        confidence = 0.5,
                        confidenceLevel = "LOW",
                        reasoning = detail,
                        demonstrationIds = demoIds,
                        sourceSlotIds = slotIds,
                        sourceVoiceEventIds = vIds,
                        sourceActionIds = aIds,
                        conflicts = listOf(detail)
                    )
                }

                internalConflictOccurrence != null -> {
                    val detail = "Preserved internal Phase 5 conflict in demonstration '${internalConflictOccurrence.demonstrationId}' for slot '$slotName'."
                    AlignedSlotInference(
                        slotName = slotName,
                        slotType = group.slotType,
                        status = SlotInferenceStatus.CONFLICTING,
                        rawValues = rawValues,
                        normalizedValues = normalizedValues,
                        confidence = 0.5,
                        confidenceLevel = "LOW",
                        reasoning = detail,
                        demonstrationIds = demoIds,
                        sourceSlotIds = slotIds,
                        sourceVoiceEventIds = vIds,
                        sourceActionIds = aIds,
                        conflicts = listOf(detail)
                    )
                }

                totalAlignedDemos < 2 -> {
                    val reasoning = "Single demonstration provided ($totalAlignedDemos=1). Insufficient evidence to determine constant vs variable."
                    AlignedSlotInference(
                        slotName = slotName,
                        slotType = group.slotType,
                        status = SlotInferenceStatus.UNKNOWN,
                        rawValues = rawValues,
                        normalizedValues = normalizedValues,
                        confidence = 0.5,
                        confidenceLevel = "LOW",
                        reasoning = reasoning,
                        demonstrationIds = demoIds,
                        sourceSlotIds = slotIds,
                        sourceVoiceEventIds = vIds,
                        sourceActionIds = aIds
                    )
                }

                occurrences.size < totalAlignedDemos -> {
                    val reasoning = "Slot '$slotName' present in only ${occurrences.size} of $totalAlignedDemos compatible demonstrations. Insufficient evidence."
                    AlignedSlotInference(
                        slotName = slotName,
                        slotType = group.slotType,
                        status = SlotInferenceStatus.UNKNOWN,
                        rawValues = rawValues,
                        normalizedValues = normalizedValues,
                        confidence = 0.5,
                        confidenceLevel = "LOW",
                        reasoning = reasoning,
                        demonstrationIds = demoIds,
                        sourceSlotIds = slotIds,
                        sourceVoiceEventIds = vIds,
                        sourceActionIds = aIds
                    )
                }

                else -> {
                    val distinctNormalized = normalizedValues.distinct()
                    if (distinctNormalized.size == 1) {
                        val reasoning = "$totalAlignedDemos compatible demonstrations contain the same normalized value ('${distinctNormalized.first()}')."
                        AlignedSlotInference(
                            slotName = slotName,
                            slotType = group.slotType,
                            status = SlotInferenceStatus.CONSTANT,
                            rawValues = rawValues,
                            normalizedValues = normalizedValues,
                            confidence = 1.0,
                            confidenceLevel = "HIGH",
                            reasoning = reasoning,
                            demonstrationIds = demoIds,
                            sourceSlotIds = slotIds,
                            sourceVoiceEventIds = vIds,
                            sourceActionIds = aIds
                        )
                    } else {
                        val reasoning = "$totalAlignedDemos compatible demonstrations contain ${distinctNormalized.size} distinct normalized values (${distinctNormalized.joinToString(", ")})."
                        AlignedSlotInference(
                            slotName = slotName,
                            slotType = group.slotType,
                            status = SlotInferenceStatus.VARIABLE,
                            rawValues = rawValues,
                            normalizedValues = normalizedValues,
                            confidence = 1.0,
                            confidenceLevel = "HIGH",
                            reasoning = reasoning,
                            demonstrationIds = demoIds,
                            sourceSlotIds = slotIds,
                            sourceVoiceEventIds = vIds,
                            sourceActionIds = aIds
                        )
                    }
                }
            }

            inferences.add(inference)
        }

        return InferenceResult(
            schemaVersion = "1.0",
            intentName = alignmentResult.primaryIntent,
            demonstrationsAnalyzedCount = totalAlignedDemos,
            slotInferences = inferences.sortedBy { it.slotName },
            warnings = warnings,
            status = "INFERRED"
        )
    }

    private fun normalizeValue(raw: String): String {
        return raw.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }
}
