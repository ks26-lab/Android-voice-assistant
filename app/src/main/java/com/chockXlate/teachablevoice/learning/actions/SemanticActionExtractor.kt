package com.chockXlate.teachablevoice.learning.actions

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.filter.FilteredDemonstrationResult
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.learning.targets.SemanticTargetNormalizer
import com.chockXlate.teachablevoice.teach.filter.DemonstrationFilter

/**
 * Extracts deterministic SemanticActions and non-coordinate SemanticTargets
 * from raw DemonstrationTrace evidence.
 */
object SemanticActionExtractor {

    /**
     * Extracts a list of SemanticActions from a DemonstrationTrace.
     */
    fun extract(
        trace: DemonstrationTrace,
        filterResult: FilteredDemonstrationResult? = null
    ): List<SemanticAction> {
        val semanticActions = mutableListOf<SemanticAction>()

        // Process Action events from the trace stream while preserving chronological order
        val actionEvents = trace.traceEvents
            .filterIsInstance<TraceEvent.Action>()
            .map { it.actionEvent }
            .ifEmpty { trace.userActions }

        // Accessibility emits one text-change action per keystroke. A contiguous edit of the
        // same identifiable field teaches its final value, not replay of intermediate prefixes.
        val sortedActions = mutableListOf<ActionEvent>()
        for (action in actionEvents.sortedBy { it.timestamp }) {
            val previous = sortedActions.lastOrNull()
            val selector = action.semanticSelector
            val previousSelector = previous?.semanticSelector
            val sameRole = (selector.role ?: "EditText").equals(previousSelector?.role ?: "EditText", ignoreCase = true)
            val sameField = previousSelector != null && sameRole &&
                ((selector.resourceId != null && selector.resourceId == previousSelector.resourceId) ||
                    (selector.contentDescription != null && selector.contentDescription == previousSelector.contentDescription) ||
                    (selector.resourceId == null && previousSelector.resourceId == null &&
                        selector.contentDescription == null && previousSelector.contentDescription == null))
            if (action.actionType == "INPUT_TEXT" && previous?.actionType == "INPUT_TEXT" && sameField) {
                sortedActions[sortedActions.lastIndex] = action
            } else sortedActions.add(action)
        }

        for (rawAction in sortedActions) {
            val actionPackage = resolveActionPackage(rawAction, trace, filterResult)
            // Own-package interactions must never become learned semantic workflow actions
            if (DemonstrationFilter.isOwnApp(actionPackage)) {
                continue
            }
            if (filterResult != null && !filterResult.isTaskRelevant(rawAction.actionId)) {
                continue
            }
            val semanticAction = classifyAndExtract(rawAction, actionPackage)
            semanticActions.add(semanticAction)
        }

        return semanticActions
    }

    private fun resolveActionPackage(
        action: ActionEvent,
        trace: DemonstrationTrace,
        filterResult: FilteredDemonstrationResult?
    ): String {
        val isTextInput = action.actionType == "INPUT_TEXT" || !action.inputData.isNullOrBlank()

        // 1. Direct packageName on action event (if not a keyboard surface for text input)
        val actPkg = action.packageName
        if (!actPkg.isNullOrBlank() && actPkg != "unknown") {
            if (!isTextInput || !DemonstrationFilter.isKeyboardSurface(actPkg)) {
                return actPkg
            }
        }
        // 2. From filterResult if available
        if (filterResult != null) {
            val filteredEv = filterResult.allEvents.find { it.eventId == action.actionId }
            val filteredPkg = filteredEv?.packageName
            if (!filteredPkg.isNullOrBlank() && filteredPkg != "unknown" && !DemonstrationFilter.isKeyboardSurface(filteredPkg)) {
                return filteredPkg
            }
        }
        // 3. From matching UI event in trace
        val matchingUi = trace.traceEvents.asSequence()
            .filterIsInstance<TraceEvent.Ui>()
            .map { it.uiEvent }
            .find { it.eventId == action.actionId || (kotlin.math.abs(it.timestamp - action.timestamp) <= 300L && it.packageName.isNotBlank()) }
        val uiPkg = matchingUi?.packageName?.takeIf { it.isNotBlank() && it != "unknown" }
        if (uiPkg != null && !DemonstrationFilter.isSystemSurface(uiPkg) && !DemonstrationFilter.isOwnApp(uiPkg)) {
            return uiPkg
        }
        // 4. Resource ID package if qualified (e.g. com.android.settings:id/title)
        val resId = action.semanticSelector.resourceId
        if (resId != null && resId.contains(":id/")) {
            val resPkg = resId.substringBefore(":id/")
            if (resPkg.isNotBlank() && resPkg != "android") {
                return resPkg
            }
        }
        // 5. From matching state event
        val stateEv = trace.stateEvents.find { it.causeActionId == action.actionId }
        val statePkg = stateEv?.beforeState?.appContext?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: stateEv?.afterState?.appContext?.takeIf { it.isNotBlank() && it != "unknown" }
        if (statePkg != null && !DemonstrationFilter.isSystemSurface(statePkg) && !DemonstrationFilter.isOwnApp(statePkg)) {
            return statePkg
        }
        // 6. Trace appContext if external
        if (trace.appContext.isNotBlank() && trace.appContext != "unknown" &&
            !DemonstrationFilter.isSystemSurface(trace.appContext) &&
            !DemonstrationFilter.isOwnApp(trace.appContext)
        ) {
            return trace.appContext
        }
        // 7. Any external package in the trace's user actions
        val externalActionPkg = trace.userActions.mapNotNull { it.packageName }
            .firstOrNull { it.isNotBlank() && it != "unknown" && !DemonstrationFilter.isSystemSurface(it) && !DemonstrationFilter.isOwnApp(it) }
        if (externalActionPkg != null) {
            return externalActionPkg
        }
        return trace.appContext.ifBlank { "unknown" }
    }

    private fun classifyAndExtract(rawAction: ActionEvent, appContext: String): SemanticAction {
        val semanticType = when (rawAction.actionType.uppercase()) {
            "CLICK", "TAP", "TOUCH_TAP" -> SemanticActionType.TAP
            "LONG_PRESS", "LONG_CLICK" -> SemanticActionType.LONG_PRESS
            "INPUT_TEXT", "TYPE", "SET_TEXT" -> SemanticActionType.INPUT_TEXT
            "SELECT" -> SemanticActionType.SELECT
            "FOCUS" -> SemanticActionType.FOCUS
            "TOGGLE", "CHECK" -> SemanticActionType.TOGGLE
            "SUBMIT" -> SemanticActionType.SUBMIT
            "SCROLL" -> SemanticActionType.SCROLL
            else -> SemanticActionType.UNKNOWN
        }

        // For INPUT_TEXT, ensure role defaults to EditText if missing
        val selector = if (semanticType == SemanticActionType.INPUT_TEXT && rawAction.semanticSelector.role.isNullOrBlank()) {
            rawAction.semanticSelector.copy(role = "EditText")
        } else {
            rawAction.semanticSelector
        }

        val target = SemanticTargetNormalizer.fromSelector(
            selector = selector,
            packageName = appContext
        )

        val (confidence, level) = when {
            target != null && target.targetConfidence >= 0.85 -> Pair(1.0, "HIGH")
            target != null -> Pair(0.75, "MEDIUM")
            else -> Pair(0.50, "LOW")
        }

        return SemanticAction(
            schemaVersion = "1.0",
            actionId = rawAction.actionId,
            timestamp = rawAction.timestamp,
            actionType = semanticType,
            target = target,
            inputValue = rawAction.inputData,
            confidence = confidence,
            confidenceLevel = level,
            rawEventId = rawAction.actionId,
            rawActionType = rawAction.actionType
        )
    }
}
