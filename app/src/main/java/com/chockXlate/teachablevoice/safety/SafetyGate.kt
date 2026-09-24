package com.chockXlate.teachablevoice.safety

import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.ui.UiObservation

data class DispatchResult(val attempted: Boolean, val accepted: Boolean, val reason: String)

/** A latched gate shared by engine and adapter. Only this critical section dispatches actions. */
class SafetyGate(val policy: RuntimeSafetyPolicy = RuntimeSafetyPolicy()) {
    private var blockedReason: String? = null

    @Synchronized fun reason(): String? = blockedReason

    @Synchronized fun block(reason: String) {
        if (blockedReason == null) blockedReason = reason
    }

    @Synchronized fun check(boundary: SafetyBoundary, ui: UiObservation, step: BoundStep): String? {
        if (blockedReason == null) blockedReason = policy.evaluate(boundary, ui, step)
        return blockedReason
    }

    @Synchronized fun dispatch(
        boundary: SafetyBoundary,
        ui: UiObservation,
        step: BoundStep,
        action: () -> Boolean
    ): DispatchResult {
        val blocked = check(boundary, ui, step)
        if (blocked != null) return DispatchResult(false, false, blocked)
        // Lock is held through dispatch: cancellation/handoff cannot interleave after this check.
        return try {
            val accepted = action()
            DispatchResult(true, accepted, if (accepted) "Android accepted the action; verification is still required." else "Android declined the action; completion is unknown.")
        } catch (_: Exception) {
            DispatchResult(true, false, "Action dispatch failed; completion is unknown.")
        }
    }
}
