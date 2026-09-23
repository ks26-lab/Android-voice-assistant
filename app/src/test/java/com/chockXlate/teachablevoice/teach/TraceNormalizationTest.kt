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
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TraceNormalizationTest {

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun test1_unorderedEventsToChronologicalOrder() {
        val t1 = 1000L
        val t2 = 1002L
        val t3 = 1005L

        val e3 = TraceEvent.Ui("u3", t3, UiEvent(eventId = "u3", timestamp = t3, accessibilityEventType = "TYPE_CLICK", packageName = "com.app"))
        val e1 = TraceEvent.Voice("v1", t1, VoiceEvent(eventId = "v1", timestamp = t1, transcript = "Order food"))
        val e2 = TraceEvent.Action("a2", t2, ActionEvent(actionId = "a2", timestamp = t2, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button")))

        val rawTrace = DemonstrationTrace(
            traceId = "tr_1",
            timestamp = t1,
            appContext = "com.app",
            traceEvents = listOf(e3, e1, e2)
        )

        val result = DemonstrationTraceNormalizer.normalize(rawTrace)
        val trace = result.normalizedTrace

        assertEquals(3, trace.traceEvents.size)
        assertEquals(t1, trace.traceEvents[0].timestamp)
        assertEquals(t2, trace.traceEvents[1].timestamp)
        assertEquals(t3, trace.traceEvents[2].timestamp)
        assertTrue(trace.traceEvents[0] is TraceEvent.Voice)
        assertTrue(trace.traceEvents[1] is TraceEvent.Action)
        assertTrue(trace.traceEvents[2] is TraceEvent.Ui)
    }

    @Test
    fun test2_equalTimestampsPreserveInsertionOrder() {
        val t = 1000L
        val e1 = TraceEvent.Voice("v1", t, VoiceEvent(eventId = "v1", timestamp = t, transcript = "First"))
        val e2 = TraceEvent.Action("a1", t, ActionEvent(actionId = "a1", timestamp = t, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button")))
        val e3 = TraceEvent.Ui("u1", t, UiEvent(eventId = "u1", timestamp = t, accessibilityEventType = "TYPE_CLICK", packageName = "com.app"))

        val rawTrace = DemonstrationTrace(
            traceId = "tr_2",
            timestamp = t,
            appContext = "com.app",
            traceEvents = listOf(e1, e2, e3)
        )

        val result = DemonstrationTraceNormalizer.normalize(rawTrace)
        val trace = result.normalizedTrace

        assertEquals(3, trace.traceEvents.size)
        assertEquals("v1", trace.traceEvents[0].eventId)
        assertEquals("a1", trace.traceEvents[1].eventId)
        assertEquals("u1", trace.traceEvents[2].eventId)
    }

    @Test
    fun test3_trueDuplicateUiEventsRemoved() {
        val t = 1000L
        val elem = UiElement(elementId = "el1", role = "Button", text = "ADD", resourceId = "btn_add")

        val ui1 = UiEvent(eventId = "u1", timestamp = t, accessibilityEventType = "TYPE_CLICK", packageName = "com.app", targetElement = elem)
        val ui2 = UiEvent(eventId = "u2", timestamp = t + 50L, accessibilityEventType = "TYPE_CLICK", packageName = "com.app", targetElement = elem)

        val rawTrace = DemonstrationTrace(
            traceId = "tr_3",
            timestamp = t,
            appContext = "com.app",
            traceEvents = listOf(TraceEvent.Ui("u1", t, ui1), TraceEvent.Ui("u2", t + 50L, ui2))
        )

        val result = DemonstrationTraceNormalizer.normalize(rawTrace)
        assertEquals(2, result.rawEventCount)
        assertEquals(1, result.normalizedEventCount)
        assertEquals(1, result.removedDuplicateCount)
        assertEquals(1, result.normalizedTrace.traceEvents.size)
    }

    @Test
    fun test4_similarButNonIdenticalUiEventsPreserved() {
        val t = 1000L
        val elem1 = UiElement(elementId = "el1", role = "Button", text = "ADD", resourceId = "btn_add")
        val elem2 = UiElement(elementId = "el2", role = "Button", text = "REMOVE", resourceId = "btn_remove")

        val ui1 = UiEvent(eventId = "u1", timestamp = t, accessibilityEventType = "TYPE_CLICK", packageName = "com.app", targetElement = elem1)
        val ui2 = UiEvent(eventId = "u2", timestamp = t + 50L, accessibilityEventType = "TYPE_CLICK", packageName = "com.app", targetElement = elem2)

        val rawTrace = DemonstrationTrace(
            traceId = "tr_4",
            timestamp = t,
            appContext = "com.app",
            traceEvents = listOf(TraceEvent.Ui("u1", t, ui1), TraceEvent.Ui("u2", t + 50L, ui2))
        )

        val result = DemonstrationTraceNormalizer.normalize(rawTrace)
        assertEquals(2, result.normalizedEventCount)
        assertEquals(0, result.removedDuplicateCount)
    }

    @Test
    fun test5_actionEventPreserved() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText"), inputData = "Pizza")
        val rawTrace = DemonstrationTrace(traceId = "tr_5", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))

        val result = DemonstrationTraceNormalizer.normalize(rawTrace)
        assertEquals(1, result.normalizedTrace.userActions.size)
        assertEquals("Pizza", result.normalizedTrace.userActions.first().inputData)
    }

    @Test
    fun test6_voiceEventPreservedExactly() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order Margherita Pizza")
        val rawTrace = DemonstrationTrace(traceId = "tr_6", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val result = DemonstrationTraceNormalizer.normalize(rawTrace)
        assertEquals(1, result.normalizedTrace.voiceEvents.size)
        assertEquals("Order Margherita Pizza", result.normalizedTrace.voiceEvents.first().transcript)
    }

    @Test
    fun test7_stateEventBeforeAfterPreserved() {
        val s1 = UiState(stateId = "state_1", timestamp = 1000L, appContext = "com.app")
        val s2 = UiState(stateId = "state_2", timestamp = 1005L, appContext = "com.app")
        val stateEvent = StateEvent(stateEventId = "se1", timestamp = 1005L, beforeState = s1, afterState = s2)

        val rawTrace = DemonstrationTrace(traceId = "tr_7", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.State("se1", 1005L, stateEvent)))

        val result = DemonstrationTraceNormalizer.normalize(rawTrace)
        assertEquals(1, result.normalizedTrace.stateEvents.size)
        assertEquals("state_1", result.normalizedTrace.stateEvents.first().beforeState.stateId)
        assertEquals("state_2", result.normalizedTrace.stateEvents.first().afterState.stateId)
    }

    @Test
    fun test8_appContextResolvedFromEvents() {
        val uiEvent = UiEvent(eventId = "u1", timestamp = 1000L, accessibilityEventType = "TYPE_CLICK", packageName = "com.example.discoveredapp")
        val rawTrace = DemonstrationTrace(traceId = "tr_8", timestamp = 1000L, appContext = "unknown", traceEvents = listOf(TraceEvent.Ui("u1", 1000L, uiEvent)))

        val result = DemonstrationTraceNormalizer.normalize(rawTrace)
        assertEquals("com.example.discoveredapp", result.normalizedTrace.appContext)
    }

    @Test
    fun test9_malformedStateEventHandledWithWarning() {
        val malformedState = StateEvent(stateEventId = "se_bad", timestamp = 1000L, beforeState = UiState(stateId = "", timestamp = 0L, appContext = ""), afterState = UiState(stateId = "", timestamp = 0L, appContext = ""))
        val rawTrace = DemonstrationTrace(traceId = "tr_9", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.State("se_bad", 1000L, malformedState)))

        val result = DemonstrationTraceNormalizer.normalize(rawTrace)
        assertEquals(1, result.warningCount)
        assertTrue(result.warnings.first().contains("blank before/after state identity"))
        assertEquals(1, result.normalizedTrace.stateEvents.size)
    }

    @Test
    fun test10_normalizationTwiceIsDeterministic() {
        val v = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order food")
        val a = ActionEvent(actionId = "a1", timestamp = 1002L, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button"))
        val rawTrace = DemonstrationTrace(traceId = "tr_10", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Voice("v1", 1000L, v), TraceEvent.Action("a1", 1002L, a)))

        val res1 = DemonstrationTraceNormalizer.normalize(rawTrace)
        val res2 = DemonstrationTraceNormalizer.normalize(res1.normalizedTrace)

        assertEquals(res1.normalizedTrace, res2.normalizedTrace)
        assertEquals(res1.normalizedEventCount, res2.normalizedEventCount)
    }

    @Test
    fun test11_normalizedTraceSerialization() {
        val v = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order food")
        val rawTrace = DemonstrationTrace(traceId = "tr_11", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Voice("v1", 1000L, v)))

        val result = DemonstrationTraceNormalizer.normalize(rawTrace)
        val json = jsonFormatter.encodeToString(DemonstrationTrace.serializer(), result.normalizedTrace)
        val decoded = jsonFormatter.decodeFromString(DemonstrationTrace.serializer(), json)

        assertEquals("1.0", decoded.schemaVersion)
        assertEquals(result.normalizedTrace.traceId, decoded.traceId)
        assertEquals(1, decoded.voiceEvents.size)
    }

    @Test
    fun test12_evidencePreservation() {
        val v = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Search pizza")
        val a = ActionEvent(actionId = "a1", timestamp = 1002L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/input"), inputData = "Pizza")
        val s = StateEvent(stateEventId = "se1", timestamp = 1005L, beforeState = UiState(stateId = "st1", timestamp = 1002L, appContext = "com.app"), afterState = UiState(stateId = "st2", timestamp = 1005L, appContext = "com.app"))

        val rawTrace = DemonstrationTrace(traceId = "tr_12", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Voice("v1", 1000L, v), TraceEvent.Action("a1", 1002L, a), TraceEvent.State("se1", 1005L, s)))

        val result = DemonstrationTraceNormalizer.normalize(rawTrace)
        val norm = result.normalizedTrace

        assertEquals("1.0", norm.schemaVersion)
        assertEquals(1, norm.voiceEvents.size)
        assertEquals(1, norm.userActions.size)
        assertEquals(1, norm.stateEvents.size)
        assertEquals("Pizza", norm.userActions.first().inputData)
        assertEquals("id/input", norm.userActions.first().semanticSelector.resourceId)
    }
}
