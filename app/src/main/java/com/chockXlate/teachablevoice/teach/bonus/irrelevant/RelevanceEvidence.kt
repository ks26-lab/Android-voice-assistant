package com.chockXlate.teachablevoice.teach.bonus.irrelevant

import kotlinx.serialization.Serializable

/**
 * Multi-factor evidence scores justifying a relevance classification decision.
 */
@Serializable
data class RelevanceEvidence(
    val eventId: String,
    val decision: RelevanceDecision,
    val confidence: Double,
    val reason: String,
    val semanticTargetScore: Double,
    val stateChangeScore: Double,
    val taskProgressScore: Double,
    val repetitionPenalty: Double,
    val navigationNoisePenalty: Double,
    val isRetained: Boolean
)
