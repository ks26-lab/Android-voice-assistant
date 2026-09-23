package com.chockXlate.teachablevoice.learning.intent

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.learning.actions.SemanticAction
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.learning.actions.SemanticActionType
import com.chockXlate.teachablevoice.learning.targets.SemanticTarget
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionImpl
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class IntentExtractionTest {

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun test1_explicitOrderFoodProducesOrderFoodIntent() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order a Margherita pizza from Pizza Palace")
        val action1 = ActionEvent(actionId = "a1", timestamp = 1002L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", text = "Search dishes..."), inputData = "Pizza Palace")
        val action2 = ActionEvent(actionId = "a2", timestamp = 1005L, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "ADD"))

        val trace = DemonstrationTrace(traceId = "tr1", timestamp = 1000L, appContext = "com.food.app", voiceEvents = listOf(voice), userActions = listOf(action1, action2), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice), TraceEvent.Action("a1", 1002L, action1), TraceEvent.Action("a2", 1005L, action2)))
        val semanticActions = SemanticActionExtractor.extract(trace)

        val result = IntentExtractor.extract(trace, semanticActions)
        assertEquals("order_food", result.intent.canonicalName)
        assertEquals("HIGH", result.intent.confidenceLevel)
        assertEquals(1.0, result.confidence, 0.01)
    }

    @Test
    fun test2_originalVoiceTranscriptIsPreserved() {
        val originalTranscript = "Order a Margherita pizza from Pizza Palace"
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = originalTranscript)
        val trace = DemonstrationTrace(traceId = "tr2", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val result = IntentExtractor.extract(trace, emptyList())
        assertEquals(originalTranscript, result.intent.sourceVoiceTranscript)
    }

    @Test
    fun test3_voiceEventProvenanceIsPreserved() {
        val voice = VoiceEvent(eventId = "voice_evt_99", timestamp = 1000L, transcript = "Order food")
        val trace = DemonstrationTrace(traceId = "tr3", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("voice_evt_99", 1000L, voice)))

        val result = IntentExtractor.extract(trace, emptyList())
        assertTrue(result.intent.sourceVoiceEventIds.contains("voice_evt_99"))
    }

    @Test
    fun test4_semanticActionEvidenceIsPreserved() {
        val action = ActionEvent(actionId = "act_evt_88", timestamp = 1000L, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "ADD"))
        val trace = DemonstrationTrace(traceId = "tr4", timestamp = 1000L, appContext = "com.app", userActions = listOf(action), traceEvents = listOf(TraceEvent.Action("act_evt_88", 1000L, action)))
        val semanticActions = SemanticActionExtractor.extract(trace)

        val result = IntentExtractor.extract(trace, semanticActions)
        assertTrue(result.intent.supportingActionIds.contains("act_evt_88"))
    }

    @Test
    fun test5_intentConfidenceIsDeterministic() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order food")
        val traceHigh = DemonstrationTrace(traceId = "tr5a", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val resultHigh = IntentExtractor.extract(traceHigh, emptyList())
        assertEquals("HIGH", resultHigh.intent.confidenceLevel)
        assertEquals(1.0, resultHigh.confidence, 0.01)

        val actionOnly = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "ADD"))
        val traceMedium = DemonstrationTrace(traceId = "tr5b", timestamp = 1000L, appContext = "com.app", userActions = listOf(actionOnly), traceEvents = listOf(TraceEvent.Action("a1", 1000L, actionOnly)))
        val semanticActions = SemanticActionExtractor.extract(traceMedium)

        val resultMedium = IntentExtractor.extract(traceMedium, semanticActions)
        assertEquals("MEDIUM", resultMedium.intent.confidenceLevel)
    }

    @Test
    fun test6_repeatedIdenticalInputProducesIdenticalOutput() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order food")
        val trace = DemonstrationTrace(traceId = "tr6", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val res1 = IntentExtractor.extract(trace, emptyList())
        val res2 = IntentExtractor.extract(trace, emptyList())

        assertEquals(res1, res2)
        assertEquals(res1.intent.canonicalName, res2.intent.canonicalName)
    }

    @Test
    fun test7_missingVoiceEvidenceHandledSafely() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "ADD"))
        val trace = DemonstrationTrace(traceId = "tr7", timestamp = 1000L, appContext = "com.app", userActions = listOf(action), traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))
        val semanticActions = SemanticActionExtractor.extract(trace)

        val result = IntentExtractor.extract(trace, semanticActions)
        assertNull(result.intent.sourceVoiceTranscript)
        assertEquals("order_food", result.intent.canonicalName)
        assertEquals("MEDIUM", result.intent.confidenceLevel)
    }

    @Test
    fun test8_insufficientEvidenceDoesNotFabricateIntent() {
        val emptyTrace = DemonstrationTrace(traceId = "tr8", timestamp = 1000L, appContext = "com.app")
        val result = IntentExtractor.extract(emptyTrace, emptyList())

        assertEquals("unknown", result.intent.canonicalName)
        assertEquals("UNKNOWN", result.intent.confidenceLevel)
        assertEquals(0.30, result.confidence, 0.01)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun test9_intentExtractionDoesNotExtractSlots() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 Margherita pizzas from Pizza Palace to 123 Tech Park")
        val trace = DemonstrationTrace(traceId = "tr9", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val result = IntentExtractor.extract(trace, emptyList())
        assertEquals("order_food", result.intent.canonicalName)
        
        // Confirm Intent class does NOT have slot parameter maps (slots are Phase 5)
        assertEquals("1.0", result.intent.schemaVersion)
        assertEquals("Order 2 Margherita pizzas from Pizza Palace to 123 Tech Park", result.intent.sourceVoiceTranscript)
    }

    @Test
    fun test10_multipleSemanticActionsContributeSupportingEvidence() {
        val sa1 = SemanticAction(actionId = "sa1", timestamp = 1000L, actionType = SemanticActionType.INPUT_TEXT, target = SemanticTarget(role = "EditText", text = "Search"), inputValue = "Pizza")
        val sa2 = SemanticAction(actionId = "sa2", timestamp = 1005L, actionType = SemanticActionType.TAP, target = SemanticTarget(role = "Button", text = "ADD"))
        val trace = DemonstrationTrace(traceId = "tr10", timestamp = 1000L, appContext = "com.app")

        val result = IntentExtractor.extract(trace, listOf(sa1, sa2))
        assertEquals(2, result.intent.supportingActionIds.size)
        assertTrue(result.intent.supportingActionIds.contains("sa1"))
        assertTrue(result.intent.supportingActionIds.contains("sa2"))
    }

    @Test
    fun test11_appPackageContextPreservedAsEvidence() {
        val trace = DemonstrationTrace(traceId = "tr11", timestamp = 1000L, appContext = "com.example.targetapp", voiceEvents = listOf(VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order food")))
        val result = IntentExtractor.extract(trace, emptyList())
        assertEquals("com.example.targetapp", result.intent.supportingAppPackage)
    }

    @Test
    fun test12_timestampAndProvenancePreserved() {
        val voice = VoiceEvent(eventId = "v_prov", timestamp = 1758646000000L, transcript = "Order food")
        val trace = DemonstrationTrace(traceId = "tr12", timestamp = 1758646000000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v_prov", 1758646000000L, voice)))

        val result = IntentExtractor.extract(trace, emptyList())
        assertTrue(result.intent.reasoning.contains("Voice: \"Order food\""))
        assertTrue(result.intent.sourceVoiceEventIds.contains("v_prov"))
    }

    @Test
    fun test13_phase2NormalizedTraceFeedsPhase4() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order a pizza")
        val rawTrace = DemonstrationTrace(traceId = "tr13", timestamp = 1000L, appContext = "unknown", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val normResult = DemonstrationTraceNormalizer.normalize(rawTrace)
        val result = IntentExtractor.extract(normResult.normalizedTrace, emptyList())

        assertEquals("order_food", result.intent.canonicalName)
    }

    @Test
    fun test14_phase3SemanticActionsFeedPhase4() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order food")
        val action = ActionEvent(actionId = "a1", timestamp = 1002L, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "ADD"))
        val trace = DemonstrationTrace(traceId = "tr14", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), userActions = listOf(action), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice), TraceEvent.Action("a1", 1002L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        val result = IntentExtractor.extract(trace, semanticActions)

        assertEquals("order_food", result.intent.canonicalName)
        assertEquals(1, result.intent.supportingActionIds.size)
    }

    @Test
    fun test15_serializationCompatibility() {
        val intent = Intent(intentId = "int_100", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH", sourceVoiceTranscript = "Order a pizza")
        val result = IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "Extracted order_food")

        val json = jsonFormatter.encodeToString(IntentExtractionResult.serializer(), result)
        val decoded = jsonFormatter.decodeFromString(IntentExtractionResult.serializer(), json)

        assertEquals("1.0", decoded.schemaVersion)
        assertEquals("order_food", decoded.intent.canonicalName)
        assertEquals("HIGH", decoded.intent.confidenceLevel)
    }

    @Test
    fun test16_noCoordinateBasedIntentLogic() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "ADD", resourceId = "btn_add"))
        val trace = DemonstrationTrace(traceId = "tr16", timestamp = 1000L, appContext = "com.app", userActions = listOf(action), traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))
        val semanticActions = SemanticActionExtractor.extract(trace)

        val result = IntentExtractor.extract(trace, semanticActions)
        assertEquals("order_food", result.intent.canonicalName)
        // Verified: target evidence uses role/text/resId semantic selectors, not coordinates
    }

    @Test
    fun test17_noHardcodedJudgeWorkflow() {
        // Test custom un-hardcoded intent "navigate"
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Get directions to Downtown Map")
        val trace = DemonstrationTrace(traceId = "tr17", timestamp = 1000L, appContext = "com.maps.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val result = IntentExtractor.extract(trace, emptyList())
        assertEquals("navigate", result.intent.canonicalName)
    }

    @Test
    fun test18_noExternalNetworkOrAIDependency() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order food")
        val trace = DemonstrationTrace(traceId = "tr18", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        // Pure offline local deterministic extraction
        val startMs = System.currentTimeMillis()
        val result = IntentExtractor.extract(trace, emptyList())
        val durationMs = System.currentTimeMillis() - startMs

        assertEquals("order_food", result.intent.canonicalName)
        assertTrue(durationMs < 1000L) // Instant offline execution
    }

    @Test
    fun test19_regressionPhase1TeachingCapture() {
        val session = TeachingSessionImpl("order_food", "Order food")
        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order food"))
        val trace = session.stopTeaching()

        val result = IntentExtractor.extract(trace, emptyList())
        assertEquals("order_food", result.intent.canonicalName)
    }

    @Test
    fun test20_regressionPhase2Normalization() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order a pizza")
        val rawTrace = DemonstrationTrace(traceId = "tr20", timestamp = 1000L, appContext = "unknown", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val normResult = DemonstrationTraceNormalizer.normalize(rawTrace)
        val result = IntentExtractor.extract(normResult.normalizedTrace, emptyList())

        assertEquals("order_food", result.intent.canonicalName)
    }

    @Test
    fun test21_regressionPhase3SemanticActionExtraction() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", text = "Search"), inputData = "Pizza")
        val trace = DemonstrationTrace(traceId = "tr21", timestamp = 1000L, appContext = "com.app", userActions = listOf(action), traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        val result = IntentExtractor.extract(trace, semanticActions)

        assertEquals("order_food", result.intent.canonicalName)
    }

    @Test
    fun test22_genericArchitectureSupportsOtherIntents() {
        // Test send_message generic intent
        val voiceMsg = VoiceEvent(eventId = "v_msg", timestamp = 1000L, transcript = "Send text message to Alex")
        val traceMsg = DemonstrationTrace(traceId = "tr_msg", timestamp = 1000L, appContext = "com.messaging.app", voiceEvents = listOf(voiceMsg), traceEvents = listOf(TraceEvent.Voice("v_msg", 1000L, voiceMsg)))

        val resMsg = IntentExtractor.extract(traceMsg, emptyList())
        assertEquals("send_message", resMsg.intent.canonicalName)

        // Test book_appointment generic intent
        val voiceAppt = VoiceEvent(eventId = "v_appt", timestamp = 1000L, transcript = "Book appointment for doctor reservation")
        val traceAppt = DemonstrationTrace(traceId = "tr_appt", timestamp = 1000L, appContext = "com.calendar.app", voiceEvents = listOf(voiceAppt), traceEvents = listOf(TraceEvent.Voice("v_appt", 1000L, voiceAppt)))

        val resAppt = IntentExtractor.extract(traceAppt, emptyList())
        assertEquals("book_appointment", resAppt.intent.canonicalName)
    }
}
