package com.chockXlate.teachablevoice.teach.bonus.irrelevant

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.teach.filter.DemonstrationFilter

/**
 * Deterministic multi-factor relevance classifier.
 * Evaluates semantic target attributes, state changes, task progress, repetition, and navigation noise.
 */
class IrrelevantActionClassifier(
    private val config: BonusFilteringConfig = BonusFilteringConfig()
) {

    fun classify(
        trace: DemonstrationTrace,
        sequenceGroups: List<InteractionSequenceGroup>
    ): List<RelevanceEvidence> {
        val events = trace.traceEvents.ifEmpty {
            val list = mutableListOf<TraceEvent>()
            trace.voiceEvents.forEach { list.add(TraceEvent.Voice(it.eventId, it.timestamp, it)) }
            trace.userActions.forEach { list.add(TraceEvent.Action(it.actionId, it.timestamp, it)) }
            trace.stateEvents.forEach { list.add(TraceEvent.State(it.stateEventId, it.timestamp, it)) }
            list.sortedBy { it.timestamp }
        }

        val results = mutableListOf<RelevanceEvidence>()
        val actions = events.filterIsInstance<TraceEvent.Action>()

        for (event in events) {
            val evidence = when (event) {
                is TraceEvent.Voice -> {
                    RelevanceEvidence(
                        eventId = event.eventId,
                        decision = RelevanceDecision.RELEVANT,
                        confidence = 1.0,
                        reason = "Voice guidance instruction providing task intent.",
                        semanticTargetScore = 1.0,
                        stateChangeScore = 1.0,
                        taskProgressScore = 1.0,
                        repetitionPenalty = 0.0,
                        navigationNoisePenalty = 0.0,
                        isRetained = true
                    )
                }
                is TraceEvent.State -> {
                    RelevanceEvidence(
                        eventId = event.eventId,
                        decision = RelevanceDecision.RELEVANT,
                        confidence = 1.0,
                        reason = "Observed UI screen state transition.",
                        semanticTargetScore = 1.0,
                        stateChangeScore = 1.0,
                        taskProgressScore = 1.0,
                        repetitionPenalty = 0.0,
                        navigationNoisePenalty = 0.0,
                        isRetained = true
                    )
                }
                is TraceEvent.Ui -> {
                    RelevanceEvidence(
                        eventId = event.eventId,
                        decision = RelevanceDecision.RELEVANT,
                        confidence = 0.9,
                        reason = "Observed UI window hierarchy event.",
                        semanticTargetScore = 0.9,
                        stateChangeScore = 0.9,
                        taskProgressScore = 0.9,
                        repetitionPenalty = 0.0,
                        navigationNoisePenalty = 0.0,
                        isRetained = true
                    )
                }
                is TraceEvent.Action -> {
                    evaluateActionRelevance(event.actionEvent, trace, actions, sequenceGroups)
                }
            }
            results.add(evidence)
        }

        return results
    }

    private fun evaluateActionRelevance(
        action: ActionEvent,
        trace: DemonstrationTrace,
        allActions: List<TraceEvent.Action>,
        sequenceGroups: List<InteractionSequenceGroup>
    ): RelevanceEvidence {
        val selector = action.semanticSelector
        val actionType = action.actionType ?: "UNKNOWN"

        // 1. Semantic Target Score
        val hasText = !selector.text.isNullOrBlank()
        val hasContentDesc = !selector.contentDescription.isNullOrBlank()
        val hasResId = !selector.resourceId.isNullOrBlank()
        val hasSlot = !selector.textSlot.isNullOrBlank()
        val isTextInput = actionType == "INPUT_TEXT" || !action.inputData.isNullOrBlank()
        val isAnonymous = DemonstrationFilter.isAnonymousContainer(selector)

        val semanticScore = when {
            isTextInput || hasSlot -> 1.0
            hasText || hasContentDesc || hasResId -> 0.9
            !selector.role.isNullOrBlank() && !isAnonymous -> 0.7
            isAnonymous -> 0.1
            else -> 0.3
        }

        // 2. State Change Score
        val stateEvent = trace.stateEvents.find { it.causeActionId == action.actionId }
        val producedStateChange = stateEvent != null &&
            stateEvent.beforeState.stateId != stateEvent.afterState.stateId &&
            stateEvent.beforeState.stateId.isNotBlank()

        val stateChangeScore = if (producedStateChange) 1.0 else 0.3

        // 3. Task Progress Score
        val actionIndex = allActions.indexOfFirst { it.eventId == action.actionId }
        val nextAction = if (actionIndex >= 0 && actionIndex < allActions.size - 1) allActions[actionIndex + 1].actionEvent else null
        val isFollowedByInputOrTransition = nextAction != null && (
            nextAction.actionType == "INPUT_TEXT" ||
                !nextAction.inputData.isNullOrBlank() ||
                trace.stateEvents.any { it.causeActionId == nextAction.actionId && it.beforeState.stateId != it.afterState.stateId }
            )

        val taskProgressScore = when {
            isTextInput -> 1.0
            producedStateChange -> 1.0
            isFollowedByInputOrTransition -> 0.85
            else -> 0.4
        }

        // 4. Repetition & Navigation Noise Penalties
        var repetitionPenalty = 0.0
        var navigationNoisePenalty = 0.0

        val group = sequenceGroups.find { g -> g.events.any { it is TraceEvent.Action && it.eventId == action.actionId } }
        if (group?.sequenceType == SequenceType.ACCIDENTAL_REPETITION_SEQUENCE) {
            // Check if this action is the duplicate (second) action in the accidental pair
            val firstActionInPair = group.events.filterIsInstance<TraceEvent.Action>().firstOrNull()?.actionEvent
            if (firstActionInPair != null && firstActionInPair.actionId != action.actionId) {
                repetitionPenalty = 0.9 // High penalty for redundant rapid second tap
            }
        }

        if (actionType == "FOCUS" && !isTextInput && !producedStateChange && !isFollowedByInputOrTransition) {
            navigationNoisePenalty = 0.8 // High penalty for transient focus change that leads nowhere
        }

        // 5. Decision & Confidence Calculation
        val totalEvidenceScore = (semanticScore * 0.35) + (stateChangeScore * 0.35) + (taskProgressScore * 0.30) - (repetitionPenalty * 0.40) - (navigationNoisePenalty * 0.35)

        val decision: RelevanceDecision
        val confidence: Double
        val reason: String

        when {
            repetitionPenalty >= 0.8 -> {
                decision = RelevanceDecision.IRRELEVANT
                confidence = 0.92
                reason = "Filtered: Accidental duplicate tap on identical target within ${config.accidentalTapThresholdMs}ms without state change."
            }
            navigationNoisePenalty >= 0.75 -> {
                decision = RelevanceDecision.IRRELEVANT
                confidence = 0.88
                reason = "Filtered: Transient focus interaction without subsequent input or screen state transition."
            }
            isAnonymous && !producedStateChange && !isFollowedByInputOrTransition -> {
                decision = RelevanceDecision.IRRELEVANT
                confidence = 0.86
                reason = "Filtered: Tap on anonymous container '${selector.role ?: "View"}' lacking text/ID evidence and state change."
            }
            totalEvidenceScore >= 0.70 -> {
                decision = RelevanceDecision.RELEVANT
                confidence = totalEvidenceScore.coerceIn(0.70, 1.0)
                reason = "Retained: Task-relevant interaction with strong semantic target and state change evidence."
            }
            totalEvidenceScore <= 0.25 -> {
                decision = RelevanceDecision.IRRELEVANT
                confidence = (1.0 - totalEvidenceScore).coerceIn(0.80, 0.95)
                reason = "Filtered: Low overall evidence score ($totalEvidenceScore) with no task contribution."
            }
            else -> {
                decision = RelevanceDecision.UNCERTAIN
                confidence = 0.60
                reason = "Retained (Conservative Policy): Ambiguous or weak evidence score ($totalEvidenceScore); preserved to prevent workflow step corruption."
            }
        }

        // CONSERVATIVE SAFETY POLICY ENFORCEMENT:
        // Only filter if decision == IRRELEVANT AND confidence >= config.highConfidenceThreshold (0.85).
        val isRetained = when {
            decision == RelevanceDecision.IRRELEVANT && confidence >= config.highConfidenceThreshold -> false
            else -> true // Retains RELEVANT, UNCERTAIN, and low/medium confidence IRRELEVANT
        }

        return RelevanceEvidence(
            eventId = action.actionId,
            decision = decision,
            confidence = confidence,
            reason = reason,
            semanticTargetScore = semanticScore,
            stateChangeScore = stateChangeScore,
            taskProgressScore = taskProgressScore,
            repetitionPenalty = repetitionPenalty,
            navigationNoisePenalty = navigationNoisePenalty,
            isRetained = isRetained
        )
    }
}
