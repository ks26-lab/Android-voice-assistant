package com.chockXlate.teachablevoice.runtime.verification

import com.chockXlate.teachablevoice.contract.workflow.EvidenceOperator
import com.chockXlate.teachablevoice.contract.workflow.EvidenceType
import com.chockXlate.teachablevoice.contract.workflow.ExpectedTransition
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.StateEvidenceRequirement
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction
import com.chockXlate.teachablevoice.runtime.ui.UiObservation

enum class EvidenceEvaluationStatus {
    VERIFIED,
    FAILED,
    UNCERTAIN
}

data class EvidenceItemResult(
    val requirement: StateEvidenceRequirement,
    val status: EvidenceEvaluationStatus,
    val reason: String
)

data class EvidenceEvaluationResult(
    val status: EvidenceEvaluationStatus,
    val matched: List<StateEvidenceRequirement> = emptyList(),
    val missing: List<StateEvidenceRequirement> = emptyList(),
    val contradictory: List<StateEvidenceRequirement> = emptyList(),
    val itemResults: List<EvidenceItemResult> = emptyList(),
    val reason: String
)

class StateEvidenceEngine(private val matcher: SemanticMatcher = SemanticMatcher()) {

    fun evaluate(
        before: UiObservation,
        transition: ExpectedTransition,
        after: UiObservation,
        step: BoundStep? = null
    ): EvidenceEvaluationResult {
        val explicitReqs = transition.effectiveEvidence()

        // If step is INPUT_TEXT and no TEXT_EQUALS requirement exists for target, add it
        val requirements = if (step?.action == RuntimeAction.INPUT_TEXT && step.inputText != null &&
            explicitReqs.none { it.type == EvidenceType.TEXT_EQUALS }) {
            explicitReqs + StateEvidenceRequirement(
                type = EvidenceType.TEXT_EQUALS,
                selector = step.selector,
                expectedValue = step.inputText,
                description = "Target input field contains expected text"
            )
        } else {
            explicitReqs
        }

        if (requirements.isEmpty()) {
            return EvidenceEvaluationResult(
                status = EvidenceEvaluationStatus.UNCERTAIN,
                reason = "No verification evidence declared or available."
            )
        }

        val itemResults = requirements.map { req -> evaluateItem(req, before, transition, after, step) }

        val matched = itemResults.filter { it.status == EvidenceEvaluationStatus.VERIFIED }.map { it.requirement }
        val contradictory = itemResults.filter { it.status == EvidenceEvaluationStatus.FAILED }.map { it.requirement }
        val missing = itemResults.filter { it.status == EvidenceEvaluationStatus.UNCERTAIN }.map { it.requirement }

        return when (transition.evidenceOperator) {
            EvidenceOperator.ALL_REQUIRED -> {
                when {
                    contradictory.isNotEmpty() -> {
                        val failItem = itemResults.first { it.status == EvidenceEvaluationStatus.FAILED }
                        EvidenceEvaluationResult(
                            status = EvidenceEvaluationStatus.FAILED,
                            matched = matched,
                            missing = missing,
                            contradictory = contradictory,
                            itemResults = itemResults,
                            reason = failItem.reason
                        )
                    }
                    missing.isNotEmpty() -> {
                        val uncertItem = itemResults.first { it.status == EvidenceEvaluationStatus.UNCERTAIN }
                        EvidenceEvaluationResult(
                            status = EvidenceEvaluationStatus.UNCERTAIN,
                            matched = matched,
                            missing = missing,
                            contradictory = contradictory,
                            itemResults = itemResults,
                            reason = uncertItem.reason
                        )
                    }
                    else -> {
                        val hasExplicit = matched.any { it.type != EvidenceType.GENERIC_STATE_CHANGE }
                        val reasonText = if (hasExplicit) {
                            "Declared transition evidence was observed."
                        } else {
                            "Weaker verification: semantic UI state changed after the action."
                        }
                        EvidenceEvaluationResult(
                            status = EvidenceEvaluationStatus.VERIFIED,
                            matched = matched,
                            missing = missing,
                            contradictory = contradictory,
                            itemResults = itemResults,
                            reason = reasonText
                        )
                    }
                }
            }
            EvidenceOperator.ANY_SUFFICIENT -> {
                when {
                    matched.isNotEmpty() -> {
                        EvidenceEvaluationResult(
                            status = EvidenceEvaluationStatus.VERIFIED,
                            matched = matched,
                            missing = missing,
                            contradictory = contradictory,
                            itemResults = itemResults,
                            reason = "Declared transition evidence was observed."
                        )
                    }
                    contradictory.size == itemResults.size -> {
                        EvidenceEvaluationResult(
                            status = EvidenceEvaluationStatus.FAILED,
                            matched = matched,
                            missing = missing,
                            contradictory = contradictory,
                            itemResults = itemResults,
                            reason = itemResults.first().reason
                        )
                    }
                    else -> {
                        EvidenceEvaluationResult(
                            status = EvidenceEvaluationStatus.UNCERTAIN,
                            matched = matched,
                            missing = missing,
                            contradictory = contradictory,
                            itemResults = itemResults,
                            reason = "Observable evidence is insufficient to verify transition."
                        )
                    }
                }
            }
        }
    }

    private fun evaluateItem(
        req: StateEvidenceRequirement,
        before: UiObservation,
        transition: ExpectedTransition,
        after: UiObservation,
        step: BoundStep?
    ): EvidenceItemResult {
        return when (req.type) {
            EvidenceType.EXPECTED_PACKAGE -> {
                val targetPkg = req.expectedPackage ?: transition.expectedPackage ?: before.state.appContext
                if (after.state.appContext == targetPkg) {
                    EvidenceItemResult(req, EvidenceEvaluationStatus.VERIFIED, "Package matches expected '$targetPkg'.")
                } else {
                    EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Expected package '$targetPkg' but observed '${after.state.appContext}'.")
                }
            }
            EvidenceType.ELEMENT_APPEARED -> {
                val sel = req.selector ?: return EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "No selector provided for ELEMENT_APPEARED.")
                if (matcher.present(sel, before)) {
                    EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Expected element was already present before action.")
                } else {
                    val match = matcher.match(sel, after)
                    when (match.status) {
                        MatchStatus.MATCHED -> EvidenceItemResult(req, EvidenceEvaluationStatus.VERIFIED, "Expected element appeared: ${label(sel)}.")
                        MatchStatus.AMBIGUOUS -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Multiple elements match appeared selector: ${label(sel)}.")
                        MatchStatus.WEAK -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Element appearance match is weak: ${label(sel)}.")
                        MatchStatus.NONE -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Expected element did not appear: ${label(sel)}.")
                        else -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Match status unsupported or sensitive: ${match.status.name}")
                    }
                }
            }
            EvidenceType.ELEMENT_DISAPPEARED -> {
                val sel = req.selector ?: return EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "No selector provided for ELEMENT_DISAPPEARED.")
                if (!matcher.present(sel, before)) {
                    EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Element was not present before action to verify disappearance.")
                } else {
                    if (!matcher.present(sel, after)) {
                        EvidenceItemResult(req, EvidenceEvaluationStatus.VERIFIED, "Expected element disappeared: ${label(sel)}.")
                    } else {
                        EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Element is still present after action: ${label(sel)}.")
                    }
                }
            }
            EvidenceType.ELEMENT_EXISTS -> {
                val sel = req.selector ?: return EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "No selector provided for ELEMENT_EXISTS.")
                val match = matcher.match(sel, after)
                when (match.status) {
                    MatchStatus.MATCHED -> EvidenceItemResult(req, EvidenceEvaluationStatus.VERIFIED, "Semantic element exists: ${label(sel)}.")
                    MatchStatus.AMBIGUOUS -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Multiple elements match selector: ${label(sel)}.")
                    MatchStatus.WEAK -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Target match is weak: ${label(sel)}.")
                    MatchStatus.NONE -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Expected semantic element does not exist: ${label(sel)}.")
                    else -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Match status unsupported or sensitive: ${match.status.name}")
                }
            }
            EvidenceType.ELEMENT_NOT_EXISTS -> {
                val sel = req.selector ?: return EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "No selector provided for ELEMENT_NOT_EXISTS.")
                if (!matcher.present(sel, after)) {
                    EvidenceItemResult(req, EvidenceEvaluationStatus.VERIFIED, "Semantic element is absent: ${label(sel)}.")
                } else {
                    EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Semantic element unexpectedly exists: ${label(sel)}.")
                }
            }
            EvidenceType.TEXT_EQUALS -> {
                val sel = req.selector ?: return EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "No selector provided for TEXT_EQUALS.")
                val match = matcher.match(sel, after, if (step?.action == RuntimeAction.INPUT_TEXT) RuntimeAction.INPUT_TEXT else null)
                when (match.status) {
                    MatchStatus.MATCHED -> {
                        val actualText = match.best?.element?.text.orEmpty()
                        val expected = req.expectedValue
                        if (expected == null) {
                            EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "No expected value specified for TEXT_EQUALS.")
                        } else if (SemanticMatcher.normalize(actualText) == SemanticMatcher.normalize(expected)) {
                            EvidenceItemResult(req, EvidenceEvaluationStatus.VERIFIED, "Target field text verified for ${label(sel)}.")
                        } else {
                            EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Target field text contradicts expected value for ${label(sel)}.")
                        }
                    }
                    MatchStatus.AMBIGUOUS -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Target element for text verification is ambiguous: ${label(sel)}.")
                    MatchStatus.WEAK -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Target element match is weak for text verification: ${label(sel)}.")
                    MatchStatus.NONE -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Target element for text verification was not found: ${label(sel)}.")
                    else -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Match status unsupported or sensitive: ${match.status.name}")
                }
            }
            EvidenceType.TEXT_CHANGED -> {
                val sel = req.selector ?: return EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "No selector provided for TEXT_CHANGED.")
                val afterMatch = matcher.match(sel, after)
                when (afterMatch.status) {
                    MatchStatus.MATCHED -> {
                        val afterText = afterMatch.best?.element?.text.orEmpty()
                        val beforeMatch = matcher.match(sel, before)
                        val beforeText = req.previousValue ?: beforeMatch.best?.element?.text.orEmpty()
                        if (SemanticMatcher.normalize(afterText) == SemanticMatcher.normalize(beforeText)) {
                            EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Element text did not change for ${label(sel)}.")
                        } else if (req.expectedValue != null) {
                            if (SemanticMatcher.normalize(afterText) == SemanticMatcher.normalize(req.expectedValue)) {
                                EvidenceItemResult(req, EvidenceEvaluationStatus.VERIFIED, "Element text changed to expected value for ${label(sel)}.")
                            } else {
                                EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Element text changed but contradicts expected value for ${label(sel)}.")
                            }
                        } else {
                            EvidenceItemResult(req, EvidenceEvaluationStatus.VERIFIED, "Element text changed for ${label(sel)}.")
                        }
                    }
                    MatchStatus.AMBIGUOUS -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Target element for text change verification is ambiguous.")
                    MatchStatus.WEAK -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Target element match is weak for text change verification.")
                    MatchStatus.NONE -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Target element for text change verification not found.")
                    else -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Match status unsupported or sensitive: ${afterMatch.status.name}")
                }
            }
            EvidenceType.CHECKED_STATE -> {
                val sel = req.selector ?: return EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "No selector provided for CHECKED_STATE.")
                val match = matcher.match(sel, after)
                when (match.status) {
                    MatchStatus.MATCHED -> {
                        val actualChecked = match.best?.element?.isChecked
                        val wantedChecked = req.expectedChecked ?: true
                        if (actualChecked == wantedChecked) {
                            EvidenceItemResult(req, EvidenceEvaluationStatus.VERIFIED, "Element checked state is $wantedChecked for ${label(sel)}.")
                        } else {
                            EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Element checked state is $actualChecked, expected $wantedChecked for ${label(sel)}.")
                        }
                    }
                    MatchStatus.AMBIGUOUS -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Target element for checked state is ambiguous.")
                    MatchStatus.WEAK -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Target element match is weak for checked state.")
                    MatchStatus.NONE -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Target element for checked state not found.")
                    else -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Match status unsupported or sensitive: ${match.status.name}")
                }
            }
            EvidenceType.SELECTED_STATE -> {
                val sel = req.selector ?: return EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "No selector provided for SELECTED_STATE.")
                val match = matcher.match(sel, after)
                when (match.status) {
                    MatchStatus.MATCHED -> {
                        val actualSelected = match.best?.element?.isSelected
                        val wantedSelected = req.expectedSelected ?: true
                        if (actualSelected == wantedSelected) {
                            EvidenceItemResult(req, EvidenceEvaluationStatus.VERIFIED, "Element selected state is $wantedSelected for ${label(sel)}.")
                        } else {
                            EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Element selected state is $actualSelected, expected $wantedSelected for ${label(sel)}.")
                        }
                    }
                    MatchStatus.AMBIGUOUS -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Target element for selected state is ambiguous.")
                    MatchStatus.WEAK -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Target element match is weak for selected state.")
                    MatchStatus.NONE -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Target element for selected state not found.")
                    else -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Match status unsupported or sensitive: ${match.status.name}")
                }
            }
            EvidenceType.COUNTER_CHANGE -> {
                val sel = req.selector ?: return EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "No selector provided for COUNTER_CHANGE.")
                val match = matcher.match(sel, after)
                when (match.status) {
                    MatchStatus.MATCHED -> {
                        val current = match.best?.element?.text?.toIntOrNull()
                        val expected = req.expectedValue?.toIntOrNull()
                        val delta = req.counterDelta
                        val prev = req.previousValue?.toIntOrNull()
                        when {
                            current == null -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Counter text is not an integer.")
                            expected != null -> {
                                if (current == expected) {
                                    EvidenceItemResult(req, EvidenceEvaluationStatus.VERIFIED, "Counter reached expected value $expected.")
                                } else {
                                    EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Counter value is $current, expected $expected.")
                                }
                            }
                            delta != null && prev != null -> {
                                if (current == prev + delta) {
                                    EvidenceItemResult(req, EvidenceEvaluationStatus.VERIFIED, "Counter changed from $prev to $current.")
                                } else {
                                    EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Counter value is $current, expected ${prev + delta}.")
                                }
                            }
                            else -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Counter expectation parameters insufficient.")
                        }
                    }
                    MatchStatus.AMBIGUOUS -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Counter element is ambiguous.")
                    MatchStatus.WEAK -> EvidenceItemResult(req, EvidenceEvaluationStatus.UNCERTAIN, "Counter element match is weak.")
                    MatchStatus.NONE -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Counter element not found.")
                    else -> EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "Match status unsupported or sensitive: ${match.status.name}")
                }
            }
            EvidenceType.GENERIC_STATE_CHANGE -> {
                val meaningfulChange = TransitionVerifier.fingerprint(before) != TransitionVerifier.fingerprint(after)
                if (meaningfulChange) {
                    EvidenceItemResult(req, EvidenceEvaluationStatus.VERIFIED, "Semantic UI state changed after action.")
                } else {
                    EvidenceItemResult(req, EvidenceEvaluationStatus.FAILED, "No observable semantic UI state change.")
                }
            }
        }
    }

    private fun label(s: SemanticSelector): String {
        val sensitive = listOf("password", "pin", "otp", "secret", "cvv", "card")
        val isSens = listOfNotNull(s.resourceId, s.text, s.contentDescription).any { str ->
            sensitive.any { str.lowercase().contains(it) }
        }
        if (isSens) return "${s.role ?: "element"} [REDACTED]"
        return when {
            !s.resourceId.isNullOrBlank() -> "${s.role ?: "element"} (${s.resourceId})"
            !s.contentDescription.isNullOrBlank() -> "${s.role ?: "element"} '${s.contentDescription}'"
            !s.text.isNullOrBlank() -> "${s.role ?: "element"} '${s.text}'"
            else -> s.role ?: "element"
        }
    }
}
