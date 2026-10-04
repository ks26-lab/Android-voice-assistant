package com.chockXlate.teachablevoice.runtime.matching

import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiObservationResult
import com.chockXlate.teachablevoice.contract.ui.UiObservationStatus
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import java.util.Locale

data class MatchConfig(val executeThreshold: Double = 0.85, val ambiguityMargin: Double = 0.12)

enum class MatchStatus {
    MATCHED,
    AMBIGUOUS,
    WEAK,
    NONE,
    NO_MATCH,
    UNSUPPORTED,
    INVALID_STATE,
    SENSITIVE_TARGET;

    val isMatched: Boolean get() = this == MATCHED
    val isAmbiguous: Boolean get() = this == AMBIGUOUS
    val isNoMatch: Boolean get() = this == NONE || this == NO_MATCH
    val isSensitive: Boolean get() = this == SENSITIVE_TARGET
    val isInvalidState: Boolean get() = this == INVALID_STATE
}

data class CandidateMatch(val element: UiElement, val confidence: Double, val evidence: List<String>)

data class MatchResult(
    val status: MatchStatus,
    val best: CandidateMatch? = null,
    val second: CandidateMatch? = null,
    val reason: String,
    val candidates: List<CandidateMatch> = emptyList(),
    val isAmbiguous: Boolean = (status == MatchStatus.AMBIGUOUS),
    val isSensitive: Boolean = (status == MatchStatus.SENSITIVE_TARGET || best?.element?.isSensitive == true),
    val confidence: Double = best?.confidence ?: 0.0,
    val matchedElement: UiElement? = if (status == MatchStatus.MATCHED || status == MatchStatus.SENSITIVE_TARGET) best?.element else null,
    val stateId: String? = null
) {
    val candidateElements: List<UiElement> get() = candidates.map { it.element }
    val isNoMatch: Boolean get() = status == MatchStatus.NO_MATCH || status == MatchStatus.NONE
}

class SemanticMatcher(private val config: MatchConfig = MatchConfig()) {

    /**
     * Phase 4.5 entry point: Resolves the semantic target from a live BoundStep and live UiState.
     */
    fun match(step: BoundStep, uiState: UiState?): MatchResult {
        return match(step.selector, uiState, step.action)
    }

    /**
     * Phase 4.5 entry point: Resolves the semantic target given ExecutionRequest, BoundStep, and live UiState.
     */
    fun match(request: ExecutionRequest, step: BoundStep, uiState: UiState?): MatchResult {
        val baseResult = match(step, uiState)
        if (request.isSensitive && baseResult.status == MatchStatus.MATCHED) {
            return baseResult.copy(
                status = MatchStatus.SENSITIVE_TARGET,
                isSensitive = true,
                reason = "Workflow execution request is flagged security-sensitive; target action requires authorization."
            )
        }
        return baseResult
    }

    /**
     * Phase 4.5 entry point: Resolves the semantic target from a live BoundStep and UiObservationResult.
     */
    fun match(step: BoundStep, observationResult: UiObservationResult): MatchResult {
        return match(step.selector, observationResult, step.action)
    }

    /**
     * Phase 4.5 entry point: Resolves the semantic target given ExecutionRequest, BoundStep, and UiObservationResult.
     */
    fun match(request: ExecutionRequest, step: BoundStep, observationResult: UiObservationResult): MatchResult {
        return when (observationResult.status) {
            UiObservationStatus.UNAVAILABLE -> MatchResult(
                status = MatchStatus.INVALID_STATE,
                reason = "Active UI state is unavailable (${observationResult.errorMessage ?: "no active window"})."
            )
            UiObservationStatus.STALE -> MatchResult(
                status = MatchStatus.INVALID_STATE,
                reason = "Active UI state is stale; target cannot be resolved safely."
            )
            UiObservationStatus.UNSUPPORTED -> MatchResult(
                status = MatchStatus.UNSUPPORTED,
                reason = "Active UI context is unsupported (${observationResult.errorMessage ?: "unsupported window"})."
            )
            UiObservationStatus.SUCCESS -> {
                val state = observationResult.state
                    ?: return MatchResult(MatchStatus.INVALID_STATE, reason = "Observation status is SUCCESS but UiState is null.")
                val baseResult = match(request, step, state)
                if (observationResult.isSensitive && baseResult.status == MatchStatus.MATCHED) {
                    baseResult.copy(
                        status = MatchStatus.SENSITIVE_TARGET,
                        isSensitive = true,
                        reason = "Active window contains sensitive fields; target action requires authorization."
                    )
                } else baseResult
            }
        }
    }

    /**
     * Phase 4.5 entry point: Resolves the semantic target from a SemanticSelector and UiObservationResult.
     */
    fun match(selector: SemanticSelector, observationResult: UiObservationResult, action: RuntimeAction? = null): MatchResult {
        return when (observationResult.status) {
            UiObservationStatus.UNAVAILABLE -> MatchResult(
                status = MatchStatus.INVALID_STATE,
                reason = "Active UI state is unavailable (${observationResult.errorMessage ?: "no active window"})."
            )
            UiObservationStatus.STALE -> MatchResult(
                status = MatchStatus.INVALID_STATE,
                reason = "Active UI state is stale; target cannot be resolved safely."
            )
            UiObservationStatus.UNSUPPORTED -> MatchResult(
                status = MatchStatus.UNSUPPORTED,
                reason = "Active UI context is unsupported (${observationResult.errorMessage ?: "unsupported window"})."
            )
            UiObservationStatus.SUCCESS -> {
                val state = observationResult.state
                    ?: return MatchResult(MatchStatus.INVALID_STATE, reason = "Observation status is SUCCESS but UiState is null.")
                val baseResult = match(selector, state, action)
                if (observationResult.isSensitive && baseResult.status == MatchStatus.MATCHED) {
                    baseResult.copy(
                        status = MatchStatus.SENSITIVE_TARGET,
                        isSensitive = true,
                        reason = "Active window contains sensitive fields; target action requires authorization."
                    )
                } else baseResult
            }
        }
    }

    /**
     * Phase 4.5 entry point: Resolves the semantic target from a SemanticSelector and live UiState.
     */
    fun match(selector: SemanticSelector, uiState: UiState?, action: RuntimeAction? = null): MatchResult {
        if (uiState == null) {
            return MatchResult(MatchStatus.INVALID_STATE, reason = "Current UI state is null.")
        }

        val visibleElements = uiState.allElements.filter { it.isVisible }
        if (visibleElements.isEmpty()) {
            return MatchResult(MatchStatus.NO_MATCH, reason = "No visible UI elements present in current UI state.")
        }

        val candidates = visibleElements.asSequence()
            .filter { compatible(it, action) }
            .mapNotNull { score(selector, it, uiState.appContext, uiState) }
            .sortedWith(
                compareByDescending<CandidateMatch> { it.confidence }
                    .thenBy { it.element.elementId }
            ).toList()

        val first = candidates.getOrNull(0)
            ?: return MatchResult(MatchStatus.NO_MATCH, reason = "No visible element matched the semantic selector.")
        val second = candidates.getOrNull(1)

        if (second != null && (first.confidence - second.confidence) < config.ambiguityMargin) {
            return MatchResult(
                status = MatchStatus.AMBIGUOUS,
                best = first,
                second = second,
                reason = "Two or more controls match the requested target within ambiguity margin (${config.ambiguityMargin}). User clarification is required.",
                candidates = candidates,
                stateId = uiState.stateId
            )
        }

        if (first.confidence >= config.executeThreshold) {
            val isSensitive = first.element.isSensitive || uiState.isSensitiveContext
            val status = if (isSensitive) MatchStatus.SENSITIVE_TARGET else MatchStatus.MATCHED
            val reason = if (isSensitive) {
                "Semantic target resolved to a security-sensitive element; action requires authorization."
            } else {
                "Unique target supported by semantic evidence."
            }
            return MatchResult(
                status = status,
                best = first,
                second = second,
                reason = reason,
                candidates = candidates,
                stateId = uiState.stateId
            )
        } else {
            return MatchResult(
                status = MatchStatus.WEAK,
                best = first,
                second = second,
                reason = "Target evidence is insufficient for safe execution (${first.confidence} < ${config.executeThreshold}).",
                candidates = candidates,
                stateId = uiState.stateId
            )
        }
    }

    /**
     * Backward-compatible legacy signature taking UiObservation snapshot.
     */
    fun match(selector: SemanticSelector, ui: UiObservation, action: RuntimeAction? = null): MatchResult {
        val candidates = ui.state.allElements.asSequence()
            .filter { action == null || compatible(it, ui, action) }
            .mapNotNull { score(selector, it, ui.state.appContext, ui.state) }
            .sortedByDescending { it.confidence }.toList()
        val first = candidates.getOrNull(0)
            ?: return MatchResult(MatchStatus.NONE, reason = "The expected semantic control is not present or cannot perform this action.")
        val second = candidates.getOrNull(1)
        if (second != null && first.confidence - second.confidence < config.ambiguityMargin) {
            return MatchResult(MatchStatus.AMBIGUOUS, first, second, "Two or more controls match the requested target. User clarification is required.", candidates = candidates, stateId = ui.state.stateId)
        }
        return if (first.confidence >= config.executeThreshold) {
            val isSensitive = first.element.isSensitive || ui.state.isSensitiveContext
            val status = if (isSensitive) MatchStatus.SENSITIVE_TARGET else MatchStatus.MATCHED
            MatchResult(status, first, second, "Unique target supported by semantic evidence.", candidates = candidates, stateId = ui.state.stateId)
        } else MatchResult(MatchStatus.WEAK, first, second, "Target evidence is insufficient for safe execution.", candidates = candidates, stateId = ui.state.stateId)
    }

    /** For presence checks, ambiguity still proves existence; weak matches do not. */
    fun present(selector: SemanticSelector, ui: UiObservation): Boolean =
        ui.state.allElements.any { (score(selector, it, ui.state.appContext, ui.state)?.confidence ?: 0.0) >= config.executeThreshold }

    private fun compatible(e: UiElement, ui: UiObservation, action: RuntimeAction): Boolean {
        if (!e.isVisible) return false
        if (!e.isEnabled) return false
        ui.capabilities[e.elementId]?.let { return action in it }
        return when (action) {
            RuntimeAction.CLICK -> e.isClickable
            RuntimeAction.INPUT_TEXT -> e.isEditable
            RuntimeAction.SCROLL -> e.isScrollable
            RuntimeAction.LONG_PRESS -> false // Requires live supported-action evidence.
        }
    }

    private fun compatible(e: UiElement, action: RuntimeAction?): Boolean {
        if (!e.isVisible) return false
        if (!e.isEnabled) return false
        if (action == null) return true
        return when (action) {
            RuntimeAction.CLICK -> e.isClickable
            RuntimeAction.INPUT_TEXT -> e.isEditable
            RuntimeAction.SCROLL -> e.isScrollable
            RuntimeAction.LONG_PRESS -> e.isClickable
        }
    }

    private fun areRolesContradictory(wanted: String, actual: String): Boolean {
        val w = normalize(wanted)
        val a = normalize(actual)
        if (w.isBlank() || a.isBlank()) return false
        val inputRoles = setOf("input", "edittext", "textinputedittext", "searchbox", "autocomplete")
        val clickRoles = setOf("button", "imagebutton")
        val isWInput = inputRoles.any { w.contains(it) }
        val isAInput = inputRoles.any { a.contains(it) }
        val isWClick = clickRoles.any { w.contains(it) }
        val isAClick = clickRoles.any { a.contains(it) }
        return (isWInput && isAClick) || (isWClick && isAInput)
    }

    private fun areRolesCompatible(wanted: String, actual: String): Boolean {
        val w = normalize(wanted)
        val a = normalize(actual)
        if (w == a) return true
        if (w.endsWith(a) || a.endsWith(w)) return true
        val inputRoles = setOf("input", "edittext", "textinputedittext", "searchbox", "autocomplete")
        val clickRoles = setOf("button", "imagebutton", "clickable", "switch", "checkbox", "radiobutton")
        if (inputRoles.any { w.contains(it) } && inputRoles.any { a.contains(it) }) return true
        if (clickRoles.any { w.contains(it) } && clickRoles.any { a.contains(it) }) return true
        return false
    }

    private fun findContextualText(e: UiElement, uiState: UiState?): String? {
        if (uiState == null) return null
        if (!e.nearbyText.isNullOrBlank()) return e.nearbyText
        val id = e.elementId
        if (id.contains("_")) {
            val parentPath = id.substringBeforeLast('_')
            val siblingWithText = uiState.allElements.firstOrNull {
                it.elementId != id && it.elementId.startsWith(parentPath) && !it.text.isNullOrBlank()
            }
            if (siblingWithText != null) return siblingWithText.text
        }
        return null
    }

    private fun score(
        s: SemanticSelector,
        e: UiElement,
        uiAppContext: String? = null,
        uiState: UiState? = null
    ): CandidateMatch? {
        if (s.textSlot != null) return null
        val evidence = mutableListOf<String>()
        var total = 0.0
        var earned = 0.0
        var anchor = false
        var contradiction = false

        val selectorPkg = s.resourceId?.takeIf { it.contains(":id/") }?.substringBefore(":id/")
        val elementPkg = e.resourceId?.takeIf { it.contains(":id/") }?.substringBefore(":id/")
            ?: e.packageName.takeIf { !it.isNullOrBlank() && it != "unknown" }
            ?: uiAppContext
        val isCrossApp = (selectorPkg != null && elementPkg != null && !selectorPkg.equals(elementPkg, ignoreCase = true)) ||
            (selectorPkg != null && elementPkg == null)

        fun hasSemanticOverlap(w: String, a: String): Boolean {
            val wTokens = w.split(Regex("[^a-zA-Z0-9]+")).filter { it.length >= 3 }.toSet()
            val aTokens = a.split(Regex("[^a-zA-Z0-9]+")).filter { it.length >= 3 }.toSet()
            if (wTokens.intersect(aTokens).isNotEmpty()) return true
            val synonymGroups = listOf(
                setOf("search", "find", "query", "lookup", "explore", "products", "dishes"),
                setOf("order", "buy", "cart", "purchase", "checkout"),
                setOf("food", "dish", "dishes", "restaurant", "restaurants", "meal"),
                setOf("product", "products", "item", "items", "goods", "shirt", "clothing", "headphones"),
                setOf("continue", "proceed", "next", "submit", "confirm", "done", "ok", "place order"),
                setOf("address", "deliver", "delivery", "location")
            )
            return synonymGroups.any { group ->
                wTokens.any { it in group } && aTokens.any { it in group }
            }
        }

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
                earned += weight * 0.65
                if (high) anchor = true
            } else if (hasSemanticOverlap(w, a)) {
                earned += weight * 0.90
                evidence.add("$name(semantic)")
                if (high) anchor = true
            } else if (strict) contradiction = true
        }

        // Role scoring with compatibility and contradiction handling
        if (!s.role.isNullOrBlank()) {
            val sRole = s.role
            val eRole = e.role
            if (areRolesContradictory(sRole, eRole)) {
                contradiction = true
            } else {
                total += 0.15
                if (normalize(sRole) == normalize(eRole)) {
                    earned += 0.15
                    evidence.add("role")
                    anchor = true
                } else if (areRolesCompatible(sRole, eRole)) {
                    earned += 0.12
                    evidence.add("role(compatible)")
                    anchor = true
                }
            }
        }

        // Text & ContentDescription cross-matching
        val wantedText = s.text
        val actualText = e.text
        val wantedDesc = s.contentDescription
        val actualDesc = e.contentDescription

        if (!wantedText.isNullOrBlank()) {
            total += 0.40
            val w = normalize(wantedText)
            val a = normalize(actualText ?: "")
            val aDesc = normalize(actualDesc ?: "")
            if (w == a) {
                earned += 0.40
                evidence.add("text")
                anchor = true
            } else if (a.isBlank() && w == aDesc) {
                earned += 0.40
                evidence.add("text(contentDesc)")
                anchor = true
            } else if (w.length >= 4 && a.length >= 4 && (a.contains(w) || w.contains(a))) {
                earned += 0.40 * 0.65
                anchor = true
            } else if (a.isBlank() && w.length >= 4 && aDesc.length >= 4 && (aDesc.contains(w) || w.contains(aDesc))) {
                earned += 0.40 * 0.65
                anchor = true
            } else if (hasSemanticOverlap(w, a)) {
                earned += 0.40 * 0.90
                evidence.add("text(semantic)")
                anchor = true
            } else if (a.isBlank() && hasSemanticOverlap(w, aDesc)) {
                earned += 0.40 * 0.90
                evidence.add("text(descSemantic)")
                anchor = true
            }
        }

        if (!wantedDesc.isNullOrBlank()) {
            total += 0.35
            val w = normalize(wantedDesc)
            val a = normalize(actualDesc ?: "")
            val aText = normalize(actualText ?: "")
            if (w == a) {
                earned += 0.35
                evidence.add("contentDescription")
                anchor = true
            } else if (a.isBlank() && w == aText) {
                earned += 0.35
                evidence.add("contentDesc(text)")
                anchor = true
            } else if (w.length >= 4 && a.length >= 4 && (a.contains(w) || w.contains(a))) {
                earned += 0.35 * 0.65
                anchor = true
            } else if (hasSemanticOverlap(w, a)) {
                earned += 0.35 * 0.90
                evidence.add("contentDesc(semantic)")
                anchor = true
            }
        }

        // Resource ID supporting evidence
        if (isCrossApp) {
            val sEntry = s.resourceId?.substringAfter(":id/")
            val eEntry = e.resourceId?.substringAfter(":id/")
            if (!sEntry.isNullOrBlank() && !eEntry.isNullOrBlank() && (sEntry == eEntry || hasSemanticOverlap(sEntry, eEntry))) {
                total += 0.20
                earned += 0.20
                evidence.add("resourceId(entry)")
                anchor = true
            }
        } else if (!s.resourceId.isNullOrBlank()) {
            field("resourceId", s.resourceId, e.resourceId, 0.25, high = true, strict = false)
        }

        // Hierarchy and contextual evidence
        field("parentRole", s.parentRole, e.parentRole, 0.05)
        field("ancestorRole", s.ancestorRole, e.ancestorRole, 0.03)

        // Contextual / nearbyText matching for disambiguating repeated items
        if (!s.nearbyText.isNullOrBlank()) {
            total += 0.20
            val effectiveNearby = findContextualText(e, uiState)
            val wNearby = normalize(s.nearbyText)
            val aNearby = normalize(effectiveNearby ?: "")
            if (wNearby == aNearby || (wNearby.length >= 4 && aNearby.length >= 4 && (aNearby.contains(wNearby) || wNearby.contains(aNearby)))) {
                earned += 0.20
                evidence.add("nearbyText")
                anchor = true
            } else if (hasSemanticOverlap(wNearby, aNearby)) {
                earned += 0.20 * 0.85
                evidence.add("nearbyText(semantic)")
                anchor = true
            }
        }

        field("relativePosition", s.relativePosition, e.relativePosition, 0.01)

        if (contradiction || total == 0.0 || earned == 0.0) return null
        val confidence = (earned / total).let { if (anchor) it else minOf(it, 0.6) }
        return CandidateMatch(e, confidence, evidence)
    }

    companion object {
        fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    }
}
