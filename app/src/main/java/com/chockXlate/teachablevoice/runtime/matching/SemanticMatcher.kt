package com.chockXlate.teachablevoice.runtime.matching

import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import java.util.Locale

data class MatchConfig(val executeThreshold: Double = 0.85, val ambiguityMargin: Double = 0.12)
enum class MatchStatus { MATCHED, AMBIGUOUS, WEAK, NONE }
data class CandidateMatch(val element: UiElement, val confidence: Double, val evidence: List<String>)
data class MatchResult(
    val status: MatchStatus,
    val best: CandidateMatch? = null,
    val second: CandidateMatch? = null,
    val reason: String
)

class SemanticMatcher(private val config: MatchConfig = MatchConfig()) {
    fun match(selector: SemanticSelector, ui: UiObservation, action: RuntimeAction? = null): MatchResult {
        val candidates = ui.state.allElements.asSequence()
            .filter { action == null || compatible(it, ui, action) }
            .mapNotNull { score(selector, it) }
            .sortedByDescending { it.confidence }.toList()
        val first = candidates.getOrNull(0)
            ?: return MatchResult(MatchStatus.NONE, reason = "The expected semantic control is not present or cannot perform this action.")
        val second = candidates.getOrNull(1)
        if (second != null && first.confidence - second.confidence < config.ambiguityMargin) {
            return MatchResult(MatchStatus.AMBIGUOUS, first, second, "Two or more controls match the requested target. User clarification is required.")
        }
        return if (first.confidence >= config.executeThreshold) {
            MatchResult(MatchStatus.MATCHED, first, second, "Unique target supported by semantic evidence.")
        } else MatchResult(MatchStatus.WEAK, first, second, "Target evidence is insufficient for safe execution.")
    }

    /** For presence checks, ambiguity still proves existence; weak matches do not. */
    fun present(selector: SemanticSelector, ui: UiObservation): Boolean =
        ui.state.allElements.any { (score(selector, it)?.confidence ?: 0.0) >= config.executeThreshold }

    private fun compatible(e: UiElement, ui: UiObservation, action: RuntimeAction): Boolean {
        if (!e.isEnabled) return false
        ui.capabilities[e.elementId]?.let { return action in it }
        return when (action) {
            RuntimeAction.CLICK -> e.isClickable
            RuntimeAction.INPUT_TEXT -> e.isEditable
            RuntimeAction.SCROLL -> e.isScrollable
            RuntimeAction.LONG_PRESS -> false // Requires live supported-action evidence.
        }
    }

    private fun score(s: SemanticSelector, e: UiElement): CandidateMatch? {
        if (s.textSlot != null) return null
        val evidence = mutableListOf<String>()
        var total = 0.0
        var earned = 0.0
        var anchor = false
        var contradiction = false
        fun field(name: String, wanted: String?, actual: String?, weight: Double, high: Boolean = false, strict: Boolean = false) {
            if (wanted.isNullOrBlank()) return
            total += weight
            val w = normalize(wanted)
            val a = normalize(actual ?: "")
            if (w == a) {
                earned += weight
                evidence.add(name)
                if (high) anchor = true
            } else if (!strict && w.length >= 4 && a.length >= 4 && (a.contains(w) || w.contains(a))) {
                earned += weight * 0.6
            } else if (strict) contradiction = true
        }
        field("resourceId", s.resourceId, e.resourceId, 0.5, high = true, strict = true)
        field("text", s.text, e.text, 0.4, high = true, strict = true)
        field("contentDescription", s.contentDescription, e.contentDescription, 0.35, high = true)
        field("role", s.role, e.role, 0.10, strict = true)
        field("parentRole", s.parentRole, e.parentRole, 0.05)
        field("ancestorRole", s.ancestorRole, e.ancestorRole, 0.03)
        field("nearbyText", s.nearbyText, e.nearbyText, 0.05)
        field("relativePosition", s.relativePosition, e.relativePosition, 0.01)
        if (contradiction || total == 0.0 || earned == 0.0) return null
        val confidence = (earned / total).let { if (anchor) it else minOf(it, 0.6) }
        return CandidateMatch(e, confidence, evidence)
    }

    companion object {
        fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    }
}
