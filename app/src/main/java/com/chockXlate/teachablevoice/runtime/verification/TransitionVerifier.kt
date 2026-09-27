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

data class VerificationResult(
    val verified: Boolean,
    val reason: String,
    val after: UiObservation? = null,
    val status: EvidenceEvaluationStatus = if (verified) EvidenceEvaluationStatus.VERIFIED else EvidenceEvaluationStatus.FAILED,
    val evidenceResult: EvidenceEvaluationResult? = null
)
data class VerificationConfig(val pollMs: Long = 100, val maximumTimeoutMs: Long = 10_000) {
    init { require(pollMs > 0 && maximumTimeoutMs in 1..60_000) }
}

class TransitionVerifier(
    private val matcher: SemanticMatcher,
    private val config: VerificationConfig = VerificationConfig()
) {
    private val evidenceEngine = StateEvidenceEngine(matcher)

    fun unsupported(t: ExpectedTransition): String? = when {
        t.schemaVersion != "1.0" -> "Unsupported transition schema."
        t.timeoutMs <= 0 -> "Transition timeout must be positive."
        t.verification != null -> "The free-form transition verification expression is unsupported."
        t.transitionType !in setOf("STATE_CHANGE", "UI_STATE_CHANGE", "SEMANTIC_ACTION") -> "Unsupported transition type."
        t.expectedEvidence.any { it.schemaVersion != "1.0" } -> "Unsupported evidence schema version."
        t.expectedEvidence.any { it.selector?.schemaVersion != null && it.selector.schemaVersion != "1.0" } -> "Unsupported evidence selector schema."
        else -> null
    }

    fun startingStateError(step: BoundStep, ui: UiObservation): String? {
        step.quantityBefore?.let {
            if (matcher.match(it, ui).status != MatchStatus.MATCHED)
                return "The quantity counter is missing, ambiguous, or differs from the demonstrated baseline. Restore it before replay."
        }
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
        unsupported(t)?.let { return VerificationResult(false, it, status = EvidenceEvaluationStatus.FAILED) }
        startingStateError(step, before)?.let { return VerificationResult(false, it, status = EvidenceEvaluationStatus.FAILED) }
        val budget = minOf(t.timeoutMs, config.maximumTimeoutMs)
        var waited = 0L
        var last: UiObservation? = null
        var lastEval: EvidenceEvaluationResult? = null
        while (waited <= budget) {
            gate.reason()?.let { return VerificationResult(false, it, last, status = EvidenceEvaluationStatus.FAILED) }
            if (!driver.isReady()) return VerificationResult(false, "Accessibility service disconnected during verification.", last, status = EvidenceEvaluationStatus.UNCERTAIN)
            val after = driver.observe()
            if (after != null) {
                last = after
                gate.check(boundary, after, step)?.let { return VerificationResult(false, it, after, status = EvidenceEvaluationStatus.FAILED) }

                val quantityOk = step.quantityBefore == null ||
                    (t.expectedElementAppeared != null && matcher.match(t.expectedElementAppeared, after).status == MatchStatus.MATCHED)

                val eval = evidenceEngine.evaluate(before, t, after, step)
                lastEval = eval

                if (eval.status == EvidenceEvaluationStatus.VERIFIED && quantityOk) {
                    return VerificationResult(
                        verified = true,
                        reason = eval.reason,
                        after = after,
                        status = EvidenceEvaluationStatus.VERIFIED,
                        evidenceResult = eval
                    )
                }
            }
            if (waited == budget) break
            val delay = minOf(config.pollMs, budget - waited)
            driver.awaitChange(delay)
            waited += delay
        }
        val finalStatus = lastEval?.status ?: EvidenceEvaluationStatus.UNCERTAIN
        val finalReason = if (lastEval != null && lastEval.contradictory.any { it.type != com.chockXlate.teachablevoice.contract.workflow.EvidenceType.GENERIC_STATE_CHANGE }) {
            lastEval.reason
        } else {
            "Transition timed out without sufficient observable completion evidence."
        }
        return VerificationResult(
            verified = false,
            reason = finalReason,
            after = last,
            status = finalStatus,
            evidenceResult = lastEval
        )
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
