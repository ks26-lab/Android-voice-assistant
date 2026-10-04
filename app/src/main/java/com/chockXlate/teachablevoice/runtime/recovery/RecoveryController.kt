package com.chockXlate.teachablevoice.runtime.recovery

import com.chockXlate.teachablevoice.contract.workflow.RecoveryPolicy
import com.chockXlate.teachablevoice.contract.workflow.RecoveryStrategy
import com.chockXlate.teachablevoice.runtime.matching.MatchResult
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.ui.ActionOutcome
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import com.chockXlate.teachablevoice.runtime.verification.VerificationResult
import com.chockXlate.teachablevoice.runtime.verification.VerificationStatus

enum class RecoveryAction {
    RETRY,
    RERESOLVE_TARGET,
    RETRY_NON_SIDE_EFFECTING_STEP_IF_PROVEN_SAFE,
    NAVIGATE_BACK,
    SCROLL,
    REOBSERVE,
    ASK_USER,
    HANDOFF,
    ABORT
}

data class RecoveryDecision(
    val action: RecoveryAction,
    val reason: String,
    val delayMs: Long = 0,
    val attemptNumber: Int = 0,
    val recoveryStrategy: RecoveryStrategy = RecoveryStrategy.HANDOFF_TO_USER
)

class RecoveryController(private val hardRetryLimit: Int = 3) {
    init { require(hardRetryLimit in 0..3) }
    fun retryLimit(policy: RecoveryPolicy): Int = minOf(policy.maxRetries.coerceAtLeast(0), hardRetryLimit)

    fun decide(policy: RecoveryPolicy, retries: Int, actionAttempted: Boolean): RecoveryDecision {
        if (policy.strategy == RecoveryStrategy.ABORT || policy.strategy == RecoveryStrategy.NONE)
            return RecoveryDecision(RecoveryAction.ABORT, "Workflow recovery policy requires abort.", recoveryStrategy = policy.strategy)
        if (actionAttempted) return RecoveryDecision(RecoveryAction.HANDOFF, "An action was dispatched but completion is uncertain. Automatic repetition could duplicate a side effect.", recoveryStrategy = policy.strategy)
        return when (policy.strategy) {
            RecoveryStrategy.RETRY_STEP -> if (retries < retryLimit(policy)) {
                RecoveryDecision(RecoveryAction.RETRY, "Re-observe and re-match before any action is dispatched.", policy.retryDelayMs.coerceIn(0, 2000), attemptNumber = retries + 1, recoveryStrategy = policy.strategy)
            } else RecoveryDecision(RecoveryAction.HANDOFF, "The bounded retry budget is exhausted.", recoveryStrategy = policy.strategy)
            RecoveryStrategy.DISMISS_POPUP_AND_RETRY -> RecoveryDecision(RecoveryAction.HANDOFF, "Popup dismissal cannot be identified safely from this recovery contract. Dismiss it manually.", recoveryStrategy = policy.strategy)
            RecoveryStrategy.NAVIGATE_BACK_AND_RETRY -> RecoveryDecision(RecoveryAction.NAVIGATE_BACK, "Back navigation requested by recovery policy.", policy.retryDelayMs.coerceIn(0, 2000), attemptNumber = retries + 1, recoveryStrategy = policy.strategy)
            RecoveryStrategy.SCROLL_AND_RETRY -> RecoveryDecision(RecoveryAction.SCROLL, "Scroll requested by recovery policy.", policy.retryDelayMs.coerceIn(0, 2000), attemptNumber = retries + 1, recoveryStrategy = policy.strategy)
            RecoveryStrategy.REOBSERVE_AND_RETRY -> RecoveryDecision(RecoveryAction.REOBSERVE, "Re-observation requested by recovery policy.", policy.retryDelayMs.coerceIn(0, 2000), attemptNumber = retries + 1, recoveryStrategy = policy.strategy)
            RecoveryStrategy.HANDOFF_TO_USER -> RecoveryDecision(RecoveryAction.HANDOFF, "Workflow recovery policy requires user handoff.", recoveryStrategy = policy.strategy)
            RecoveryStrategy.ABORT, RecoveryStrategy.NONE -> RecoveryDecision(RecoveryAction.ABORT, "Workflow recovery policy requires abort.", recoveryStrategy = policy.strategy)
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
            return RecoveryDecision(RecoveryAction.HANDOFF, "Safety boundary prevents recovery action.", recoveryStrategy = policy.strategy)
        }
        if (policy.strategy == RecoveryStrategy.ABORT || policy.strategy == RecoveryStrategy.NONE) {
            return RecoveryDecision(RecoveryAction.ABORT, "Workflow recovery policy requires abort.", recoveryStrategy = policy.strategy)
        }
        if (actionAttempted) {
            return RecoveryDecision(
                RecoveryAction.HANDOFF,
                "An action was dispatched but completion is uncertain. Automatic repetition could duplicate a side effect.",
                recoveryStrategy = policy.strategy
            )
        }
        if (isAmbiguousMatch) {
            return RecoveryDecision(RecoveryAction.ASK_USER, "Target is ambiguous on current UI. Clarification required.", recoveryStrategy = policy.strategy)
        }
        if (retries >= retryLimit(policy)) {
            return RecoveryDecision(RecoveryAction.HANDOFF, "The bounded retry budget is exhausted.", recoveryStrategy = policy.strategy)
        }
        if (targetAbsentOrAmbiguous) {
            return RecoveryDecision(
                RecoveryAction.RERESOLVE_TARGET,
                "Target temporarily absent or selector varied. Re-observe UI and semantically re-resolve target.",
                policy.retryDelayMs.coerceIn(0, 2000),
                attemptNumber = retries + 1,
                recoveryStrategy = policy.strategy
            )
        }
        if (isNonSideEffecting) {
            return RecoveryDecision(
                RecoveryAction.RETRY_NON_SIDE_EFFECTING_STEP_IF_PROVEN_SAFE,
                "Non-side-effecting observation step can be safely retried.",
                policy.retryDelayMs.coerceIn(0, 2000),
                attemptNumber = retries + 1,
                recoveryStrategy = policy.strategy
            )
        }
        return decide(policy, retries, actionAttempted)
    }

    /**
     * Phase 4.8: Closed-Loop Bounded Recovery Decision.
     * Consumes Phase 4.7 verification results, live UI state, action outcome, and workflow policy.
     * Invariants:
     * 1. Safety Gate is authoritative (sensitive states and safety blocks strictly stop automation).
     * 2. Bounded retries: attempts <= maxRetries.
     * 3. No blind repetitions: fresh observation and rematch required before acting.
     */
    fun decideRecovery(
        policy: RecoveryPolicy,
        verification: VerificationResult,
        actionOutcome: ActionOutcome? = null,
        currentUi: UiObservation? = null,
        retries: Int = 0,
        targetMatch: MatchResult? = null,
        isSensitive: Boolean = false,
        safetyBlocked: Boolean = false,
        scrollCount: Int = 0,
        maxScrolls: Int = 2
    ): RecoveryDecision {
        // 1. Authoritative Safety & Sensitive Checks: Under no circumstances does recovery bypass SafetyGate
        if (safetyBlocked) {
            return RecoveryDecision(RecoveryAction.HANDOFF, "Safety boundary prevents recovery action.", recoveryStrategy = policy.strategy)
        }
        if (isSensitive || currentUi?.state?.isSensitiveContext == true || currentUi?.credentialFieldPresent == true || verification.isSensitive) {
            return RecoveryDecision(RecoveryAction.HANDOFF, "Security-sensitive UI state detected. Automated recovery stopped.", recoveryStrategy = policy.strategy)
        }

        // 2. Verified success: No recovery needed
        if (verification.status == VerificationStatus.VERIFIED_SUCCESS || verification.verified) {
            return RecoveryDecision(RecoveryAction.HANDOFF, "Step verified successfully; no recovery needed.", recoveryStrategy = policy.strategy)
        }

        // 3. Invalid expectation: Stop immediately, no speculative recovery
        if (verification.status == VerificationStatus.INVALID_EXPECTATION) {
            return RecoveryDecision(RecoveryAction.ABORT, "Invalid workflow transition expectation; cannot recover speculatively.", recoveryStrategy = RecoveryStrategy.ABORT)
        }

        // 4. Policy is ABORT or NONE
        if (policy.strategy == RecoveryStrategy.ABORT || policy.strategy == RecoveryStrategy.NONE) {
            return RecoveryDecision(RecoveryAction.ABORT, "Workflow recovery policy requires immediate abort (strategy: ${policy.strategy}).", recoveryStrategy = policy.strategy)
        }

        // 5. Policy is HANDOFF_TO_USER
        if (policy.strategy == RecoveryStrategy.HANDOFF_TO_USER) {
            return RecoveryDecision(RecoveryAction.HANDOFF, "Workflow recovery policy requires user handoff.", recoveryStrategy = policy.strategy)
        }

        // 6. Hard bounded retry limit check
        if (retries >= retryLimit(policy)) {
            return RecoveryDecision(RecoveryAction.HANDOFF, "Bounded recovery attempt budget is exhausted ($retries/${retryLimit(policy)}).", recoveryStrategy = policy.strategy)
        }

        // 7. UI Unavailable
        if (verification.status == VerificationStatus.UI_UNAVAILABLE || currentUi == null) {
            return if (retries < retryLimit(policy)) {
                RecoveryDecision(
                    RecoveryAction.REOBSERVE,
                    "UI temporarily unavailable. Bounded re-observation permitted.",
                    delayMs = policy.retryDelayMs.coerceIn(100, 2000),
                    attemptNumber = retries + 1,
                    recoveryStrategy = policy.strategy
                )
            } else {
                RecoveryDecision(RecoveryAction.HANDOFF, "UI remains unavailable after bounded re-observation.", recoveryStrategy = policy.strategy)
            }
        }

        // 8. Rematch Target Evaluation
        if (targetMatch != null) {
            when (targetMatch.status) {
                MatchStatus.AMBIGUOUS -> return RecoveryDecision(
                    RecoveryAction.ASK_USER,
                    "Target is ambiguous on current UI. User clarification required.",
                    recoveryStrategy = policy.strategy
                )
                MatchStatus.SENSITIVE_TARGET -> return RecoveryDecision(
                    RecoveryAction.HANDOFF,
                    "Recovery target is security-sensitive; user handoff required.",
                    recoveryStrategy = policy.strategy
                )
                MatchStatus.WEAK -> return RecoveryDecision(
                    RecoveryAction.HANDOFF,
                    "Target match confidence is too weak for safe recovery.",
                    recoveryStrategy = policy.strategy
                )
                MatchStatus.NONE, MatchStatus.NO_MATCH -> {
                    if (policy.strategy == RecoveryStrategy.SCROLL_AND_RETRY) {
                        return if (scrollCount < maxScrolls) {
                            RecoveryDecision(
                                RecoveryAction.SCROLL,
                                "Target absent on current view. Bounded scroll allowed by policy ($scrollCount/$maxScrolls).",
                                delayMs = policy.retryDelayMs.coerceIn(100, 2000),
                                attemptNumber = retries + 1,
                                recoveryStrategy = policy.strategy
                            )
                        } else {
                            RecoveryDecision(
                                RecoveryAction.HANDOFF,
                                "Target absent and scroll attempt limit reached ($scrollCount/$maxScrolls).",
                                recoveryStrategy = policy.strategy
                            )
                        }
                    } else if (policy.strategy == RecoveryStrategy.NAVIGATE_BACK_AND_RETRY) {
                        return RecoveryDecision(
                            RecoveryAction.NAVIGATE_BACK,
                            "Target absent on current screen. Bounded back navigation allowed by policy.",
                            delayMs = policy.retryDelayMs.coerceIn(100, 2000),
                            attemptNumber = retries + 1,
                            recoveryStrategy = policy.strategy
                        )
                    } else {
                        return RecoveryDecision(
                            RecoveryAction.HANDOFF,
                            "Target absent on current UI and policy does not permit scroll/back.",
                            recoveryStrategy = policy.strategy
                        )
                    }
                }
                MatchStatus.MATCHED -> { /* Target matched on current UI, proceed to transition status evaluation */ }
                else -> {}
            }
        }

        // 9. Unexpected Transition
        if (verification.status == VerificationStatus.UNEXPECTED_TRANSITION) {
            return when (policy.strategy) {
                RecoveryStrategy.NAVIGATE_BACK_AND_RETRY -> RecoveryDecision(
                    RecoveryAction.NAVIGATE_BACK,
                    "Unexpected transition occurred. Bounded back navigation allowed by policy.",
                    delayMs = policy.retryDelayMs.coerceIn(100, 2000),
                    attemptNumber = retries + 1,
                    recoveryStrategy = policy.strategy
                )
                RecoveryStrategy.DISMISS_POPUP_AND_RETRY,
                RecoveryStrategy.RETRY_STEP,
                RecoveryStrategy.REOBSERVE_AND_RETRY -> RecoveryDecision(
                    RecoveryAction.RERESOLVE_TARGET,
                    "Unexpected screen encountered. Re-observing current UI and rematching target.",
                    delayMs = policy.retryDelayMs.coerceIn(100, 2000),
                    attemptNumber = retries + 1,
                    recoveryStrategy = policy.strategy
                )
                else -> RecoveryDecision(
                    RecoveryAction.HANDOFF,
                    "Unexpected transition occurred and policy does not permit automated back/rematch.",
                    recoveryStrategy = policy.strategy
                )
            }
        }

        // 10. Verification Timeout
        if (verification.status == VerificationStatus.VERIFICATION_TIMEOUT) {
            return RecoveryDecision(
                RecoveryAction.REOBSERVE,
                "Verification timed out. Bounded re-observation to detect delayed UI settlement.",
                delayMs = policy.retryDelayMs.coerceIn(100, 2000),
                attemptNumber = retries + 1,
                recoveryStrategy = policy.strategy
            )
        }

        // 11. Not Verified (Action attempted, but no expected state change)
        if (verification.status == VerificationStatus.NOT_VERIFIED) {
            val wasDispatched = actionOutcome?.attempted == true && actionOutcome.accepted
            return when (policy.strategy) {
                RecoveryStrategy.RETRY_STEP -> {
                    if (wasDispatched) {
                        RecoveryDecision(
                            RecoveryAction.RERESOLVE_TARGET,
                            "Action dispatched but unverified. Re-observing UI and rematching before any retry.",
                            delayMs = policy.retryDelayMs.coerceIn(100, 2000),
                            attemptNumber = retries + 1,
                            recoveryStrategy = policy.strategy
                        )
                    } else {
                        RecoveryDecision(
                            RecoveryAction.RETRY,
                            "Action rejected without side effect. Bounded retry permitted.",
                            delayMs = policy.retryDelayMs.coerceIn(100, 2000),
                            attemptNumber = retries + 1,
                            recoveryStrategy = policy.strategy
                        )
                    }
                }
                RecoveryStrategy.REOBSERVE_AND_RETRY -> RecoveryDecision(
                    RecoveryAction.REOBSERVE,
                    "Step unverified. Bounded re-observation permitted.",
                    delayMs = policy.retryDelayMs.coerceIn(100, 2000),
                    attemptNumber = retries + 1,
                    recoveryStrategy = policy.strategy
                )
                RecoveryStrategy.NAVIGATE_BACK_AND_RETRY -> RecoveryDecision(
                    RecoveryAction.NAVIGATE_BACK,
                    "Step unverified. Bounded back navigation allowed by policy.",
                    delayMs = policy.retryDelayMs.coerceIn(100, 2000),
                    attemptNumber = retries + 1,
                    recoveryStrategy = policy.strategy
                )
                RecoveryStrategy.SCROLL_AND_RETRY -> {
                    if (scrollCount < maxScrolls) {
                        RecoveryDecision(
                            RecoveryAction.SCROLL,
                            "Step unverified. Bounded scroll allowed by policy ($scrollCount/$maxScrolls).",
                            delayMs = policy.retryDelayMs.coerceIn(100, 2000),
                            attemptNumber = retries + 1,
                            recoveryStrategy = policy.strategy
                        )
                    } else {
                        RecoveryDecision(RecoveryAction.HANDOFF, "Scroll attempt limit reached.", recoveryStrategy = policy.strategy)
                    }
                }
                else -> RecoveryDecision(
                    RecoveryAction.HANDOFF,
                    "Step unverified and policy does not permit automated retry.",
                    recoveryStrategy = policy.strategy
                )
            }
        }

        return decide(policy, retries, actionOutcome?.attempted == true)
    }
}
