package com.chockXlate.teachablevoice.teach.bonus.irrelevant

import kotlinx.serialization.Serializable

/**
 * Configuration threshold parameters for the Bonus Irrelevant-Action Filtering System.
 * Enforces a conservative policy where only high-confidence irrelevant events are filtered.
 */
@Serializable
data class BonusFilteringConfig(
    /** Minimum confidence score required to filter an IRRELEVANT event (default 0.85). */
    val highConfidenceThreshold: Double = 0.85,

    /** Maximum allowed time gap (ms) between rapid accidental taps on identical targets (default 400ms). */
    val accidentalTapThresholdMs: Long = 400L,

    /** Whether to preserve UNCERTAIN events (must ALWAYS be true for safety). */
    val preserveUncertainEvents: Boolean = true,

    /** Whether to preserve events with low/medium confidence (must ALWAYS be true for safety). */
    val preserveLowConfidenceEvents: Boolean = true
)
