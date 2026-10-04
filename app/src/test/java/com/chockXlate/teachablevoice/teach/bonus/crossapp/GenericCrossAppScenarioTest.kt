package com.chockXlate.teachablevoice.teach.bonus.crossapp

import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericCrossAppScenarioTest {

    private val engine = CrossAppGeneralizationEngine()

    @Test
    fun testGenericCrossAppTransfer_SourceAppAToTargetAppB_producesTransferable() {
        // SOURCE APPLICATION: App A
        val sourceWorkflow = Workflow(
            skillId = "generic_search_select_skill",
            name = "Search for item and select result",
            intent = "search_and_select",
            appContext = "com.generic.appA",
            steps = listOf(
                WorkflowStep(
                    stepId = "step_1_search_input",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(
                        role = "android.widget.EditText",
                        text = "Search catalog",
                        contentDescription = "Search bar",
                        resourceId = "com.generic.appA:id/src_input"
                    )
                ),
                WorkflowStep(
                    stepId = "step_2_select_result",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(
                        role = "android.widget.TextView",
                        text = "Result Item Title",
                        contentDescription = "Card item",
                        resourceId = "com.generic.appA:id/src_item_title"
                    )
                )
            )
        )

        // TARGET APPLICATION: App B (Different package name, resource IDs, wording, hierarchy)
        val targetUiElementsAppB = listOf(
            UiElement(
                elementId = "appB_container",
                role = "android.widget.FrameLayout",
                children = listOf(
                    UiElement(
                        elementId = "appB_search_input",
                        role = "android.widget.EditText",
                        text = "Find products", // Synonym of search catalog
                        contentDescription = "Search input field",
                        resourceId = "com.generic.appB:id/target_find_edittext",
                        isEditable = true
                    ),
                    UiElement(
                        elementId = "appB_item_card",
                        role = "android.widget.TextView",
                        text = "Result Item Title",
                        contentDescription = "Item entry view",
                        resourceId = "com.generic.appB:id/target_item_card_heading",
                        isClickable = true
                    )
                )
            )
        )

        val result = engine.analyze(sourceWorkflow, "com.generic.appB", targetUiElementsAppB)

        assertEquals("com.generic.appA", result.sourcePackage)
        assertEquals("com.generic.appB", result.targetPackage)
        assertEquals(TransferabilityDecision.TRANSFERABLE, result.decision)
        assertEquals(2, result.compatibleStepCount)
        assertTrue(result.overallConfidence >= 0.70)
        assertTrue(result.preservesSafetyBoundary)
        assertFalse(result.executionAuthorized)
    }

    @Test
    fun testNonTransferableScenario_TargetIsPaymentScreen_producesNotTransferable() {
        val sourceWorkflow = Workflow(
            skillId = "generic_search_select_skill",
            name = "Search for item and select result",
            intent = "search_and_select",
            appContext = "com.generic.appA",
            steps = listOf(
                WorkflowStep(
                    stepId = "step_1_search_input",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "android.widget.EditText", text = "Search catalog")
                )
            )
        )

        // TARGET APPLICATION: Payment Screen
        val targetUiPaymentScreen = listOf(
            UiElement(
                elementId = "payment_root",
                role = "android.widget.LinearLayout",
                children = listOf(
                    UiElement(
                        elementId = "card_num",
                        role = "android.widget.EditText",
                        text = "Enter Card Number",
                        contentDescription = "Payment credit card details",
                        isEditable = true
                    ),
                    UiElement(
                        elementId = "upi_pin",
                        role = "android.widget.EditText",
                        text = "UPI PIN",
                        contentDescription = "Enter Security PIN",
                        isEditable = true
                    )
                )
            )
        )

        val result = engine.analyze(sourceWorkflow, "com.generic.paymentApp", targetUiPaymentScreen)

        assertEquals(TransferabilityDecision.NOT_TRANSFERABLE, result.decision)
        assertFalse(result.preservesSafetyBoundary)
        assertFalse(result.executionAuthorized)
    }
}
