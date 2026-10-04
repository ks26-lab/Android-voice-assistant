package com.chockXlate.teachablevoice.teach.bonus.crossapp

/**
 * Result data contract for cross-app generalization analysis.
 */
data class CrossAppGeneralizationResult(
    val schemaVersion: String = "1.0",
    val sourcePackage: String,
    val targetPackage: String,
    val decision: TransferabilityDecision,
    val overallConfidence: Double,
    val totalSteps: Int,
    val compatibleStepCount: Int,
    val stepEvidences: List<CrossAppEvidence>,
    val preservesSafetyBoundary: Boolean,
    val executionAuthorized: Boolean = false, // ALWAYS false for safety gate isolation
    val summaryReason: String
)
