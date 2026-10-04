package com.chockXlate.teachablevoice.command.request

import com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter
import com.chockXlate.teachablevoice.command.interpretation.CommandSlot
import com.chockXlate.teachablevoice.command.interpretation.CommandUnderstandingResult
import com.chockXlate.teachablevoice.command.matching.SkillCandidateMatch
import com.chockXlate.teachablevoice.command.matching.SkillMatchResult
import com.chockXlate.teachablevoice.command.matching.SkillMatchStatus
import com.chockXlate.teachablevoice.command.matching.SkillMatcher
import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExecutionRequestBuilderTest {

    private lateinit var repository: LocalSkillRepository
    private lateinit var matcher: SkillMatcher

    @Before
    fun setUp() {
        repository = LocalSkillRepository()
        matcher = SkillMatcher(repository)
    }

    private fun createWorkflow(
        skillId: String = "skill_order_food_v1",
        name: String = "Order Food Skill",
        intent: String = "order_food",
        slots: List<WorkflowSlot> = emptyList(),
        requiresExplicitConfirmation: Boolean = false,
        sensitiveKeywords: List<String> = emptyList()
    ): Workflow {
        val steps = slots.mapIndexed { index, slot ->
            WorkflowStep(stepId = "step_$index", semanticAction = "INPUT_TEXT",
                semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example:id/${slot.name}",
                    textSlot = if (slot.required) slot.name else null),
                parameters = if (slot.required) emptyMap() else mapOf("input_literal" to slot.exampleValue.orEmpty()))
        }.ifEmpty { listOf(WorkflowStep(stepId = "continue", semanticAction = "CLICK",
            semanticSelector = SemanticSelector(text = "Continue"))) }
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

    // --- 1. Successful Creation: Exact Command ---
    @Test
    fun testExactCommandRequestCreated() {
        val wf = createWorkflow(
            skillId = "skill.order_food.v1",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "constant"),
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Farmhouse Pizza", provenance = "variable"),
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "\${quantity}", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order 2 Farmhouse pizzas from Pizza Palace")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository, overrideExecutionId = "exec_001")

        assertEquals(ExecutionRequestStatus.READY_FOR_PERSON_2, buildResult.status)
        val req = buildResult.executionRequest
        assertNotNull(req)
        assertEquals("exec_001", req!!.executionId)
        assertEquals("skill.order_food.v1", req.skillId)
        assertEquals("Pizza Palace", req.boundSlots["restaurant"])
        assertEquals("Farmhouse Pizza", req.boundSlots["item"])
        assertEquals("2", req.boundSlots["quantity"])
    }

    // --- 2. Paraphrased Command Request Created ---
    @Test
    fun testParaphrasedCommandRequestCreated() {
        val wf = createWorkflow(
            skillId = "skill.order_food.v1",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("I want to get a Margherita pizza")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals(ExecutionRequestStatus.READY_FOR_PERSON_2, buildResult.status)
        assertNotNull(buildResult.executionRequest)
        assertEquals("Margherita Pizza", buildResult.executionRequest?.boundSlots?.get("item"))
    }

    // --- 3. Correct Skill ID ---
    @Test
    fun testCorrectSkillIdInRequest() {
        val wf = createWorkflow(skillId = "skill_my_unique_id", intent = "order_food")
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order food")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals("skill_my_unique_id", buildResult.executionRequest?.skillId)
    }

    // --- 4. Correct Version ---
    @Test
    fun testCorrectVersionInRequest() {
        val wf = createWorkflow(skillId = "skill_ver_check", intent = "order_food")
        repository.saveWorkflow(wf)
        val ver = repository.getSkillVersion("skill_ver_check")

        val cmd = CommandInterpreter.understandCommand("Order food")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals(ver, buildResult.executionRequest?.version)
    }

    // --- 5. Correct Bound Variable Values ---
    @Test
    fun testCorrectBoundVariableValues() {
        val wf = createWorkflow(
            skillId = "skill_vars",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Teaching Example Dish")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order Pepperoni Pizza")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals("Pepperoni Pizza", buildResult.executionRequest?.boundSlots?.get("item"))
    }

    // --- 6. Constant Values Retained ---
    @Test
    fun testConstantValuesRetained() {
        val wf = createWorkflow(
            skillId = "skill_const",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "address", type = SlotType.ADDRESS, required = false, exampleValue = "Home", provenance = "constant")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order food")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals("Home", buildResult.executionRequest?.boundSlots?.get("address"))
    }

    // --- 7. Multiple Variables Bound Correctly ---
    @Test
    fun testMultipleVariablesBound() {
        val wf = createWorkflow(
            skillId = "skill_multi_vars",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}"),
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "\${quantity}")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order 3 Garlic Knots")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals("Garlic Knots", buildResult.executionRequest?.boundSlots?.get("item"))
        assertEquals("3", buildResult.executionRequest?.boundSlots?.get("quantity"))
    }

    // --- 8. Typed INTEGER Preserved ---
    @Test
    fun testTypedIntegerPreserved() {
        val wf = createWorkflow(
            skillId = "skill_int",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "\${quantity}")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order 5 pizzas")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals("5", buildResult.executionRequest?.boundSlots?.get("quantity"))
    }

    // --- 9. Typed TEXT Preserved ---
    @Test
    fun testTypedTextPreserved() {
        val wf = createWorkflow(
            skillId = "skill_text",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order Cheeseburger")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals("Cheeseburger", buildResult.executionRequest?.boundSlots?.get("item"))
    }

    // --- 10. ADDRESS Preserved ---
    @Test
    fun testAddressPreserved() {
        val wf = createWorkflow(
            skillId = "skill_addr",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "address", type = SlotType.ADDRESS, required = false, exampleValue = "123 Main St", provenance = "constant")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order food to 123 Main St")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals("123 Main St", buildResult.executionRequest?.boundSlots?.get("address"))
    }

    // --- 11. Changed Variable Does Not Use Teaching Example ---
    @Test
    fun testChangedVariableDoesNotUseTeachingExample() {
        val wf = createWorkflow(
            skillId = "skill_no_teach_ex",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Farmhouse Pizza", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order Margherita Pizza")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        val itemBound = buildResult.executionRequest?.boundSlots?.get("item")
        assertEquals("Margherita Pizza", itemBound)
        assertFalse("Farmhouse Pizza" == itemBound)
    }

    // --- 12. Constant Remains Workflow Constant ---
    @Test
    fun testConstantRemainsWorkflowConstant() {
        val wf = createWorkflow(
            skillId = "skill_const_fixed",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "constant")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order pizza")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals("Pizza Palace", buildResult.executionRequest?.boundSlots?.get("restaurant"))
    }

    // --- 13. Explicit Compatible Constant Preserved ---
    @Test
    fun testExplicitCompatibleConstantPreserved() {
        val wf = createWorkflow(
            skillId = "skill_exp_const",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "constant")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order pizza from Pizza Palace")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals("Pizza Palace", buildResult.executionRequest?.boundSlots?.get("restaurant"))
    }

    // --- 14. Conflicting Constant Rejected ---
    @Test
    fun testConflictingConstantRejected() {
        val wf = createWorkflow(
            skillId = "skill_conflict_const",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "constant")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order pizza from Domino's")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertNull(buildResult.executionRequest)
        assertTrue(buildResult.status != ExecutionRequestStatus.READY_FOR_PERSON_2)
    }

    // --- 15. Unknown Slot Not Fabricated ---
    @Test
    fun testUnknownSlotNotFabricated() {
        val wf = createWorkflow(
            skillId = "skill_no_fab",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order pizza")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        val bound = buildResult.executionRequest?.boundSlots
        assertFalse(bound?.containsKey("unknown_slot") == true)
        assertFalse(bound?.containsKey("quantity") == true)
    }

    // --- 16. Conflicting Slot Rejected ---
    @Test
    fun testConflictingSlotRejected() {
        val wf = createWorkflow(
            skillId = "skill_conf_slot",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "constant")
            )
        )
        repository.saveWorkflow(wf)

        val matchCandidate = SkillCandidateMatch(
            skillId = "skill_conf_slot",
            intent = "order_food",
            isIntentCompatible = true,
            isConstantCompatible = false,
            incompatibleSlots = listOf("restaurant")
        )
        val matchResult = SkillMatchResult(
            status = SkillMatchStatus.UNKNOWN,
            commandText = "Order pizza from Burger King",
            intent = "order_food",
            candidates = listOf(matchCandidate)
        )

        val cmd = CommandInterpreter.understandCommand("Order pizza from Burger King")
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertNull(buildResult.executionRequest)
        assertEquals(ExecutionRequestStatus.REJECTED_UNKNOWN_MATCH, buildResult.status)
    }

    // --- 17. Missing Required Variable Slot -> No Request ---
    @Test
    fun testMissingRequiredSlotNoRequestCreated() {
        val wf = createWorkflow(
            skillId = "skill_missing_req",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "\${quantity}", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order pizza")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertNull(buildResult.executionRequest)
        assertEquals(ExecutionRequestStatus.REJECTED_MISSING_REQUIRED_SLOTS, buildResult.status)
        assertTrue(buildResult.missingSlots.contains("quantity"))
        assertTrue(buildResult.rejectionReason?.contains("quantity") == true)
    }

    // --- 18. UNKNOWN Match -> No Request ---
    @Test
    fun testUnknownMatchNoRequestCreated() {
        val cmd = CommandInterpreter.understandCommand("Book flight to Tokyo")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertNull(buildResult.executionRequest)
        assertEquals(ExecutionRequestStatus.REJECTED_UNKNOWN_MATCH, buildResult.status)
    }

    // --- 19. AMBIGUOUS Match -> No Request ---
    @Test
    fun testAmbiguousMatchNoRequestCreated() {
        val wf1 = createWorkflow(skillId = "skill_amb1", intent = "order_food")
        val wf2 = createWorkflow(skillId = "skill_amb2", intent = "order_food")
        repository.saveWorkflow(wf1)
        repository.saveWorkflow(wf2)

        val cmd = CommandInterpreter.understandCommand("Order food")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertNull(buildResult.executionRequest)
        assertEquals(ExecutionRequestStatus.REJECTED_AMBIGUOUS_MATCH, buildResult.status)
        assertTrue(buildResult.rejectionReason?.contains("AMBIGUOUS") == true)
    }

    // --- 20. Missing Skill -> No Request ---
    @Test
    fun testMissingSkillInRepoNoRequestCreated() {
        val dummyMatch = SkillMatchResult(
            status = SkillMatchStatus.MATCHED,
            commandText = "Order food",
            intent = "order_food",
            selectedSkillId = "non_existent_skill_id",
            selectedVersion = 1
        )
        val cmd = CommandInterpreter.understandCommand("Order food")
        val buildResult = ExecutionRequestBuilder.build(cmd, dummyMatch, repository)

        assertNull(buildResult.executionRequest)
        assertEquals(ExecutionRequestStatus.REJECTED_INVALID_WORKFLOW, buildResult.status)
    }

    // --- 21. Blocked Workflow -> No Request ---
    @Test
    fun testBlockedWorkflowNoRequestCreated() {
        val wfBlocked = Workflow(
            skillId = "skill_blocked_req",
            name = "Blocked Skill",
            intent = "order_food",
            appContext = "com.example.foodapp",
            slots = emptyList(),
            steps = emptyList(),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = true, sensitiveKeywords = listOf("OTP", "PIN", "BLOCKED"))
        )
        val dummyMatch = SkillMatchResult(
            status = SkillMatchStatus.MATCHED,
            commandText = "Order food",
            intent = "order_food",
            selectedSkillId = "skill_blocked_req",
            selectedVersion = 1
        )
        val cmd = CommandInterpreter.understandCommand("Order food")
        val buildResult = ExecutionRequestBuilder.buildWithWorkflow(cmd, dummyMatch, wfBlocked)

        assertNull(buildResult.executionRequest)
        assertEquals(ExecutionRequestStatus.REJECTED_SAFETY_BLOCKED, buildResult.status)
    }

    // --- 22. Sensitive Workflow -> No Request ---
    @Test
    fun testSensitiveWorkflowNoRequestCreated() {
        val wfSensitive = createWorkflow(
            skillId = "skill_sensitive",
            intent = "pay_bill",
            slots = listOf(
                WorkflowSlot(name = "password", type = SlotType.TEXT, required = true)
            )
        )
        val dummyMatch = SkillMatchResult(
            status = SkillMatchStatus.MATCHED,
            commandText = "Pay bill",
            intent = "pay_bill",
            selectedSkillId = "skill_sensitive",
            selectedVersion = 1
        )
        val cmd = CommandInterpreter.understandCommand("Pay bill with password 1234")
        val buildResult = ExecutionRequestBuilder.buildWithWorkflow(cmd, dummyMatch, wfSensitive)

        assertNull(buildResult.executionRequest)
        assertEquals(ExecutionRequestStatus.REJECTED_SAFETY_BLOCKED, buildResult.status)
        assertTrue(buildResult.rejectionReason?.contains("Sensitive/credential") == true)
    }

    // --- 23. Credential Value Never Appears in Executable Request ---
    @Test
    fun testCredentialValueNeverAppearsInBoundSlots() {
        val wf = createWorkflow(
            skillId = "skill_cred_check",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "pin", type = SlotType.TEXT, required = true)
            )
        )
        val cmd = CommandInterpreter.understandCommand("Order food with pin 9999")
        val match = SkillMatchResult(status = SkillMatchStatus.MATCHED, commandText = "Order food", intent = "order_food", selectedSkillId = "skill_cred_check")
        val buildResult = ExecutionRequestBuilder.buildWithWorkflow(cmd, match, wf)

        assertNull(buildResult.executionRequest)
        assertFalse(buildResult.boundSlots.containsValue("9999"))
    }

    // --- 24. Sensitive Values Redacted in Diagnostics ---
    @Test
    fun testSensitiveValuesRedactedInDiagnostics() {
        val wf = createWorkflow(
            skillId = "skill_redact",
            intent = "order_food",
            slots = listOf(WorkflowSlot(name = "otp", type = SlotType.TEXT, required = true))
        )
        val cmd = CommandInterpreter.understandCommand("Order food with otp 8888")
        val match = SkillMatchResult(status = SkillMatchStatus.MATCHED, commandText = "Order food", intent = "order_food", selectedSkillId = "skill_redact")
        val buildResult = ExecutionRequestBuilder.buildWithWorkflow(cmd, match, wf)

        assertFalse(buildResult.diagnostics.any { it.contains("8888") })
    }

    // --- 25. Coordinate Data Never Appears in Request ---
    @Test
    fun testCoordinateDataNeverAppearsInRequest() {
        val wf = createWorkflow(
            skillId = "skill_coords",
            intent = "order_food",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true))
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order pizza")
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        val keys = buildResult.executionRequest?.boundSlots?.keys ?: emptySet()
        assertFalse(keys.contains("x"))
        assertFalse(keys.contains("y"))
        assertFalse(keys.contains("tap_x"))
        assertFalse(keys.contains("tap_y"))
    }

    // --- 26. Deterministic Request Semantics ---
    @Test
    fun testDeterministicRequestSemantics() {
        val wf = createWorkflow(
            skillId = "skill_det",
            intent = "order_food",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true))
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order pizza")
        val match = matcher.match(cmd)
        val buildResult1 = ExecutionRequestBuilder.build(cmd, match, repository, overrideExecutionId = "exec_fixed")
        val buildResult2 = ExecutionRequestBuilder.build(cmd, match, repository, overrideExecutionId = "exec_fixed")

        assertEquals(buildResult1.status, buildResult2.status)
        assertEquals(buildResult1.executionRequest, buildResult2.executionRequest)
    }

    // --- 27. Serialization Round-Trip ---
    @Test
    fun testSerializationRoundTrip() {
        val wf = createWorkflow(
            skillId = "skill_ser_req",
            intent = "order_food",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true))
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order pizza")
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository, overrideExecutionId = "exec_ser")

        val json = Json { prettyPrint = true }
        val serialized = json.encodeToString(ExecutionRequestBuildResult.serializer(), buildResult)
        val deserialized = json.decodeFromString(ExecutionRequestBuildResult.serializer(), serialized)

        assertEquals(buildResult.status, deserialized.status)
        assertEquals(buildResult.executionRequest?.executionId, deserialized.executionRequest?.executionId)
        assertEquals(buildResult.executionRequest?.boundSlots, deserialized.executionRequest?.boundSlots)
    }

    // --- 28. Workflow Object Not Mutated ---
    @Test
    fun testWorkflowObjectNotMutated() {
        val wf = createWorkflow(
            skillId = "skill_no_mutate",
            intent = "order_food",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Original"))
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order Margherita")
        val match = matcher.match(cmd)
        val originalSlots = wf.slots.toList()
        ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(originalSlots, wf.slots)
        assertEquals("Original", wf.slots[0].exampleValue)
    }

    // --- 29. Command Result Not Mutated ---
    @Test
    fun testCommandResultNotMutated() {
        val wf = createWorkflow(
            skillId = "skill_cmd_no_mutate",
            intent = "order_food",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true))
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order pizza")
        val match = matcher.match(cmd)
        val originalCmdSlots = cmd.slots.toList()

        ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(originalCmdSlots, cmd.slots)
    }

    // --- 30. Skill Match Result Not Mutated ---
    @Test
    fun testSkillMatchResultNotMutated() {
        val wf = createWorkflow(
            skillId = "skill_match_no_mutate",
            intent = "order_food",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true))
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order pizza")
        val match = matcher.match(cmd)
        val originalStatus = match.status

        ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(originalStatus, match.status)
    }

    // --- 31. Phase 10 -> Phase 11 -> Phase 12 Integration ---
    @Test
    fun testEndToEndIntegrationPhase10To12() {
        val wf = createWorkflow(
            skillId = "skill.order_food.v1",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "constant"),
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}", provenance = "variable"),
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "\${quantity}", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        // Phase 10
        val cmdResult = CommandInterpreter.understandCommand("Order 2 Farmhouse pizzas from Pizza Palace")
        assertEquals("order_food", cmdResult.intent.canonicalName)

        // Phase 11
        val matchResult = matcher.match(cmdResult)
        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)
        assertEquals("skill.order_food.v1", matchResult.selectedSkillId)

        // Phase 12
        val buildResult = ExecutionRequestBuilder.build(cmdResult, matchResult, repository, overrideExecutionId = "exec_e2e")
        assertEquals(ExecutionRequestStatus.READY_FOR_PERSON_2, buildResult.status)

        val req = buildResult.executionRequest
        assertNotNull(req)
        assertEquals("exec_e2e", req!!.executionId)
        assertEquals("skill.order_food.v1", req.skillId)
        assertEquals("Pizza Palace", req.boundSlots["restaurant"])
        assertEquals("Farmhouse Pizza", req.boundSlots["item"])
        assertEquals("2", req.boundSlots["quantity"])
    }

    // --- 32. Mock Person 2 Handoff Formatting Verification ---
    @Test
    fun testPerson1MockRuntimeFormatting() {
        val wf = createWorkflow(
            skillId = "skill_mock_handoff",
            intent = "order_food",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true))
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order pizza")
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        val formatted = Person1MockRuntime.formatMockHandoff(buildResult)

        assertTrue(formatted.contains("PERSON 1 → PERSON 2 HANDOFF"))
        assertTrue(formatted.contains("READY FOR PERSON 2"))
        assertTrue(formatted.contains("NOT PERFORMED (Person 1 Boundary)"))
    }

    // --- 33. Shared Contract Serialization Regression ---
    @Test
    fun testSharedContractExecutionRequestSerialization() {
        val request = ExecutionRequest(
            schemaVersion = "1.0",
            executionId = "exec_test_123",
            skillId = "skill_test",
            boundSlots = mapOf("item" to "Pizza", "quantity" to "2"),
            version = 1,
            provenance = "person_1_matching"
        )

        val json = Json { prettyPrint = true }
        val serialized = json.encodeToString(ExecutionRequest.serializer(), request)
        val deserialized = json.decodeFromString(ExecutionRequest.serializer(), serialized)

        assertEquals(request.executionId, deserialized.executionId)
        assertEquals(request.skillId, deserialized.skillId)
        assertEquals(request.boundSlots, deserialized.boundSlots)
        assertEquals(request.version, deserialized.version)
    }

    // =========================================================================
    // PHASE 4.3 TEST MATRIX (PH4.3-T1 through PH4.3-T18 + End-to-End + Kill Test)
    // =========================================================================

    // --- PH4.3-T1 — Basic variable binding ---
    @Test
    fun testPH4_3_T1_BasicVariableBinding() {
        val wf = createWorkflow(
            skillId = "skill_shop_basic",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "\${platform}", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Can you get me a blue jacket from Myntra?")
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(ExecutionRequestStatus.SUCCESS, buildResult.status)
        assertNotNull(buildResult.executionRequest)
        assertEquals("Blue Jacket", buildResult.executionRequest?.boundSlots?.get("item"))
        assertEquals("Myntra", buildResult.executionRequest?.boundSlots?.get("platform"))
    }

    // --- PH4.3-T2 — Quantity binding ---
    @Test
    fun testPH4_3_T2_QuantityBinding() {
        val wf = createWorkflow(
            skillId = "skill_qty_bind",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}", provenance = "variable"),
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "\${quantity}", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Order 3 blue jackets")
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(ExecutionRequestStatus.SUCCESS, buildResult.status)
        assertEquals("3", buildResult.executionRequest?.boundSlots?.get("quantity"))
    }

    // --- PH4.3-T3 — Demonstration value must not leak ---
    @Test
    fun testPH4_3_T3_DemonstrationValueMustNotLeak() {
        val wf = createWorkflow(
            skillId = "skill_no_leak",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "white shirt", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Get me a blue jacket from Myntra")
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(ExecutionRequestStatus.SUCCESS, buildResult.status)
        val bound = buildResult.executionRequest?.boundSlots!!
        assertEquals("Blue Jacket", bound["item"])
        assertEquals("Myntra", bound["platform"])
        assertFalse(bound["item"] == "white shirt")
        assertFalse(bound["platform"] == "Amazon")
    }

    // --- PH4.3-T4 — Template substitution ---
    @Test
    fun testPH4_3_T4_TemplateSubstitution() {
        val searchStep = WorkflowStep(
            stepId = "step_search",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.shop:id/search"),
            parameters = mapOf("value" to "\${item}")
        )
        val wf = Workflow(
            skillId = "skill_tmpl_sub",
            name = "Shop Item",
            intent = "shop_item",
            appContext = "com.shop",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}")),
            steps = listOf(searchStep),
            safetyBoundary = SafetyBoundary()
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Search Blue Jacket")
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(ExecutionRequestStatus.SUCCESS, buildResult.status)
        assertEquals(1, buildResult.boundSteps.size)
        assertEquals("Blue Jacket", buildResult.boundSteps[0].inputText)
        // Stored workflow remains untouched
        assertEquals("\${item}", wf.steps[0].parameters["value"])
    }

    // --- PH4.3-T5 — Quantity template ---
    @Test
    fun testPH4_3_T5_QuantityTemplate() {
        val qtyStep = WorkflowStep(
            stepId = "step_qty_set",
            semanticAction = "SET_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.shop:id/qty"),
            parameters = mapOf("value" to "\${quantity}")
        )
        val wf = Workflow(
            skillId = "skill_qty_tmpl",
            name = "Shop Item",
            intent = "shop_item",
            appContext = "com.shop",
            slots = listOf(WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "\${quantity}")),
            steps = listOf(qtyStep),
            safetyBoundary = SafetyBoundary()
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Set quantity to 3")
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(ExecutionRequestStatus.SUCCESS, buildResult.status)
        assertEquals(1, buildResult.boundSteps.size)
        assertEquals("3", buildResult.boundSteps[0].inputText)
    }

    // --- PH4.3-T6 — Platform role binding ---
    @Test
    fun testPH4_3_T6_PlatformRoleBinding() {
        val wf = createWorkflow(
            skillId = "skill_platform_role",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "shopping_platform", type = SlotType.PLATFORM, role = "shopping_platform", required = true)
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandUnderstandingResult(
            rawCommand = "Buy headphones from Myntra",
            normalizedCommand = "buy headphones from myntra",
            intent = com.chockXlate.teachablevoice.learning.intent.Intent(canonicalName = "shop_item", category = "shopping", confidence = 1.0),
            intentConfidence = 1.0,
            slots = listOf(
                CommandSlot(name = "shopping_platform", type = SlotType.PLATFORM, rawValue = "Myntra", typedValue = "Myntra", role = "shopping_platform")
            )
        )
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(ExecutionRequestStatus.SUCCESS, buildResult.status)
        assertEquals("Myntra", buildResult.executionRequest?.boundSlots?.get("shopping_platform"))
        assertFalse(buildResult.executionRequest?.boundSlots?.values?.contains("com.myntra.android") == true)
    }

    // --- PH4.3-T7 — Multi-app role binding ---
    @Test
    fun testPH4_3_T7_MultiAppRoleBinding() {
        val wf = createWorkflow(
            skillId = "skill_multi_app",
            intent = "share_product",
            slots = listOf(
                WorkflowSlot(name = "shopping_platform", type = SlotType.PLATFORM, role = "shopping_platform", required = true),
                WorkflowSlot(name = "messaging_platform", type = SlotType.PLATFORM, role = "messaging_platform", required = true)
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandUnderstandingResult(
            rawCommand = "Find a white shirt on Myntra and send the link to me on Telegram",
            normalizedCommand = "find a white shirt on myntra and send the link to me on telegram",
            intent = com.chockXlate.teachablevoice.learning.intent.Intent(canonicalName = "share_product", category = "cross_app", confidence = 1.0),
            intentConfidence = 1.0,
            slots = listOf(
                CommandSlot(name = "shopping_platform", type = SlotType.PLATFORM, rawValue = "Myntra", typedValue = "Myntra", role = "shopping_platform"),
                CommandSlot(name = "messaging_platform", type = SlotType.PLATFORM, rawValue = "Telegram", typedValue = "Telegram", role = "messaging_platform")
            )
        )
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(ExecutionRequestStatus.SUCCESS, buildResult.status)
        assertEquals("Myntra", buildResult.executionRequest?.boundSlots?.get("shopping_platform"))
        assertEquals("Telegram", buildResult.executionRequest?.boundSlots?.get("messaging_platform"))
    }

    // --- PH4.3-T8 — Missing required slot ---
    @Test
    fun testPH4_3_T8_MissingRequiredSlot() {
        val wf = createWorkflow(
            skillId = "skill_req_plat",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "white shirt", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandUnderstandingResult(
            rawCommand = "Buy a white shirt",
            normalizedCommand = "buy a white shirt",
            intent = com.chockXlate.teachablevoice.learning.intent.Intent(canonicalName = "shop_item", category = "shopping", confidence = 1.0),
            intentConfidence = 1.0,
            slots = listOf(
                CommandSlot(name = "item", type = SlotType.TEXT, rawValue = "white shirt", typedValue = "White Shirt")
            )
        )
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(ExecutionRequestStatus.NEEDS_CLARIFICATION, buildResult.status)
        assertNull(buildResult.executionRequest)
        assertTrue(buildResult.missingSlots.contains("platform"))
    }

    // --- PH4.3-T9 — Unresolved reference ---
    @Test
    fun testPH4_3_T9_UnresolvedReference() {
        val wf = createWorkflow(
            skillId = "skill_ref_slot",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, provenance = "variable")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandUnderstandingResult(
            rawCommand = "Order it from Swiggy",
            normalizedCommand = "order it from swiggy",
            intent = com.chockXlate.teachablevoice.learning.intent.Intent(canonicalName = "order_food", category = "food", confidence = 1.0),
            intentConfidence = 1.0,
            slots = listOf(
                CommandSlot(name = "item", type = SlotType.TEXT, rawValue = "it", typedValue = "it", status = com.chockXlate.teachablevoice.command.interpretation.CommandSlotStatus.REFERENCE),
                CommandSlot(name = "platform", type = SlotType.PLATFORM, rawValue = "Swiggy", typedValue = "Swiggy")
            )
        )
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(ExecutionRequestStatus.NEEDS_CLARIFICATION, buildResult.status)
        assertNull(buildResult.executionRequest)
        assertTrue(buildResult.missingSlots.contains("item"))
    }

    // --- PH4.3-T10 — Constant conflict ---
    @Test
    fun testPH4_3_T10_ConstantConflict() {
        val wf = createWorkflow(
            skillId = "skill_const_conflict_plat",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "constant")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandUnderstandingResult(
            rawCommand = "Buy a white shirt from Myntra",
            normalizedCommand = "buy a white shirt from myntra",
            intent = com.chockXlate.teachablevoice.learning.intent.Intent(canonicalName = "shop_item", category = "shopping", confidence = 1.0),
            intentConfidence = 1.0,
            slots = listOf(
                CommandSlot(name = "platform", type = SlotType.PLATFORM, rawValue = "Myntra", typedValue = "Myntra")
            )
        )
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(ExecutionRequestStatus.BINDING_CONFLICT, buildResult.status)
        assertNull(buildResult.executionRequest)
    }

    // --- PH4.3-T11 — Constant compatible ---
    @Test
    fun testPH4_3_T11_ConstantCompatible() {
        val wf = createWorkflow(
            skillId = "skill_const_compat_plat",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "constant")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandUnderstandingResult(
            rawCommand = "Buy a white shirt from Amazon",
            normalizedCommand = "buy a white shirt from amazon",
            intent = com.chockXlate.teachablevoice.learning.intent.Intent(canonicalName = "shop_item", category = "shopping", confidence = 1.0),
            intentConfidence = 1.0,
            slots = listOf(
                CommandSlot(name = "platform", type = SlotType.PLATFORM, rawValue = "Amazon", typedValue = "Amazon")
            )
        )
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(ExecutionRequestStatus.SUCCESS, buildResult.status)
        assertEquals("Amazon", buildResult.executionRequest?.boundSlots?.get("platform"))
    }

    // --- PH4.3-T12 — Wrong slot type ---
    @Test
    fun testPH4_3_T12_WrongSlotType() {
        val wf = createWorkflow(
            skillId = "skill_wrong_type",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true)
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandUnderstandingResult(
            rawCommand = "Buy some shirts",
            normalizedCommand = "buy some shirts",
            intent = com.chockXlate.teachablevoice.learning.intent.Intent(canonicalName = "shop_item", category = "shopping", confidence = 1.0),
            intentConfidence = 1.0,
            slots = listOf(
                CommandSlot(name = "quantity", type = SlotType.TEXT, rawValue = "invalid text", typedValue = "invalid text")
            )
        )
        val match = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repository)

        assertEquals(ExecutionRequestStatus.INVALID, buildResult.status)
        assertNull(buildResult.executionRequest)
    }

    // --- PH4.3-T13 — Unknown match ---
    @Test
    fun testPH4_3_T13_UnknownMatch() {
        val cmd = CommandInterpreter.understandCommand("Book a flight to Mars")
        val matchResult = matcher.match(cmd)
        assertEquals(SkillMatchStatus.UNKNOWN, matchResult.status)

        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)
        assertNull(buildResult.executionRequest)
        assertEquals(ExecutionRequestStatus.REJECTED_UNKNOWN_MATCH, buildResult.status)
    }

    // --- PH4.3-T14 — Ambiguous match ---
    @Test
    fun testPH4_3_T14_AmbiguousMatch() {
        val wf1 = createWorkflow(skillId = "skill_amb_a", intent = "shop_item")
        val wf2 = createWorkflow(skillId = "skill_amb_b", intent = "shop_item")
        repository.saveWorkflow(wf1)
        repository.saveWorkflow(wf2)

        val cmd = CommandInterpreter.understandCommand("Shop item")
        val matchResult = matcher.match(cmd)
        assertEquals(SkillMatchStatus.AMBIGUOUS, matchResult.status)

        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)
        assertNull(buildResult.executionRequest)
        assertEquals(ExecutionRequestStatus.REJECTED_AMBIGUOUS_MATCH, buildResult.status)
    }

    // --- PH4.3-T15 — Phase 4.1 clarification ---
    @Test
    fun testPH4_3_T15_Phase41Clarification() {
        val wf = createWorkflow(skillId = "skill_shop_clarify", intent = "shop_item")
        repository.saveWorkflow(wf)

        val cmd = CommandUnderstandingResult(
            rawCommand = "Buy something from Myntra",
            normalizedCommand = "buy something from myntra",
            intent = com.chockXlate.teachablevoice.learning.intent.Intent(canonicalName = "shop_item", category = "shopping", confidence = 1.0),
            intentConfidence = 1.0,
            status = "NEEDS_CLARIFICATION",
            unresolvedItems = listOf("item")
        )
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertNull(buildResult.executionRequest)
        assertEquals(ExecutionRequestStatus.NEEDS_CLARIFICATION, buildResult.status)
    }

    // --- PH4.3-T16 — Sensitive command propagation ---
    @Test
    fun testPH4_3_T16_SensitiveCommandPropagation() {
        val wf = createWorkflow(
            skillId = "skill_sensitive_checkout",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandUnderstandingResult(
            rawCommand = "Proceed to payment and complete checkout for shoes",
            normalizedCommand = "proceed to payment and complete checkout for shoes",
            intent = com.chockXlate.teachablevoice.learning.intent.Intent(canonicalName = "shop_item", category = "shopping", confidence = 1.0),
            intentConfidence = 1.0,
            slots = listOf(
                CommandSlot(name = "item", type = SlotType.TEXT, rawValue = "shoes", typedValue = "Shoes")
            ),
            isSensitive = true
        )
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals(ExecutionRequestStatus.SUCCESS, buildResult.status)
        assertNotNull(buildResult.executionRequest)
        assertTrue(buildResult.executionRequest!!.isSensitive)
    }

    // --- PH4.3-T17 — Stored workflow immutability ---
    @Test
    fun testPH4_3_T17_StoredWorkflowImmutability() {
        val searchStep = WorkflowStep(
            stepId = "step_search",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.shop:id/search"),
            parameters = mapOf("value" to "\${item}")
        )
        val wf = Workflow(
            skillId = "skill_immutable_check",
            name = "Shop Item",
            intent = "shop_item",
            appContext = "com.shop",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "\${item}")),
            steps = listOf(searchStep),
            safetyBoundary = SafetyBoundary()
        )
        repository.saveWorkflow(wf)

        // Verify state before binding
        assertEquals("\${item}", wf.slots[0].exampleValue)
        assertEquals("\${item}", wf.steps[0].parameters["value"])

        val cmd = CommandInterpreter.understandCommand("Search Blue Jacket")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals(ExecutionRequestStatus.SUCCESS, buildResult.status)
        assertEquals("Blue Jacket", buildResult.executionRequest?.boundSlots?.get("item"))

        // Verify stored workflow in repo and in memory is strictly unchanged
        val storedWf = repository.getWorkflowById("skill_immutable_check")!!
        assertEquals("\${item}", storedWf.slots[0].exampleValue)
        assertEquals("\${item}", storedWf.steps[0].parameters["value"])
        assertEquals("\${item}", wf.slots[0].exampleValue)
        assertEquals("\${item}", wf.steps[0].parameters["value"])
    }

    // --- PH4.3-T18 — No package mapping ---
    @Test
    fun testPH4_3_T18_NoPackageMapping() {
        val wf = createWorkflow(
            skillId = "skill_pkg_check",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "\${platform}")
            )
        )
        repository.saveWorkflow(wf)

        val cmd = CommandInterpreter.understandCommand("Buy jacket from Myntra")
        val matchResult = matcher.match(cmd)
        val buildResult = ExecutionRequestBuilder.build(cmd, matchResult, repository)

        assertEquals(ExecutionRequestStatus.SUCCESS, buildResult.status)
        val plat = buildResult.executionRequest?.boundSlots?.get("platform")
        assertEquals("Myntra", plat)
        assertFalse("com.myntra.android" == plat)
    }

    // =========================================================================
    // END-TO-END PHASE 4.3 TEST (Phase 4.1 -> Phase 4.2 -> Phase 4.3)
    // =========================================================================
    @Test
    fun testEndToEndPhase4_3() {
        // Teach: "Order a white shirt from Amazon"
        val taughtWorkflow = createWorkflow(
            skillId = "skill_learned_shopping",
            name = "Order a white shirt from Amazon",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "white shirt", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "variable")
            )
        )
        repository.saveWorkflow(taughtWorkflow)

        // Runtime: "Can you get me a blue jacket from Myntra?"
        // Phase 4.1: Command Understanding
        val phase41Result = CommandInterpreter.understandCommand("Can you get me a blue jacket from Myntra?")
        assertEquals("shop_item", phase41Result.intent.canonicalName)
        assertEquals("Blue Jacket", phase41Result.item)
        assertEquals("Myntra", phase41Result.platform)

        // Phase 4.2: Skill Matching
        val phase42Result = matcher.match(phase41Result)
        assertEquals(SkillMatchStatus.MATCHED, phase42Result.status)
        assertEquals("skill_learned_shopping", phase42Result.selectedSkillId)
        assertNotNull(phase42Result.selectedWorkflow)

        // Phase 4.3: Runtime Slot Binding & ExecutionRequest Construction
        val phase43Result = ExecutionRequestBuilder.build(phase41Result, phase42Result, repository)
        assertEquals(ExecutionRequestStatus.SUCCESS, phase43Result.status)

        val req = phase43Result.executionRequest
        assertNotNull(req)
        assertEquals("skill_learned_shopping", req!!.skillId)
        assertEquals("Blue Jacket", req.boundSlots["item"])
        assertEquals("Myntra", req.boundSlots["platform"])

        // Immutability confirmation: stored workflow untouched
        val stored = repository.getWorkflowById("skill_learned_shopping")!!
        assertEquals("white shirt", stored.slots.find { it.name == "item" }?.exampleValue)
        assertEquals("Amazon", stored.slots.find { it.name == "platform" }?.exampleValue)
    }

    // =========================================================================
    // KILL TEST (Mandatory)
    // =========================================================================
    @Test
    fun testKillTestPhase4_3() {
        val taughtWorkflow = createWorkflow(
            skillId = "skill_kill_test_shopping",
            name = "Order a white shirt from Amazon",
            intent = "shop_item",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "white shirt", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "variable")
            )
        )
        repository.saveWorkflow(taughtWorkflow)

        val runtimeCommand = "Can you get me a blue jacket from Myntra?"

        // Phase 4.1
        val understanding = CommandInterpreter.understandCommand(runtimeCommand)
        assertEquals("shop_item", understanding.intent.canonicalName)
        assertEquals("Blue Jacket", understanding.item)
        assertEquals("Myntra", understanding.platform)

        // Phase 4.2
        val match = matcher.match(understanding)
        assertEquals(SkillMatchStatus.MATCHED, match.status)
        assertEquals("skill_kill_test_shopping", match.selectedSkillId)

        // Phase 4.3
        val buildResult = ExecutionRequestBuilder.build(understanding, match, repository)
        assertEquals(ExecutionRequestStatus.READY_FOR_PERSON_2, buildResult.status)

        val req = buildResult.executionRequest
        assertNotNull(req)
        assertEquals("skill_kill_test_shopping", req!!.skillId)
        assertEquals("Blue Jacket", req.boundSlots["item"])
        assertEquals("Myntra", req.boundSlots["platform"])

        // Invariant: Demonstration values never leaked
        assertFalse("white shirt" == req.boundSlots["item"])
        assertFalse("Amazon" == req.boundSlots["platform"])

        // Invariant: Stored workflow not mutated
        val storedWf = repository.getWorkflowById("skill_kill_test_shopping")!!
        assertEquals("white shirt", storedWf.slots.find { it.name == "item" }?.exampleValue)
        assertEquals("Amazon", storedWf.slots.find { it.name == "platform" }?.exampleValue)

        // Invariant: No package mapping
        assertFalse(req.boundSlots.containsValue("com.myntra.android"))
        assertFalse(req.boundSlots.containsValue("com.amazon.mShop.android.shopping"))

        // Invariant: No UI execution occurred
        assertTrue(buildResult.diagnostics.none { it.contains("AccessibilityNodeInfo") })
        assertTrue(buildResult.diagnostics.none { it.contains("performAction") })
    }
}
