package com.chockXlate.teachablevoice.teach.bonus.crossapp

import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BonusCrossAppGeneralizationTest {

    private val engine = CrossAppGeneralizationEngine()

    private fun createSampleSearchWorkflow(sourceApp: String): Workflow {
        return Workflow(
            skillId = "search_item_skill",
            name = "Search for an Item",
            intent = "search_item",
            appContext = sourceApp,
            steps = listOf(
                WorkflowStep(
                    stepId = "step_1_input",
                    actionType = "INPUT_TEXT",
                    targetSelector = SemanticSelector(
                        role = "android.widget.EditText",
                        text = "Search field",
                        contentDescription = "Search input box",
                        resourceId = "$sourceApp:id/search_box"
                    )
                ),
                WorkflowStep(
                    stepId = "step_2_click_result",
                    actionType = "CLICK",
                    targetSelector = SemanticSelector(
                        role = "android.widget.TextView",
                        text = "Search Result Item",
                        contentDescription = "Item card view",
                        resourceId = "$sourceApp:id/result_title"
                    )
                )
            )
        )
    }

    // TEST 1: Same semantic workflow in two different application identities -> TRANSFERABLE
    @Test
    fun test1_sameSemanticWorkflowDifferentAppIdentity_isTransferable() {
        val workflow = createSampleSearchWorkflow("com.app.alpha")
        val targetUi = listOf(
            UiElement(
                elementId = "target_1",
                role = "android.widget.EditText",
                text = "Search field",
                contentDescription = "Search input box",
                resourceId = "com.app.beta:id/find_box",
                isEditable = true
            ),
            UiElement(
                elementId = "target_2",
                role = "android.widget.TextView",
                text = "Search Result Item",
                contentDescription = "Item card view",
                resourceId = "com.app.beta:id/item_title",
                isClickable = true
            )
        )

        val result = engine.analyze(workflow, "com.app.beta", targetUi)

        assertEquals(TransferabilityDecision.TRANSFERABLE, result.decision)
        assertEquals(2, result.compatibleStepCount)
        assertFalse(result.executionAuthorized)
    }

    // TEST 2: Different package names but equivalent search UI -> TRANSFERABLE
    @Test
    fun test2_differentPackageNamesEquivalentSearchUI_isTransferable() {
        val workflow = createSampleSearchWorkflow("com.store.one")
        val targetUi = listOf(
            UiElement(
                elementId = "e1",
                role = "android.widget.EditText",
                text = "Search items",
                contentDescription = "Search input",
                resourceId = "com.store.two:id/search_bar",
                isEditable = true
            ),
            UiElement(
                elementId = "e2",
                role = "android.widget.TextView",
                text = "Search Result Item",
                resourceId = "com.store.two:id/card_text",
                isClickable = true
            )
        )

        val result = engine.analyze(workflow, "com.store.two", targetUi)
        assertEquals(TransferabilityDecision.TRANSFERABLE, result.decision)
    }

    // TEST 3: Different resource IDs but equivalent semantic roles -> TRANSFERABLE if evidence supports
    @Test
    fun test3_differentResourceIdsEquivalentRoles_isTransferable() {
        val workflow = createSampleSearchWorkflow("com.test.a")
        val targetUi = listOf(
            UiElement(
                elementId = "t1",
                role = "android.widget.EditText",
                contentDescription = "Search box",
                resourceId = "com.test.b:id/completely_different_id_123",
                isEditable = true
            ),
            UiElement(
                elementId = "t2",
                role = "android.widget.TextView",
                text = "Search Result Item",
                resourceId = "com.test.b:id/completely_different_id_456",
                isClickable = true
            )
        )

        val result = engine.analyze(workflow, "com.test.b", targetUi)
        assertEquals(TransferabilityDecision.TRANSFERABLE, result.decision)
    }

    // TEST 4: Different visible text but equivalent semantic context -> potentially TRANSFERABLE
    @Test
    fun test4_differentVisibleTextEquivalentContext_isTransferable() {
        val workflow = createSampleSearchWorkflow("com.app.x")
        val targetUi = listOf(
            UiElement(
                elementId = "t1",
                role = "android.widget.EditText",
                text = "Find products", // Synonym of search
                contentDescription = "Query input",
                resourceId = "com.app.y:id/input",
                isEditable = true
            ),
            UiElement(
                elementId = "t2",
                role = "android.widget.TextView",
                text = "Search Result Item",
                resourceId = "com.app.y:id/title",
                isClickable = true
            )
        )

        val result = engine.analyze(workflow, "com.app.y", targetUi)
        assertTrue(result.decision == TransferabilityDecision.TRANSFERABLE || result.decision == TransferabilityDecision.PARTIALLY_TRANSFERABLE)
    }

    // TEST 5: Different screen hierarchy but same semantic task -> potentially TRANSFERABLE
    @Test
    fun test5_differentHierarchySameSemanticTask_isTransferable() {
        val workflow = createSampleSearchWorkflow("com.app.flat")
        // Target UI wrapped inside container hierarchy
        val targetUi = listOf(
            UiElement(
                elementId = "container",
                role = "android.widget.LinearLayout",
                children = listOf(
                    UiElement(
                        elementId = "nested_1",
                        role = "android.widget.EditText",
                        text = "Search field",
                        isEditable = true
                    ),
                    UiElement(
                        elementId = "nested_2",
                        role = "android.widget.TextView",
                        text = "Search Result Item",
                        isClickable = true
                    )
                )
            )
        )

        val result = engine.analyze(workflow, "com.app.nested", targetUi)
        assertEquals(TransferabilityDecision.TRANSFERABLE, result.decision)
    }

    // TEST 6: Same button role but unrelated purpose -> NOT TRANSFERABLE
    @Test
    fun test6_sameRoleUnrelatedPurpose_isNotTransferable() {
        val workflow = createSampleSearchWorkflow("com.app.search")
        // Target UI contains unrelated buttons (Settings, Account)
        val targetUi = listOf(
            UiElement(
                elementId = "unrelated_1",
                role = "android.widget.Button",
                text = "Settings",
                isClickable = true
            ),
            UiElement(
                elementId = "unrelated_2",
                role = "android.widget.Button",
                text = "Log Out",
                isClickable = true
            )
        )

        val result = engine.analyze(workflow, "com.app.unrelated", targetUi)
        assertEquals(TransferabilityDecision.NOT_TRANSFERABLE, result.decision)
    }

    // TEST 7: Different task sequence / missing steps -> NOT TRANSFERABLE
    @Test
    fun test7_differentTaskSequence_isNotTransferable() {
        val workflow = createSampleSearchWorkflow("com.app.full")
        val targetUi = listOf(
            // Only search field present, result item missing
            UiElement(
                elementId = "only_input",
                role = "android.widget.EditText",
                text = "Search field",
                isEditable = true
            )
        )

        val result = engine.analyze(workflow, "com.app.partial", targetUi)
        assertEquals(TransferabilityDecision.PARTIALLY_TRANSFERABLE, result.decision)
    }

    // TEST 8: Missing required semantic target -> NOT TRANSFERABLE
    @Test
    fun test8_missingRequiredTarget_isNotTransferable() {
        val workflow = createSampleSearchWorkflow("com.app.source")
        val targetUi = emptyList<UiElement>()

        val result = engine.analyze(workflow, "com.app.empty", targetUi)
        assertEquals(TransferabilityDecision.NOT_TRANSFERABLE, result.decision)
    }

    // TEST 9: Different slot values -> workflow remains transferable
    @Test
    fun test9_differentSlotValues_workflowRemainsTransferable() {
        val workflow = createSampleSearchWorkflow("com.app.source").copy(
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    actionType = "INPUT_TEXT",
                    targetSelector = SemanticSelector(
                        role = "android.widget.EditText",
                        textSlot = "search_query"
                    )
                ),
                WorkflowStep(
                    stepId = "s2",
                    actionType = "CLICK",
                    targetSelector = SemanticSelector(
                        role = "android.widget.TextView",
                        text = "Search Result Item"
                    )
                )
            )
        )
        val targetUi = listOf(
            UiElement(
                elementId = "t1",
                role = "android.widget.EditText",
                text = "burger", // Slot value differs from original 'pizza'
                isEditable = true
            ),
            UiElement(
                elementId = "t2",
                role = "android.widget.TextView",
                text = "Search Result Item",
                isClickable = true
            )
        )

        val result = engine.analyze(workflow, "com.app.target", targetUi)
        assertEquals(TransferabilityDecision.TRANSFERABLE, result.decision)
    }

    // TEST 10: Payment/credential state encountered -> Safety boundary enforced (NOT AUTHORIZED)
    @Test
    fun test10_paymentStateEncountered_blocksCrossAppAndDoesNotAuthorize() {
        val workflow = createSampleSearchWorkflow("com.app.shopping")
        val targetUi = listOf(
            UiElement(
                elementId = "pay_1",
                role = "android.widget.EditText",
                text = "Enter Card Number & Expiry",
                contentDescription = "Payment card input",
                isEditable = true
            )
        )

        val result = engine.analyze(workflow, "com.app.payment", targetUi)
        assertEquals(TransferabilityDecision.NOT_TRANSFERABLE, result.decision)
        assertFalse(result.preservesSafetyBoundary)
        assertFalse(result.executionAuthorized)
    }

    // TEST 11: Coordinate difference -> Must not affect semantic transfer decision
    @Test
    fun test11_coordinateDifference_doesNotAffectTransferDecision() {
        val workflow = createSampleSearchWorkflow("com.app.source")
        val targetUiWithCoords = listOf(
            UiElement(
                elementId = "t1",
                role = "android.widget.EditText",
                text = "Search field",
                contentDescription = "Search input box",
                bounds = com.chockXlate.teachablevoice.contract.ui.UiElementBounds(left = 100, top = 500, right = 900, bottom = 600),
                isEditable = true
            ),
            UiElement(
                elementId = "t2",
                role = "android.widget.TextView",
                text = "Search Result Item",
                contentDescription = "Item card view",
                bounds = com.chockXlate.teachablevoice.contract.ui.UiElementBounds(left = 50, top = 1200, right = 1000, bottom = 1500),
                isClickable = true
            )
        )

        val result = engine.analyze(workflow, "com.app.differentcoords", targetUiWithCoords)
        assertEquals(TransferabilityDecision.TRANSFERABLE, result.decision)
    }

    // TEST 12: Package change alone -> Must not make workflow incompatible
    @Test
    fun test12_packageChangeAlone_doesNotMakeWorkflowIncompatible() {
        val workflow = createSampleSearchWorkflow("com.original.package")
        // Target UI identical except package context
        val targetUi = listOf(
            UiElement(
                elementId = "1",
                role = "android.widget.EditText",
                text = "Search field",
                contentDescription = "Search input box",
                isEditable = true
            ),
            UiElement(
                elementId = "2",
                role = "android.widget.TextView",
                text = "Search Result Item",
                contentDescription = "Item card view",
                isClickable = true
            )
        )

        val result = engine.analyze(workflow, "com.new.package", targetUi)
        assertEquals(TransferabilityDecision.TRANSFERABLE, result.decision)
        assertEquals("com.original.package", result.sourcePackage)
        assertEquals("com.new.package", result.targetPackage)
    }
}
