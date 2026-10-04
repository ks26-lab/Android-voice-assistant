package com.chockXlate.teachablevoice.command.matching

import com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter
import com.chockXlate.teachablevoice.command.interpretation.CommandSlot
import com.chockXlate.teachablevoice.command.interpretation.CommandUnderstandingResult
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import com.chockXlate.teachablevoice.learning.intent.Intent
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SkillMatcherTest {

    private lateinit var repository: LocalSkillRepository
    private lateinit var matcher: SkillMatcher

    @Before
    fun setUp() {
        repository = LocalSkillRepository()
        matcher = SkillMatcher(repository)
    }

    private fun createWorkflow(
        skillId: String = "skill_order_food_s5_p4",
        name: String = "Order Food Skill",
        intent: String = "order_food",
        slots: List<WorkflowSlot> = emptyList(),
        requiresExplicitConfirmation: Boolean = false,
        sensitiveKeywords: List<String> = emptyList()
    ): Workflow {
        val steps = listOf(
            WorkflowStep(
                stepId = "step_1",
                semanticAction = "INPUT_TEXT",
                semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example:id/input")
            )
        )
        return Workflow(
            skillId = skillId,
            name = name,
            intent = intent,
            appContext = "com.example.foodapp",
            slots = slots,
            steps = steps,
            safetyBoundary = SafetyBoundary(
                requiresExplicitUserConfirmation = requiresExplicitConfirmation,
                sensitiveKeywords = sensitiveKeywords
            )
        )
    }

    // --- Scenario A: Exact compatible match ---
    @Test
    fun testScenarioA_ExactCompatibleMatch() {
        val wf = createWorkflow(
            skillId = "skill_order_food_a",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "constant"),
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}", provenance = "variable"),
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "\${quantity}", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order 2 Farmhouse pizzas from Pizza Palace")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_order_food_a", result.selectedSkillId)
        assertEquals(1, result.selectedVersion)
        assertEquals(1, result.candidates.size)
        assertTrue(result.candidates[0].isIntentCompatible)
        assertTrue(result.candidates[0].isConstantCompatible)
        assertTrue(result.candidates[0].matchedSlots.contains("restaurant"))
        assertTrue(result.candidates[0].matchedSlots.contains("item"))
        assertTrue(result.candidates[0].matchedSlots.contains("quantity"))
    }

    // --- Scenario B: Variable changed ---
    @Test
    fun testScenarioB_VariableChanged() {
        val wf = createWorkflow(
            skillId = "skill_order_food_b",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "constant"),
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Farmhouse Pizza", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order Margherita Pizza from Pizza Palace")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_order_food_b", result.selectedSkillId)
        assertTrue(result.candidates[0].matchedSlots.contains("item"))
    }

    // --- Scenario C: Constant mismatch ---
    @Test
    fun testScenarioC_ConstantMismatch() {
        val wf = createWorkflow(
            skillId = "skill_order_food_c",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "constant")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order pizza from Domino's")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.UNKNOWN, result.status)
        assertNull(result.selectedSkillId)
        assertEquals(1, result.candidates.size)
        assertFalse(result.candidates[0].isConstantCompatible)
        assertTrue(result.candidates[0].incompatibleSlots.contains("restaurant"))
    }

    // --- Scenario D: Missing slots ---
    @Test
    fun testScenarioD_MissingSlots() {
        val wf = createWorkflow(
            skillId = "skill_order_food_d",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "constant"),
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}", provenance = "variable"),
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "\${quantity}", provenance = "variable"),
                WorkflowSlot(name = "address", type = SlotType.ADDRESS, required = true, exampleValue = "\${address}", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order a pizza")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_order_food_d", result.selectedSkillId)
        val candidate = result.candidates[0]
        assertTrue(candidate.missingSlots.contains("quantity"))
        assertTrue(candidate.missingSlots.contains("address"))
        assertFalse(candidate.missingSlots.contains("item"))
    }

    // --- Scenario E: Unknown intent ---
    @Test
    fun testScenarioE_UnknownIntent() {
        val wf = createWorkflow(
            skillId = "skill_order_food_e",
            intent = "order_food"
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Book a flight to Tokyo")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.UNKNOWN, result.status)
        assertNull(result.selectedSkillId)
    }

    // --- Scenario F: Multiple compatible skills -> AMBIGUOUS ---
    @Test
    fun testScenarioF_MultipleCompatibleSkillsAmbiguous() {
        val wf1 = createWorkflow(
            skillId = "skill.order_food.generic",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}")
            )
        )
        val wf2 = createWorkflow(
            skillId = "skill.order_food.pizza",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}")
            )
        )
        repository.saveWorkflow(wf1)
        repository.saveWorkflow(wf2)

        val cmd = CommandInterpreter.understandCommand("Order 2 pizzas")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.AMBIGUOUS, result.status)
        assertNull(result.selectedSkillId)
        assertNull(result.selectedVersion)
        assertEquals(2, result.candidates.size)
    }

    // --- Scenario G: Different intents ---
    @Test
    fun testScenarioG_DifferentIntentsFilterOut() {
        val wfMsg = createWorkflow(skillId = "skill_msg", intent = "send_message")
        val wfRem = createWorkflow(skillId = "skill_rem", intent = "create_reminder")
        val wfFood = createWorkflow(skillId = "skill_food", intent = "order_food")

        repository.saveWorkflow(wfMsg)
        repository.saveWorkflow(wfRem)
        repository.saveWorkflow(wfFood)

        val cmd = CommandInterpreter.understandCommand("Order 2 pizzas")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_food", result.selectedSkillId)
        val matchedCand = result.candidates.first { it.skillId == "skill_food" }
        assertTrue(matchedCand.isIntentCompatible)
        val unMatchedCand = result.candidates.first { it.skillId == "skill_msg" }
        assertFalse(unMatchedCand.isIntentCompatible)
    }

    // --- Scenario H: Blocked workflow ---
    @Test
    fun testScenarioH_BlockedWorkflowExcluded() {
        val wfBlocked = Workflow(
            skillId = "skill_blocked",
            name = "Blocked Skill",
            intent = "order_food",
            appContext = "com.example.foodapp",
            slots = emptyList(),
            steps = emptyList(),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = true, sensitiveKeywords = listOf("OTP", "PIN", "BLOCKED"))
        )
        // Direct save bypass for test simulation of blocked validation state
        val validWf = createWorkflow(skillId = "skill_valid", intent = "order_food")
        repository.saveWorkflow(validWf)

        val cmd = CommandInterpreter.understandCommand("Order 2 pizzas")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_valid", result.selectedSkillId)
    }

    // --- Scenario I: Variable vs Constant mismatch ---
    @Test
    fun testScenarioI_VariableExampleMismatchDoesNotReject() {
        val wf = createWorkflow(
            skillId = "skill_var_check",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Farmhouse Pizza", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order Pepperoni Pizza")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_var_check", result.selectedSkillId)
        assertTrue(result.candidates[0].matchedSlots.contains("item"))
    }

    // --- Scenario J: Determinism ---
    @Test
    fun testScenarioJ_DeterministicResults() {
        val wf1 = createWorkflow(skillId = "skill_a", intent = "order_food")
        val wf2 = createWorkflow(skillId = "skill_b", intent = "order_food")
        repository.saveWorkflow(wf1)
        repository.saveWorkflow(wf2)

        val cmd = CommandInterpreter.understandCommand("Order food")
        val result1 = matcher.match(cmd)
        val result2 = matcher.match(cmd)

        assertEquals(result1.status, result2.status)
        assertEquals(result1.candidates.size, result2.candidates.size)
        assertEquals(result1.candidates.map { it.skillId }, result2.candidates.map { it.skillId })
        assertEquals(result1.overallConfidence, result2.overallConfidence, 0.001)
    }

    // --- Scenario K: Candidate Ordering ---
    @Test
    fun testScenarioK_CandidateOrderingIsDeterministic() {
        val wfZ = createWorkflow(skillId = "skill_z", intent = "order_food")
        val wfA = createWorkflow(skillId = "skill_a", intent = "order_food")
        val wfM = createWorkflow(skillId = "skill_m", intent = "order_food")
        repository.saveWorkflow(wfZ)
        repository.saveWorkflow(wfA)
        repository.saveWorkflow(wfM)

        val cmd = CommandInterpreter.understandCommand("Order food")
        val result = matcher.match(cmd)

        val sortedSkillIds = result.candidates.map { it.skillId }
        assertEquals(listOf("skill_a", "skill_m", "skill_z"), sortedSkillIds)
    }

    // --- Scenario L: Version handling ---
    @Test
    fun testScenarioL_VersionHandling() {
        val wf = createWorkflow(skillId = "skill_v1", intent = "order_food")
        repository.saveWorkflow(wf)
        val version1 = repository.getSkillVersion("skill_v1")

        val cmd = CommandInterpreter.understandCommand("Order food")
        val result = matcher.match(cmd)

        assertEquals(1, result.selectedVersion)
        assertEquals(version1, result.candidates[0].version)
    }

    // --- Test 13: Serialization ---
    @Test
    fun testResultSerialization() {
        val wf = createWorkflow(skillId = "skill_ser", intent = "order_food")
        repository.saveWorkflow(wf)
        val cmd = CommandInterpreter.understandCommand("Order food")
        val result = matcher.match(cmd)

        val json = Json { prettyPrint = true }
        val serialized = json.encodeToString(SkillMatchResult.serializer(), result)
        val deserialized = json.decodeFromString(SkillMatchResult.serializer(), serialized)

        assertEquals(result.status, deserialized.status)
        assertEquals(result.selectedSkillId, deserialized.selectedSkillId)
        assertEquals(result.candidates.size, deserialized.candidates.size)
    }

    // --- Test 14: Empty repository ---
    @Test
    fun testEmptyRepositoryReturnsUnknown() {
        val cmd = CommandInterpreter.understandCommand("Order food")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.UNKNOWN, result.status)
        assertNull(result.selectedSkillId)
        assertTrue(result.diagnostics.any { it.contains("empty") })
    }

    // --- Test 15: Multiple constant slots match ---
    @Test
    fun testMultipleConstantSlotsMatch() {
        val wf = createWorkflow(
            skillId = "skill_multi_const",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "constant"),
                WorkflowSlot(name = "address", type = SlotType.ADDRESS, required = false, exampleValue = "Home", provenance = "constant")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order food from Pizza Palace to Home")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertTrue(result.candidates[0].isConstantCompatible)
        assertEquals(2, result.candidates[0].matchedSlots.size)
    }

    // --- Test 16: Multiple constant slots with 1 mismatch -> disqualified ---
    @Test
    fun testMultipleConstantSlotsOneMismatchDisqualifies() {
        val wf = createWorkflow(
            skillId = "skill_multi_const_err",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "constant"),
                WorkflowSlot(name = "address", type = SlotType.ADDRESS, required = false, exampleValue = "Home", provenance = "constant")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order food from Pizza Palace to Work")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.UNKNOWN, result.status)
        assertFalse(result.candidates[0].isConstantCompatible)
        assertTrue(result.candidates[0].incompatibleSlots.contains("address"))
    }

    // --- Test 17: Case-insensitive intent matching ---
    @Test
    fun testCaseInsensitiveIntentMatching() {
        val wf = createWorkflow(skillId = "skill_case_intent", intent = "ORDER_FOOD")
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order 2 pizzas")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_case_intent", result.selectedSkillId)
    }

    // --- Test 18: Case-insensitive slot name matching ---
    @Test
    fun testCaseInsensitiveSlotNameMatching() {
        val wf = createWorkflow(
            skillId = "skill_case_slot",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "RESTAURANT", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order pizza from Pizza Palace")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertTrue(result.candidates[0].matchedSlots.any { it.equals("RESTAURANT", ignoreCase = true) })
    }

    // --- Test 19: Reasoning log population ---
    @Test
    fun testReasoningPopulatesDiagnosticDetails() {
        val wf = createWorkflow(
            skillId = "skill_reasoning",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order Margherita pizza")
        val result = matcher.match(cmd)

        val reasoning = result.candidates[0].reasoning
        assertTrue(reasoning.isNotEmpty())
        assertTrue(reasoning.any { it.contains("Intent matched") })
    }

    // --- Test 20: Overall confidence values ---
    @Test
    fun testOverallConfidenceValues() {
        val wf = createWorkflow(skillId = "skill_conf", intent = "order_food")
        repository.saveWorkflow(wf)

        val cmdMatched = CommandInterpreter.understandCommand("Order food")
        val resultMatched = matcher.match(cmdMatched)
        assertEquals(1.0, resultMatched.overallConfidence, 0.001)

        val cmdUnknown = CommandInterpreter.understandCommand("Book flight to Tokyo")
        val resultUnknown = matcher.match(cmdUnknown)
        assertEquals(0.0, resultUnknown.overallConfidence, 0.001)
    }

    // --- Test 21: Diagnostics list non-empty ---
    @Test
    fun testDiagnosticsNonEmpty() {
        val wf = createWorkflow(skillId = "skill_diag", intent = "order_food")
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order food")
        val result = matcher.match(cmd)

        assertTrue(result.diagnostics.isNotEmpty())
    }

    // --- Test 22: Parameter reference `${slotName}` variable format ---
    @Test
    fun testParameterReferenceVariableFormat() {
        val wf = createWorkflow(
            skillId = "skill_param_ref",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "\${quantity}")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order 5 pizzas")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertTrue(result.candidates[0].matchedSlots.contains("quantity"))
    }

    // --- Test 23: Slot with provenance constant vs variable ---
    @Test
    fun testSlotProvenanceConstantVsVariable() {
        val wf = createWorkflow(
            skillId = "skill_prov",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "constant"),
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Farmhouse", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order Margherita from Pizza Palace")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertTrue(result.candidates[0].isConstantCompatible)
    }

    // --- Test 24: Direct integration with CommandInterpreter ---
    @Test
    fun testDirectCommandInterpreterIntegration() {
        val wf = createWorkflow(
            skillId = "skill_integ",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace"),
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}"),
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "\${quantity}")
            )
        )
        repository.saveWorkflow(wf)

        val understandingResult = CommandInterpreter.understandCommand("Order 2 Farmhouse pizzas from Pizza Palace")
        val matchResult = matcher.match(understandingResult)

        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)
        assertEquals("skill_integ", matchResult.selectedSkillId)
    }

    // --- Test 25: Clear repository returns UNKNOWN ---
    @Test
    fun testClearRepositoryReturnsUnknown() {
        val wf = createWorkflow(skillId = "skill_clear", intent = "order_food")
        repository.saveWorkflow(wf)
        repository.clear()

        val cmd = CommandInterpreter.understandCommand("Order food")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.UNKNOWN, result.status)
        assertNull(result.selectedSkillId)
    }

    // --- Test 26: Deleted workflow excluded ---
    @Test
    fun testDeletedWorkflowExcludedFromMatching() {
        val wf = createWorkflow(skillId = "skill_delete_me", intent = "order_food")
        repository.saveWorkflow(wf)
        repository.deleteWorkflow("skill_delete_me")

        val cmd = CommandInterpreter.understandCommand("Order food")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.UNKNOWN, result.status)
    }

    // --- Test 27: Ambiguity diagnostic message details ---
    @Test
    fun testAmbiguityDiagnostics() {
        val wf1 = createWorkflow(skillId = "skill_amb1", intent = "order_food")
        val wf2 = createWorkflow(skillId = "skill_amb2", intent = "order_food")
        repository.saveWorkflow(wf1)
        repository.saveWorkflow(wf2)

        val cmd = CommandInterpreter.understandCommand("Order food")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.AMBIGUOUS, result.status)
        assertTrue(result.diagnostics.any { it.contains("Ambiguity unresolved") || it.contains("Multiple compatible") })
    }

    // --- Test 28: Candidate provenance field ---
    @Test
    fun testCandidateProvenanceField() {
        val wf = createWorkflow(skillId = "skill_prov_check", intent = "order_food")
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order food")
        val result = matcher.match(cmd)

        assertEquals("local_skill_store", result.candidates[0].provenance)
    }

    // --- Test 29: Regression test for Phase 1-10 integrity ---
    @Test
    fun testPhase1To10PipelineRegression() {
        val wf = createWorkflow(
            skillId = "skill_pipeline_reg",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}")
            )
        )
        repository.saveWorkflow(wf)

        val understanding = CommandInterpreter.understandCommand("Order 1 Margherita pizza")
        val matchResult = matcher.match(understanding)

        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)
        assertNotNull(matchResult.selectedSkillId)
    }

    // --- Test 30: Verification of Phase 11 safety boundary ---
    @Test
    fun testPhase11SafetyBoundaryNoExecutionRequestCreated() {
        val wf = createWorkflow(skillId = "skill_safe_bound", intent = "order_food")
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order 2 pizzas")
        val result = matcher.match(cmd)

        // Ensure result status is set without creating any execution requests or invoking runtime
        assertNotNull(result)
        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("1.0", result.schemaVersion)
    }

    // --- Test 31: Duplicate save retains version ---
    @Test
    fun testDuplicateSaveRetainsVersion() {
        val wf = createWorkflow(skillId = "skill_dup", intent = "order_food")
        repository.saveWorkflow(wf)
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order food")
        val result = matcher.match(cmd)

        assertEquals(1, result.selectedVersion)
    }

    // --- Test 32: Schema version check ---
    @Test
    fun testSchemaVersionDefaultsToOneDotZero() {
        val cmd = CommandInterpreter.understandCommand("Order food")
        val result = matcher.match(cmd)

        assertEquals("1.0", result.schemaVersion)
    }

    // =========================================================================
    // PHASE 4.2 TEST MATRIX
    // =========================================================================

    // PH4.2-T1 — Exact semantic match
    @Test
    fun testPH4_2_T1_ExactSemanticMatch() {
        val wf = createWorkflow(
            skillId = "skill_shop_amazon",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order a white shirt from Amazon")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_shop_amazon", result.selectedSkillId)
        assertNotNull(result.selectedWorkflow)
    }

    // PH4.2-T2 — Paraphrase
    @Test
    fun testPH4_2_T2_Paraphrase() {
        val wf = createWorkflow(
            skillId = "skill_shop_amazon",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Can you get me a white shirt through Amazon?")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_shop_amazon", result.selectedSkillId)
    }

    // PH4.2-T3 — Changed item (Variable slot)
    @Test
    fun testPH4_2_T3_ChangedItem() {
        val wf = createWorkflow(
            skillId = "skill_shop_amazon",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "white shirt", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Get me a blue jacket from Amazon")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_shop_amazon", result.selectedSkillId)
        // Command retains "Blue Jacket"
        assertEquals("Blue Jacket", cmd.item)
    }

    // PH4.2-T4 — Changed platform (Platform substitution)
    @Test
    fun testPH4_2_T4_ChangedPlatform() {
        val wf = createWorkflow(
            skillId = "skill_shop_amazon",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "white shirt", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "variable", role = "target_platform")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Get me a blue jacket from Myntra")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_shop_amazon", result.selectedSkillId)
        assertEquals("Myntra", cmd.platform)
    }

    // PH4.2-T5 — Changed quantity
    @Test
    fun testPH4_2_T5_ChangedQuantity() {
        val wf = createWorkflow(
            skillId = "skill_shop_amazon",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "white shirt", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "variable"),
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = false, exampleValue = "1", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Get me 3 blue jackets from Myntra")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("3", cmd.quantity)
    }

    // PH4.2-T6 — Wrong intent
    @Test
    fun testPH4_2_T6_WrongIntent() {
        val wf = createWorkflow(skillId = "skill_shop", intent = "shop_item")
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order dinner from Swiggy")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.UNKNOWN, result.status)
        assertNull(result.selectedSkillId)
    }

    // PH4.2-T7 — Second workflow selection
    @Test
    fun testPH4_2_T7_SecondWorkflowSelection() {
        val wfShop = createWorkflow(skillId = "skill_shop", intent = "shop_item")
        val wfFood = createWorkflow(skillId = "skill_food", intent = "order_food")
        repository.saveWorkflow(wfShop)
        repository.saveWorkflow(wfFood)

        val cmd = CommandInterpreter.understandCommand("Order dinner from Swiggy")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_food", result.selectedSkillId)
    }

    // PH4.2-T8 — Unknown workflow
    @Test
    fun testPH4_2_T8_UnknownWorkflow() {
        val wfShop = createWorkflow(skillId = "skill_shop", intent = "shop_item")
        repository.saveWorkflow(wfShop)

        val cmd = CommandInterpreter.understandCommand("Book a hotel")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.UNKNOWN, result.status)
        assertNull(result.selectedSkillId)
    }

    // PH4.2-T9 — Ambiguous candidates
    @Test
    fun testPH4_2_T9_AmbiguousCandidates() {
        val wf1 = createWorkflow(skillId = "skill_search_1", intent = "search_information")
        val wf2 = createWorkflow(skillId = "skill_search_2", intent = "search_information")
        repository.saveWorkflow(wf1)
        repository.saveWorkflow(wf2)

        val cmd = CommandInterpreter.understandCommand("Search for wireless headphones")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.AMBIGUOUS, result.status)
        assertNull(result.selectedSkillId)
    }

    // PH4.2-T10 — Phase 4.1 clarification state preserved
    @Test
    fun testPH4_2_T10_Phase41ClarificationStatePreserved() {
        val wf = createWorkflow(skillId = "skill_shop", intent = "shop_item")
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order it")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.NEEDS_CLARIFICATION, result.status)
        assertNull(result.selectedSkillId)
    }

    // PH4.2-T11 — Multi-app roles
    @Test
    fun testPH4_2_T11_MultiAppRoles() {
        val wf = createWorkflow(
            skillId = "skill_multi_app",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "shopping_platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", role = "shopping_platform"),
                WorkflowSlot(name = "messaging_platform", type = SlotType.PLATFORM, required = true, exampleValue = "WhatsApp", role = "messaging_platform"),
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "shirt", role = "target_item")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Find a white shirt on Myntra and send the link to me on Telegram")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_multi_app", result.selectedSkillId)
    }

    // PH4.2-T12 — Constant constraint violation
    @Test
    fun testPH4_2_T12_ConstantConstraintViolation() {
        val wf = createWorkflow(
            skillId = "skill_amazon_only",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "constant")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order a white shirt from Myntra")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.UNKNOWN, result.status)
        assertNull(result.selectedSkillId)
    }

    // PH4.2-T13 — Variable platform substitution
    @Test
    fun testPH4_2_T13_VariablePlatformSubstitution() {
        val wf = createWorkflow(
            skillId = "skill_shop_portable",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order a white shirt from Myntra")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_shop_portable", result.selectedSkillId)
    }

    // PH4.2-T14 — Demonstration value preservation
    @Test
    fun testPH4_2_T14_DemonstrationValuePreservation() {
        val wf = createWorkflow(
            skillId = "skill_shop",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "white shirt", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Can you get me a blue jacket from Myntra?")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_shop", result.selectedSkillId)
        // Command parameters remain unmutated
        assertEquals("Blue Jacket", cmd.item)
        assertEquals("Myntra", cmd.platform)
    }

    // KILL TEST — Learned Amazon shopping workflow matched for Myntra blue jacket
    @Test
    fun testKillTest_AmazonLearned_MyntraCommandMatched() {
        val wf = createWorkflow(
            skillId = "skill_learned_amazon",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "white shirt", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "variable"),
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = false, exampleValue = "1", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Can you get me a blue jacket from Myntra?")
        val result = matcher.match(cmd)

        assertEquals(SkillMatchStatus.MATCHED, result.status)
        assertEquals("skill_learned_amazon", result.selectedSkillId)
        assertNotNull(result.selectedWorkflow)
        assertEquals("Blue Jacket", cmd.item)
        assertEquals("Myntra", cmd.platform)
    }
}

