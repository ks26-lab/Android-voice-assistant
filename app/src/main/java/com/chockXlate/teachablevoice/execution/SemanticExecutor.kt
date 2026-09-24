package com.chockXlate.teachablevoice.execution

import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.ui.ActionOutcome
import com.chockXlate.teachablevoice.runtime.ui.UiDriver
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import com.chockXlate.teachablevoice.safety.SafetyGate

class SemanticExecutor(private val matcher: SemanticMatcher) {
    suspend fun execute(
        driver: UiDriver, step: BoundStep, before: UiObservation,
        packageName: String, boundary: SafetyBoundary, gate: SafetyGate
    ): ActionOutcome {
        gate.check(boundary, before, step)?.let { return ActionOutcome(false, reason = it, before = before) }
        val match = matcher.match(step.selector, before, step.action)
        if (match.status != MatchStatus.MATCHED) return ActionOutcome(false, reason = match.reason, before = before)
        return driver.execute(step, packageName, boundary, matcher, gate)
    }
}
