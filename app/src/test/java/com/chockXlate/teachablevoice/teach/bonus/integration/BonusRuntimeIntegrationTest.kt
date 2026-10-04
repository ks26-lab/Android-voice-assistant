package com.chockXlate.teachablevoice.teach.bonus.integration

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import com.chockXlate.teachablevoice.teach.bonus.clarification.ClarificationState
import com.chockXlate.teachablevoice.teach.bonus.crossapp.TransferabilityDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BonusRuntimeIntegrationTest {

    private val coordinator = BonusRuntimeCoordinator()

    // 1. FLOW A: REAL DEMONSTRATION TRACE -> IRRELEVANT ACTION FILTERING
    @Test
    fun testFlowA_realDemonstrationTrace_processedByBonus1Filter() {
        val realTrace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "real_trace_101",
            appContext = "com.real.app",
            userActions = listOf(
                ActionEvent(actionId = "a1", actionType = "CLICK", semanticSelector = SemanticSelector(text = "Search")),
                ActionEvent(actionId = "a2", actionType = "FOCUS", semanticSelector = SemanticSelector(role = "View")), // Irrelevant focus
                ActionEvent(actionId = "a3", actionType = "INPUT_TEXT", inputData = "real_query", semanticSelector = SemanticSelector(textSlot = "query"))
            )
        )

        val filteredResult = coordinator.processCapturedTeachingTrace(realTrace)

        assertNotNull(filteredResult)
        assertEquals("real_trace_101", filteredResult!!.originalSessionId)
        assertEquals(3, filteredResult.originalEventCount)
        assertEquals(2, filteredResult.retainedEventCount) // Action a2 filtered
        assertEquals(1, filteredResult.removedEventCount)

        val events = coordinator.getDiagnosticEvents()
        assertTrue(events.any { it.bonusName == "Bonus1_Filtering" && it.stage == "TEACHING_COMPLETED" })
    }

    // 2. FLOW B: REAL UI OBSERVATION -> CROSS-APP GENERALIZATION
    @Test
    fun testFlowB_realUiObservation_evaluatedByBonus2Engine() {
        val sourceWorkflow = Workflow(
            skillId = "real_workflow_202",
            name = "Real Search",
            intent = "search",
            appContext = "com.app.alpha",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    actionType = "INPUT_TEXT",
                    targetSelector = SemanticSelector(role = "android.widget.EditText", text = "Search catalog")
                )
            )
        )

        val realTargetUi = listOf(
            UiElement(
                elementId = "target_e1",
                role = "android.widget.EditText",
                text = "Find items", // Real synonym
                isEditable = true
            )
        )

        val crossAppResult = coordinator.evaluateCrossAppGeneralization(sourceWorkflow, "com.app.beta", realTargetUi)

        assertNotNull(crossAppResult)
        assertEquals("com.app.alpha", crossAppResult!!.sourcePackage)
        assertEquals("com.app.beta", crossAppResult.targetPackage)
        assertEquals(TransferabilityDecision.TRANSFERABLE, crossAppResult.decision)

        val events = coordinator.getDiagnosticEvents()
        assertTrue(events.any { it.bonusName == "Bonus2_CrossApp" && it.stage == "CROSS_APP_ANALYSIS" })
    }

    // 3. FLOW C: REAL EXECUTION -> MID-FLOW CLARIFICATION & RESUME
    @Test
    fun testFlowC_realMissingSlot_triggersClarificationAndResumesWithUserResponse() {
        val realWorkflow = Workflow(
            skillId = "real_workflow_303",
            name = "Real Workflow",
            intent = "real_intent",
            appContext = "com.app.real",
            slots = listOf(
                WorkflowSlot(name = "user_param", type = SlotType.TEXT, required = true, provenance = "user_input")
            ),
            steps = listOf(
                WorkflowStep(stepId = "step_0", actionType = "CLICK", targetSelector = SemanticSelector(text = "Start")),
                WorkflowStep(stepId = "step_1", actionType = "INPUT_TEXT", targetSelector = SemanticSelector(textSlot = "user_param"))
            )
        )

        // 1. Start execution missing required slot 'user_param' at step index 1
        val pauseResult = coordinator.evaluateExecutionForMissingSlots(realWorkflow, suppliedSlots = emptyMap(), startStepIndex = 1)

        assertNotNull(pauseResult)
        assertEquals(ClarificationState.WAITING_FOR_CLARIFICATION, pauseResult!!.finalState)

        val request = coordinator.getPendingClarificationRequest()
        assertNotNull(request)
        assertEquals("user_param", request!!.missingSlotName)
        assertEquals(1, request.resumePoint.pausedStepIndex)

        // 2. Real user inputs "dynamic_user_value"
        val resumeResult = coordinator.submitUserClarificationResponse(realWorkflow, "dynamic_user_value")

        assertNotNull(resumeResult)
        assertEquals(ClarificationState.RESUMING, resumeResult!!.finalState)
        assertEquals(1, resumeResult.resumedFromStepIndex) // Step 1 preserved
        assertEquals("dynamic_user_value", resumeResult.resolvedSlots["user_param"])

        val events = coordinator.getDiagnosticEvents()
        assertTrue(events.any { it.bonusName == "Bonus3_Clarification" && it.stage == "USER_RESPONSE_PROCESSED" })
    }

    // 4. HARD INTEGRATION TEST — DIFFERENT INPUT VALUES
    @Test
    fun testHardIntegration_differentInputs_behavesDynamicallyWithoutHardcoding() {
        val workflow = Workflow(
            skillId = "dynamic_skill",
            name = "Dynamic Skill",
            intent = "dynamic_intent",
            appContext = "com.dynamic.app",
            slots = listOf(
                WorkflowSlot(name = "dynamic_slot", type = SlotType.TEXT, required = true, provenance = "user_input")
            ),
            steps = listOf(
                WorkflowStep(stepId = "s1", actionType = "INPUT_TEXT", targetSelector = SemanticSelector(textSlot = "dynamic_slot"))
            )
        )

        // Run 1: Input "Input_Alpha_123"
        coordinator.evaluateExecutionForMissingSlots(workflow, emptyMap(), 0)
        val res1 = coordinator.submitUserClarificationResponse(workflow, "Input_Alpha_123")
        assertEquals("Input_Alpha_123", res1!!.resolvedSlots["dynamic_slot"])

        // Run 2: Input "Input_Beta_999"
        coordinator.evaluateExecutionForMissingSlots(workflow, emptyMap(), 0)
        val res2 = coordinator.submitUserClarificationResponse(workflow, "Input_Beta_999")
        assertEquals("Input_Beta_999", res2!!.resolvedSlots["dynamic_slot"])
    }
}
