package com.chockXlate.teachablevoice.teach.bonus.clarification

import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Test

class AdversarialMidFlowClarificationTest {

    private val controller = MidFlowClarificationController()

    private fun createWorkflow(): Workflow {
        return Workflow(
            skillId = "adv_workflow",
            name = "Adversarial Workflow",
            intent = "adv_intent",
            appContext = "com.adv.app",
            slots = listOf(
                WorkflowSlot(name = "item_name", type = SlotType.TEXT, required = true, provenance = "user_input")
            ),
            steps = listOf(
                WorkflowStep(
                    stepId = "s0",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "android.widget.EditText", textSlot = "item_name")
                )
            )
        )
    }

    @Test
    fun testAdversarial_vagueOrEmptyAnswer_remainsPending() {
        val workflow = createWorkflow()
        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 0)

        val res = controller.submitUserResponse(workflow, "")
        assertEquals(ClarificationState.WAITING_FOR_CLARIFICATION, res.finalState)
    }

    @Test
    fun testAdversarial_userSaysStop_returnsCancelled() {
        val workflow = createWorkflow()
        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 0)

        val res = controller.submitUserResponse(workflow, "stop")
        assertEquals(ClarificationState.CANCELLED, res.finalState)
    }

    @Test
    fun testAdversarial_credentialInjectedInResponse_blocksSafety() {
        val workflow = createWorkflow()
        controller.startWorkflowExecution(workflow, suppliedSlots = emptyMap(), startStepIndex = 0)

        val res = controller.submitUserResponse(workflow, "My OTP code is 987654")
        assertEquals(ClarificationState.BLOCKED_SAFETY, res.finalState)
    }
}
