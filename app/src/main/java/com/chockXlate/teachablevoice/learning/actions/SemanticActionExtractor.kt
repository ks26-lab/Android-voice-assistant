package com.chockXlate.teachablevoice.learning.actions

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.learning.targets.SemanticTargetNormalizer

/**
 * Extracts deterministic SemanticActions and non-coordinate SemanticTargets
 * from raw DemonstrationTrace evidence.
 */
object SemanticActionExtractor {

    /**
     * Extracts a list of SemanticActions from a DemonstrationTrace.
     */
    fun extract(trace: DemonstrationTrace): List<SemanticAction> {
        val semanticActions = mutableListOf<SemanticAction>()

        // Process Action events from the trace stream while preserving chronological order
        val actionEvents = trace.traceEvents
            .filterIsInstance<TraceEvent.Action>()
            .map { it.actionEvent }
            .ifEmpty { trace.userActions }

        val sortedActions = actionEvents.sortedBy { it.timestamp }

        for (rawAction in sortedActions) {
            val semanticAction = classifyAndExtract(rawAction, trace.appContext)
            semanticActions.add(semanticAction)
        }

        return semanticActions
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

        val target = SemanticTargetNormalizer.fromSelector(
            selector = rawAction.semanticSelector,
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
