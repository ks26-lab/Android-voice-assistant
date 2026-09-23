package com.chockXlate.teachablevoice.skill.validation

import com.chockXlate.teachablevoice.contract.workflow.RecoveryPolicy
import com.chockXlate.teachablevoice.contract.workflow.RecoveryStrategy
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowValidatorTest {

    private fun createValidWorkflow(): Workflow {
        val slot1 = WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Pizza")
        val step1 = WorkflowStep(
            stepId = "step1",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/search", textSlot = "\${item}"),
            parameters = mapOf("input_parameter" to "\${item}")
        )
        return Workflow(
            skillId = "skill_order_food_01",
            name = "Order Food",
            intent = "order_food",
            appContext = "com.food.app",
            slots = listOf(slot1),
            steps = listOf(step1),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = false)
        )
    }

    @Test
    fun test1_validWorkflowPasses() {
        val wf = createValidWorkflow()
        val result = WorkflowValidator.validate(wf)

        assertEquals(ValidationStatus.VALID, result.status)
        assertTrue(result.isStoreable)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun test2_missingSchemaVersionRejected() {
        val wf = createValidWorkflow().copy(schemaVersion = "")
        val result = WorkflowValidator.validate(wf)

        assertEquals(ValidationStatus.INVALID, result.status)
        assertFalse(result.isStoreable)
        assertTrue(result.issues.any { it.message.contains("Schema version") })
    }

    @Test
    fun test3_missingWorkflowIdRejected() {
        val wf = createValidWorkflow().copy(skillId = "")
        val result = WorkflowValidator.validate(wf)

        assertEquals(ValidationStatus.INVALID, result.status)
        assertTrue(result.issues.any { it.message.contains("Skill ID") })
    }

    @Test
    fun test4_missingIntentRejected() {
        val wf = createValidWorkflow().copy(intent = "")
        val result = WorkflowValidator.validate(wf)

        assertEquals(ValidationStatus.INVALID, result.status)
        assertTrue(result.issues.any { it.message.contains("intent") })
    }

    @Test
    fun test5_emptyProcedureRejected() {
        val wf = createValidWorkflow().copy(steps = emptyList())
        val result = WorkflowValidator.validate(wf)

        assertEquals(ValidationStatus.INVALID, result.status)
        assertTrue(result.issues.any { it.message.contains("0 steps") })
    }

    @Test
    fun test6_duplicateStepIdsRejected() {
        val step1 = WorkflowStep(stepId = "step1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button"))
        val step2 = WorkflowStep(stepId = "step1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button"))
        val wf = createValidWorkflow().copy(steps = listOf(step1, step2))

        val result = WorkflowValidator.validate(wf)
        assertEquals(ValidationStatus.INVALID, result.status)
        assertTrue(result.issues.any { it.message.contains("Duplicate step ID") })
    }

    @Test
    fun test7_invalidActionTypeRejected() {
        val step = WorkflowStep(stepId = "s1", semanticAction = "", semanticSelector = SemanticSelector(role = "Button"))
        val wf = createValidWorkflow().copy(steps = listOf(step))

        val result = WorkflowValidator.validate(wf)
        assertEquals(ValidationStatus.INVALID, result.status)
        assertTrue(result.issues.any { it.message.contains("blank semantic action") })
    }

    @Test
    fun test8_invalidSemanticSelectorRejected() {
        val step = WorkflowStep(stepId = "s1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = null, resourceId = null, text = null, textSlot = null))
        val wf = createValidWorkflow().copy(steps = listOf(step))

        val result = WorkflowValidator.validate(wf)
        assertEquals(ValidationStatus.INVALID, result.status)
        assertTrue(result.issues.any { it.message.contains("invalid/empty SemanticSelector") })
    }

    @Test
    fun test9_undefinedVariableReferenceRejected() {
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", textSlot = "\${undeclared_var}")
        )
        val wf = createValidWorkflow().copy(steps = listOf(step))

        val result = WorkflowValidator.validate(wf)
        assertEquals(ValidationStatus.INVALID, result.status)
        assertTrue(result.issues.any { it.message.contains("undeclared variable slot") })
    }

    @Test
    fun test10_variableSlotTypeMismatchRejected() {
        // Step simultaneously defines literal text and variable slot reference
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", text = "LiteralText", textSlot = "\${item}")
        )
        val wf = createValidWorkflow().copy(steps = listOf(step))

        val result = WorkflowValidator.validate(wf)
        assertEquals(ValidationStatus.INVALID, result.status)
        assertTrue(result.issues.any { it.message.contains("simultaneously defines literal text") })
    }

    @Test
    fun test15_coordinateExecutionTruthBlocked() {
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", relativePosition = "tapPosition(x=400,y=800)")
        )
        val wf = createValidWorkflow().copy(steps = listOf(step))

        val result = WorkflowValidator.validate(wf)
        assertEquals(ValidationStatus.BLOCKED, result.status)
        assertFalse(result.isStoreable)
        assertTrue(result.issues.any { it.category == ValidationCategory.COORDINATE_REPLAY })
    }

    @Test
    fun test16_sensitiveCredentialValueBlocked() {
        val step = WorkflowStep(
            stepId = "s_pass",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/enter_password"),
            parameters = mapOf("input_literal" to "RawSecretPassword123"),
            recoveryPolicy = RecoveryPolicy(strategy = RecoveryStrategy.HANDOFF_TO_USER, maxRetries = 0)
        )
        val wf = createValidWorkflow().copy(
            steps = listOf(step),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = true, sensitiveKeywords = listOf("password"))
        )

        val result = WorkflowValidator.validate(wf)
        assertEquals(ValidationStatus.BLOCKED, result.status)
        assertTrue(result.issues.any { it.category == ValidationCategory.SAFETY && it.message.contains("raw secret value") })
    }

    @Test
    fun test17_sensitiveActionWithoutHandoffBlocked() {
        val step = WorkflowStep(
            stepId = "s_otp",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/enter_otp"),
            recoveryPolicy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP) // Invalid for sensitive step
        )
        val wf = createValidWorkflow().copy(
            steps = listOf(step),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = true, sensitiveKeywords = listOf("otp"))
        )

        val result = WorkflowValidator.validate(wf)
        assertEquals(ValidationStatus.BLOCKED, result.status)
        assertTrue(result.issues.any { it.category == ValidationCategory.SAFETY && it.message.contains("HANDOFF_TO_USER") })
    }

    @Test
    fun test18_automaticSensitiveRetryBlocked() {
        val step = WorkflowStep(
            stepId = "s_pin",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/enter_pin"),
            recoveryPolicy = RecoveryPolicy(strategy = RecoveryStrategy.HANDOFF_TO_USER, maxRetries = 3) // maxRetries != 0 invalid
        )
        val wf = createValidWorkflow().copy(
            steps = listOf(step),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = true, sensitiveKeywords = listOf("pin"))
        )

        val result = WorkflowValidator.validate(wf)
        assertEquals(ValidationStatus.BLOCKED, result.status)
        assertTrue(result.issues.any { it.category == ValidationCategory.SAFETY && it.message.contains("maxRetries") })
    }

    @Test
    fun test21_validationDeterministic() {
        val wf = createValidWorkflow()
        val res1 = WorkflowValidator.validate(wf)
        val res2 = WorkflowValidator.validate(wf)

        assertEquals(res1, res2)
    }

    @Test
    fun test22_validationIssueOrderingDeterministic() {
        val step1 = WorkflowStep(stepId = "s2", semanticAction = "", semanticSelector = SemanticSelector())
        val step2 = WorkflowStep(stepId = "s1", semanticAction = "", semanticSelector = SemanticSelector())
        val wf = createValidWorkflow().copy(steps = listOf(step1, step2))

        val result = WorkflowValidator.validate(wf)
        val firstIssue = result.issues.first()
        val secondIssue = result.issues[1]

        assertTrue(firstIssue.severity <= secondIssue.severity)
    }
}
