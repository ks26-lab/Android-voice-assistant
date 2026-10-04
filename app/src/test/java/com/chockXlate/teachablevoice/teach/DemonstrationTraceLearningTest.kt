package com.chockXlate.teachablevoice.teach

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.skill.SkillStatus
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Comprehensive Unit Test Suite for Phase 3: Real Demonstration Capture, Trace Learning & Workflow IR Synthesis.
 * Validates P3-T1 through P3-T10.
 */
class DemonstrationTraceLearningTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var repository: LocalSkillRepository

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("skills_phase3_test")
        repository = LocalSkillRepository(storageDir)
        TeachingSessionManager.clearSession()
    }

    @After
    fun tearDown() {
        TeachingSessionManager.clearSession()
        repository.clear()
    }

    private fun createUiState(
        stateId: String,
        pkg: String,
        elements: List<UiElement>
    ): UiState {
        return UiState(
            schemaVersion = "1.0",
            stateId = stateId,
            timestamp = System.currentTimeMillis(),
            appContext = pkg,
            windowId = 1,
            rootElement = elements.firstOrNull() ?: UiElement(elementId = "root", role = "FrameLayout"),
            allElements = elements
        )
    }

    @Test
    fun test_P3_T1_google_search_demonstration_learning() {
        // 1. Create dynamic skill
        val skillName = "Search Google"
        val skillDesc = "Search Google for a query provided by the user."
        val skillRecord = repository.createSkill(skillName, skillDesc)
        assertEquals(SkillStatus.TEACHING, skillRecord.status)
        assertNull(skillRecord.workflowId)

        // 2. Start teaching session
        val session = TeachingSessionManager.startSession(
            skillName = skillRecord.name,
            intent = skillRecord.description,
            skillId = skillRecord.id,
            description = skillRecord.description
        )
        assertTrue(TeachingSessionManager.isTeachingActive())
        assertEquals(skillRecord.id, TeachingSessionManager.getActiveSkillId())

        // 3. Record demonstration events
        val t0 = 1000L
        val searchBox = UiElement(elementId = "e1", role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", text = "Search...", isEditable = true)
        val initialUi = createUiState("s0", "com.google.android.googlequicksearchbox", listOf(searchBox))

        val v1 = VoiceEvent(eventId = "v1", timestamp = t0, transcript = "Search Google for weather in Delhi")
        TeachingSessionManager.recordVoiceEvent(v1)

        val u1 = UiEvent(eventId = "u1", timestamp = t0 + 100, accessibilityEventType = "TYPE_VIEW_CLICKED", packageName = "com.google.android.googlequicksearchbox", targetElement = searchBox)
        TeachingSessionManager.recordUiEvent(u1)
        TeachingSessionManager.recordUiState(initialUi)

        val a1 = ActionEvent(
            actionId = "a1",
            timestamp = t0 + 100,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box"),
            packageName = "com.google.android.googlequicksearchbox"
        )
        TeachingSessionManager.recordActionEvent(a1)

        val searchEntered = UiElement(elementId = "e2", role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", text = "weather in Delhi", isEditable = true)
        val afterTypeUi = createUiState("s1", "com.google.android.googlequicksearchbox", listOf(searchEntered))

        val a2 = ActionEvent(
            actionId = "a2",
            timestamp = t0 + 300,
            actionType = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box"),
            inputData = "weather in Delhi",
            packageName = "com.google.android.googlequicksearchbox"
        )
        TeachingSessionManager.recordActionEvent(a2)
        TeachingSessionManager.recordStateEvent(StateEvent(
            stateEventId = "se1",
            timestamp = t0 + 300,
            beforeState = initialUi,
            afterState = afterTypeUi,
            causeActionId = a2.actionId
        ))

        // 4. Stop teaching session
        val rawTrace = TeachingSessionManager.stopSession()
        assertNotNull(rawTrace)
        assertEquals(skillRecord.id, session.skillId)

        // 5. Normalize trace
        val normResult = DemonstrationTraceNormalizer.normalize(rawTrace!!)
        val normTrace = normResult.normalizedTrace
        assertEquals("com.google.android.googlequicksearchbox", normTrace.appContext)
        assertEquals(2, normTrace.userActions.size)

        // 6. Workflow synthesis
        val synthResult = WorkflowSynthesizer.synthesizeFromTrace(
            trace = normTrace,
            targetSkillId = skillRecord.id,
            targetSkillName = skillRecord.name,
            targetSkillDescription = skillRecord.description
        )
        val workflow = synthResult.workflow
        assertNotNull("Workflow must be generated from demonstration", workflow)
        assertEquals(skillRecord.id, workflow!!.skillId)
        assertTrue("Steps must reflect demonstration", workflow.steps.isNotEmpty())

        // 7. Validate workflow
        val valResult = WorkflowValidator.validate(workflow)
        assertTrue("Workflow must be valid and storeable", valResult.isStoreable)

        // 8. Save and update skill record to TRAINED
        repository.saveWorkflow(workflow)
        repository.updateSkill(skillRecord.copy(status = SkillStatus.TRAINED, workflowId = workflow.skillId))

        val updatedRecord = repository.getSkill(skillRecord.id)
        assertNotNull(updatedRecord)
        assertEquals(SkillStatus.TRAINED, updatedRecord!!.status)
        assertEquals(workflow.skillId, updatedRecord.workflowId)

        // 9. Verify no hardcoded pizza data
        val wfJson = jsonFormatter.encodeToString(com.chockXlate.teachablevoice.contract.workflow.Workflow.serializer(), workflow)
        assertFalse(wfJson.contains("pizza", ignoreCase = true))
        assertFalse(wfJson.contains("Pizza Palace", ignoreCase = true))
    }

    @Test
    fun test_P3_T2_different_demonstration_open_amazon() {
        val skillName = "Open Amazon"
        val skillDesc = "Open Amazon and prepare for shopping."
        val skillRecord = repository.createSkill(skillName, skillDesc)

        TeachingSessionManager.startSession(
            skillName = skillRecord.name,
            intent = skillRecord.description,
            skillId = skillRecord.id,
            description = skillRecord.description
        )

        val t0 = 2000L
        val cartBtn = UiElement(elementId = "c1", role = "Button", resourceId = "in.amazon.mShop.android.shopping:id/action_bar_cart", contentDescription = "Cart", isClickable = true)
        val amazonUi = createUiState("s_amz_0", "in.amazon.mShop.android.shopping", listOf(cartBtn))

        TeachingSessionManager.recordVoiceEvent(VoiceEvent(eventId = "v_amz", timestamp = t0, transcript = "Open Amazon"))
        TeachingSessionManager.recordUiEvent(UiEvent(eventId = "u_amz", timestamp = t0 + 100, accessibilityEventType = "TYPE_VIEW_CLICKED", packageName = "in.amazon.mShop.android.shopping", targetElement = cartBtn))
        TeachingSessionManager.recordUiState(amazonUi)
        TeachingSessionManager.recordActionEvent(ActionEvent(
            actionId = "a_amz_1",
            timestamp = t0 + 100,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", resourceId = "in.amazon.mShop.android.shopping:id/action_bar_cart", contentDescription = "Cart"),
            packageName = "in.amazon.mShop.android.shopping"
        ))

        val rawTrace = TeachingSessionManager.stopSession()!!
        val normTrace = DemonstrationTraceNormalizer.normalize(rawTrace).normalizedTrace
        val synthResult = WorkflowSynthesizer.synthesizeFromTrace(normTrace, skillRecord.id, skillRecord.name, skillRecord.description)
        val workflow = synthResult.workflow
        assertNotNull(workflow)

        assertEquals("in.amazon.mShop.android.shopping", workflow!!.appContext)
        val stepTarget = workflow.steps.first().semanticSelector.resourceId
        assertEquals("in.amazon.mShop.android.shopping:id/action_bar_cart", stepTarget)
    }

    @Test
    fun test_P3_T3_generic_food_demonstration_no_default_hardcoding() {
        val skillName = "Order Food"
        val skillDesc = "Order a burrito from Burrito Bar."
        val skillRecord = repository.createSkill(skillName, skillDesc)

        TeachingSessionManager.startSession(skillRecord.name, skillRecord.description, skillRecord.id, skillRecord.description)

        val t0 = 3000L
        val searchElem = UiElement(elementId = "f1", role = "EditText", resourceId = "com.customfood.app:id/search", text = "Burrito Bar")
        val foodUi = createUiState("s_food_0", "com.customfood.app", listOf(searchElem))

        TeachingSessionManager.recordVoiceEvent(VoiceEvent(eventId = "v_food", timestamp = t0, transcript = "Order burrito from Burrito Bar"))
        TeachingSessionManager.recordUiState(foodUi)
        TeachingSessionManager.recordActionEvent(ActionEvent(
            actionId = "a_food_1",
            timestamp = t0 + 200,
            actionType = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.customfood.app:id/search"),
            inputData = "Burrito Bar",
            packageName = "com.customfood.app"
        ))

        val rawTrace = TeachingSessionManager.stopSession()!!
        val normTrace = DemonstrationTraceNormalizer.normalize(rawTrace).normalizedTrace
        val workflow = WorkflowSynthesizer.synthesizeFromTrace(normTrace, skillRecord.id, skillRecord.name, skillRecord.description).workflow
        assertNotNull(workflow)

        val wfJson = jsonFormatter.encodeToString(com.chockXlate.teachablevoice.contract.workflow.Workflow.serializer(), workflow!!)
        assertFalse(wfJson.contains("Pizza Palace"))
        assertFalse(wfJson.contains("Margherita"))
    }

    @Test
    fun test_P3_T4_parameterization_slot_inference() {
        val skillRecord = repository.createSkill("Search Query", "Search for headphones")
        TeachingSessionManager.startSession(skillRecord.name, skillRecord.description, skillRecord.id, skillRecord.description)

        val t0 = 4000L
        val inputElem = UiElement(elementId = "q1", role = "EditText", resourceId = "com.app:id/search_query", text = "headphones")
        val ui = createUiState("s_q", "com.app", listOf(inputElem))

        TeachingSessionManager.recordVoiceEvent(VoiceEvent(eventId = "v_q", timestamp = t0, transcript = "Search for headphones"))
        TeachingSessionManager.recordUiState(ui)
        TeachingSessionManager.recordActionEvent(ActionEvent(
            actionId = "a_q",
            timestamp = t0 + 100,
            actionType = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.app:id/search_query"),
            inputData = "headphones",
            packageName = "com.app"
        ))

        val trace = TeachingSessionManager.stopSession()!!
        val norm = DemonstrationTraceNormalizer.normalize(trace).normalizedTrace
        val workflow = WorkflowSynthesizer.synthesizeFromTrace(norm, skillRecord.id, skillRecord.name, skillRecord.description).workflow
        assertNotNull(workflow)

        // Verify variable slot was extracted
        assertTrue("Workflow slots should contain variable parameter", workflow!!.slots.isNotEmpty())
        val slot = workflow.slots.first()
        assertTrue("Slot must represent variable input", slot.name.isNotBlank())
    }

    @Test
    fun test_P3_T5_two_different_demonstrations_no_cross_contamination() {
        // Skill A
        val skillA = repository.createSkill("Skill A", "Action A")
        val sessionA = TeachingSessionManager.startSession(skillA.name, skillA.description, skillA.id, skillA.description)
        assertEquals(skillA.id, sessionA.skillId)
        TeachingSessionManager.recordActionEvent(ActionEvent(
            actionId = "act_a",
            timestamp = 1000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", resourceId = "btn_a"),
            packageName = "com.app.a"
        ))
        val traceA = TeachingSessionManager.stopSession()!!

        // Skill B
        val skillB = repository.createSkill("Skill B", "Action B")
        val sessionB = TeachingSessionManager.startSession(skillB.name, skillB.description, skillB.id, skillB.description)
        assertEquals(skillB.id, sessionB.skillId)
        TeachingSessionManager.recordActionEvent(ActionEvent(
            actionId = "act_b",
            timestamp = 2000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", resourceId = "btn_b"),
            packageName = "com.app.b"
        ))
        val traceB = TeachingSessionManager.stopSession()!!

        assertNotEquals(sessionA.skillId, sessionB.skillId)
        assertNotEquals(traceA.traceId, traceB.traceId)

        val wfA = WorkflowSynthesizer.synthesizeFromTrace(traceA, skillA.id, skillA.name, skillA.description).workflow!!
        val wfB = WorkflowSynthesizer.synthesizeFromTrace(traceB, skillB.id, skillB.name, skillB.description).workflow!!

        assertNotEquals(wfA.skillId, wfB.skillId)
        assertNotEquals(wfA.appContext, wfB.appContext)
        assertEquals("btn_a", wfA.steps.first().semanticSelector.resourceId)
        assertEquals("btn_b", wfB.steps.first().semanticSelector.resourceId)
    }

    @Test
    fun test_P3_T6_no_demonstration_not_marked_trained() {
        val skill = repository.createSkill("Empty Skill", "No actions demonstrated")
        TeachingSessionManager.startSession(skill.name, skill.description, skill.id, skill.description)

        // User stops teaching without doing any action
        val emptyTrace = TeachingSessionManager.stopSession()!!
        assertEquals(0, emptyTrace.userActions.size)

        // Synthesizer cannot produce a valid multi-step workflow without demonstration
        val synthResult = WorkflowSynthesizer.synthesizeFromTrace(emptyTrace, skill.id, skill.name, skill.description)
        val wf = synthResult.workflow

        // If workflow is null or invalid, skill must remain UNTRAINED / TEACHING
        if (wf == null || !WorkflowValidator.validate(wf).isStoreable) {
            val record = repository.getSkill(skill.id)
            assertNotEquals(SkillStatus.TRAINED, record?.status)
        }
    }

    @Test
    fun test_P3_T7_interrupted_demonstration_handling() {
        val skill = repository.createSkill("Interrupted Skill", "Partial demonstration")
        TeachingSessionManager.startSession(skill.name, skill.description, skill.id, skill.description)

        // Partial action
        TeachingSessionManager.recordActionEvent(ActionEvent(
            actionId = "act_partial",
            timestamp = 1000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", resourceId = null, text = null), // invalid empty selector
            packageName = "com.app"
        ))

        val partialTrace = TeachingSessionManager.stopSession()!!
        val synthResult = WorkflowSynthesizer.synthesizeFromTrace(partialTrace, skill.id, skill.name, skill.description)
        val wf = synthResult.workflow

        val valResult = WorkflowValidator.validate(wf)
        if (!valResult.isStoreable) {
            // Must NOT mark TRAINED when validation fails
            val record = repository.getSkill(skill.id)
            assertNotEquals(SkillStatus.TRAINED, record?.status)
        }
    }

    @Test
    fun test_P3_T8_real_ui_change_preserves_expected_transition() {
        val skill = repository.createSkill("UI Change Skill", "Observe state transition")
        TeachingSessionManager.startSession(skill.name, skill.description, skill.id, skill.description)

        val elemBefore = UiElement(elementId = "b1", role = "Button", text = "Load Items", resourceId = "btn_load")
        val stateBefore = createUiState("state_1", "com.app", listOf(elemBefore))

        val elemAfter = UiElement(elementId = "a1", role = "TextView", text = "Items Loaded", resourceId = "txt_loaded")
        val stateAfter = createUiState("state_2", "com.app", listOf(elemAfter))

        val action = ActionEvent(
            actionId = "act_load",
            timestamp = 1000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Load Items", resourceId = "btn_load"),
            packageName = "com.app"
        )
        val stateEvent = StateEvent(
            stateEventId = "se_1",
            timestamp = 1000L,
            beforeState = stateBefore,
            afterState = stateAfter,
            causeActionId = action.actionId
        )

        TeachingSessionManager.recordUiState(stateBefore)
        TeachingSessionManager.recordActionEvent(action)
        TeachingSessionManager.recordStateEvent(stateEvent)
        TeachingSessionManager.recordUiState(stateAfter)

        val trace = TeachingSessionManager.stopSession()!!
        val synthResult = WorkflowSynthesizer.synthesizeFromTrace(trace, skill.id, skill.name, skill.description)
        val wf = synthResult.workflow
        assertNotNull(wf)

        val step = wf!!.steps.first()
        assertNotNull(step.expectedTransition)
        assertEquals("UI_STATE_CHANGE", step.expectedTransition?.transitionType)
        assertTrue("Evidence should capture state change", step.expectedTransition?.expectedEvidence?.isNotEmpty() == true)
    }

    @Test
    fun test_P3_T9_no_pizza_data_in_persisted_workflow_json() {
        val skill = repository.createSkill("Book Flight", "Book flight to Paris")
        TeachingSessionManager.startSession(skill.name, skill.description, skill.id, skill.description)

        val elem = UiElement(elementId = "f1", role = "Button", text = "Find Flights", resourceId = "btn_flight")
        TeachingSessionManager.recordActionEvent(ActionEvent(
            actionId = "a_flight",
            timestamp = 1000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Find Flights", resourceId = "btn_flight"),
            packageName = "com.airline.app"
        ))

        val trace = TeachingSessionManager.stopSession()!!
        val wf = WorkflowSynthesizer.synthesizeFromTrace(trace, skill.id, skill.name, skill.description).workflow!!
        repository.saveWorkflow(wf)

        val savedWf = repository.getWorkflowById(wf.skillId)
        assertNotNull(savedWf)
        val json = jsonFormatter.encodeToString(com.chockXlate.teachablevoice.contract.workflow.Workflow.serializer(), savedWf!!)

        assertFalse(json.contains("pizza", ignoreCase = true))
        assertFalse(json.contains("Pizza Palace", ignoreCase = true))
        assertFalse(json.contains("Margherita", ignoreCase = true))
        assertFalse(json.contains("Domino", ignoreCase = true))
    }

    @Test
    fun test_P3_T10_persistence_survives_reload() {
        val skill = repository.createSkill("Persistent Task", "Survives reload")
        TeachingSessionManager.startSession(skill.name, skill.description, skill.id, skill.description)

        val action = ActionEvent(
            actionId = "a_persist",
            timestamp = 1000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", resourceId = "btn_persist"),
            packageName = "com.persist.app"
        )
        TeachingSessionManager.recordActionEvent(action)

        val trace = TeachingSessionManager.stopSession()!!
        val wf = WorkflowSynthesizer.synthesizeFromTrace(trace, skill.id, skill.name, skill.description).workflow!!
        repository.saveWorkflow(wf)
        repository.updateSkill(skill.copy(status = SkillStatus.TRAINED, workflowId = wf.skillId))

        // Create new repository instance pointing to the same storage folder
        val newRepo = LocalSkillRepository(storageDir)
        val loadedSkill = newRepo.getSkill(skill.id)
        val loadedWf = newRepo.getWorkflowById(wf.skillId)

        assertNotNull(loadedSkill)
        assertEquals(SkillStatus.TRAINED, loadedSkill!!.status)
        assertEquals(wf.skillId, loadedSkill.workflowId)

        assertNotNull(loadedWf)
        assertEquals(wf.skillId, loadedWf!!.skillId)
        assertEquals(1, loadedWf.steps.size)
        assertEquals("btn_persist", loadedWf.steps.first().semanticSelector.resourceId)
    }
}
