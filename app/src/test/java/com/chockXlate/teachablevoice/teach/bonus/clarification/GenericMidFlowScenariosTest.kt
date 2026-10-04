package com.chockXlate.teachablevoice.teach.bonus.clarification

import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericMidFlowScenariosTest {

    private val controller = MidFlowClarificationController()

    @Test
    fun testGenericEndToEndScenario_missingSlotPromptAndResume() {
        val workflow = Workflow(
            skillId = "e2e_search_skill",
            name = "Search Item Workflow",
            intent = "search_item",
            appContext = "com.generic.app",
            slots = listOf(
                WorkflowSlot(name = "item_query", type = SlotType.TEXT, required = true, provenance = "user_input")
            ),
            steps = listOf(
                WorkflowStep(
                    stepId = "step_0_open",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "android.widget.Button", text = "Search Icon")
                ),
                WorkflowStep(
                    stepId = "step_1_input",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "android.widget.EditText", textSlot = "item_query")
                ),
                WorkflowStep(
                    stepId = "step_2_select",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "android.widget.TextView", text = "Result Item")
                )
            )
        )

        // Step 1: Start execution. Step 0 completes, Step 1 requires missing 'item_query'
        val startResult = controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 1)
        assertEquals(ClarificationState.WAITING_FOR_CLARIFICATION, startResult.finalState)

        val activeRequest = controller.getActiveRequest()
        assertEquals("item_query", activeRequest?.missingSlotName)
        assertEquals(1, activeRequest?.resumePoint?.pausedStepIndex)
        assertEquals(listOf("step_0_open"), activeRequest?.resumePoint?.completedStepIds)

        // Step 2: User responds with "wireless headphones"
        val resumeResult = controller.submitUserResponse(workflow, "wireless headphones")

        assertEquals(ClarificationState.RESUMING, resumeResult.finalState)
        assertEquals("wireless headphones", controller.getCurrentBoundSlots()["item_query"])
        assertEquals(1, resumeResult.resumedFromStepIndex) // Does NOT restart from step 0
        assertTrue(resumeResult.requiresUiReobservation)
    }

    @Test
    fun testCancellationScenario_userSaysCancel_stopsExecution() {
        val workflow = Workflow(
            skillId = "cancel_skill",
            name = "Cancel Scenario",
            intent = "cancel_intent",
            appContext = "com.generic.app",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, provenance = "user_input")
            ),
            steps = listOf(
                WorkflowStep(stepId = "s1", semanticAction = "INPUT_TEXT", semanticSelector = SemanticSelector(textSlot = "item"))
            )
        )

        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 0)
        val result = controller.submitUserResponse(workflow, "cancel")

        assertEquals(ClarificationState.CANCELLED, result.finalState)
    }

    @Test
    fun testSafetyScenario_userProvidesCredential_blocksAndDoesNotInject() {
        val workflow = Workflow(
            skillId = "safety_skill",
            name = "Safety Scenario",
            intent = "safety_intent",
            appContext = "com.generic.app",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, provenance = "user_input")
            ),
            steps = listOf(
                WorkflowStep(stepId = "s1", semanticAction = "INPUT_TEXT", semanticSelector = SemanticSelector(textSlot = "item"))
            )
        )

        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 0)
        val result = controller.submitUserResponse(workflow, "Enter password 1234")

        assertEquals(ClarificationState.BLOCKED_SAFETY, result.finalState)
        assertFalse(controller.getCurrentBoundSlots().containsKey("item"))
    }
}
