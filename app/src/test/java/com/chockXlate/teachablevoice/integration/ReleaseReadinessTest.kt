package com.chockXlate.teachablevoice.integration

import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.learning.actions.*
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationAlignment
import com.chockXlate.teachablevoice.learning.inference.*
import com.chockXlate.teachablevoice.learning.intent.*
import com.chockXlate.teachablevoice.learning.slots.SlotExtractionResult
import com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer
import com.chockXlate.teachablevoice.learning.targets.SemanticTarget
import com.chockXlate.teachablevoice.runtime.slots.SlotBinder
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import com.chockXlate.teachablevoice.teach.voice.VoiceCaptureController
import org.junit.Assert.*
import org.junit.Test

class ReleaseReadinessTest {
    private fun inference(name: String = "item", value: String = "First", status: SlotInferenceStatus = SlotInferenceStatus.UNKNOWN) =
        AlignedSlotInference(slotName = name, slotType = SlotType.TEXT, status = status,
            rawValues = listOf(value), confidence = 0.5, confidenceLevel = "LOW", reasoning = "One example",
            demonstrationIds = listOf("real_trace"), sourceActionIds = listOf("a"))
    private fun result(vararg slots: AlignedSlotInference) = InferenceResult(
        intentName = "search_information", demonstrationsAnalyzedCount = 1, slotInferences = slots.toList())
    private fun synthesize(actions: List<SemanticAction>, inference: InferenceResult, trace: DemonstrationTrace =
        DemonstrationTrace(traceId = "real_trace", timestamp = 1, appContext = "test.generic")) = WorkflowSynthesizer.synthesize(
        IntentExtractionResult(intent = Intent(intentId = "intent", canonicalName = "search_information",
            confidence = 1.0, confidenceLevel = "HIGH"), confidence = 1.0, evidenceSummary = "Recorded evidence"),
        actions, SlotExtractionResult(intentName = "search_information"), DemonstrationAlignment.align(emptyList()), inference, trace
    ).workflow!!

    @Test fun explicitConfirmationUsesOneRealExampleWithoutInventingValues() {
        val original = result(inference(), inference("context", "Fixed"))
        val confirmed = SingleDemonstrationConfirmation.confirm(original, setOf("item"))
        assertEquals(1, confirmed.demonstrationsAnalyzedCount)
        assertEquals(original.slotInferences.map { it.rawValues }, confirmed.slotInferences.map { it.rawValues })
        assertEquals(listOf(SlotInferenceStatus.VARIABLE, SlotInferenceStatus.CONSTANT), confirmed.slotInferences.map { it.status })
        assertEquals(SlotInferenceStatus.UNKNOWN, original.slotInferences.first().status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun confirmationCannotInventAnUndemonstratedSlot() {
        SingleDemonstrationConfirmation.confirm(result(inference()), setOf("missing"))
    }

    @Test fun confirmationPreservesConflictingEvidence() {
        val confirmed = SingleDemonstrationConfirmation.confirm(result(inference(status = SlotInferenceStatus.CONFLICTING)), setOf("item"))
        assertEquals(SlotInferenceStatus.CONFLICTING, confirmed.slotInferences.single().status)
    }

    @Test fun capturedStateTransitionsUseConsistentLogicalLabelsNotSnapshotIds() {
        val before = UiState(stateId = "old_uuid", timestamp = 1, appContext = "test.generic")
        val after = before.copy(stateId = "new_uuid")
        val trace = DemonstrationTrace(traceId = "real_trace", timestamp = 1, appContext = "test.generic",
            stateEvents = listOf(StateEvent(stateEventId = "event", timestamp = 2, beforeState = before,
                afterState = after, causeActionId = "a")))
        val actions = listOf("a", "b", "c").mapIndexed { index, id -> SemanticAction(actionId = id,
            timestamp = index.toLong(), actionType = SemanticActionType.TAP,
            target = SemanticTarget(role = "Button", text = "Control $index", contentDescription = "Description $index")) }
        val workflow = synthesize(actions, result(), trace)
        assertEquals("INITIAL_STATE", workflow.steps[0].expectedTransition.fromState)
        assertEquals(workflow.steps[0].expectedTransition.toState, workflow.steps[1].preconditions.fromState)
        assertEquals(workflow.steps[1].expectedTransition.toState, workflow.steps[2].preconditions.fromState)
        assertFalse(workflow.toString().contains("new_uuid"))
        assertEquals("Description 0", workflow.steps.first().semanticSelector.contentDescription)
    }

    @Test fun confirmedVariableClickBindsChangedLabelWithoutTextInputParameters() {
        val action = SemanticAction(actionId = "a", timestamp = 1, actionType = SemanticActionType.TAP,
            target = SemanticTarget(role = "Button", text = "First"))
        val wf = synthesize(listOf(action), SingleDemonstrationConfirmation.confirm(result(inference()), setOf("item")))
        val binding = SlotBinder.bind(wf, mapOf("item" to "Second"))
        assertNull(binding.error)
        assertEquals("Second", binding.steps.single().selector.text)
        assertTrue(wf.steps.single().parameters.isEmpty())
    }

    @Test fun credentialValueWithGenericSlotNameIsRedactedFromWorkflow() {
        val action = SemanticAction(actionId = "a", timestamp = 1, actionType = SemanticActionType.INPUT_TEXT,
            target = SemanticTarget(role = "EditText", contentDescription = "Verification password"), inputValue = "private-value")
        val wf = synthesize(listOf(action), result(inference("input_text", "private-value", SlotInferenceStatus.CONSTANT)))
        assertFalse(wf.toString().contains("private-value"))
        assertTrue(wf.safetyBoundary.requiresExplicitUserConfirmation)
    }

    @Test fun credentialUtteranceAndAudioUriAreNotRetained() {
        TeachingSessionManager.startSession("test", "test")
        try {
            val event = VoiceCaptureController.recordUtterance("My password is private-value", rawAudioUri = "private-uri")
            assertFalse(event.transcript.contains("private-value"))
            assertNull(event.rawAudioUri)
            assertFalse(TeachingSessionManager.peekSessionTrace().toString().contains("private-value"))
        } finally { TeachingSessionManager.clearSession() }
    }
    @Test fun typedPrefixesProduceOneFinalSemanticInputAction() {
        val actions = listOf("B", "Bl", "Blue").mapIndexed { index, value ->
            com.chockXlate.teachablevoice.contract.event.ActionEvent(actionId = "a$index", timestamp = index.toLong(),
                actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/search"), inputData = value)
        }
        val trace = DemonstrationTrace(traceId = "typing", timestamp = 0, appContext = "test.generic", userActions = actions)
        val extracted = SemanticActionExtractor.extract(trace)
        assertEquals(1, extracted.size)
        assertEquals("Blue", extracted.single().inputValue)
        assertEquals("a2", extracted.single().actionId)
    }

    @Test fun searchParaphrasesBindExplicitQueryAndKeepMissingQueryMissing() {
        val interpreter = com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter
        for (command in listOf("Search for blue notebooks", "Find blue notebooks", "Look up blue notebooks")) {
            val understood = interpreter.understandCommand(command)
            assertEquals("search_information", understood.intent.canonicalName)
            assertEquals("blue notebooks", understood.slots.single().typedValue)
        }
        assertTrue(interpreter.understandCommand("Search").slots.isEmpty())
        assertEquals("unknown", interpreter.understandCommand("Book a cab to airport").intent.canonicalName)
    }

}
