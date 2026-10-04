package com.chockXlate.teachablevoice.teach.bonus.crossapp

/**
 * Evidence metrics for cross-app semantic transferability.
 */
data class CrossAppEvidence(
    val stepIndex: Int,
    val stepId: String,
    val sourceRole: String?,
    val matchedTargetElementId: String?,
    val roleMatchScore: Double,
    val semanticTextScore: Double,
    val contentDescScore: Double,
    val structuralContextScore: Double,
    val actionCompatibilityScore: Double,
    val overallStepConfidence: Double,
    val isSensitiveScreenBlocked: Boolean = false,
    val reason: String
)
