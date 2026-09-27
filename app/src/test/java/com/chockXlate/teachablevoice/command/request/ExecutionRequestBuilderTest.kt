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
}
