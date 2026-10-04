package com.chockXlate.teachablevoice.teach.bonus.irrelevant

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import org.junit.Assert.*
import org.junit.Test

class AdversarialIrrelevantActionTest {

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
        inputData: String? = null
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
            packageName = "com.example.targetapp"
        )
    }

    @Test
    fun testAdversarial_accidentalScroll_evaluatedConservatively() {
        val t0 = 1000L
        val scrollAction = createActionEvent("a1", t0, "SCROLL", "ScrollView", null, "com.example.targetapp:id/scroll_container")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "adv_1",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 100L,
            traceEvents = listOf(TraceEvent.Action("a1", t0, scrollAction)),
            userActions = listOf(scrollAction)
        )

        val result = BonusTeachingFilter.filter(trace)
        // Conservative policy: scroll has resourceId evidence, preserved as UNCERTAIN or RELEVANT rather than deleted
        assertEquals(1, result.retainedEventCount)
        assertEquals(0, result.removedEventCount)
    }

    @Test
    fun testAdversarial_repeatedTap_filtered() {
        val t0 = 1000L
        val tap1 = createActionEvent("a1", t0, "CLICK", "Button", "Submit", "com.example.targetapp:id/submit")
        val stateEv1 = StateEvent("s1", t0 + 10L, createDummyState("s0"), createDummyState("s1"), "a1")
        val tap2 = createActionEvent("a2", t0 + 50L, "CLICK", "Button", "Submit", "com.example.targetapp:id/submit")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "adv_2",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 100L,
            traceEvents = listOf(
                TraceEvent.Action("a1", t0, tap1),
                TraceEvent.State("s1", t0 + 10L, stateEv1),
                TraceEvent.Action("a2", t0 + 50L, tap2)
            ),
            userActions = listOf(tap1, tap2),
            stateEvents = listOf(stateEv1)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(2, result.retainedEventCount)
        assertEquals(1, result.removedEventCount)
        assertEquals("a2", result.removedEvents.first().eventId)
    }

    @Test
    fun testAdversarial_unrelatedButtonTap_retainedIfStateChanged() {
        val t0 = 1000L
        val tap = createActionEvent("a1", t0, "CLICK", "Button", "Info", "com.example.targetapp:id/info_btn")
        val stateEv = StateEvent("s1", t0 + 10L, createDummyState("s0"), createDummyState("s_info"), "a1")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "adv_3",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 100L,
            traceEvents = listOf(TraceEvent.Action("a1", t0, tap), TraceEvent.State("s1", t0 + 10L, stateEv)),
            userActions = listOf(tap),
            stateEvents = listOf(stateEv)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(2, result.retainedEventCount) // Retained because it caused a state transition
    }

    @Test
    fun testAdversarial_transientFocusChange_filtered() {
        val t0 = 1000L
        val focus = createActionEvent("a1", t0, "FOCUS", "View", null, null)

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "adv_4",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 100L,
            traceEvents = listOf(TraceEvent.Action("a1", t0, focus)),
            userActions = listOf(focus)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(1, result.removedEventCount)
    }

    @Test
    fun testAdversarial_userPausing_retained() {
        val t0 = 1000L
        val action1 = createActionEvent("a1", t0, "CLICK", "Button", "Step 1", "com.example.targetapp:id/btn1")
        val stateEv1 = StateEvent("s1", t0 + 10L, createDummyState("s0"), createDummyState("s1"), "a1")
        // User pauses for 10 seconds before next action
        val action2 = createActionEvent("a2", t0 + 10_000L, "INPUT_TEXT", "EditText", "Pizza", "com.example.targetapp:id/input", inputData = "Pizza")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "adv_5",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 10_100L,
            traceEvents = listOf(
                TraceEvent.Action("a1", t0, action1),
                TraceEvent.State("s1", t0 + 10L, stateEv1),
                TraceEvent.Action("a2", t0 + 10_000L, action2)
            ),
            userActions = listOf(action1, action2),
            stateEvents = listOf(stateEv1)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(3, result.retainedEventCount)
        assertEquals(0, result.removedEventCount)
    }

    @Test
    fun testAdversarial_uncertainAction_neverAggressivelyRemoved() {
        val t0 = 1000L
        // Weak evidence action: TextView tap with text but no state change
        val action = createActionEvent("a1", t0, "CLICK", "TextView", "Help text", null)

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "adv_6",
            sessionId = "session_1",
            appContext = "com.example.targetapp",
            startTime = t0,
            endTime = t0 + 100L,
            traceEvents = listOf(TraceEvent.Action("a1", t0, action)),
            userActions = listOf(action)
        )

        val result = BonusTeachingFilter.filter(trace)
        assertEquals(1, result.retainedEventCount) // Preserved under conservative policy
        assertEquals(0, result.removedEventCount)
    }
}
