package com.chockXlate.teachablevoice.teach.bonus.clarification

import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BonusMidFlowClarificationTest {

    private fun createSampleWorkflow(): Workflow {
        return Workflow(
            skillId = "skill_search_item",
            name = "Search Item Workflow",
            intent = "search_item",
            appContext = "com.example.app",
            slots = listOf(
                WorkflowSlot(
                    name = "item_query",
                    type = SlotType.TEXT,
                    required = true,
                    provenance = "user_input"
                )
            ),
            steps = listOf(
                WorkflowStep(
                    stepId = "step_0_open_search",
                    actionType = "CLICK",
                    targetSelector = SemanticSelector(role = "android.widget.Button", text = "Search Icon")
                ),
                WorkflowStep(
                    stepId = "step_1_input_query",
                    actionType = "INPUT_TEXT",
                    targetSelector = SemanticSelector(role = "android.widget.EditText", textSlot = "item_query")
                )
            )
        )
    }

    // TEST 1: Required slot missing -> Clarification requested
    @Test
    fun test1_requiredSlotMissing_clarificationRequested() {
        val controller = MidFlowClarificationController()
        val workflow = createSampleWorkflow()

        val result = controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 1)

        assertEquals(ClarificationState.WAITING_FOR_CLARIFICATION, result.finalState)
        assertNotNull(controller.getActiveRequest())
        assertEquals("item_query", controller.getActiveRequest()?.missingSlotName)
    }

    // TEST 2: User supplies valid value -> Slot validated
    @Test
    fun test2_userSuppliesValidValue_slotValidated() {
        val controller = MidFlowClarificationController()
        val workflow = createSampleWorkflow()
        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 1)

        val result = controller.submitUserResponse(workflow, "wireless headphones")

        assertEquals(ClarificationState.RESUMING, result.finalState)
        assertEquals("wireless headphones", controller.getCurrentBoundSlots()["item_query"])
    }

    // TEST 3 & 4: Valid value -> Resumes from saved point without restarting from step 0
    @Test
    fun test3and4_validValue_resumesFromSavedPointWithoutRestarting() {
        val controller = MidFlowClarificationController()
        val workflow = createSampleWorkflow()
        // Paused at step index 1
        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 1)

        val result = controller.submitUserResponse(workflow, "wireless headphones")

        assertEquals(ClarificationState.RESUMING, result.finalState)
        assertEquals(1, result.resumedFromStepIndex) // Step 1 preserved, step 0 NOT repeated
        assertEquals(1, result.totalStepsCompleted)
    }

    // TEST 5: Empty response -> Clarification remains pending
    @Test
    fun test5_emptyResponse_remainsPending() {
        val controller = MidFlowClarificationController()
        val workflow = createSampleWorkflow()
        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 1)

        val result = controller.submitUserResponse(workflow, "   ")

        assertEquals(ClarificationState.WAITING_FOR_CLARIFICATION, result.finalState)
        assertNotNull(controller.getActiveRequest())
    }

    // TEST 6: Invalid response type -> Clarification remains pending
    @Test
    fun test6_invalidResponseType_remainsPending() {
        val controller = MidFlowClarificationController()
        val workflow = createSampleWorkflow().copy(
            slots = listOf(
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, provenance = "user_input")
            )
        )
        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 1)

        val result = controller.submitUserResponse(workflow, "not_a_number")

        assertEquals(ClarificationState.WAITING_FOR_CLARIFICATION, result.finalState)
    }

    // TEST 7: Multiple missing slots -> Asks one at a time
    @Test
    fun test7_multipleMissingSlots_asksOneAtATime() {
        val controller = MidFlowClarificationController()
        val workflow = createSampleWorkflow().copy(
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, provenance = "user_input"),
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, provenance = "user_input")
            )
        )

        val res1 = controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 1)
        assertEquals(ClarificationState.WAITING_FOR_CLARIFICATION, res1.finalState)
        assertEquals("item", controller.getActiveRequest()?.missingSlotName)

        val res2 = controller.submitUserResponse(workflow, "pizza")
        assertEquals(ClarificationState.WAITING_FOR_CLARIFICATION, res2.finalState)
        assertEquals("quantity", controller.getActiveRequest()?.missingSlotName)

        val res3 = controller.submitUserResponse(workflow, "2")
        assertEquals(ClarificationState.RESUMING, res3.finalState)
        assertEquals("pizza", controller.getCurrentBoundSlots()["item"])
        assertEquals("2", controller.getCurrentBoundSlots()["quantity"])
    }

    // TEST 8: User cancels -> CANCELLED
    @Test
    fun test8_userCancels_returnsCancelled() {
        val controller = MidFlowClarificationController()
        val workflow = createSampleWorkflow()
        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 1)

        val result = controller.submitUserResponse(workflow, "cancel")

        assertEquals(ClarificationState.CANCELLED, result.finalState)
    }

    // TEST 9: No response -> Remains in WAITING_FOR_CLARIFICATION state
    @Test
    fun test9_noResponse_remainsWaiting() {
        val controller = MidFlowClarificationController()
        val workflow = createSampleWorkflow()
        val result = controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 1)

        assertEquals(ClarificationState.WAITING_FOR_CLARIFICATION, controller.getCurrentState())
        assertEquals(ClarificationState.WAITING_FOR_CLARIFICATION, result.finalState)
    }

    // TEST 10 & 11: Credential/OTP response -> Safety blocked
    @Test
    fun test10and11_credentialOrOtpResponse_blockedBySafety() {
        val controller = MidFlowClarificationController()
        val workflow = createSampleWorkflow()
        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 1)

        val result = controller.submitUserResponse(workflow, "My secret password is 1234")

        assertEquals(ClarificationState.BLOCKED_SAFETY, result.finalState)
    }

    // TEST 12: After clarification -> Requires UI re-observation before continuation
    @Test
    fun test12_afterClarification_requiresUiReobservation() {
        val controller = MidFlowClarificationController()
        val workflow = createSampleWorkflow()
        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 1)

        val result = controller.submitUserResponse(workflow, "headphones")

        assertEquals(ClarificationState.RESUMING, result.finalState)
        assertTrue(result.requiresUiReobservation)
    }

    // TEST 13: Same request processed twice -> Deterministic output
    @Test
    fun test13_sameRequestProcessedTwice_deterministicOutput() {
        val controller = MidFlowClarificationController()
        val workflow = createSampleWorkflow()
        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 1)

        val res1 = controller.submitUserResponse(workflow, "item_a")
        assertEquals(ClarificationState.RESUMING, res1.finalState)

        val res2 = controller.submitUserResponse(workflow, "item_a")
        assertEquals(ClarificationState.FAILED, res2.finalState) // Second attempt correctly rejected as no active request
    }

    // TEST 14: Resume point preserves workflow step ordering
    @Test
    fun test14_resumePointPreservesStepOrdering() {
        val controller = MidFlowClarificationController()
        val workflow = createSampleWorkflow()
        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 1)

        val req = controller.getActiveRequest()
        assertNotNull(req)
        assertEquals(1, req!!.resumePoint.pausedStepIndex)
        assertEquals("step_1_input_query", req.resumePoint.pausedStepId)
        assertEquals(listOf("step_0_open_search"), req.resumePoint.completedStepIds)
    }
}
