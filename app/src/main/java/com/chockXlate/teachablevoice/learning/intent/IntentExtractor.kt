package com.chockXlate.teachablevoice.learning.intent

import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.learning.actions.SemanticAction

/**
 * Generic intent extraction rule definition.
 */
data class IntentRule(
    val canonicalName: String,
    val keywords: List<String>
)

/**
 * Deterministic component for extracting high-level task intents from teaching evidence.
 * Does NOT extract variable slots (which belong to Phase 5).
 */
object IntentExtractor {

    private fun deterministicIntentId(
        trace: DemonstrationTrace,
        canonicalName: String,
        semanticActions: List<SemanticAction> = emptyList()
    ): String {
        val seed = buildString {
            append(trace.traceId)
            append('|')
            append(trace.appContext)
            append('|')
            append(canonicalName)
            append('|')
            trace.voiceEvents.forEach {
                append(it.eventId)
                append(':')
                append(it.transcript.trim().lowercase())
                append('|')
            }
            semanticActions.forEach {
                append(it.actionId)
                append(':')
                append(it.actionType.name)
                append(':')
                append(it.inputValue ?: "")
                append('|')
            }
        }

        return "intent_${seed.hashCode().toUInt().toString(16)}"
    }

    private val GENERIC_RULES = listOf(
        IntentRule("order_food", listOf("order", "buy", "food", "dish", "meal", "delivery", "restaurant", "pizza", "burger", "cart", "add", "menu", "item", "tacos", "sushi")),
        IntentRule("send_message", listOf("send", "message", "text", "chat", "sms", "mail", "email")),
        IntentRule("book_appointment", listOf("book", "schedule", "appointment", "reservation", "calendar")),
        IntentRule("create_reminder", listOf("remind", "reminder", "alarm", "task")),
        IntentRule("navigate", listOf("navigate", "directions", "drive", "map", "route")),
        IntentRule("search_information", listOf("search", "find", "lookup", "google", "query"))
    )

    /**
     * Extracts a high-level task Intent from a normalized DemonstrationTrace and SemanticActions.
     */
    fun extract(
        trace: DemonstrationTrace,
        semanticActions: List<SemanticAction>
    ): IntentExtractionResult {
        val warnings = mutableListOf<String>()

        val voiceEvents = trace.voiceEvents
        val voiceTranscript = voiceEvents.map { it.transcript.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { null }

        val voiceEventIds = voiceEvents.map { it.eventId }
        val actionIds = semanticActions.map { it.actionId }
        val appContext = trace.appContext.ifBlank { null }

        if (voiceTranscript == null && semanticActions.isEmpty()) {
            warnings.add("Insufficient voice and action evidence to extract intent.")
            val unknownIntent = Intent(
                schemaVersion = "1.0",
                intentId = deterministicIntentId(trace, "unknown", semanticActions),
                canonicalName = "unknown",
                confidence = 0.30,
                confidenceLevel = "UNKNOWN",
                sourceVoiceTranscript = null,
                sourceVoiceEventIds = emptyList(),
                supportingActionIds = emptyList(),
                supportingAppPackage = appContext,
                reasoning = "No voice transcript or semantic action evidence available."
            )
            return IntentExtractionResult(
                intent = unknownIntent,
                confidence = 0.30,
                evidenceSummary = "No evidence available.",
                warnings = warnings,
                status = "UNRESOLVED"
            )
        }

        // Evaluate matching scores against generic intent rules
        var bestRule: IntentRule? = null
        var maxScore = 0

        val normalizedVoice = (voiceTranscript ?: "").lowercase()
        val normalizedApp = (appContext ?: "").lowercase()
        val inputValues = semanticActions.mapNotNull { it.inputValue?.lowercase() }
        val targetTexts = semanticActions.flatMap {
            listOfNotNull(
                it.target?.text?.lowercase(),
                it.target?.resourceId?.lowercase(),
                it.target?.contentDescription?.lowercase()
            )
        }

        for (rule in GENERIC_RULES) {
            var score = 0
            for (kw in rule.keywords) {
                // Voice evidence has high priority
                if (normalizedVoice.contains(kw)) {
                    score += 5
                }
                // Action input values represent explicit domain data entered by user (e.g. typing "Pizza")
                if (inputValues.any { it.contains(kw) }) {
                    score += 5
                }
                // App package context (e.g. "com.food.app")
                if (normalizedApp.contains(kw)) {
                    score += 3
                }
                // Target UI elements (e.g. "ADD" button, "id/dish")
                if (targetTexts.any { it.contains(kw) }) {
                    // Do not let generic search UI chrome overpower domain evidence
                    if (rule.canonicalName == "search_information" && inputValues.isNotEmpty()) {
                        score += 1
                    } else {
                        score += 2
                    }
                }
            }
            if (score > maxScore) {
                maxScore = score
                bestRule = rule
            }
        }

        val canonicalName = when {
            bestRule != null -> bestRule.canonicalName
            voiceTranscript != null -> "custom_task"
            else -> "unknown"
        }

        val (confidence, level) = when {
            bestRule != null && voiceTranscript != null -> Pair(1.0, "HIGH")
            bestRule != null || voiceTranscript != null -> Pair(0.75, "MEDIUM")
            semanticActions.isNotEmpty() -> Pair(0.50, "LOW")
            else -> Pair(0.30, "UNKNOWN")
        }

        val reasoning = StringBuilder().apply {
            if (voiceTranscript != null) append("Voice: \"$voiceTranscript\". ")
            if (bestRule != null) append("Matched canonical rule '${bestRule.canonicalName}' (Score: $maxScore). ")
            append("Supported by ${semanticActions.size} semantic actions.")
        }.toString()

        val intent = Intent(
            schemaVersion = "1.0",
            intentId = deterministicIntentId(trace, canonicalName, semanticActions),
            canonicalName = canonicalName,
            confidence = confidence,
            confidenceLevel = level,
            sourceVoiceTranscript = voiceTranscript,
            sourceVoiceEventIds = voiceEventIds,
            supportingActionIds = actionIds,
            supportingAppPackage = appContext,
            reasoning = reasoning
        )

        return IntentExtractionResult(
            schemaVersion = "1.0",
            intent = intent,
            confidence = confidence,
            evidenceSummary = "Intent '$canonicalName' extracted with $level confidence.",
            warnings = warnings,
            status = "EXTRACTED"
        )
    }
}
