package com.chockXlate.teachablevoice.teach.bonus.irrelevant

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent

/**
 * Logical interaction group classified by sequence analysis.
 */
data class InteractionSequenceGroup(
    val groupId: String,
    val sequenceType: SequenceType,
    val events: List<TraceEvent>,
    val primaryActionEvent: ActionEvent?,
    val isCoherentTaskSequence: Boolean
)

enum class SequenceType {
    SEARCH_INPUT_SEQUENCE,
    SELECTION_SEQUENCE,
    ACCIDENTAL_REPETITION_SEQUENCE,
    TRANSIENT_FOCUS_SEQUENCE,
    NAVIGATION_TRANSITION_SEQUENCE,
    ISOLATED_ACTION_SEQUENCE
}

/**
 * Layer that groups raw/normalized trace events into coherent logical interaction sequences
 * before evaluating relevance metrics.
 */
object ActionSequenceGrouper {

    fun groupEvents(trace: DemonstrationTrace): List<InteractionSequenceGroup> {
        val events = trace.traceEvents.ifEmpty {
            val list = mutableListOf<TraceEvent>()
            trace.voiceEvents.forEach { list.add(TraceEvent.Voice(it.eventId, it.timestamp, it)) }
            trace.userActions.forEach { list.add(TraceEvent.Action(it.actionId, it.timestamp, it)) }
            trace.stateEvents.forEach { list.add(TraceEvent.State(it.stateEventId, it.timestamp, it)) }
            list.sortedBy { it.timestamp }
        }

        if (events.isEmpty()) return emptyList()

        val groups = mutableListOf<InteractionSequenceGroup>()
        var currentCluster = mutableListOf<TraceEvent>()
        var groupIndex = 1

        fun flushCluster(type: SequenceType, isCoherent: Boolean) {
            if (currentCluster.isNotEmpty()) {
                val action = currentCluster.filterIsInstance<TraceEvent.Action>().firstOrNull()?.actionEvent
                groups.add(
                    InteractionSequenceGroup(
                        groupId = "group_$groupIndex",
                        sequenceType = type,
                        events = currentCluster.toList(),
                        primaryActionEvent = action,
                        isCoherentTaskSequence = isCoherent
                    )
                )
                groupIndex++
                currentCluster = mutableListOf()
            }
        }

        var i = 0
        while (i < events.size) {
            val ev = events[i]
            if (ev is TraceEvent.Action) {
                val action = ev.actionEvent
                val nextEv = events.getOrNull(i + 1)

                // 1. Check for rapid accidental duplicate tap on identical target (<400ms without state change)
                if (nextEv is TraceEvent.Action) {
                    val nextAction = nextEv.actionEvent
                    val timeDiff = kotlin.math.abs(nextAction.timestamp - action.timestamp)
                    val sameTarget = isSameTarget(action, nextAction)
                    val stateEv = trace.stateEvents.find { it.causeActionId == action.actionId }
                    val producedStateChange = stateEv != null && stateEv.beforeState.stateId != stateEv.afterState.stateId

                    if (sameTarget && timeDiff <= 400L && !producedStateChange) {
                        currentCluster.add(ev)
                        currentCluster.add(nextEv)
                        flushCluster(SequenceType.ACCIDENTAL_REPETITION_SEQUENCE, isCoherent = false)
                        i += 2
                        continue
                    }
                }

                // 2. Check for Search/Input sequence: FOCUS or CLICK on editable followed by INPUT_TEXT
                if (action.actionType == "CLICK" || action.actionType == "FOCUS") {
                    val isEditable = action.semanticSelector.role?.lowercase()?.contains("edit") == true ||
                        !action.semanticSelector.textSlot.isNullOrBlank()
                    if (isEditable && nextEv is TraceEvent.Action && nextEv.actionEvent.actionType == "INPUT_TEXT") {
                        currentCluster.add(ev)
                        currentCluster.add(nextEv)
                        flushCluster(SequenceType.SEARCH_INPUT_SEQUENCE, isCoherent = true)
                        i += 2
                        continue
                    }
                }

                // 3. Isolated action
                currentCluster.add(ev)
                val stateEv = trace.stateEvents.find { it.causeActionId == action.actionId }
                val isSelection = stateEv != null && stateEv.beforeState.stateId != stateEv.afterState.stateId
                flushCluster(if (isSelection) SequenceType.SELECTION_SEQUENCE else SequenceType.ISOLATED_ACTION_SEQUENCE, isCoherent = isSelection)
                i++
            } else {
                currentCluster.add(ev)
                flushCluster(SequenceType.ISOLATED_ACTION_SEQUENCE, isCoherent = true)
                i++
            }
        }

        return groups
    }

    private fun isSameTarget(a1: ActionEvent, a2: ActionEvent): Boolean {
        val s1 = a1.semanticSelector
        val s2 = a2.semanticSelector
        if (!s1.resourceId.isNullOrBlank() && s1.resourceId == s2.resourceId) return true
        if (!s1.text.isNullOrBlank() && s1.text == s2.text) return true
        if (!s1.contentDescription.isNullOrBlank() && s1.contentDescription == s2.contentDescription) return true
        return false
    }
}
