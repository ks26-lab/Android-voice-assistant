package com.chockXlate.teachablevoice.runtime.verification

import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.EvidenceType
import com.chockXlate.teachablevoice.contract.workflow.ExpectedTransition
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.contract.workflow.StateEvidenceRequirement
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.ui.UiDriver
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import com.chockXlate.teachablevoice.safety.SafetyGate
import java.security.MessageDigest

enum class VerificationStatus {
    VERIFIED_SUCCESS,
    NOT_VERIFIED,
    UNEXPECTED_TRANSITION,
    VERIFICATION_TIMEOUT,
    UI_UNAVAILABLE,
    INVALID_EXPECTATION;

    val isSuccess: Boolean get() = this == VERIFIED_SUCCESS
    val isTimeout: Boolean get() = this == VERIFICATION_TIMEOUT
    val isUnavailable: Boolean get() = this == UI_UNAVAILABLE
    val isUnexpected: Boolean get() = this == UNEXPECTED_TRANSITION
    val isNotVerified: Boolean get() = this == NOT_VERIFIED
}

data class VerificationResult(
    val verified: Boolean,
    val reason: String,
    val after: UiObservation? = null,
    val status: VerificationStatus = if (verified) VerificationStatus.VERIFIED_SUCCESS else VerificationStatus.NOT_VERIFIED,
    val evidenceStatus: EvidenceEvaluationStatus = if (verified) EvidenceEvaluationStatus.VERIFIED else EvidenceEvaluationStatus.FAILED,
    val evidenceResult: EvidenceEvaluationResult? = null,
    val beforeStateId: String? = null,
    val afterStateId: String? = after?.state?.stateId,
    val confidence: Double = if (verified) 1.0 else 0.0,
    val isSensitive: Boolean = after?.state?.isSensitiveContext == true || after?.credentialFieldPresent == true,
    val expectedEvidence: List<StateEvidenceRequirement> = emptyList(),
    val observedEvidence: List<StateEvidenceRequirement> = emptyList()
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

    /**
     * Synchronous semantic verification of two observed state snapshots.
     */
    fun verifyStateTransition(
        before: UiObservation,
        step: BoundStep,
        after: UiObservation?
    ): VerificationResult {
        val t = step.transition
        unsupported(t)?.let {
            return VerificationResult(
                verified = false,
                reason = it,
                after = after,
                status = VerificationStatus.INVALID_EXPECTATION,
                beforeStateId = before.state.stateId,
                afterStateId = after?.state?.stateId
            )
        }
        startingStateError(step, before)?.let {
            return VerificationResult(
                verified = false,
                reason = it,
                after = after,
                status = VerificationStatus.NOT_VERIFIED,
                beforeStateId = before.state.stateId,
                afterStateId = after?.state?.stateId
            )
        }
        if (after == null) {
            return VerificationResult(
                verified = false,
                reason = "Active UI state is unavailable after action dispatch.",
                after = null,
                status = VerificationStatus.UI_UNAVAILABLE,
                beforeStateId = before.state.stateId
            )
        }

        val eval = evidenceEngine.evaluate(before, t, after, step)
        val quantityOk = step.quantityBefore == null ||
            (t.expectedElementAppeared != null && matcher.match(t.expectedElementAppeared, after).status == MatchStatus.MATCHED)

        if (eval.status == EvidenceEvaluationStatus.VERIFIED && quantityOk) {
            return VerificationResult(
                verified = true,
                reason = eval.reason,
                after = after,
                status = VerificationStatus.VERIFIED_SUCCESS,
                evidenceResult = eval,
                beforeStateId = before.state.stateId,
                afterStateId = after.state.stateId,
                confidence = 1.0,
                expectedEvidence = t.effectiveEvidence(),
                observedEvidence = eval.matched
            )
        }

        val (status, reason) = classifyFailureStatus(before, after, eval, timedOut = false)
        return VerificationResult(
            verified = false,
            reason = reason,
            after = after,
            status = status,
            evidenceResult = eval,
            beforeStateId = before.state.stateId,
            afterStateId = after.state.stateId,
            confidence = 0.0,
            expectedEvidence = t.effectiveEvidence(),
            observedEvidence = eval.matched
        )
    }

    /**
     * Synchronous semantic verification taking raw UiState snapshots.
     */
    fun verifyStateTransition(
        beforeState: UiState,
        step: BoundStep,
        afterState: UiState?
    ): VerificationResult = verifyStateTransition(
        before = UiObservation(beforeState),
        step = step,
        after = afterState?.let { UiObservation(it) }
    )

    /**
     * Asynchronous bounded verification loop observing the live Android UI via UiDriver.
     */
    suspend fun verify(
        driver: UiDriver,
        before: UiObservation,
        step: BoundStep,
        boundary: SafetyBoundary = SafetyBoundary(),
        gate: SafetyGate = SafetyGate()
    ): VerificationResult {
        val t = step.transition
        unsupported(t)?.let {
            return VerificationResult(
                verified = false,
                reason = it,
                status = VerificationStatus.INVALID_EXPECTATION,
                beforeStateId = before.state.stateId
            )
        }
        startingStateError(step, before)?.let {
            return VerificationResult(
                verified = false,
                reason = it,
                status = VerificationStatus.NOT_VERIFIED,
                beforeStateId = before.state.stateId
            )
        }

        val budget = minOf(t.timeoutMs, config.maximumTimeoutMs)
        var waited = 0L
        var last: UiObservation? = null
        var lastEval: EvidenceEvaluationResult? = null

        while (waited <= budget) {
            gate.reason()?.let {
                return VerificationResult(
                    verified = false,
                    reason = it,
                    after = last,
                    status = VerificationStatus.NOT_VERIFIED,
                    beforeStateId = before.state.stateId,
                    afterStateId = last?.state?.stateId
                )
            }
            if (!driver.isReady()) {
                return VerificationResult(
                    verified = false,
                    reason = "Accessibility service or driver disconnected during verification.",
                    after = last,
                    status = VerificationStatus.UI_UNAVAILABLE,
                    beforeStateId = before.state.stateId,
                    afterStateId = last?.state?.stateId
                )
            }

            val after = driver.observe()
            if (after != null) {
                last = after
                gate.check(boundary, after, step)?.let {
                    return VerificationResult(
                        verified = false,
                        reason = it,
                        after = after,
                        status = VerificationStatus.NOT_VERIFIED,
                        beforeStateId = before.state.stateId,
                        afterStateId = after.state.stateId
                    )
                }

                val quantityOk = step.quantityBefore == null ||
                    (t.expectedElementAppeared != null && matcher.match(t.expectedElementAppeared, after).status == MatchStatus.MATCHED)

                val eval = evidenceEngine.evaluate(before, t, after, step)
                lastEval = eval

                if (eval.status == EvidenceEvaluationStatus.VERIFIED && quantityOk) {
                    return VerificationResult(
                        verified = true,
                        reason = eval.reason,
                        after = after,
                        status = VerificationStatus.VERIFIED_SUCCESS,
                        evidenceResult = eval,
                        beforeStateId = before.state.stateId,
                        afterStateId = after.state.stateId,
                        confidence = 1.0,
                        expectedEvidence = t.effectiveEvidence(),
                        observedEvidence = eval.matched
                    )
                }
            }

            if (waited == budget) break
            val delay = minOf(config.pollMs, budget - waited)
            driver.awaitChange(delay)
            waited += delay
        }

        val (finalStatus, finalReason) = classifyFailureStatus(before, last, lastEval, timedOut = (waited >= budget))
        return VerificationResult(
            verified = false,
            reason = finalReason,
            after = last,
            status = finalStatus,
            evidenceResult = lastEval,
            beforeStateId = before.state.stateId,
            afterStateId = last?.state?.stateId,
            confidence = 0.0,
            expectedEvidence = t.effectiveEvidence(),
            observedEvidence = lastEval?.matched ?: emptyList()
        )
    }

    private fun classifyFailureStatus(
        before: UiObservation,
        after: UiObservation?,
        eval: EvidenceEvaluationResult?,
        timedOut: Boolean
    ): Pair<VerificationStatus, String> {
        if (after == null) {
            return Pair(VerificationStatus.UI_UNAVAILABLE, "Active UI state is unavailable.")
        }
        val stateChanged = before.state.stateId != after.state.stateId ||
            fingerprint(before) != fingerprint(after)

        if (eval != null && eval.contradictory.isNotEmpty()) {
            val failReason = eval.reason
            return if (stateChanged || eval.contradictory.any { it.type == EvidenceType.EXPECTED_PACKAGE }) {
                Pair(VerificationStatus.UNEXPECTED_TRANSITION, "Unexpected transition occurred: $failReason")
            } else {
                Pair(VerificationStatus.NOT_VERIFIED, failReason)
            }
        }

        if (!stateChanged) {
            return Pair(VerificationStatus.NOT_VERIFIED, "No observable UI transition occurred after action.")
        }

        if (timedOut) {
            return Pair(VerificationStatus.VERIFICATION_TIMEOUT, "Transition timed out without sufficient observable completion evidence.")
        }

        return Pair(VerificationStatus.UNEXPECTED_TRANSITION, "Observed state does not satisfy expected transition.")
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
