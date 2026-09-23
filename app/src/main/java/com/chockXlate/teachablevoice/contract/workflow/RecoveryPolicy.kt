package com.chockXlate.teachablevoice.contract.workflow

import kotlinx.serialization.Serializable

@Serializable
enum class RecoveryStrategy {
    RETRY_STEP,
    DISMISS_POPUP_AND_RETRY,
    NAVIGATE_BACK_AND_RETRY,
    HANDOFF_TO_USER,
    ABORT
}

@Serializable
data class RecoveryPolicy(
    val schemaVersion: String = "1.0",
    val maxRetries: Int = 2,
    val strategy: RecoveryStrategy = RecoveryStrategy.HANDOFF_TO_USER,
    val retryDelayMs: Long = 1000L
)
