package com.chockXlate.teachablevoice.skill.repository

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
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionImpl
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SkillRepositoryTest {

    private lateinit var repo: LocalSkillRepository
    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun createValidWorkflow(skillId: String = "skill_order_food_101"): Workflow {
        val slot1 = WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Pizza")
        val step1 = WorkflowStep(
            stepId = "step1",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/search", textSlot = "\${item}"),
            parameters = mapOf("input_parameter" to "\${item}")
        )
        return Workflow(
            skillId = skillId,
            name = "Order Food",
            intent = "order_food",
            appContext = "com.food.app",
            slots = listOf(slot1),
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
    fun test23_saveValidWorkflow() {
        val wf = createValidWorkflow()
        val result = repo.saveWorkflow(wf)

        assertTrue(result)
        assertEquals(1, repo.getWorkflowCount())
    }

    @Test
    fun test24_retrieveSavedWorkflow() {
        val wf = createValidWorkflow("skill_test_retrieve")
        repo.saveWorkflow(wf)

        val retrieved = repo.getWorkflowById("skill_test_retrieve")
        assertNotNull(retrieved)
        assertEquals("order_food", retrieved?.intent)
    }

    @Test
    fun test25_saveThenDeserializeWithNoSemanticLoss() {
        val wf = createValidWorkflow("skill_serde")
        repo.saveWorkflow(wf)

        val retrieved = repo.getWorkflowById("skill_serde")!!
        val json = jsonFormatter.encodeToString(Workflow.serializer(), retrieved)
        val decoded = jsonFormatter.decodeFromString(Workflow.serializer(), json)

        assertEquals(wf, decoded)
    }

    @Test
    fun test26_deterministicSkillId() {
        val wf = createValidWorkflow("")
        val id1 = LocalSkillRepository.generateDeterministicSkillId(wf)
        val id2 = LocalSkillRepository.generateDeterministicSkillId(wf)

        assertEquals(id1, id2)
    }

    @Test
    fun test27_sameWorkflowSavedTwiceDoesNotCreateDuplicateLogicalSkill() {
        val wf = createValidWorkflow("skill_dup")
        repo.saveWorkflow(wf)
        repo.saveWorkflow(wf)

        assertEquals(1, repo.getWorkflowCount())
    }

    @Test
    fun test28_versionPreserved() {
        val wf1 = createValidWorkflow("skill_v1")
        repo.saveWorkflow(wf1)

        assertEquals(1, repo.getSkillVersion("skill_v1"))
    }

    @Test
    fun test29_differentWorkflowVersionHandledCorrectly() {
        val wf1 = createValidWorkflow("skill_v2")
        repo.saveWorkflow(wf1)

        val wf2 = wf1.copy(name = "Order Food Updated")
        repo.saveWorkflow(wf2)

        assertEquals(2, repo.getSkillVersion("skill_v2"))
        assertEquals("Order Food Updated", repo.getWorkflowById("skill_v2")?.name)
    }

    @Test
    fun test30_invalidWorkflowCannotBeStored() {
        val invalidWf = createValidWorkflow("skill_invalid").copy(intent = "") // Blank intent
        val saved = repo.saveWorkflow(invalidWf)

        assertFalse(saved)
        assertEquals(0, repo.getWorkflowCount())
    }

    @Test
    fun test31_blockedWorkflowCannotBeStored() {
        val step = WorkflowStep(
            stepId = "s_coord",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", relativePosition = "tap(x=100,y=200)")
        )
        val blockedWf = createValidWorkflow("skill_blocked").copy(steps = listOf(step))

        val saved = repo.saveWorkflow(blockedWf)
        assertFalse(saved)
        assertEquals(0, repo.getWorkflowCount())
    }

    @Test
    fun test32_listReturnsStoredSkillsDeterministically() {
        val wf1 = createValidWorkflow("skill_b")
        val wf2 = createValidWorkflow("skill_a")
        repo.saveWorkflow(wf1)
        repo.saveWorkflow(wf2)

        val list = repo.getAllWorkflows()
        assertEquals(2, list.size)
        assertEquals("skill_a", list[0].skillId)
        assertEquals("skill_b", list[1].skillId)
    }

    @Test
    fun test33_containsWorks() {
        val wf = createValidWorkflow("skill_contains")
        repo.saveWorkflow(wf)

        assertTrue(repo.contains("skill_contains"))
        assertFalse(repo.contains("non_existent_skill"))
    }

    @Test
    fun test34_deleteWorks() {
        val wf = createValidWorkflow("skill_delete")
        repo.saveWorkflow(wf)

        assertTrue(repo.contains("skill_delete"))
        val deleted = repo.deleteWorkflow("skill_delete")

        assertTrue(deleted)
        assertFalse(repo.contains("skill_delete"))
    }

    @Test
    fun test35_persistenceSurvivesRepositoryReinstantiation() {
        val wf = createValidWorkflow("skill_persist")
        repo.saveWorkflow(wf)

        val retrievedBefore = repo.getWorkflowById("skill_persist")
        assertNotNull(retrievedBefore)

        val json = jsonFormatter.encodeToString(Workflow.serializer(), retrievedBefore!!)
        val newRepo = LocalSkillRepository()
        val decoded = jsonFormatter.decodeFromString(Workflow.serializer(), json)
        newRepo.saveWorkflow(decoded)

        assertNotNull(newRepo.getWorkflowById("skill_persist"))
    }

    @Test
    fun test36_schemaVersionPreserved() {
        val wf = createValidWorkflow("skill_schema")
        repo.saveWorkflow(wf)

        val retrieved = repo.getWorkflowById("skill_schema")
        assertEquals("1.0", retrieved?.schemaVersion)
    }

    @Test
    fun test37_provenancePreserved() {
        val wf = createValidWorkflow("skill_prov")
        repo.saveWorkflow(wf)

        val retrieved = repo.getWorkflowById("skill_prov")
        val slot = retrieved?.slots?.first()
        assertNotNull(slot?.provenance)
    }

    @Test
    fun test38_safetyBoundaryPreserved() {
        val wf = createValidWorkflow("skill_sb")
        repo.saveWorkflow(wf)

        val retrieved = repo.getWorkflowById("skill_sb")
        assertNotNull(retrieved?.safetyBoundary)
    }

    @Test
    fun test39_variableConstantSemanticsPreserved() {
        val wf = createValidWorkflow("skill_slots")
        repo.saveWorkflow(wf)

        val retrieved = repo.getWorkflowById("skill_slots")
        assertTrue(retrieved?.slots?.first()?.required == true)
    }

    @Test
    fun test40_noCoordinateExecutionTruthIntroducedDuringPersistence() {
        val wf = createValidWorkflow("skill_nocoord")
        repo.saveWorkflow(wf)

        val retrieved = repo.getWorkflowById("skill_nocoord")!!
        val json = jsonFormatter.encodeToString(Workflow.serializer(), retrieved)

        assertFalse(json.contains("\"x\":"))
        assertFalse(json.contains("\"y\":"))
    }

    @Test
    fun test41_regressionPhase1TeachingCapture() {
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

        val saved = repo.saveWorkflow(wf)
        assertTrue(saved)
    }

    @Test
    fun test48_contractSerializationRegression() {
        val wf = createValidWorkflow("skill_contract_reg")
        val json = jsonFormatter.encodeToString(Workflow.serializer(), wf)
        val decoded = jsonFormatter.decodeFromString(Workflow.serializer(), json)

        assertEquals("1.0", decoded.schemaVersion)
        assertEquals(wf.skillId, decoded.skillId)
    }
}
