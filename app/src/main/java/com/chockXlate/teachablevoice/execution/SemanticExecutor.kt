package com.chockXlate.teachablevoice.execution

import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.ui.ActionOutcome
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction
import com.chockXlate.teachablevoice.runtime.ui.UiDriver
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import com.chockXlate.teachablevoice.safety.SafetyGate

/**
 * Phase 4.6: Safety-Gated Generic Android Action Execution.
 * Resolves the semantic target from Phase 4.5, validates target and action capabilities,
 * enforces authoritative SafetyGate checks, and executes through generic UiDriver.
 */
class SemanticExecutor(private val matcher: SemanticMatcher) {

    suspend fun execute(
        driver: UiDriver,
        step: BoundStep,
        before: UiObservation,
        packageName: String,
        boundary: SafetyBoundary,
        gate: SafetyGate
    ): ActionOutcome {
        return executeInternal(driver, null, step, before, packageName, boundary, gate)
    }

    suspend fun execute(
        driver: UiDriver,
        request: ExecutionRequest,
        step: BoundStep,
        before: UiObservation,
        packageName: String,
        boundary: SafetyBoundary,
        gate: SafetyGate
    ): ActionOutcome {
        return executeInternal(driver, request, step, before, packageName, boundary, gate)
    }

    private suspend fun executeInternal(
        driver: UiDriver,
        request: ExecutionRequest?,
        step: BoundStep,
        before: UiObservation,
        packageName: String,
        boundary: SafetyBoundary,
        gate: SafetyGate
    ): ActionOutcome {
        // 1. Authoritative SafetyGate check first (existing blocked reason or policy evaluation)
        gate.reason()?.let { return ActionOutcome(false, reason = it, before = before) }
        gate.check(boundary, before, step)?.let { return ActionOutcome(false, reason = it, before = before) }

        // If request is explicitly flagged sensitive, block automated execution immediately
        if (request?.isSensitive == true) {
            gate.block("ExecutionRequest is flagged security-sensitive; user authorization required.")
            return ActionOutcome(
                attempted = false,
                reason = "SENSITIVE_BLOCKED: Request requires user authorization.",
                before = before,
                isSensitiveBlocked = true,
                executedAction = step.action
            )
        }

        // 2. Resolve semantic target from Phase 4.5
        val match = matcher.match(step.selector, before, step.action)

        // 3. Handle non-matched statuses strictly without execution
        when (match.status) {
            MatchStatus.AMBIGUOUS -> return ActionOutcome(
                attempted = false,
                reason = "AMBIGUOUS: ${match.reason}",
                before = before,
                executedAction = step.action
            )
            MatchStatus.NONE, MatchStatus.NO_MATCH -> return ActionOutcome(
                attempted = false,
                reason = "NO_MATCH: ${match.reason}",
                before = before,
                executedAction = step.action
            )
            MatchStatus.WEAK -> return ActionOutcome(
                attempted = false,
                reason = "WEAK: ${match.reason}",
                before = before,
                executedAction = step.action
            )
            MatchStatus.SENSITIVE_TARGET -> {
                gate.block("Semantic target is security-sensitive; user authorization required.")
                return ActionOutcome(
                    attempted = false,
                    reason = "SENSITIVE_BLOCKED: Target is sensitive.",
                    before = before,
                    targetElementId = match.best?.element?.elementId,
                    isSensitiveBlocked = true,
                    executedAction = step.action
                )
            }
            MatchStatus.INVALID_STATE -> return ActionOutcome(
                attempted = false,
                reason = "INVALID_STATE: ${match.reason}",
                before = before,
                executedAction = step.action
            )
            MatchStatus.UNSUPPORTED -> return ActionOutcome(
                attempted = false,
                reason = "UNSUPPORTED_ACTION: ${match.reason}",
                before = before,
                executedAction = step.action
            )
            MatchStatus.MATCHED -> { /* Proceed to target property validation */ }
        }

        val target = match.best?.element ?: match.matchedElement
            ?: return ActionOutcome(false, reason = "TARGET_INVALID: Resolved target is null.", before = before)

        // 4. Validate resolved target properties before execution
        if (!target.isVisible) {
            return ActionOutcome(
                attempted = false,
                reason = "TARGET_INVALID: Target is not visible.",
                before = before,
                targetElementId = target.elementId,
                executedAction = step.action
            )
        }
        if (!target.isEnabled) {
            return ActionOutcome(
                attempted = false,
                reason = "TARGET_INVALID: Target is disabled.",
                before = before,
                targetElementId = target.elementId,
                executedAction = step.action
            )
        }
        if (target.isSensitive || before.state.isSensitiveContext) {
            gate.block("Resolved target or active context is security-sensitive.")
            return ActionOutcome(
                attempted = false,
                reason = "SENSITIVE_BLOCKED: Target or context is sensitive.",
                before = before,
                targetElementId = target.elementId,
                isSensitiveBlocked = true,
                executedAction = step.action
            )
        }

        // Action-specific capability validation
        when (step.action) {
            RuntimeAction.CLICK -> {
                if (!target.isClickable) {
                    return ActionOutcome(
                        attempted = false,
                        reason = "TARGET_INVALID: Target is not clickable.",
                        before = before,
                        targetElementId = target.elementId,
                        executedAction = step.action
                    )
                }
            }
            RuntimeAction.INPUT_TEXT -> {
                if (!target.isEditable) {
                    return ActionOutcome(
                        attempted = false,
                        reason = "TARGET_INVALID: Target is not editable.",
                        before = before,
                        targetElementId = target.elementId,
                        executedAction = step.action
                    )
                }
            }
            RuntimeAction.SCROLL -> {
                if (!target.isScrollable) {
                    return ActionOutcome(
                        attempted = false,
                        reason = "TARGET_INVALID: Target is not scrollable.",
                        before = before,
                        targetElementId = target.elementId,
                        executedAction = step.action
                    )
                }
            }
            RuntimeAction.LONG_PRESS -> {
                if (!target.isClickable) {
                    return ActionOutcome(
                        attempted = false,
                        reason = "TARGET_INVALID: Target does not support long press.",
                        before = before,
                        targetElementId = target.elementId,
                        executedAction = step.action
                    )
                }
            }
            RuntimeAction.BACK -> {
                // Navigation back action permitted
            }
        }

        // 5. Stale target check: verify state identity if present
        if (match.stateId != null && before.state.stateId.isNotBlank() && match.stateId != before.state.stateId) {
            return ActionOutcome(
                attempted = false,
                reason = "TARGET_STALE: Observation state changed before execution.",
                before = before,
                targetElementId = target.elementId,
                isStale = true,
                executedAction = step.action
            )
        }

        // 6. Final dispatch through SafetyGate and UiDriver
        val outcome = driver.execute(step, packageName, boundary, matcher, gate)
        return outcome.copy(
            targetElementId = target.elementId,
            executedAction = step.action
        )
    }
}
