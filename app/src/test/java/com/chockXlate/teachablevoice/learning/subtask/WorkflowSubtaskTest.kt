package com.chockXlate.teachablevoice.learning.subtask

import com.chockXlate.teachablevoice.contract.workflow.EvidenceType
import com.chockXlate.teachablevoice.contract.workflow.ExpectedTransition
import com.chockXlate.teachablevoice.contract.workflow.Preconditions
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.StateEvidenceRequirement
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSubtask
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkflowSubtaskTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun testSimpleLinearWorkflowSegmentedConsistently() {
        val step1 = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Start")
        )
        val step2 = WorkflowStep(
            stepId = "s2",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Next")
        )

        val subtasks = WorkflowSubtaskSegmenter.segment(listOf(step1, step2))
        assertFalse(subtasks.isEmpty())
        assertEquals(listOf("s1", "s2"), subtasks[0].stepIds)
    }

    @Test
    fun testClearStateBoundaryCreatesNewSubtask() {
        val step1 = WorkflowStep(
            stepId = "s1_select",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Select Item"),
            expectedTransition = ExpectedTransition(
                fromState = "STATE_CATALOG",
                toState = "STATE_ITEM_DETAILS",
                expectedEvidence = listOf(
                    StateEvidenceRequirement(type = EvidenceType.ELEMENT_APPEARED, description = "Details sheet appeared")
                )
            )
        )
        val step2 = WorkflowStep(
            stepId = "s2_configure",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "RadioButton", text = "Large Size"),
            preconditions = Preconditions(fromState = "STATE_ITEM_DETAILS")
        )

        val subtasks = WorkflowSubtaskSegmenter.segment(listOf(step1, step2))
        assertEquals(2, subtasks.size)
        assertEquals(listOf("s1_select"), subtasks[0].stepIds)
        assertEquals(listOf("s2_configure"), subtasks[1].stepIds)
    }

    @Test
    fun testRelatedConsecutiveActionsRemainGrouped() {
        // Text entry followed immediately by submit/search button remains in the same subtask
        val stepInput = WorkflowStep(
            stepId = "s_input",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/search_box"),
            parameters = mapOf("input_parameter" to "\${query}")
        )
        val stepSubmit = WorkflowStep(
            stepId = "s_submit",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Search")
        )

        val subtasks = WorkflowSubtaskSegmenter.segment(listOf(stepInput, stepSubmit))
        assertEquals(1, subtasks.size)
        assertEquals(listOf("s_input", "s_submit"), subtasks[0].stepIds)
        assertTrue(subtasks[0].label.contains("TEXT_ENTRY"))
    }

    @Test
    fun testPackageTransitionBoundaryCreatesNewSubtask() {
        val stepApp1 = WorkflowStep(
            stepId = "s_app1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Open Maps"),
            preconditions = Preconditions(requiredPackage = "com.main.app"),
            expectedTransition = ExpectedTransition(expectedPackage = "com.maps.picker")
        )
        val stepApp2 = WorkflowStep(
            stepId = "s_app2",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Confirm Pin"),
            preconditions = Preconditions(requiredPackage = "com.maps.picker")
        )

        val subtasks = WorkflowSubtaskSegmenter.segment(listOf(stepApp1, stepApp2))
        assertEquals(2, subtasks.size)
        assertEquals(listOf("s_app1"), subtasks[0].stepIds)
        assertEquals(listOf("s_app2"), subtasks[1].stepIds)
    }

    @Test
    fun testQuantityStepsRemainLogicallyGrouped() {
        val qStep1 = WorkflowStep(
            stepId = "s_qty_1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", resourceId = "id/plus_btn"),
            parameters = mapOf("quantity" to "\${qty}"),
            expectedTransition = ExpectedTransition(
                expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.COUNTER_CHANGE, counterDelta = 1))
            )
        )
        val qStep2 = WorkflowStep(
            stepId = "s_qty_2",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", resourceId = "id/plus_btn"),
            parameters = mapOf("quantity" to "\${qty}"),
            expectedTransition = ExpectedTransition(
                expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.COUNTER_CHANGE, counterDelta = 1))
            )
        )

        val subtasks = WorkflowSubtaskSegmenter.segment(listOf(qStep1, qStep2))
        assertEquals(1, subtasks.size)
        assertEquals(listOf("s_qty_1", "s_qty_2"), subtasks[0].stepIds)
        assertTrue(subtasks[0].label.contains("QUANTITY_ADJUSTMENT"))
    }

    @Test
    fun testLegacyWorkflowWithoutSubtasksRemainsCompatible() {
        val legacyJson = """
        {
            "schemaVersion": "1.0",
            "skillId": "skill_legacy_001",
            "name": "Legacy Workflow",
            "intent": "legacy_intent",
            "appContext": "com.legacy.app",
            "slots": [],
            "steps": [
                {
                    "schemaVersion": "1.0",
                    "stepId": "legacy_step_1",
                    "semanticAction": "CLICK",
                    "semanticSelector": {
                        "role": "Button",
                        "text": "Go"
                    }
                }
            ]
        }
        """.trimIndent()

        val decoded = json.decodeFromString(Workflow.serializer(), legacyJson)
        assertEquals("skill_legacy_001", decoded.skillId)
        assertEquals(1, decoded.steps.size)
        assertTrue(decoded.subtasks.isEmpty())
        assertNull(decoded.provenanceGraph)
    }

    @Test
    fun testSerializationRoundTripWithSubtasks() {
        val subtask = WorkflowSubtask(
            schemaVersion = "1.0",
            subtaskId = "subtask_1",
            label = "SUBTASK_1_SEARCH",
            stepIds = listOf("step_1", "step_2"),
            preconditions = Preconditions(fromState = "INIT"),
            expectedOutcome = ExpectedTransition(toState = "RESULTS_DISPLAYED"),
            confidence = 0.95
        )

        val serialized = json.encodeToString(WorkflowSubtask.serializer(), subtask)
        val decoded = json.decodeFromString(WorkflowSubtask.serializer(), serialized)

        assertEquals("subtask_1", decoded.subtaskId)
        assertEquals("SUBTASK_1_SEARCH", decoded.label)
        assertEquals(listOf("step_1", "step_2"), decoded.stepIds)
        assertEquals(0.95, decoded.confidence, 0.001)
    }
}
