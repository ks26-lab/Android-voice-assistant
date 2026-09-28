package com.chockXlate.teachablevoice.contract.runtime

import kotlinx.serialization.Serializable

@Serializable
enum class ExecutionState {
    IDLE,
    INITIATED,
    MATCHING_STATE,
    EXECUTING_STEP,
    WAITING_TRANSITION,
    PAUSED_FOR_HANDOFF,
    PAUSED_FOR_SAFETY_CONFIRMATION,
    WAITING_FOR_USER,
    COMPLETED,
    FAILED,
    ABORTED
}
