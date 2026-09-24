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
        "navigate" to listOf("destination")
    )

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

        val primaryVoiceId = voiceEvents.firstOrNull()?.eventId

        // 1. Extract Candidate Evidence from Semantic Actions
        for (action in semanticActions) {
            val inputVal = action.inputValue?.trim()
            val target = action.target
            val targetResId = target?.resourceId?.lowercase() ?: ""
            val targetText = target?.text?.lowercase() ?: ""
            val targetRole = target?.role ?: ""

            if (!inputVal.isNullOrBlank()) {
                val (slotName, slotType) = inferSlotNameAndType(targetResId, targetText, inputVal)
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
                                value = target.text ?: targetText,
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

            if (voiceVal != null && actionVal != null && !voiceVal.equals(actionVal, ignoreCase = true)) {
                // Conflict detected: retain both sources, flag conflict, assign MEDIUM confidence
                val conflict = SlotConflict(
                    slotName = slotName,
                    voiceValue = voiceVal,
                    actionValue = actionVal,
                    description = "Conflicting evidence for '$slotName': Voice='$voiceVal' vs Action='$actionVal'."
                )
                conflicts.add(conflict)
                warnings.add(conflict.description)

                val primaryVal = actionVal // Action input takes priority for raw value display
                val slotType = evList.first().slotType
                val typedVal = parseTypedValue(primaryVal, slotType, warnings)

                val slot = ExtractedSlot(
                    schemaVersion = "1.0",
                    slotId = UUID.randomUUID().toString(),
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
                    slotId = UUID.randomUUID().toString(),
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
        val intentName = intentResult.intent.canonicalName
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

    private fun inferSlotNameAndType(targetResId: String, targetText: String, inputVal: String): Pair<String, SlotType> {
        val num = inputVal.toIntOrNull()
        if (num != null) {
            return Pair("quantity", SlotType.INTEGER)
        }
        val dbl = inputVal.toDoubleOrNull()
        if (dbl != null) {
            return Pair("amount", SlotType.DECIMAL)
        }

        if (targetResId.contains("search") || targetResId.contains("dish") || targetResId.contains("item") || targetText.contains("search")) {
            return Pair("item", SlotType.TEXT)
        }
        if (targetResId.contains("restaurant") || targetResId.contains("vendor") || targetResId.contains("store")) {
            return Pair("restaurant", SlotType.TEXT)
        }
        if (targetResId.contains("address") || targetResId.contains("location") || targetResId.contains("delivery")) {
            return Pair("address", SlotType.ADDRESS)
        }

        return Pair("input_text", SlotType.TEXT)
    }

    private fun extractVoiceCandidates(transcript: String, voiceId: String?, candidates: MutableList<CandidateSlotEvidence>) {
        val lower = transcript.lowercase()

        // Extract Quantity (Integer)
        val numMatch = Regex("""\b(\d+)\b""").find(transcript)
        if (numMatch != null) {
            candidates.add(
                CandidateSlotEvidence(
                    slotName = "quantity",
                    slotType = SlotType.INTEGER,
                    value = numMatch.groupValues[1],
                    voiceEventId = voiceId,
                    transcriptSnippet = transcript,
                    source = "VOICE"
                )
            )
        }

        // Extract Restaurant ("from <XYZ>")
        val fromMatch = Regex("""\bfrom\s+([A-Za-z0-9\s]+?)(?=\s+to\b|\$|$)""", RegexOption.IGNORE_CASE).find(transcript)
        if (fromMatch != null) {
            val restName = fromMatch.groupValues[1].trim()
            if (restName.isNotBlank()) {
                candidates.add(
                    CandidateSlotEvidence(
                        slotName = "restaurant",
                        slotType = SlotType.TEXT,
                        value = restName,
                        voiceEventId = voiceId,
                        transcriptSnippet = transcript,
                        source = "VOICE"
                    )
                )
            }
        }

        // Extract Address ("to <XYZ>")
        val toMatch = Regex("""\bto\s+([A-Za-z0-9\s]+?)$""", RegexOption.IGNORE_CASE).find(transcript)
        if (toMatch != null) {
            val addr = toMatch.groupValues[1].trim()
            if (addr.isNotBlank() && !addr.equals("cart", ignoreCase = true)) {
                candidates.add(
                    CandidateSlotEvidence(
                        slotName = "address",
                        slotType = SlotType.ADDRESS,
                        value = addr,
                        voiceEventId = voiceId,
                        transcriptSnippet = transcript,
                        source = "VOICE"
                    )
                )
            }
        }

        // Extract Item ("order <XYZ>" or "<XYZ> pizza")
        val orderMatch = Regex("""\border\s+(?:a\s+|an\s+|the\s+)?(?:\d+\s+)?([A-Za-z0-9\s]+?)(?=\s+from\b|\s+to\b|\$|$)""", RegexOption.IGNORE_CASE).find(transcript)
        if (orderMatch != null) {
            val itemName = orderMatch.groupValues[1].trim()
            if (itemName.isNotBlank()) {
                candidates.add(
                    CandidateSlotEvidence(
                        slotName = "item",
                        slotType = SlotType.TEXT,
                        value = itemName,
                        voiceEventId = voiceId,
                        transcriptSnippet = transcript,
                        source = "VOICE"
                    )
                )
            }
        }
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
