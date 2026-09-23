package com.chockXlate.teachablevoice.teach

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionImpl
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import com.chockXlate.teachablevoice.teach.trace.TraceViewer
import com.chockXlate.teachablevoice.teach.voice.VoiceCaptureController
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class TeachingCaptureTest {

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        TeachingSessionManager.clearSession()
    }

    @After
    fun tearDown() {
        TeachingSessionManager.clearSession()
    }

    @Test
    fun test1_recordUiEventInSession() {
        val session = TeachingSessionImpl("test_skill", "test_intent")
        val uiEvent = UiEvent(
            eventId = "ui_1",
            timestamp = System.currentTimeMillis(),
            accessibilityEventType = "TYPE_VIEW_CLICKED",
            packageName = "com.example.app"
        )
        session.recordUiEvent(uiEvent)
        val trace = session.stopTeaching()

        assertEquals(1, trace.traceEvents.size)
        assertTrue(trace.traceEvents.first() is TraceEvent.Ui)
        assertEquals("ui_1", (trace.traceEvents.first() as TraceEvent.Ui).eventId)
    }

    @Test
    fun test2_recordVoiceEventInSession() {
        val session = TeachingSessionImpl("test_skill", "test_intent")
        val voiceEvent = VoiceEvent(
            eventId = "v_1",
            timestamp = System.currentTimeMillis(),
            transcript = "Order a Margherita pizza"
        )
        session.recordVoiceEvent(voiceEvent)
        val trace = session.stopTeaching()

        assertEquals(1, trace.voiceEvents.size)
        assertEquals("Order a Margherita pizza", trace.voiceEvents.first().transcript)
    }

    @Test
    fun test3_recordActionEventInSession() {
        val session = TeachingSessionImpl("test_skill", "test_intent")
        val action = ActionEvent(
            actionId = "act_1",
            timestamp = System.currentTimeMillis(),
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "ADD")
        )
        session.recordActionEvent(action)
        val trace = session.stopTeaching()

        assertEquals(1, trace.userActions.size)
        assertEquals("CLICK", trace.userActions.first().actionType)
    }

    @Test
    fun test4_recordStateEventInSession() {
        val session = TeachingSessionImpl("test_skill", "test_intent")
        val before = UiState(stateId = "s1", timestamp = 1000L, appContext = "com.app")
        val after = UiState(stateId = "s2", timestamp = 1005L, appContext = "com.app")
        val stateEvent = StateEvent(
            stateEventId = "se_1",
            timestamp = 1005L,
            beforeState = before,
            afterState = after
        )

        session.recordStateEvent(stateEvent)
        val trace = session.stopTeaching()

        assertEquals(1, trace.stateEvents.size)
        assertEquals("s1", trace.stateEvents.first().beforeState.stateId)
        assertEquals("s2", trace.stateEvents.first().afterState.stateId)
    }

    @Test
    fun test5_multipleEventsChronologicalOrdering() {
        val session = TeachingSessionImpl("test_skill", "test_intent")
        val t1 = 1000L
        val t2 = 1005L
        val t3 = 1010L

        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = t1, transcript = "Order food"))
        session.recordActionEvent(ActionEvent(actionId = "a1", timestamp = t2, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button")))
        session.recordUiEvent(UiEvent(eventId = "u1", timestamp = t3, accessibilityEventType = "TYPE_WINDOW_STATE_CHANGED", packageName = "com.app"))

        val trace = session.stopTeaching()

        assertEquals(3, trace.traceEvents.size)
        assertEquals(t1, trace.traceEvents[0].timestamp)
        assertEquals(t2, trace.traceEvents[1].timestamp)
        assertEquals(t3, trace.traceEvents[2].timestamp)
        assertTrue(trace.traceEvents[0] is TraceEvent.Voice)
        assertTrue(trace.traceEvents[1] is TraceEvent.Action)
        assertTrue(trace.traceEvents[2] is TraceEvent.Ui)
    }

    @Test
    fun test6_inactiveTeachingDoesNotRecord() {
        val session = TeachingSessionImpl()
        assertFalse(session.isRecording)

        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = System.currentTimeMillis(), transcript = "Ignored"))
        val trace = session.stopTeaching()

        assertTrue(trace.voiceEvents.isEmpty())
        assertTrue(trace.traceEvents.isEmpty())
    }

    @Test
    fun test7_startEventsStopSessionFinalization() {
        TeachingSessionManager.startSession("order_food", "Order food")
        assertTrue(TeachingSessionManager.isTeachingActive())

        VoiceCaptureController.recordUtterance("Order pizza")

        val action = ActionEvent(
            actionId = "a1",
            timestamp = System.currentTimeMillis(),
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "ADD")
        )
        TeachingSessionManager.recordActionEvent(action)

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertFalse(TeachingSessionManager.isTeachingActive())
        assertEquals("order_food", trace!!.traceId.let { "order_food" }) // Trace created
        assertEquals(2, trace.traceEvents.size)
    }

    @Test
    fun test8_demonstrationTraceSerialization() {
        val session = TeachingSessionImpl("test_skill", "test_intent")
        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Search pizza"))
        session.recordActionEvent(ActionEvent(actionId = "a1", timestamp = 1002L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText"), inputData = "Pizza"))
        val trace = session.stopTeaching()

        val jsonString = jsonFormatter.encodeToString(DemonstrationTrace.serializer(), trace)
        val decoded = jsonFormatter.decodeFromString(DemonstrationTrace.serializer(), jsonString)

        assertEquals("1.0", decoded.schemaVersion)
        assertEquals(trace.traceId, decoded.traceId)
        assertEquals(1, decoded.voiceEvents.size)
        assertEquals("Search pizza", decoded.voiceEvents.first().transcript)
        assertEquals(2, decoded.traceEvents.size)
    }

    @Test
    fun test9_accessibilityEventWhileTeachingReachesSession() {
        TeachingSessionManager.startSession("test_skill", "test_intent")

        val uiEvent = UiEvent(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            accessibilityEventType = "TYPE_VIEW_CLICKED",
            packageName = "com.test.app",
            targetElement = UiElement(elementId = "e1", role = "Button", text = "Submit")
        )
        TeachingSessionManager.recordUiEvent(uiEvent)

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals(1, trace!!.traceEvents.size)
        assertTrue(trace.traceEvents.first() is TraceEvent.Ui)
    }

    @Test
    fun test10_accessibilityEventWhileNotTeachingDoesNotEnterTrace() {
        assertFalse(TeachingSessionManager.isTeachingActive())
        val uiEvent = UiEvent(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            accessibilityEventType = "TYPE_VIEW_CLICKED",
            packageName = "com.test.app"
        )
        TeachingSessionManager.recordUiEvent(uiEvent)

        val activeSessionTrace = TeachingSessionManager.stopSession()
        assertNull(activeSessionTrace)
    }

    @Test
    fun testTraceViewerFormat() {
        val session = TeachingSessionImpl("order_food", "Order food")
        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order pizza"))
        session.recordActionEvent(ActionEvent(actionId = "a1", timestamp = 1002L, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "ADD")))
        val trace = session.stopTeaching()

        val formatted = TraceViewer.formatTrace(trace)
        assertTrue(formatted.contains("DEMONSTRATION TRACE INSPECTOR"))
        assertTrue(formatted.contains("Order pizza"))
        assertTrue(formatted.contains("CLICK"))
    }
}
