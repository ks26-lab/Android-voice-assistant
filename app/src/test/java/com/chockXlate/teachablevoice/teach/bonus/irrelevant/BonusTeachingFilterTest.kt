package com.chockXlate.teachablevoice.teach.bonus.irrelevant

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BonusTeachingFilterTest {

    private fun createDummyState(id: String, appPkg: String = "com.example.targetapp"): UiState {
        return UiState(
            schemaVersion = "1.0",
            stateId = id,
            timestamp = System.currentTimeMillis(),
            appContext = appPkg,
            windowId = 1,
            rootElement = com.chockXlate.teachablevoice.contract.ui.UiElement(
                elementId = "root",
                role = "FrameLayout"
            ),
            allElements = emptyList()
        )
    }

    private fun createActionEvent(
        actionId: String,
        timestamp: Long,
        actionType: String,
        role: String?,
        text: String?,
        resourceId: String?,
        inputData: String? = null,
        pkg: String = "com.example.targetapp"
    ): ActionEvent {
        return ActionEvent(
            schemaVersion = "1.0",
            actionId = actionId,
            timestamp = timestamp,
            actionType = actionType,
            semanticSelector = SemanticSelector(
                schemaVersion = "1.0",
                role = role,
                text = text,
                resourceId = resourceId
            ),
            inputData = inputData,
            packageName = pkg
        )
    }

    @Test
    fun test1_requiredTapFollowedByMeaningfulStateChange_retained() {
        val t0 = 1000L
        val action = createActionEvent("a1", t0, "CLICK", "Button", "Search", "com.example.targetapp:id/search_btn")
        val stateEv = StateEvent("s1", t0 + 10L, createDummyState("state_home"), createDummyState("state_results"), "a1")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_1",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 100L,
            traceEvents = listOf(TraceEvent.Action("a1", t0, action), TraceEvent.State("s1", t0 + 10L, stateEv)),
            userActions = listOf(action),
            stateEvents = listOf(stateEv)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(2, result.retainedEventCount)
        assertEquals(0, result.removedEventCount)
        assertTrue(result.filteredTrace.userActions.any { it.actionId == "a1" })
    }

    @Test
    fun test2_typingRequiredWorkflowValue_retained() {
        val t0 = 1000L
        val action = createActionEvent("a1", t0, "INPUT_TEXT", "EditText", "Pizza", "com.example.targetapp:id/input", inputData = "Pizza")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_2",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 100L,
            traceEvents = listOf(TraceEvent.Action("a1", t0, action)),
            userActions = listOf(action)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(1, result.retainedEventCount)
        assertEquals(0, result.removedEventCount)
    }

    @Test
    fun test3_requiredSearchInteraction_retained() {
        val t0 = 1000L
        val voice = VoiceEvent("v1", t0, "search for pizza")
        val action1 = createActionEvent("a1", t0 + 100L, "INPUT_TEXT", "EditText", "Pizza", "com.example.targetapp:id/search_box", inputData = "Pizza")
        val action2 = createActionEvent("a2", t0 + 200L, "CLICK", "Button", "Submit", "com.example.targetapp:id/btn_submit")
        val stateEv = StateEvent("s1", t0 + 210L, createDummyState("state_input"), createDummyState("state_results"), "a2")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_3",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 300L,
            traceEvents = listOf(
                TraceEvent.Voice("v1", t0, voice),
                TraceEvent.Action("a1", t0 + 100L, action1),
                TraceEvent.Action("a2", t0 + 200L, action2),
                TraceEvent.State("s1", t0 + 210L, stateEv)
            ),
            userActions = listOf(action1, action2),
            voiceEvents = listOf(voice),
            stateEvents = listOf(stateEv)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(4, result.retainedEventCount)
        assertEquals(0, result.removedEventCount)
    }

    @Test
    fun test4_repeatedAccidentalTapNoStateChange_filtered() {
        val t0 = 1000L
        val action1 = createActionEvent("a1", t0, "CLICK", "Button", "Item", "com.example.targetapp:id/item")
        val stateEv1 = StateEvent("s1", t0 + 10L, createDummyState("state_list"), createDummyState("state_detail"), "a1")
        // Rapid duplicate tap 100ms later on same target without state change
        val action2 = createActionEvent("a2", t0 + 100L, "CLICK", "Button", "Item", "com.example.targetapp:id/item")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_4",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 200L,
            traceEvents = listOf(
                TraceEvent.Action("a1", t0, action1),
                TraceEvent.State("s1", t0 + 10L, stateEv1),
                TraceEvent.Action("a2", t0 + 100L, action2)
            ),
            userActions = listOf(action1, action2),
            stateEvents = listOf(stateEv1)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(2, result.retainedEventCount) // a1 and s1 retained
        assertEquals(1, result.removedEventCount)  // a2 filtered
        assertTrue(result.removedEvents.any { it.eventId == "a2" })
    }

    @Test
    fun test5_transientFocusChange_filteredIfHighConfidence() {
        val t0 = 1000L
        // Focus event on generic view without state change or input
        val action = createActionEvent("a1", t0, "FOCUS", "View", null, null)

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_5",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 100L,
            traceEvents = listOf(TraceEvent.Action("a1", t0, action)),
            userActions = listOf(action)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(1, result.removedEventCount)
        assertEquals("a1", result.removedEvents.first().eventId)
    }

    @Test
    fun test6_unrelatedNavigationAction_filteredWhenSemanticEvidenceClear() {
        val t0 = 1000L
        // Tap on anonymous view container without text/ID evidence and no state change
        val action = createActionEvent("a1", t0, "CLICK", "View", null, null)

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_6",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 100L,
            traceEvents = listOf(TraceEvent.Action("a1", t0, action)),
            userActions = listOf(action)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(1, result.removedEventCount)
    }

    @Test
    fun test7_ambiguousAction_retained() {
        val t0 = 1000L
        // Action with partial evidence (role present, but no text or state change)
        val action = createActionEvent("a1", t0, "CLICK", "ImageButton", null, "com.example.targetapp:id/icon_btn")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_7",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 100L,
            traceEvents = listOf(TraceEvent.Action("a1", t0, action)),
            userActions = listOf(action)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(1, result.retainedEventCount)
        assertEquals(0, result.removedEventCount)
    }

    @Test
    fun test8_weakEvidenceAction_retained() {
        val t0 = 1000L
        // Action with role View and ID but no text
        val action = createActionEvent("a1", t0, "CLICK", "View", null, "com.example.targetapp:id/card")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_8",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 100L,
            traceEvents = listOf(TraceEvent.Action("a1", t0, action)),
            userActions = listOf(action)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(1, result.retainedEventCount)
        assertEquals(0, result.removedEventCount)
    }

    @Test
    fun test9_interleavedIrrelevantActions_meaningfulActionsRemainIntact() {
        val t0 = 1000L
        val action1 = createActionEvent("a1", t0, "CLICK", "Button", "Open Search", "com.example.targetapp:id/open_search")
        val stateEv1 = StateEvent("s1", t0 + 10L, createDummyState("s0"), createDummyState("s1"), "a1")
        // Irrelevant noise 1: transient focus
        val noise1 = createActionEvent("n1", t0 + 50L, "FOCUS", "View", null, null)
        // Irrelevant noise 2: rapid duplicate click
        val noise2 = createActionEvent("n2", t0 + 100L, "CLICK", "View", null, null)
        // Meaningful action 2: type query
        val action2 = createActionEvent("a2", t0 + 200L, "INPUT_TEXT", "EditText", "Pizza", "com.example.targetapp:id/search_box", inputData = "Pizza")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_9",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 300L,
            traceEvents = listOf(
                TraceEvent.Action("a1", t0, action1),
                TraceEvent.State("s1", t0 + 10L, stateEv1),
                TraceEvent.Action("n1", t0 + 50L, noise1),
                TraceEvent.Action("n2", t0 + 100L, noise2),
                TraceEvent.Action("a2", t0 + 200L, action2)
            ),
            userActions = listOf(action1, noise1, noise2, action2),
            stateEvents = listOf(stateEv1)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(3, result.retainedEventCount) // a1, s1, a2 retained
        assertEquals(2, result.removedEventCount)  // n1, n2 removed
        val retainedIds = result.filteredTrace.traceEvents.map { it.eventId }
        assertEquals(listOf("a1", "s1", "a2"), retainedIds)
    }

    @Test
    fun test10_filteringNeverReordersRetainedEvents() {
        val t0 = 1000L
        val a1 = createActionEvent("a1", t0, "CLICK", "Button", "Step 1", "com.example.targetapp:id/s1")
        val n1 = createActionEvent("n1", t0 + 10L, "FOCUS", "View", null, null)
        val a2 = createActionEvent("a2", t0 + 20L, "CLICK", "Button", "Step 2", "com.example.targetapp:id/s2")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_10",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 50L,
            traceEvents = listOf(
                TraceEvent.Action("a1", t0, a1),
                TraceEvent.Action("n1", t0 + 10L, n1),
                TraceEvent.Action("a2", t0 + 20L, a2)
            ),
            userActions = listOf(a1, n1, a2)
        )

        val result = BonusTeachingFilter.filter(trace)
        val retainedIds = result.filteredTrace.traceEvents.map { it.eventId }
        assertEquals(listOf("a1", "a2"), retainedIds)
    }

    @Test
    fun test11_filteringPreservesTimestampsAndOrderRelationships() {
        val t0 = 1000L
        val a1 = createActionEvent("a1", t0, "CLICK", "Button", "Step 1", "com.example.targetapp:id/s1")
        val a2 = createActionEvent("a2", t0 + 500L, "CLICK", "Button", "Step 2", "com.example.targetapp:id/s2")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_11",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 600L,
            traceEvents = listOf(TraceEvent.Action("a1", t0, a1), TraceEvent.Action("a2", t0 + 500L, a2)),
            userActions = listOf(a1, a2)
        )

        val result = BonusTeachingFilter.filter(trace)
        val timestamps = result.filteredTrace.traceEvents.map { it.timestamp }
        assertEquals(listOf(t0, t0 + 500L), timestamps)
        assertTrue(timestamps[0] < timestamps[1])
    }

    @Test
    fun test12_deterministicFilteringOutput() {
        val t0 = 1000L
        val a1 = createActionEvent("a1", t0, "CLICK", "Button", "Search", "com.example.targetapp:id/btn")
        val n1 = createActionEvent("n1", t0 + 10L, "FOCUS", "View", null, null)

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_12",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 100L,
            traceEvents = listOf(TraceEvent.Action("a1", t0, a1), TraceEvent.Action("n1", t0 + 10L, n1)),
            userActions = listOf(a1, n1)
        )

        val run1 = BonusTeachingFilter.filter(trace)
        val run2 = BonusTeachingFilter.filter(trace)

        assertEquals(run1.retainedEventCount, run2.retainedEventCount)
        assertEquals(run1.removedEventCount, run2.removedEventCount)
        assertEquals(run1.filteredTrace.traceEvents.map { it.eventId }, run2.filteredTrace.traceEvents.map { it.eventId })
    }
}
