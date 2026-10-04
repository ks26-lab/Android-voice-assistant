package com.chockXlate.teachablevoice.contract.runtime

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class UncertaintyType {
    MISSING_SLOT,
    AMBIGUOUS_SLOT,
    AMBIGUOUS_TARGET,
    WEAK_TARGET_AMBIGUITY,
    AMBIGUOUS_WORKFLOW,
    RUNTIME_STATE_AMBIGUITY,
    RECOVERY_EXHAUSTED,
    SAFETY_BOUNDARY,
    UNSUPPORTED_CAPABILITY,
    AMBIGUOUS_REFERENCE,
    INVALID_RESPONSE
}

@Serializable
enum class UncertaintyDisposition {
    CONTINUE,
    CLARIFY,
    HANDOFF,
    ABORT
}

@Serializable
data class ClarificationCandidate(
    val index: Int,
    val description: String,
    val semanticRole: String? = null,
    val semanticText: String? = null,
    val attributes: Map<String, String> = emptyMap()
)

@Serializable
data class ClarificationRequest(
    val schemaVersion: String = "1.0",
    val clarificationId: String = UUID.randomUUID().toString(),
    val executionId: String,
    val reason: String,
    val question: String,
    val requiredSlot: String? = null,
    val candidateDescriptions: List<String> = emptyList(),
    val candidates: List<ClarificationCandidate> = emptyList(),
    val pausedSubtaskId: String? = null,
    val pausedStepId: String? = null,
    val uncertaintyType: UncertaintyType = UncertaintyType.AMBIGUOUS_TARGET,
    val attemptCount: Int = 1,
    val maxAttempts: Int = 3,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ClarificationResponse(
    val schemaVersion: String = "1.0",
    val clarificationId: String? = null,
    val executionId: String,
    val providedSlotValue: String? = null,
    val selectedCandidateIndex: Int? = null,
    val selectedCandidateDescription: String? = null,
    val userResponseText: String? = null,
    val resolvedValue: String? = null,
    val isValid: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class ClarificationResolutionResult {
    data class ResolvedCandidate(val index: Int, val description: String, val confidence: Double = 1.0) : ClarificationResolutionResult()
    data class ResolvedSlot(val slot: String, val value: String, val confidence: Double = 1.0) : ClarificationResolutionResult()
    data class AmbiguousReference(val reason: String) : ClarificationResolutionResult()
    data class InvalidResponse(val reason: String) : ClarificationResolutionResult()
    data class SensitiveBlock(val reason: String) : ClarificationResolutionResult()
}

/**
 * Deterministic Natural Language Clarification Resolver for Phase 4.9L.
 * Resolves natural language user answers (e.g. "the second one", "blue", "Home")
 * against clarification requests without bypassing semantic matching or leaking secrets.
 */
object NaturalLanguageClarificationResolver {

    private val CREDIT_CARD_REGEX = Regex("\\b(?:\\d[ -]*?){13,19}\\b")
    private val SENSITIVE_KEYWORD_REGEX = Regex("(?i)\\b(password|pin|cvv|cvv2|otp|passcode|secret|card[ _-]*number|credit[ _-]*card)\\b")

    fun isSensitive(text: String): Boolean {
        if (CREDIT_CARD_REGEX.containsMatchIn(text)) return true
        if (SENSITIVE_KEYWORD_REGEX.containsMatchIn(text)) return true
        return false
    }

    fun resolve(
        request: ClarificationRequest,
        response: ClarificationResponse
    ): ClarificationResolutionResult {
        // 1. Direct explicit index selection
        if (response.selectedCandidateIndex != null) {
            val idx = response.selectedCandidateIndex
            val options = request.candidateDescriptions.ifEmpty { request.candidates.map { it.description } }
            if (options.isNotEmpty() && idx in options.indices) {
                return ClarificationResolutionResult.ResolvedCandidate(idx, options[idx], 1.0)
            }
        }

        // 2. Extract answer text from response
        val rawText = response.userResponseText?.trim()
            ?: response.providedSlotValue?.trim()
            ?: response.selectedCandidateDescription?.trim()
            ?: ""

        if (rawText.isBlank()) {
            return ClarificationResolutionResult.InvalidResponse("User clarification response is empty.")
        }

        // 3. Authoritative Security Guard: Sensitive credential/card detection
        if (isSensitive(rawText)) {
            return ClarificationResolutionResult.SensitiveBlock(
                "Sensitive credential or payment information detected in user clarification response. Automated processing blocked."
            )
        }

        // 4. Missing Slot Resolution
        if (request.requiredSlot != null) {
            val lower = rawText.lowercase()
            // Check if user gave an evasive or invalid response when asked for a slot
            if (lower in listOf("that", "that one", "this", "it", "whatever", "none", "unknown", "idk")) {
                return ClarificationResolutionResult.InvalidResponse(
                    "Response '$rawText' is too vague to resolve required slot '${request.requiredSlot}'."
                )
            }
            return ClarificationResolutionResult.ResolvedSlot(
                slot = request.requiredSlot,
                value = rawText,
                confidence = 1.0
            )
        }

        // 5. Candidate Disambiguation Resolution
        val options = request.candidateDescriptions.ifEmpty { request.candidates.map { it.description } }
        if (options.isNotEmpty()) {
            val lower = rawText.lowercase()

            // A. Check for ambiguous pronouns when multiple candidates exist
            val ambiguousPronouns = setOf("that", "that one", "this", "this one", "it", "the item", "the one", "that item")
            if (lower in ambiguousPronouns && options.size > 1) {
                return ClarificationResolutionResult.AmbiguousReference(
                    "Pronoun reference '$rawText' is ambiguous among ${options.size} visible candidate options."
                )
            }

            // B. Ordinal / Index Reference Matching
            val ordinalIndex = parseOrdinal(lower, options.size)
            if (ordinalIndex != null && ordinalIndex in options.indices) {
                return ClarificationResolutionResult.ResolvedCandidate(
                    index = ordinalIndex,
                    description = options[ordinalIndex],
                    confidence = 1.0
                )
            }

            // C. Semantic / Attribute Matching against candidate descriptions
            val tokens = lower.split(Regex("[^a-zA-Z0-9]+")).filter {
                it.isNotBlank() && it !in setOf("the", "one", "please", "choose", "select", "i", "want", "pick", "option")
            }

            if (tokens.isNotEmpty()) {
                val scores = options.mapIndexed { idx, desc ->
                    val descLower = desc.lowercase()
                    val matchCount = tokens.count { token -> descLower.contains(token) }
                    idx to matchCount
                }

                val maxScore = scores.maxOfOrNull { it.second } ?: 0
                if (maxScore > 0) {
                    val bestMatches = scores.filter { it.second == maxScore }
                    if (bestMatches.size == 1) {
                        val winner = bestMatches.first().first
                        return ClarificationResolutionResult.ResolvedCandidate(
                            index = winner,
                            description = options[winner],
                            confidence = 0.95
                        )
                    } else {
                        return ClarificationResolutionResult.AmbiguousReference(
                            "Attribute reference '$rawText' matches ${bestMatches.size} options equally."
                        )
                    }
                }
            }

            return ClarificationResolutionResult.InvalidResponse(
                "Response '$rawText' does not match any of the available ${options.size} options."
            )
        }

        return ClarificationResolutionResult.InvalidResponse("Clarification request has no resolvable slot or candidates.")
    }

    private fun parseOrdinal(text: String, candidateCount: Int): Int? {
        val t = text.trim().removePrefix("the ").removeSuffix(" one").removeSuffix(" option").trim()
        return when (t) {
            "first", "1st", "1", "one" -> 0
            "second", "2nd", "2", "two" -> 1
            "third", "3rd", "3", "three" -> 2
            "fourth", "4th", "4", "four" -> 3
            "fifth", "5th", "5", "five" -> 4
            "last" -> if (candidateCount > 0) candidateCount - 1 else null
            else -> {
                // Check if text starts with "option 1", "option 2", etc.
                val optMatch = Regex("option\\s*([1-9])").find(text)
                if (optMatch != null) {
                    optMatch.groupValues[1].toIntOrNull()?.minus(1)
                } else null
            }
        }
    }
}

