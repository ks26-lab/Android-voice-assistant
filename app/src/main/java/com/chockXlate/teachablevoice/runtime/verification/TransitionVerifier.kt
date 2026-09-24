package com.chockXlate.teachablevoice.runtime.verification

import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.workflow.ExpectedTransition
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.ui.UiDriver
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.safety.SafetyGate
import java.security.MessageDigest

data class VerificationResult(val verified: Boolean, val reason: String, val after: UiObservation? = null)
data class VerificationConfig(val pollMs: Long = 100, val maximumTimeoutMs: Long = 10_000) {
    init { require(pollMs > 0 && maximumTimeoutMs in 1..60_000) }
}

class TransitionVerifier(
    private val matcher: SemanticMatcher,
    private val config: VerificationConfig = VerificationConfig()
) {
    fun unsupported(t: ExpectedTransition): String? = when {
        t.schemaVersion != "1.0" -> "Unsupported transition schema."
        t.timeoutMs <= 0 -> "Transition timeout must be positive."
        t.verification != null -> "The free-form transition verification expression is unsupported."
        t.transitionType !in setOf("STATE_CHANGE", "UI_STATE_CHANGE", "SEMANTIC_ACTION") -> "Unsupported transition type."
        else -> null
    }

    fun startingStateError(step: BoundStep, ui: UiObservation): String? {
        val label = step.transition.fromState ?: return null
        if (step.stateEvidence[label] == fingerprint(ui)) return null
        if (label == step.preconditions.fromState &&
            PreconditionEvaluator(matcher).stateAgrees(step.preconditions, ui, step.stateEvidence)) return null
        return "The transition's starting state has no matching observed semantic evidence."
    }

    suspend fun verify(
        driver: UiDriver,
        before: UiObservation,
        step: BoundStep,
        boundary: SafetyBoundary,
        gate: SafetyGate
    ): VerificationResult {
        val t = step.transition
        unsupported(t)?.let { return VerificationResult(false, it) }
        startingStateError(step, before)?.let { return VerificationResult(false, it) }
        val budget = minOf(t.timeoutMs, config.maximumTimeoutMs)
        var waited = 0L
        var last: UiObservation? = null
        while (waited <= budget) {
            gate.reason()?.let { return VerificationResult(false, it, last) }
            if (!driver.isReady()) return VerificationResult(false, "Accessibility service disconnected during verification.", last)
            val after = driver.observe()
            if (after != null) {
                last = after
                gate.check(boundary, after, step)?.let { return VerificationResult(false, it, after) }
                val packageOk = after.state.appContext == (t.expectedPackage ?: before.state.appContext)
                val appeared = t.expectedElementAppeared
                val disappeared = t.expectedElementDisappeared
                val appearanceOk = appeared == null || (!matcher.present(appeared, before) && matcher.present(appeared, after))
                val disappearanceOk = disappeared == null || (matcher.present(disappeared, before) && !matcher.present(disappeared, after))
                val meaningfulChange = fingerprint(before) != fingerprint(after)
                val inputMatch = if (step.action == RuntimeAction.INPUT_TEXT) matcher.match(step.selector, after, step.action) else null
                val inputOk = inputMatch == null || (inputMatch.status == MatchStatus.MATCHED && inputMatch.best?.element?.text == step.inputText)
                if (packageOk && appearanceOk && disappearanceOk && meaningfulChange && inputOk) {
                    val explicit = appeared != null || disappeared != null || (t.expectedPackage != null && t.expectedPackage != before.state.appContext)
                    return VerificationResult(true, if (explicit) "Declared transition evidence was observed." else "Weaker verification: semantic UI state changed after the action.", after)
                }
            }
            if (waited == budget) break
            val delay = minOf(config.pollMs, budget - waited)
            driver.awaitChange(delay)
            waited += delay
        }
        return VerificationResult(false, "Transition timed out without sufficient observable completion evidence.", last)
    }

    companion object {
        /** Excludes UUIDs, timestamps, bounds, and focus-only noise. */
        fun fingerprint(ui: UiObservation): String {
            fun semantic(e: UiElement): List<String?> = listOf(
                e.role, e.text, e.contentDescription, e.resourceId, e.parentRole, e.ancestorRole,
                e.isEnabled.toString(), e.isChecked.toString(), e.isSelected.toString(),
                e.isEditable.toString(), e.isClickable.toString(), e.isScrollable.toString()
            )
            fun encode(fields: List<String?>): String = fields.joinToString("") { value ->
                if (value == null) "-1:" else "${value.length}:$value"
            }
            val elements = ui.state.allElements.map { encode(semantic(it)) }.sorted()
            val canonical = encode(listOf(ui.state.appContext) + elements)
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
