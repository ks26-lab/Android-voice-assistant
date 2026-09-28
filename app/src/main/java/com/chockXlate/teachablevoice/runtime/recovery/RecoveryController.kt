package com.chockXlate.teachablevoice.runtime.recovery

import com.chockXlate.teachablevoice.contract.workflow.RecoveryPolicy
import com.chockXlate.teachablevoice.contract.workflow.RecoveryStrategy

enum class RecoveryAction {
    RETRY,
    RERESOLVE_TARGET,
    RETRY_NON_SIDE_EFFECTING_STEP_IF_PROVEN_SAFE,
    ASK_USER,
    HANDOFF,
    ABORT
}

data class RecoveryDecision(val action: RecoveryAction, val reason: String, val delayMs: Long = 0)

class RecoveryController(private val hardRetryLimit: Int = 3) {
    init { require(hardRetryLimit in 0..3) }
    fun retryLimit(policy: RecoveryPolicy): Int = minOf(policy.maxRetries.coerceAtLeast(0), hardRetryLimit)

    fun decide(policy: RecoveryPolicy, retries: Int, actionAttempted: Boolean): RecoveryDecision {
        if (policy.strategy == RecoveryStrategy.ABORT) return RecoveryDecision(RecoveryAction.ABORT, "Workflow recovery policy requires abort.")
        if (actionAttempted) return RecoveryDecision(RecoveryAction.HANDOFF, "An action was dispatched but completion is uncertain. Automatic repetition could duplicate a side effect.")
        return when (policy.strategy) {
            RecoveryStrategy.RETRY_STEP -> if (retries < retryLimit(policy)) {
                RecoveryDecision(RecoveryAction.RETRY, "Re-observe and re-match before any action is dispatched.", policy.retryDelayMs.coerceIn(0, 2000))
            } else RecoveryDecision(RecoveryAction.HANDOFF, "The bounded retry budget is exhausted.")
            RecoveryStrategy.DISMISS_POPUP_AND_RETRY -> RecoveryDecision(RecoveryAction.HANDOFF, "Popup dismissal cannot be identified safely from this recovery contract. Dismiss it manually.")
            RecoveryStrategy.NAVIGATE_BACK_AND_RETRY -> RecoveryDecision(RecoveryAction.HANDOFF, "Back navigation may discard user state. Navigate manually before starting a new execution.")
            RecoveryStrategy.HANDOFF_TO_USER -> RecoveryDecision(RecoveryAction.HANDOFF, "Workflow recovery policy requires user handoff.")
            RecoveryStrategy.ABORT -> RecoveryDecision(RecoveryAction.ABORT, "Workflow recovery policy requires abort.")
        }
    }

    /**
     * Subtask-local deterministic recovery.
     * Evaluates UI absence, ambiguity, side-effect safety, and safety boundaries.
     * Invariant: Never repeats an action if actionAttempted is true and verification was uncertain.
     */
    fun decideSubtaskRecovery(
        policy: RecoveryPolicy,
        retries: Int,
        actionAttempted: Boolean,
        targetAbsentOrAmbiguous: Boolean = false,
        isAmbiguousMatch: Boolean = false,
        isNonSideEffecting: Boolean = false,
        safetyBlocked: Boolean = false
    ): RecoveryDecision {
        if (safetyBlocked) {
            return RecoveryDecision(RecoveryAction.HANDOFF, "Safety boundary prevents recovery action.")
        }
        if (policy.strategy == RecoveryStrategy.ABORT) {
            return RecoveryDecision(RecoveryAction.ABORT, "Workflow recovery policy requires abort.")
        }
        if (actionAttempted) {
            return RecoveryDecision(
                RecoveryAction.HANDOFF,
                "An action was dispatched but completion is uncertain. Automatic repetition could duplicate a side effect."
            )
        }
        if (isAmbiguousMatch) {
            return RecoveryDecision(RecoveryAction.ASK_USER, "Target is ambiguous on current UI. Clarification required.")
        }
        if (retries >= retryLimit(policy)) {
            return RecoveryDecision(RecoveryAction.HANDOFF, "The bounded retry budget is exhausted.")
        }
        if (targetAbsentOrAmbiguous) {
            return RecoveryDecision(
                RecoveryAction.RERESOLVE_TARGET,
                "Target temporarily absent or selector varied. Re-observe UI and semantically re-resolve target.",
                policy.retryDelayMs.coerceIn(0, 2000)
            )
        }
        if (isNonSideEffecting) {
            return RecoveryDecision(
                RecoveryAction.RETRY_NON_SIDE_EFFECTING_STEP_IF_PROVEN_SAFE,
                "Non-side-effecting observation step can be safely retried.",
                policy.retryDelayMs.coerceIn(0, 2000)
            )
        }
        return decide(policy, retries, actionAttempted)
    }
}
