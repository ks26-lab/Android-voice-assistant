package com.chockXlate.teachablevoice.learning.slots

import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.learning.actions.SemanticAction
import com.chockXlate.teachablevoice.learning.actions.SemanticActionType
import com.chockXlate.teachablevoice.learning.intent.IntentExtractionResult
import java.util.UUID

/**
 * Generic Candidate Slot Evidence container used during Phase 5 extraction.
 */
private data class CandidateSlotEvidence(
    val slotName: String,
    val slotType: SlotType,
    val value: String,
    val voiceEventId: String? = null,
    val actionId: String? = null,
    val transcriptSnippet: String? = null,
    val targetRole: String? = null,
    val targetResourceId: String? = null,
    val source: String // "VOICE", "ACTION"
)

/**
 * Deterministic component for extracting structured, provenance-aware parameters/slots
 * from a single teaching demonstration.
 * Does NOT perform constant-vs-variable inference across demonstrations (Phase 6).
 */
object SlotExtractor {

    private val EXPECTED_CANONICAL_SLOTS = mapOf(
        "order_food" to listOf("restaurant", "item", "quantity", "address"),
        "send_message" to listOf("recipient", "message"),
        "book_appointment" to listOf("service", "date", "time"),
        "create_reminder" to listOf("task", "time"),
        "navigate" to listOf("destination"),
        "search_information" to listOf("item")
    )

    private fun deterministicSlotId(traceId: String, slotName: String, slotType: SlotType, value: String): String {
        val seed = "$traceId|$slotName|${slotType.name}|$value"
        return "slot_${seed.hashCode().toUInt().toString(16)}"
    }

    fun extract(
        trace: DemonstrationTrace,
        semanticActions: List<SemanticAction>,
        intentResult: IntentExtractionResult
    ): SlotExtractionResult {
        val warnings = mutableListOf<String>()
        val candidates = mutableListOf<CandidateSlotEvidence>()

        val voiceEvents = trace.voiceEvents
        val fullTranscript = voiceEvents.map { it.transcript.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { intentResult.intent.sourceVoiceTranscript?.trim().orEmpty() }

        val primaryVoiceId = voiceEvents.firstOrNull()?.eventId ?: intentResult.intent.sourceVoiceEventIds.firstOrNull()
        val canonicalIntent = com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy
            .canonicalizeIntent(intentResult.intent.canonicalName)

        // 1. Extract Candidate Evidence from Semantic Actions
        for (action in semanticActions) {
            val inputVal = action.inputValue?.trim()
            val target = action.target
            val targetResId = target?.resourceId?.lowercase() ?: ""
            val targetText = target?.text?.lowercase() ?: ""
            val targetDesc = target?.contentDescription?.lowercase() ?: ""
            val targetRole = target?.role ?: ""

            if (!inputVal.isNullOrBlank()) {
                val hasSpokenTranscript = fullTranscript.isNotBlank()
                val inferred = inferSlotNameAndType(canonicalIntent, targetResId, targetText, targetDesc, inputVal, hasSpokenTranscript)
                val spoken = com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy
                    .understandCommand(fullTranscript).slots.filter { it.rawValue.equals(inputVal, true) || it.typedValue.equals(inputVal, true) }
                val (slotName, slotType) = if (spoken.size == 1)
                    spoken.single().let { it.name to it.type } else inferred
                candidates.add(
                    CandidateSlotEvidence(
                        slotName = slotName,
                        slotType = slotType,
                        value = inputVal,
                        actionId = action.actionId,
                        targetRole = targetRole,
                        targetResourceId = target?.resourceId,
                        source = "ACTION"
                    )
                )
            } else if (action.actionType == SemanticActionType.SELECT || action.actionType == SemanticActionType.TAP) {
                if (targetText.isNotBlank()) {
                    if (targetResId.contains("address") || targetText.contains("home") || targetText.contains("work")) {
                        candidates.add(
                            CandidateSlotEvidence(
                                slotName = "address",
                                slotType = SlotType.ADDRESS,
                                value = target?.text ?: targetText,
                                actionId = action.actionId,
                                targetRole = targetRole,
                                targetResourceId = target?.resourceId,
                                source = "ACTION"
                            )
                        )
                    }
                }
            }
        }

        // 2. Extract Candidate Evidence from Voice Transcript
        if (fullTranscript.isNotBlank()) {
            extractVoiceCandidates(fullTranscript, primaryVoiceId, candidates)
        }

        // 3. Merge Compatible Evidence & Detect Conflicts
        val extractedSlots = mutableListOf<ExtractedSlot>()
        val conflicts = mutableListOf<SlotConflict>()

        val groupedByName = candidates.groupBy { it.slotName }

        for ((slotName, evList) in groupedByName) {
            val voiceEvs = evList.filter { it.source == "VOICE" }
            val actionEvs = evList.filter { it.source == "ACTION" }

            val voiceVal = voiceEvs.firstOrNull()?.value
            val actionVal = actionEvs.firstOrNull()?.value

            val distinctValues = evList.map { it.value.lowercase() }.distinct()

            if ((voiceVal != null && actionVal != null && !voiceVal.equals(actionVal, ignoreCase = true)) ||
                actionEvs.map { it.value.lowercase() }.distinct().size > 1 ||
                voiceEvs.map { it.value.lowercase() }.distinct().size > 1) {
                // Conflict detected: retain both sources, flag conflict, assign MEDIUM confidence
                val conflict = SlotConflict(
                    slotName = slotName,
                    voiceValue = voiceVal,
                    actionValue = actionVal,
                    description = "Conflicting evidence for '$slotName': Voice='$voiceVal' vs Action='$actionVal'."
                )
                conflicts.add(conflict)
                warnings.add(conflict.description)

                val primaryVal = actionVal ?: voiceVal!! // Preserve evidence; synthesis blocks the conflict.
                val slotType = evList.first().slotType
                val typedVal = parseTypedValue(primaryVal, slotType, warnings)

                val slot = ExtractedSlot(
                    schemaVersion = "1.0",
                    slotId = deterministicSlotId(trace.traceId, slotName, slotType, primaryVal),
                    name = slotName,
                    type = slotType,
                    rawValue = primaryVal,
                    typedValue = typedVal,
                    confidence = 0.75,
                    confidenceLevel = "MEDIUM",
                    sourceVoiceEventIds = voiceEvs.mapNotNull { it.voiceEventId },
                    sourceActionIds = actionEvs.mapNotNull { it.actionId },
                    sourceTranscriptSnippet = voiceEvs.firstOrNull()?.transcriptSnippet ?: fullTranscript.ifBlank { null },
                    targetRole = actionEvs.firstOrNull()?.targetRole,
                    targetResourceId = actionEvs.firstOrNull()?.targetResourceId,
                    provenanceReasoning = "Conflict detected between voice ('$voiceVal') and action ('$actionVal'). Action value preserved."
                )
                extractedSlots.add(slot)
            } else {
                // Compatible or single-source evidence: Merge cleanly
                val primaryEv = evList.first()
                val primaryVal = primaryEv.value
                val slotType = primaryEv.slotType
                val typedVal = parseTypedValue(primaryVal, slotType, warnings)

                val vIds = evList.mapNotNull { it.voiceEventId }.distinct()
                val aIds = evList.mapNotNull { it.actionId }.distinct()

                val reasoning = if (vIds.isNotEmpty() && aIds.isNotEmpty()) {
                    "Merged compatible evidence from Voice ('$primaryVal') and Action ('$primaryVal')."
                } else if (vIds.isNotEmpty()) {
                    "Extracted from Voice transcript ('$primaryVal')."
                } else {
                    "Extracted from Action input ('$primaryVal')."
                }

                val slot = ExtractedSlot(
                    schemaVersion = "1.0",
                    slotId = deterministicSlotId(trace.traceId, slotName, slotType, primaryVal),
                    name = slotName,
                    type = slotType,
                    rawValue = primaryVal,
                    typedValue = typedVal,
                    confidence = 1.0,
                    confidenceLevel = "HIGH",
                    sourceVoiceEventIds = vIds,
                    sourceActionIds = aIds,
                    sourceTranscriptSnippet = if (vIds.isNotEmpty()) fullTranscript.ifBlank { null } else null,
                    targetRole = primaryEv.targetRole,
                    targetResourceId = primaryEv.targetResourceId,
                    provenanceReasoning = reasoning
                )
                extractedSlots.add(slot)
            }
        }

        // 4. Identify Unresolved Expected Slots
        val intentName = canonicalIntent
        val expected = EXPECTED_CANONICAL_SLOTS[intentName] ?: emptyList()
        val extractedNames = extractedSlots.map { it.name }.toSet()
        val unresolved = expected.filter { it !in extractedNames }

        return SlotExtractionResult(
            schemaVersion = "1.0",
            intentName = intentName,
            extractedSlots = extractedSlots.sortedBy { it.name },
            unresolvedSlots = unresolved,
            conflicts = conflicts,
            warnings = warnings,
            status = if (conflicts.isEmpty()) "EXTRACTED" else "EXTRACTED_WITH_CONFLICTS"
        )
    }

    private fun inferSlotNameAndType(
        canonicalIntent: String,
        targetResId: String,
        targetText: String,
        targetDesc: String,
        inputVal: String,
        hasSpokenTranscript: Boolean
    ): Pair<String, SlotType> {
        val num = inputVal.toIntOrNull()
        if (num != null && canonicalIntent != "search_information") {
            return Pair("quantity", SlotType.INTEGER)
        }
        val dbl = inputVal.toDoubleOrNull()
        if (dbl != null && canonicalIntent != "search_information") {
            return Pair("amount", SlotType.DECIMAL)
        }

        if (targetResId.contains("search") || targetResId.contains("dish") || targetResId.contains("item") ||
            targetText.contains("search") || targetDesc.contains("search") || targetDesc.contains("query") || targetDesc.contains("find")) {
            return Pair("item", SlotType.TEXT)
        }
        if (targetResId.contains("restaurant") || targetResId.contains("vendor") || targetResId.contains("store")) {
            return Pair("restaurant", SlotType.TEXT)
        }
        if (targetResId.contains("address") || targetResId.contains("location") || targetResId.contains("delivery")) {
            return Pair("address", SlotType.ADDRESS)
        }

        // Canonical intent alignment:
        // When demonstrating without spoken voice evidence, a text input in a search workflow
        // aligns to the canonical search slot ("item").
        // If a spoken command was present but did not match this input, it remains fail-closed ("input_text").
        if (canonicalIntent == "search_information" && !hasSpokenTranscript) {
            return Pair("item", SlotType.TEXT)
        }

        return Pair("input_text", SlotType.TEXT)
    }

    private fun extractVoiceCandidates(transcript: String, voiceId: String?, candidates: MutableList<CandidateSlotEvidence>) {
        val understood = com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy.understandCommand(transcript)
        for (slot in understood.slots) candidates.add(CandidateSlotEvidence(
            slotName = slot.name, slotType = slot.type, value = slot.rawValue,
            voiceEventId = voiceId, transcriptSnippet = transcript, source = "VOICE"
        ))
    }

    private fun parseTypedValue(rawValue: String, type: SlotType, warnings: MutableList<String>): String {
        return when (type) {
            SlotType.INTEGER -> {
                val parsed = rawValue.toIntOrNull()
                if (parsed == null) {
                    warnings.add("Value '$rawValue' could not be strictly parsed as INTEGER. Retaining raw value.")
                    rawValue
                } else {
                    parsed.toString()
                }
            }
            SlotType.DECIMAL -> {
                val parsed = rawValue.toDoubleOrNull()
                if (parsed == null) {
                    warnings.add("Value '$rawValue' could not be strictly parsed as DECIMAL. Retaining raw value.")
                    rawValue
                } else {
                    parsed.toString()
                }
            }
            else -> rawValue
        }
    }
}
