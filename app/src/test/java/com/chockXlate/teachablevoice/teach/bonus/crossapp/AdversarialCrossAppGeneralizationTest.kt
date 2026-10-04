package com.chockXlate.teachablevoice.teach.bonus.crossapp

import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdversarialCrossAppGeneralizationTest {

    private val engine = CrossAppGeneralizationEngine()

    private fun createWorkflow(): Workflow {
        return Workflow(
            skillId = "skill_search",
            name = "Search Workflow",
            intent = "search",
            appContext = "com.source.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "step_1",
                    actionType = "INPUT_TEXT",
                    targetSelector = SemanticSelector(
                        role = "android.widget.EditText",
                        text = "Search product",
                        contentDescription = "Search bar input"
                    )
                ),
                WorkflowStep(
                    stepId = "step_2",
                    actionType = "CLICK",
                    targetSelector = SemanticSelector(
                        role = "android.widget.Button",
                        text = "Submit Search",
                        contentDescription = "Go button"
                    )
                )
            )
        )
    }

    @Test
    fun testAdversarial_sameButtonRoleDifferentPurpose_returnsNotTransferable() {
        val workflow = createWorkflow()
        val targetUi = listOf(
            UiElement(
                elementId = "b1",
                role = "android.widget.Button",
                text = "Delete Account",
                isClickable = true
            ),
            UiElement(
                elementId = "b2",
                role = "android.widget.Button",
                text = "Cancel Subscription",
                isClickable = true
            )
        )

        val result = engine.analyze(workflow, "com.target.unrelated", targetUi)
        assertEquals(TransferabilityDecision.NOT_TRANSFERABLE, result.decision)
    }

    @Test
    fun testAdversarial_identicalTextDifferentPurpose_returnsNotTransferable() {
        val workflow = createWorkflow()
        // 'Submit Search' vs 'Submit Complaint'
        val targetUi = listOf(
            UiElement(
                elementId = "e1",
                role = "android.widget.EditText",
                text = "Write customer feedback / complaint",
                isEditable = true
            ),
            UiElement(
                elementId = "b1",
                role = "android.widget.Button",
                text = "Submit Complaint",
                isClickable = true
            )
        )

        val result = engine.analyze(workflow, "com.target.feedback", targetUi)
        assertTrue(result.decision == TransferabilityDecision.PARTIALLY_TRANSFERABLE || result.decision == TransferabilityDecision.NOT_TRANSFERABLE)
        assertFalse(result.executionAuthorized)
    }

    @Test
    fun testAdversarial_sensitiveCredentialScreen_returnsNotTransferableAndBlocksSafety() {
        val workflow = createWorkflow()
        val targetUi = listOf(
            UiElement(
                elementId = "pwd_field",
                role = "android.widget.EditText",
                text = "Enter Account Password & OTP",
                isEditable = true
            )
        )

        val result = engine.analyze(workflow, "com.target.auth", targetUi)
        assertEquals(TransferabilityDecision.NOT_TRANSFERABLE, result.decision)
        assertFalse(result.preservesSafetyBoundary)
        assertFalse(result.executionAuthorized)
    }
}
