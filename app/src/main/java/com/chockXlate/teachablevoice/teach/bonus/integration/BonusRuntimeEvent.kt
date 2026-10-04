package com.chockXlate.teachablevoice.teach.bonus.integration

/**
 * Diagnostic event for real-device observability of bonus execution.
 * Sensitive values are strictly redacted.
 */
data class BonusRuntimeEvent(
    val bonusName: String,
    val stage: String,
    val inputAvailable: Boolean,
    val decision: String,
    val confidence: Double,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)
