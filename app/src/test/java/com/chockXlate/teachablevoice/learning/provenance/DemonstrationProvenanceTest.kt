package com.chockXlate.teachablevoice.learning.provenance

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.filter.DemonstrationFilterClassification
import com.chockXlate.teachablevoice.contract.filter.FilteredDemonstrationResult
import com.chockXlate.teachablevoice.contract.filter.FilteredTraceEvent
import com.chockXlate.teachablevoice.contract.provenance.DemonstrationProvenanceGraph
import com.chockXlate.teachablevoice.contract.provenance.StepProvenanceLink
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.EvidenceType
import com.chockXlate.teachablevoice.contract.workflow.ExpectedTransition
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.StateEvidenceRequirement
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import com.chockXlate.teachablevoice.learning.actions.SemanticAction
import com.chockXlate.teachablevoice.learning.actions.SemanticActionType
import com.chockXlate.teachablevoice.learning.inference.InferenceResult
import com.chockXlate.teachablevoice.learning.inference.SlotInferenceStatus
import com.chockXlate.teachablevoice.learning.targets.SemanticTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DemonstrationProvenanceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun testProvenanceAnswersAllRequiredInspectabilityQuestions() {
        val actionId = "act_101"
        val rawAction = ActionEvent(
            actionId = actionId,
            timestamp = 1000L,
            actionType = "INPUT_TEXT",
            semanticSelector = SemanticSelector(
                role = "EditText",
                text = "Margherita",
                resourceId = "com.example.food:id/search_query"
            ),
            inputData = "Margherita"
        )
        val stateEv = StateEvent(
            stateEventId = "st_101",
            timestamp = 1050L,
            causeActionId = actionId,
            beforeState = UiState(stateId = "state_search_empty", timestamp = 1000L, appContext = "com.example.food"),
            afterState = UiState(stateId = "state_search_filled", timestamp = 1050L, appContext = "com.example.food")
        )
        val trace = DemonstrationTrace(
            traceId = "demo_prov_1",
            timestamp = 1000L,
            appContext = "com.example.food",
            traceEvents = listOf(
                TraceEvent.Action(actionId, 1000L, rawAction),
                TraceEvent.State("st_101", 1050L, stateEv)
            ),
            stateEvents = listOf(stateEv)
        )

        val semanticAction = SemanticAction(
            schemaVersion = "1.0",
            actionId = actionId,
            timestamp = 1000L,
            actionType = SemanticActionType.INPUT_TEXT,
            target = SemanticTarget(
                role = "EditText",
                text = "Margherita",
                resourceId = "com.example.food:id/search_query",
                packageName = "com.example.food",
                targetConfidence = 0.95
            ),
            inputValue = "Margherita",
            confidence = 0.95
        )

        val step = WorkflowStep(
            schemaVersion = "1.0",
            stepId = "step_1_act_101",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(
                role = "EditText",
                textSlot = "\${item_name}",
                resourceId = "com.example.food:id/search_query"
            ),
            parameters = mapOf("input_parameter" to "\${item_name}"),
            expectedTransition = ExpectedTransition(
                schemaVersion = "1.0",
                fromState = "state_search_empty",
                toState = "state_search_filled",
                expectedEvidence = listOf(
                    StateEvidenceRequirement(
                        type = EvidenceType.TEXT_EQUALS,
                        expectedValue = "\${item_name}",
                        description = "Search query field contains target item name"
                    )
                )
            ),
            confidence = 0.95
        )

        val inference = InferenceResult(
            schemaVersion = "1.0",
            intentName = "order_food",
            demonstrationsAnalyzedCount = 1,
            slotInferences = listOf(
                com.chockXlate.teachablevoice.learning.inference.AlignedSlotInference(
                    slotName = "item_name",
                    slotType = com.chockXlate.teachablevoice.contract.workflow.SlotType.TEXT,
                    status = SlotInferenceStatus.VARIABLE,
                    confidence = 0.9,
                    confidenceLevel = "HIGH",
                    reasoning = "User varied search query across demonstrations.",
                    demonstrationIds = listOf("demo_prov_1"),
                    sourceActionIds = listOf(actionId),
                    rawValues = listOf("Margherita")
                )
            )
        )

        val filterResult = FilteredDemonstrationResult(
            traceId = trace.traceId,
            targetAppContext = "com.example.food",
            allEvents = listOf(
                FilteredTraceEvent(
                    eventId = actionId,
                    classification = DemonstrationFilterClassification.TASK_RELEVANT,
                    reason = "Valid task-relevant action."
                ),
                FilteredTraceEvent(
                    eventId = "act_launcher_noise",
                    classification = DemonstrationFilterClassification.NAVIGATION_CONTEXT,
                    reason = "Preceding launcher navigation."
                )
            ),
            taskRelevantEventIds = listOf(actionId),
            navigationContextEventIds = listOf("act_launcher_noise")
        )

        val graph = DemonstrationProvenanceBuilder.build(
            workflowSkillId = "skill_food_test",
            trace = trace,
            steps = listOf(step),
            semanticActions = listOf(semanticAction),
            inferenceResult = inference,
            filterResult = filterResult
        )

        val stepLink = graph.findStepProvenance(step.stepId)
        assertNotNull(stepLink)

        // Question 1: which raw action produced this step?
        assertEquals(actionId, stepLink!!.rawActionId)
        assertTrue(stepLink.rawEventIds.contains(actionId))
        assertTrue(stepLink.rawEventIds.contains("st_101"))

        // Question 2: which UI element supported the target?
        assertNotNull(stepLink.target)
        assertEquals("EditText", stepLink.target!!.role)
        assertEquals("com.example.food:id/search_query", stepLink.target!!.resourceId)
        assertEquals("Margherita", stepLink.target!!.textSnippet)

        // Question 3: why was this parameter considered variable/constant?
        assertNotNull(stepLink.parameter)
        assertEquals("item_name", stepLink.parameter!!.slotName)
        assertTrue(stepLink.parameter!!.isVariable)
        assertEquals("VARIABLE", stepLink.parameter!!.inferenceStatus)
        assertTrue(stepLink.parameter!!.rationale.contains("varied"))

        // Question 4: which before/after evidence produced expected transition evidence?
        assertEquals(1, stepLink.transitionEvidence.size)
        assertEquals("TEXT_EQUALS", stepLink.transitionEvidence[0].evidenceType)
        assertEquals("state_search_empty", stepLink.transitionEvidence[0].beforeStateId)
        assertEquals("state_search_filled", stepLink.transitionEvidence[0].afterStateId)

        // Question 5: was any source evidence filtered or uncertain?
        assertEquals(1, graph.filteredOrUncertainEvents.size)
        val filtered = graph.filteredOrUncertainEvents[0]
        assertEquals("act_launcher_noise", filtered.rawActionId)
        assertEquals(DemonstrationFilterClassification.NAVIGATION_CONTEXT, filtered.filterClassification)
        assertTrue(filtered.isFilteredOrUncertain)

        // Serialization round trip
        val serialized = json.encodeToString(DemonstrationProvenanceGraph.serializer(), graph)
        val decoded = json.decodeFromString(DemonstrationProvenanceGraph.serializer(), serialized)
        assertEquals(graph.workflowSkillId, decoded.workflowSkillId)
        assertEquals(graph.stepLinks.size, decoded.stepLinks.size)
        assertEquals(graph.filteredOrUncertainEvents.size, decoded.filteredOrUncertainEvents.size)
    }

    @Test
    fun testPrivacyPreservingRedactionInProvenance() {
        val actionId = "act_pwd"
        val rawAction = ActionEvent(
            actionId = actionId,
            timestamp = 1000L,
            actionType = "INPUT_TEXT",
            semanticSelector = SemanticSelector(
                role = "EditText",
                text = "secretPass123",
                resourceId = "com.example.app:id/user_password"
            ),
            inputData = "secretPass123"
        )
        val trace = DemonstrationTrace(
            traceId = "demo_pwd",
            timestamp = 1000L,
            appContext = "com.example.app",
            traceEvents = listOf(TraceEvent.Action(actionId, 1000L, rawAction))
        )
        val semanticAction = SemanticAction(
            schemaVersion = "1.0",
            actionId = actionId,
            timestamp = 1000L,
            actionType = SemanticActionType.INPUT_TEXT,
            target = SemanticTarget(
                role = "EditText",
                text = "secretPass123",
                resourceId = "com.example.app:id/user_password",
                packageName = "com.example.app"
            ),
            inputValue = "secretPass123",
            confidence = 1.0
        )
        val step = WorkflowStep(
            schemaVersion = "1.0",
            stepId = "step_pwd",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(
                role = "EditText",
                resourceId = "com.example.app:id/user_password"
            )
        )
        val filterResult = FilteredDemonstrationResult(
            traceId = trace.traceId,
            targetAppContext = "com.example.app",
            allEvents = listOf(
                FilteredTraceEvent(eventId = actionId, classification = DemonstrationFilterClassification.TASK_RELEVANT, reason = "ok")
            ),
            taskRelevantEventIds = listOf(actionId)
        )

        val graph = DemonstrationProvenanceBuilder.build(
            workflowSkillId = "skill_secure",
            trace = trace,
            steps = listOf(step),
            semanticActions = listOf(semanticAction),
            inferenceResult = InferenceResult(intentName = "secure_task", demonstrationsAnalyzedCount = 1),
            filterResult = filterResult
        )

        val link = graph.findStepProvenance(step.stepId)
        assertNotNull(link)
        assertNotNull(link!!.target)
        // Ensure sensitive password was never leaked in provenance textSnippet
        assertEquals("[REDACTED_SENSITIVE]", link.target!!.textSnippet)
        assertFalse(link.target!!.textSnippet!!.contains("secretPass123"))
    }
}
