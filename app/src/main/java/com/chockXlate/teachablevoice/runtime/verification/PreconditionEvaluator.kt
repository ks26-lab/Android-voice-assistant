package com.chockXlate.teachablevoice.runtime.verification

import com.chockXlate.teachablevoice.contract.workflow.Preconditions
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.ui.UiObservation

class PreconditionEvaluator(private val matcher: SemanticMatcher) {
    fun evaluate(p: Preconditions, ui: UiObservation, workflowPackage: String, knownStates: Map<String, String> = emptyMap()): String? = when {
        p.schemaVersion != "1.0" -> "Unsupported precondition schema."
        ui.state.appContext != (p.requiredPackage ?: workflowPackage) -> "The required app is not the active app. Open it before continuing."
        p.requiredActivity != null -> "Required activity cannot be observed reliably through the current UI contract."
        p.fromState != null && !stateAgrees(p, ui, knownStates) -> "The declared starting state has no matching observed semantic evidence."
        p.customConditions.isNotEmpty() -> "This workflow uses unsupported custom preconditions."
        p.requiredElementPresent != null && !matcher.present(p.requiredElementPresent, ui) -> "A required control is missing from the current screen."
        else -> null
    }

    fun stateAgrees(p: Preconditions, ui: UiObservation, knownStates: Map<String, String>): Boolean {
        val label = p.fromState ?: return true
        if (knownStates[label] == TransitionVerifier.fingerprint(ui)) return true
        // INITIAL_STATE is established only by explicit package AND element evidence.
        return label == "INITIAL_STATE" && p.requiredPackage == ui.state.appContext &&
            p.requiredElementPresent?.let { matcher.present(it, ui) } == true
    }
}
