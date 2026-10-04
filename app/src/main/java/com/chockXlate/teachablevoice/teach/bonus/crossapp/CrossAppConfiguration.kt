package com.chockXlate.teachablevoice.teach.bonus.crossapp

/**
 * Configuration for cross-app generalization threshold and safety policies.
 */
data class CrossAppConfiguration(
    val transferableThreshold: Double = 0.70,
    val partialThreshold: Double = 0.45,
    val roleMatchWeight: Double = 0.25,
    val semanticTextWeight: Double = 0.35,
    val contentDescWeight: Double = 0.20,
    val structuralContextWeight: Double = 0.20
)
