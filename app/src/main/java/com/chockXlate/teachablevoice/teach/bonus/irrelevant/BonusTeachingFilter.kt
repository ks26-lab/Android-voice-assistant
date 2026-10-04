package com.chockXlate.teachablevoice.teach.bonus.irrelevant

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent

/**
 * Isolated public API for Samsung Bonus 1: Irrelevant-Action Filtering (+3 Points).
 *
 * Evaluates raw or normalized DemonstrationTrace objects, filters out high-confidence
 * irrelevant events (accidental duplicate taps, transient focus shifts, non-state-changing noise),
 * and produces a BonusFilteredDemonstration contract while leaving existing core architecture 100% untouched.
 */
object BonusTeachingFilter {

    fun filter(
        trace: DemonstrationTrace,
        config: BonusFilteringConfig = BonusFilteringConfig()
    ): BonusFilteredDemonstration {
        val groups = ActionSequenceGrouper.groupEvents(trace)
        val classifier = IrrelevantActionClassifier(config)
        val decisions = classifier.classify(trace, groups)

        val decisionMap = decisions.associateBy { it.eventId }

        // Filter events preserving strict timestamp ordering
        val retainedEvents = mutableListOf<TraceEvent>()
        val removedEvents = mutableListOf<TraceEvent>()

        val eventsToProcess = trace.traceEvents.ifEmpty {
            val list = mutableListOf<TraceEvent>()
            trace.voiceEvents.forEach { list.add(TraceEvent.Voice(it.eventId, it.timestamp, it)) }
            trace.userActions.forEach { list.add(TraceEvent.Action(it.actionId, it.timestamp, it)) }
            trace.stateEvents.forEach { list.add(TraceEvent.State(it.stateEventId, it.timestamp, it)) }
            list.sortedBy { it.timestamp }
        }

        for (event in eventsToProcess) {
            val evidence = decisionMap[event.eventId]
            val isRetained = evidence?.isRetained ?: true

            if (isRetained) {
                retainedEvents.add(event)
            } else {
                removedEvents.add(event)
            }
        }

        // Retain corresponding userActions and stateEvents
        val retainedActionIds = retainedEvents.filterIsInstance<TraceEvent.Action>().map { it.eventId }.toSet()
        val retainedUserActions = trace.userActions.filter { it.actionId in retainedActionIds || decisionMap[it.actionId]?.isRetained == true }

        val retainedStateIds = retainedEvents.filterIsInstance<TraceEvent.State>().map { it.eventId }.toSet()
        val retainedStateEvents = trace.stateEvents.filter { it.stateEventId in retainedStateIds || decisionMap[it.stateEventId]?.isRetained == true }

        val newTrace = trace.copy(
            traceEvents = retainedEvents,
            userActions = retainedUserActions,
            stateEvents = retainedStateEvents
        )

        val originalCount = eventsToProcess.size
        val retainedCount = retainedEvents.size
        val removedCount = removedEvents.size
        val uncertainCount = decisions.count { it.decision == RelevanceDecision.UNCERTAIN }

        val summary = buildString {
            append("=== SAMSUNG BONUS 1: IRRELEVANT-ACTION FILTERING REPORT ===\n")
            append("Trace ID          : ${trace.traceId}\n")
            append("Session ID        : ${trace.traceId}\n")
            append("Original Events   : $originalCount\n")
            append("Retained Events   : $retainedCount\n")
            append("Removed Events    : $removedCount\n")
            append("Uncertain Events  : $uncertainCount (Preserved via Conservative Safety Policy)\n")
            append("--------------------------------------------------\n")
            if (removedEvents.isNotEmpty()) {
                append("REMOVED IRRELEVANT EVENTS:\n")
                removedEvents.forEach { ev ->
                    val evId = ev.eventId
                    val info = decisionMap[evId]
                    append("  - [ID: $evId] Reason: ${info?.reason ?: "Filtered"}\n")
                }
            } else {
                append("REMOVED IRRELEVANT EVENTS: None (All events retained as task-relevant or uncertain)\n")
            }
            append("==================================================")
        }

        return BonusFilteredDemonstration(
            schemaVersion = "1.0",
            traceId = trace.traceId,
            sessionId = trace.traceId,
            originalEventCount = originalCount,
            retainedEventCount = retainedCount,
            removedEventCount = removedCount,
            uncertainEventCount = uncertainCount,
            filteredTrace = newTrace,
            removedEvents = removedEvents,
            relevanceDecisions = decisions,
            filterSummary = summary
        )
    }
}
