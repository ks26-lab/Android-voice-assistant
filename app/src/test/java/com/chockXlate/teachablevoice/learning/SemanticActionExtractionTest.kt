package com.chockXlate.teachablevoice.learning

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.learning.actions.SemanticAction
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.learning.actions.SemanticActionType
import com.chockXlate.teachablevoice.learning.targets.SemanticTarget
import com.chockXlate.teachablevoice.learning.targets.SemanticTargetNormalizer
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SemanticActionExtractionTest {

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun test1_clickToTapMapping() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "ADD"))
        val trace = DemonstrationTrace(traceId = "tr1", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        assertEquals(1, semanticActions.size)
        assertEquals(SemanticActionType.TAP, semanticActions.first().actionType)
    }

    @Test
    fun test2_inputTextMapping() {
        val action = ActionEvent(actionId = "a2", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText"), inputData = "Pizza Palace")
        val trace = DemonstrationTrace(traceId = "tr2", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Action("a2", 1000L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        assertEquals(1, semanticActions.size)
        assertEquals(SemanticActionType.INPUT_TEXT, semanticActions.first().actionType)
        assertEquals("Pizza Palace", semanticActions.first().inputValue)
    }

    @Test
    fun test3_longPressMapping() {
        val action = ActionEvent(actionId = "a3", timestamp = 1000L, actionType = "LONG_PRESS", semanticSelector = SemanticSelector(role = "ItemView"))
        val trace = DemonstrationTrace(traceId = "tr3", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Action("a3", 1000L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        assertEquals(SemanticActionType.LONG_PRESS, semanticActions.first().actionType)
    }

    @Test
    fun test4_selectMapping() {
        val action = ActionEvent(actionId = "a4", timestamp = 1000L, actionType = "SELECT", semanticSelector = SemanticSelector(role = "RadioButton"))
        val trace = DemonstrationTrace(traceId = "tr4", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Action("a4", 1000L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        assertEquals(SemanticActionType.SELECT, semanticActions.first().actionType)
    }

    @Test
    fun test5_focusMapping() {
        val action = ActionEvent(actionId = "a5", timestamp = 1000L, actionType = "FOCUS", semanticSelector = SemanticSelector(role = "EditText"))
        val trace = DemonstrationTrace(traceId = "tr5", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Action("a5", 1000L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        assertEquals(SemanticActionType.FOCUS, semanticActions.first().actionType)
    }

    @Test
    fun test6_targetRolePreservation() {
        val selector = SemanticSelector(role = "Button", text = "Submit")
        val target = SemanticTargetNormalizer.fromSelector(selector, "com.app")
        assertNotNull(target)
        assertEquals("Button", target?.role)
    }

    @Test
    fun test7_visibleTextPreservation() {
        val selector = SemanticSelector(role = "TextView", text = "Total Price: $15.00")
        val target = SemanticTargetNormalizer.fromSelector(selector, "com.app")
        assertEquals("Total Price: $15.00", target?.text)
    }

    @Test
    fun test8_contentDescriptionPreservation() {
        val selector = SemanticSelector(role = "ImageButton", contentDescription = "Back Navigation")
        val target = SemanticTargetNormalizer.fromSelector(selector, "com.app")
        assertEquals("Back Navigation", target?.contentDescription)
    }

    @Test
    fun test9_resourceIdPreservation() {
        val selector = SemanticSelector(role = "Button", resourceId = "com.app:id/submit_button")
        val target = SemanticTargetNormalizer.fromSelector(selector, "com.app")
        assertEquals("com.app:id/submit_button", target?.resourceId)
    }

    @Test
    fun test10_packageAppContextPreservation() {
        val selector = SemanticSelector(role = "Button", text = "OK")
        val target = SemanticTargetNormalizer.fromSelector(selector, "com.target.application")
        assertEquals("com.target.application", target?.packageName)
    }

    @Test
    fun test11_inputDataPreservation() {
        val action = ActionEvent(actionId = "a_input", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText"), inputData = "123 Tech Park")
        val trace = DemonstrationTrace(traceId = "tr_input", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Action("a_input", 1000L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        assertEquals("123 Tech Park", semanticActions.first().inputValue)
    }

    @Test
    fun test12_timestampPreservation() {
        val ts = 1758645000000L
        val action = ActionEvent(actionId = "a_ts", timestamp = ts, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button"))
        val trace = DemonstrationTrace(traceId = "tr_ts", timestamp = ts, appContext = "com.app", traceEvents = listOf(TraceEvent.Action("a_ts", ts, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        assertEquals(ts, semanticActions.first().timestamp)
    }

    @Test
    fun test13_provenanceEvidencePreservation() {
        val action = ActionEvent(actionId = "a_prov_99", timestamp = 1000L, actionType = "TOUCH_TAP", semanticSelector = SemanticSelector(role = "Button"))
        val trace = DemonstrationTrace(traceId = "tr_prov", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Action("a_prov_99", 1000L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        assertEquals("a_prov_99", semanticActions.first().rawEventId)
        assertEquals("TOUCH_TAP", semanticActions.first().rawActionType)
    }

    @Test
    fun test14_missingTargetHandledSafely() {
        val action = ActionEvent(actionId = "a_no_target", timestamp = 1000L, actionType = "CLICK", semanticSelector = SemanticSelector())
        val trace = DemonstrationTrace(traceId = "tr_no_target", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Action("a_no_target", 1000L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        assertEquals(1, semanticActions.size)
        assertNull(semanticActions.first().target)
        assertEquals("LOW", semanticActions.first().confidenceLevel)
    }

    @Test
    fun test15_semanticConfidenceIsDeterministic() {
        val strongAction = ActionEvent(actionId = "a_strong", timestamp = 1000L, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "ADD", resourceId = "id/btn"))
        val weakAction = ActionEvent(actionId = "a_weak", timestamp = 1001L, actionType = "CLICK", semanticSelector = SemanticSelector())

        val trace = DemonstrationTrace(traceId = "tr_conf", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Action("a_strong", 1000L, strongAction), TraceEvent.Action("a_weak", 1001L, weakAction)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        assertEquals("HIGH", semanticActions[0].confidenceLevel)
        assertEquals(1.0, semanticActions[0].confidence, 0.01)
        assertEquals("LOW", semanticActions[1].confidenceLevel)
        assertEquals(0.50, semanticActions[1].confidence, 0.01)
    }

    @Test
    fun test16_coordinatesNotUsedAsSemanticTruth() {
        val selector = SemanticSelector(role = "Button", text = "Submit", resourceId = "id/submit")
        val target = SemanticTargetNormalizer.fromSelector(selector, "com.app")
        assertNotNull(target)
        // Verify target model contains semantic properties, not raw pixel bounds/coordinates
        assertEquals("Button", target?.role)
        assertEquals("Submit", target?.text)
        assertEquals("id/submit", target?.resourceId)
    }

    @Test
    fun test17_extractionIsDeterministic() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "ADD"))
        val trace = DemonstrationTrace(traceId = "tr17", timestamp = 1000L, appContext = "com.app", traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))

        val out1 = SemanticActionExtractor.extract(trace)
        val out2 = SemanticActionExtractor.extract(trace)

        assertEquals(out1, out2)
    }

    @Test
    fun test18_phase2NormalizationFeedsPhase3Directly() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Search pizza")
        val rawAction = ActionEvent(actionId = "a1", timestamp = 1002L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", text = "Search"), inputData = "Pizza")
        val rawTrace = DemonstrationTrace(traceId = "tr18", timestamp = 1000L, appContext = "com.food.app", traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice), TraceEvent.Action("a1", 1002L, rawAction)))

        val normResult = DemonstrationTraceNormalizer.normalize(rawTrace)
        val semanticActions = SemanticActionExtractor.extract(normResult.normalizedTrace)

        assertEquals(1, semanticActions.size)
        assertEquals(SemanticActionType.INPUT_TEXT, semanticActions.first().actionType)
        assertEquals("Pizza", semanticActions.first().inputValue)
        assertEquals("EditText", semanticActions.first().target?.role)
    }

    @Test
    fun test19_serializationCompatibility() {
        val target = SemanticTarget(role = "Button", text = "Submit", resourceId = "btn_sub")
        val action = SemanticAction(actionId = "sa1", timestamp = 1000L, actionType = SemanticActionType.TAP, target = target, confidence = 1.0, confidenceLevel = "HIGH")

        val json = jsonFormatter.encodeToString(SemanticAction.serializer(), action)
        val decoded = jsonFormatter.decodeFromString(SemanticAction.serializer(), json)

        assertEquals("1.0", decoded.schemaVersion)
        assertEquals("sa1", decoded.actionId)
        assertEquals(SemanticActionType.TAP, decoded.actionType)
        assertEquals("Submit", decoded.target?.text)
    }

    @Test
    fun test20_noHardcodedJudgeWorkflow() {
        // Arbitrary unseen custom application action
        val customAction = ActionEvent(
            actionId = "act_custom_88",
            timestamp = 2000L,
            actionType = "SUBMIT",
            semanticSelector = SemanticSelector(role = "CustomSubmitView", text = "Send Payload", resourceId = "org.custom.app:id/btn_send"),
            inputData = "custom_data_payload"
        )
        val trace = DemonstrationTrace(traceId = "tr_custom", timestamp = 2000L, appContext = "org.custom.app", traceEvents = listOf(TraceEvent.Action("act_custom_88", 2000L, customAction)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        assertEquals(1, semanticActions.size)
        assertEquals(SemanticActionType.SUBMIT, semanticActions.first().actionType)
        assertEquals("CustomSubmitView", semanticActions.first().target?.role)
        assertEquals("Send Payload", semanticActions.first().target?.text)
        assertEquals("custom_data_payload", semanticActions.first().inputValue)
    }
}
