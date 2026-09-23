package com.chockXlate.teachablevoice.skill.inspector

import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.workflow.RecoveryPolicy
import com.chockXlate.teachablevoice.contract.workflow.RecoveryStrategy
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationAlignment
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset
import com.chockXlate.teachablevoice.learning.inference.ConstantVariableInference
import com.chockXlate.teachablevoice.learning.intent.IntentExtractor
import com.chockXlate.teachablevoice.learning.slots.SlotExtractor
import com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.skill.validation.ValidationStatus
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionImpl
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkflowInspectorTest {

    private lateinit var repo: LocalSkillRepository
    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun createValidWorkflow(skillId: String = "skill_order_food_insp"): Workflow {
        val slot1 = WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Pizza", provenance = "Phase 6")
        val slot2 = WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = false, exampleValue = "Pizza Palace", provenance = "Phase 6")
        val step1 = WorkflowStep(
            stepId = "step1",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/search", textSlot = "\${item}"),
            parameters = mapOf("input_parameter" to "\${item}"),
            confidence = 1.0,
            provenance = "Phase 3"
        )
        return Workflow(
            skillId = skillId,
            name = "Order Food",
            intent = "order_food",
            appContext = "com.food.app",
            slots = listOf(slot1, slot2),
            steps = listOf(step1),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = false)
        )
    }

    @Before
    fun setUp() {
        repo = LocalSkillRepository()
        repo.clear()
    }

    @Test
    fun test1_validWorkflowInspection() {
        val wf = createValidWorkflow()
        repo.saveWorkflow(wf)

        val result = WorkflowInspectorImpl.inspect(wf, repo)
        assertEquals(ValidationStatus.VALID, result.validationStatus)
        assertEquals("STORED", result.storeStatus)
        assertTrue(result.isExecutableByPerson2)
        assertTrue(result.coordinateReplayPass)
    }

    @Test
    fun test2_intentInspection() {
        val wf = createValidWorkflow()
        val result = WorkflowInspectorImpl.inspect(wf)

        assertEquals("order_food", result.intent)
        assertTrue(result.formattedText.contains("order_food"))
    }

    @Test
    fun test3_slotInspection() {
        val wf = createValidWorkflow()
        val result = WorkflowInspectorImpl.inspect(wf)

        assertEquals(2, result.slots.size)
        val varSlot = result.slots.find { it.name == "item" }
        val constSlot = result.slots.find { it.name == "restaurant" }

        assertNotNull(varSlot)
        assertNotNull(constSlot)
        assertEquals("VARIABLE", varSlot?.role)
        assertEquals("CONSTANT", constSlot?.role)
    }

    @Test
    fun test4_variableSlotDisplayedAsSlotRef() {
        val wf = createValidWorkflow()
        val result = WorkflowInspectorImpl.inspect(wf)

        val varSlot = result.slots.find { it.name == "item" }
        assertEquals("\${item}", varSlot?.referenceForm)
    }

    @Test
    fun test5_constantSlotDisplayedAsFixedValue() {
        val wf = createValidWorkflow()
        val result = WorkflowInspectorImpl.inspect(wf)

        val constSlot = result.slots.find { it.name == "restaurant" }
        assertNull(constSlot?.referenceForm)
        assertEquals("Pizza Palace", constSlot?.exampleValue)
    }

    @Test
    fun test8_stepOrderingInspection() {
        val step1 = WorkflowStep(stepId = "step1", semanticAction = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText"))
        val step2 = WorkflowStep(stepId = "step2", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button"))
        val wf = createValidWorkflow().copy(steps = listOf(step1, step2))

        val result = WorkflowInspectorImpl.inspect(wf)
        assertEquals(2, result.steps.size)
        assertEquals("step1", result.steps[0].stepId)
        assertEquals("step2", result.steps[1].stepId)
    }

    @Test
    fun test9_semanticTargetInspection() {
        val wf = createValidWorkflow()
        val result = WorkflowInspectorImpl.inspect(wf)

        val step = result.steps.first()
        assertTrue(step.targetDescription.contains("EditText"))
        assertTrue(step.targetDescription.contains("id/search"))
    }

    @Test
    fun test10_parameterInspection() {
        val wf = createValidWorkflow()
        val result = WorkflowInspectorImpl.inspect(wf)

        val step = result.steps.first()
        assertEquals("\${item}", step.textSlotReference)
    }

    @Test
    fun test14_safetyBoundaryInspection() {
        val wf = createValidWorkflow()
        val result = WorkflowInspectorImpl.inspect(wf)

        assertFalse(result.safety.requiresExplicitUserConfirmation)
        assertTrue(result.safety.credentialAutomationBlocked)
    }

    @Test
    fun test15_sensitiveWorkflowInspection() {
        val step = WorkflowStep(
            stepId = "step_pin",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/pin_field"),
            recoveryPolicy = RecoveryPolicy(strategy = RecoveryStrategy.HANDOFF_TO_USER, maxRetries = 0)
        )
        val wf = createValidWorkflow().copy(
            steps = listOf(step),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = true, sensitiveKeywords = listOf("pin"))
        )

        val result = WorkflowInspectorImpl.inspect(wf)
        assertTrue(result.safety.requiresExplicitUserConfirmation)
        assertTrue(result.safety.sensitiveKeywords.contains("pin"))
    }

    @Test
    fun test16_sensitiveValuesAreRedacted() {
        val slot = WorkflowSlot(name = "password", type = SlotType.TEXT, required = true, exampleValue = "SecretPass123")
        val step = WorkflowStep(
            stepId = "step_pass",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/pass"),
            parameters = mapOf("input_literal" to "SecretPass123"),
            recoveryPolicy = RecoveryPolicy(strategy = RecoveryStrategy.HANDOFF_TO_USER, maxRetries = 0)
        )
        val wf = createValidWorkflow().copy(
            slots = listOf(slot),
            steps = listOf(step),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = true, sensitiveKeywords = listOf("password"))
        )

        val result = WorkflowInspectorImpl.inspect(wf)
        val slotInsp = result.slots.find { it.name == "password" }
        assertEquals("[REDACTED_SENSITIVE]", slotInsp?.exampleValue)
        assertFalse(result.formattedText.contains("SecretPass123"))
    }

    @Test
    fun test17_coordinateReplayDetected() {
        val step = WorkflowStep(
            stepId = "s_coord",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", relativePosition = "tap(x=400,y=800)")
        )
        val wf = createValidWorkflow().copy(steps = listOf(step))

        val result = WorkflowInspectorImpl.inspect(wf)
        assertFalse(result.coordinateReplayPass)
        assertFalse(result.isExecutableByPerson2)
        assertTrue(result.formattedText.contains("YES (BLOCKED)"))
    }

    @Test
    fun test18_invalidWorkflowInspection() {
        val wf = createValidWorkflow().copy(intent = "") // Blank intent
        val result = WorkflowInspectorImpl.inspect(wf)

        assertEquals(ValidationStatus.INVALID, result.validationStatus)
        assertFalse(result.isExecutableByPerson2)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun test19_blockedWorkflowInspection() {
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", relativePosition = "x=10,y=20")
        )
        val wf = createValidWorkflow().copy(steps = listOf(step))

        val result = WorkflowInspectorImpl.inspect(wf)
        assertEquals(ValidationStatus.BLOCKED, result.validationStatus)
        assertFalse(result.isExecutableByPerson2)
    }

    @Test
    fun test20_provenancePreservation() {
        val wf = createValidWorkflow()
        val result = WorkflowInspectorImpl.inspect(wf)

        val slot = result.slots.first()
        assertEquals("Phase 6", slot.provenance)
    }

    @Test
    fun test21_confidencePreservation() {
        val wf = createValidWorkflow()
        val result = WorkflowInspectorImpl.inspect(wf)

        val step = result.steps.first()
        assertEquals(1.0, step.confidence, 0.001)
    }

    @Test
    fun test22_deterministicInspectionOutput() {
        val wf = createValidWorkflow()
        val res1 = WorkflowInspectorImpl.inspect(wf)
        val res2 = WorkflowInspectorImpl.inspect(wf)

        assertEquals(res1.formattedText, res2.formattedText)
        assertEquals(res1, res2)
    }

    @Test
    fun test23_serializationRoundTrip() {
        val wf = createValidWorkflow()
        val result = WorkflowInspectorImpl.inspect(wf)

        val json = jsonFormatter.encodeToString(WorkflowInspectionResult.serializer(), result)
        val decoded = jsonFormatter.decodeFromString(WorkflowInspectionResult.serializer(), json)

        assertEquals("1.0", decoded.schemaVersion)
        assertEquals("skill_order_food_insp", decoded.skillId)
        assertEquals("order_food", decoded.intent)
    }

    @Test
    fun test24_phase1To8Regressions() {
        val session = TeachingSessionImpl("order_food", "Order food")
        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas"))
        val trace = session.stopTeaching()

        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val act = SemanticActionExtractor.extract(norm.normalizedTrace)
        val intRes = IntentExtractor.extract(norm.normalizedTrace, act)
        val slotRes = SlotExtractor.extract(norm.normalizedTrace, act, intRes)
        val ds = DemonstrationDataset("d1", trace.traceId, intRes, slotRes)
        val infRes = ConstantVariableInference.infer(listOf(ds))

        val synthRes = WorkflowSynthesizer.synthesize(intRes, act, slotRes, DemonstrationAlignment.align(listOf(ds)), infRes, norm.normalizedTrace)
        val wf = synthRes.workflow!!

        val valRes = WorkflowValidator.validate(wf)
        repo.saveWorkflow(wf)

        val inspection = WorkflowInspectorImpl.inspect(wf, repo)

        assertEquals(ValidationStatus.VALID, valRes.status)
        assertEquals(ValidationStatus.VALID, inspection.validationStatus)
        assertEquals("STORED", inspection.storeStatus)
        assertTrue(inspection.isExecutableByPerson2)
    }
}
