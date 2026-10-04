package com.chockXlate.teachablevoice.teach.bonus.clarification

/**
 * State machine enum for Mid-Flow Clarification execution lifecycle.
 */
enum class ClarificationState {
    RUNNING,
    WAITING_FOR_CLARIFICATION,
    VALIDATING_RESPONSE,
    RESUMING,
    COMPLETED,
    FAILED,
    CANCELLED,
    BLOCKED_SAFETY
}
