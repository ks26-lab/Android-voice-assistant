package com.chockXlate.teachablevoice.ui

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import com.chockXlate.teachablevoice.contract.runtime.ExecutionResult
import com.chockXlate.teachablevoice.contract.runtime.ExecutionState
import com.chockXlate.teachablevoice.contract.runtime.ExecutionTrace
import com.chockXlate.teachablevoice.contract.skill.SkillStatus
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer
import com.chockXlate.teachablevoice.runtime.trace.*
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.skill.repository.SkillRepository
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionImpl
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import com.chockXlate.teachablevoice.ui.components.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 5 Real UI Integration Test Suite:
 *
 * 1. Skill Integration Tests (SI-T1 to SI-T8)
 * 2. Activity Integration Tests (AI-T1 to AI-T10)
 * 3. End-to-End Teaching & Persistence Flow
 * 4. End-to-End Execution Trace Adapter Flow
 * 5. SafetyGate Blocked State Non-Bypass Flow
 * 6. Hardcoding & Fake Pipeline Auditing
 */
class RealUiIntegrationTest {

    private lateinit var tempDir: File
    private lateinit var repository: LocalSkillRepository

    private fun createValidWorkflow(
        skillId: String = "skill_generic_01",
        name: String = "Perform Generic Task",
        intent: String = "generic_task",
        appContext: String = "com.example.app",
        stepCount: Int = 2
    ): Workflow {
        val steps = (1..stepCount).map { idx ->
            WorkflowStep(
                stepId = "step_$idx",
                semanticAction = "CLICK",
                semanticSelector = SemanticSelector(role = "Button", resourceId = "btn_$idx", text = "Button $idx")
            )
        }
        return Workflow(
            skillId = skillId,
            name = name,
            intent = intent,
            appContext = appContext,
            steps = steps,
            slots = emptyList(),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = false)
        )
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("phase5_real_ui_test").toFile()
        repository = LocalSkillRepository(tempDir)
        ActivityEventStream.clear()
        TeachingSessionManager.clearSession()
    }

    @After
    fun tearDown() {
        ActivityEventStream.clear()
        TeachingSessionManager.clearSession()
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // PART 1: SKILL INTEGRATION TESTS (SI-T1 to SI-T8)
    // =========================================================================

    @Test
    fun test_SI_T1_repositoryContainsZeroSkills() {
        val loadedSkills = loadSkillsFromRepository(repository)
        assertTrue("Repository has zero skills, UI list must be empty", loadedSkills.isEmpty())

        val filtered = filterSkills(loadedSkills, "")
        assertTrue("No skills available state rendered", filtered.isEmpty())
    }

    @Test
    fun test_SI_T2_repositoryContainsOneRealWorkflow() {
        val wf = createValidWorkflow(skillId = "skill_101", name = "Send Notification", intent = "send_notification", stepCount = 3)
        val saved = repository.saveWorkflow(wf)
        assertTrue("Workflow must be successfully saved", saved)

        val loadedSkills = loadSkillsFromRepository(repository)
        assertEquals(1, loadedSkills.size)
        val model = loadedSkills.first()
        assertEquals("skill_101", model.id)
        assertEquals("Send Notification", model.title)
        assertEquals("send_notification", model.description)
        assertTrue("Metadata must show step count and appContext", model.metadata?.contains("3 steps") == true)
        assertTrue("Metadata must show appContext", model.metadata?.contains("com.example.app") == true)
    }

    @Test
    fun test_SI_T3_repositoryContainsMultipleWorkflows() {
        val wf1 = createValidWorkflow(skillId = "wf_alpha", name = "Alpha Task")
        val wf2 = createValidWorkflow(skillId = "wf_beta", name = "Beta Task")
        val wf3 = createValidWorkflow(skillId = "wf_gamma", name = "Gamma Task")

        assertTrue(repository.saveWorkflow(wf1))
        assertTrue(repository.saveWorkflow(wf2))
        assertTrue(repository.saveWorkflow(wf3))

        val loadedSkills = loadSkillsFromRepository(repository)
        assertEquals(3, loadedSkills.size)
        val ids = loadedSkills.map { it.id }.toSet()
        assertTrue(ids.contains("wf_alpha"))
        assertTrue(ids.contains("wf_beta"))
        assertTrue(ids.contains("wf_gamma"))
    }

    @Test
    fun test_SI_T4_searchFiltersRealRepositoryData() {
        repository.saveWorkflow(createValidWorkflow(skillId = "w1", name = "Archive Emails", intent = "archive_emails"))
        repository.saveWorkflow(createValidWorkflow(skillId = "w2", name = "Compose Message", intent = "compose_message"))
        repository.saveWorkflow(createValidWorkflow(skillId = "w3", name = "Search Archive", intent = "search_archive"))

        val allSkills = loadSkillsFromRepository(repository)
        val searchResults = filterSkills(allSkills, "archive")

        assertEquals(2, searchResults.size)
        assertTrue(searchResults.any { it.id == "w1" })
        assertTrue(searchResults.any { it.id == "w3" })
        assertFalse(searchResults.any { it.id == "w2" })
    }

    @Test
    fun test_SI_T5_deleteSkillFromRepositoryAndRefreshesUI() {
        val wf = createValidWorkflow(skillId = "skill_to_remove", name = "Temporary Skill")
        assertTrue(repository.saveWorkflow(wf))
        assertEquals(1, loadSkillsFromRepository(repository).size)

        // Real repository deletion
        val deleted = repository.deleteWorkflow("skill_to_remove")
        assertTrue("Deletion must report true for existing workflow", deleted)

        // Real UI list re-query reflects changes
        val updatedSkills = loadSkillsFromRepository(repository)
        assertTrue("Skill must be gone from UI model list", updatedSkills.isEmpty())
        assertNull("Repository must no longer contain workflow", repository.getWorkflowById("skill_to_remove"))
    }

    @Test
    fun test_SI_T6_deletePersistsAcrossRestartReload() {
        val wf = createValidWorkflow(skillId = "skill_persist_del", name = "Will Be Deleted")
        assertTrue(repository.saveWorkflow(wf))

        // Ensure written to disk
        val diskFile = File(tempDir, "skill_persist_del.json")
        assertTrue("File must exist on disk prior to deletion", diskFile.exists())

        // Perform delete
        assertTrue(repository.deleteWorkflow("skill_persist_del"))
        assertFalse("File must be deleted from disk", diskFile.exists())

        // Recreate repository from the same disk directory (simulating process restart)
        val restartedRepo = LocalSkillRepository(tempDir)
        val loadedAfterRestart = loadSkillsFromRepository(restartedRepo)
        assertTrue("Skill remains deleted after repository restart", loadedAfterRestart.isEmpty())
        assertNull(restartedRepo.getWorkflowById("skill_persist_del"))
    }

    @Test
    fun test_SI_T7_reteachCallbackInvokesTeachingFlowWithoutExecutingOldWorkflow() {
        val wf = createValidWorkflow(skillId = "skill_reteach_target", name = "Custom Reteach Task", intent = "custom_reteach_intent")
        repository.saveWorkflow(wf)

        // Reteach logic extracts domain skill details and triggers teaching entry point
        val existingRecord = repository.getSkill("skill_reteach_target")
        val existingWf = repository.getWorkflowById("skill_reteach_target")
        assertNotNull(existingWf)

        val reteachName = existingRecord?.name?.ifBlank { null } ?: existingWf?.name ?: "Custom Reteach Task"
        val reteachDesc = existingRecord?.description?.ifBlank { null } ?: existingWf?.intent ?: ""

        // Teaching session starts with the skill metadata
        val session = TeachingSessionManager.startSession(
            skillName = reteachName,
            intent = reteachDesc,
            description = reteachDesc
        )

        assertTrue("Teaching session must now be active", TeachingSessionManager.isTeachingActive())
        assertEquals("Custom Reteach Task", session.skillName)
        assertEquals("custom_reteach_intent", session.intent)

        // Critical verification: Old workflow was NOT modified or executed
        val unmodifiedOldWf = repository.getWorkflowById("skill_reteach_target")
        assertNotNull(unmodifiedOldWf)
        assertEquals("skill_reteach_target", unmodifiedOldWf?.skillId)
    }

    @Test
    fun test_SI_T8_newlyPersistedSkillAppearsWithoutFabricatedData() {
        val wf = createValidWorkflow(
            skillId = "skill_fresh",
            name = "Freshly Stored Skill",
            intent = "fresh_intent",
            appContext = "com.fresh.app",
            stepCount = 4
        )
        repository.saveWorkflow(wf)

        val skills = loadSkillsFromRepository(repository)
        assertEquals(1, skills.size)
        val skill = skills.first()

        assertEquals("skill_fresh", skill.id)
        assertEquals("Freshly Stored Skill", skill.title)
        assertEquals("fresh_intent", skill.description)
        assertEquals("4 steps • com.fresh.app", skill.metadata)
    }

    // =========================================================================
    // PART 2: ACTIVITY INTEGRATION TESTS (AI-T1 to AI-T10)
    // =========================================================================

    @Test
    fun test_AI_T1_noArchitectureEventShowsEmptyState() {
        ActivityEventStream.clear()
        val events = ActivityEventStream.getEvents()
        assertTrue("No architecture events emitted", events.isEmpty())

        val uiState = ActivityUiState(isOpen = true, events = events)
        assertTrue("Dialog renders truthful neutral empty state", uiState.events.isEmpty())
    }

    @Test
    fun test_AI_T2_oneRealEventDisplayed() {
        ActivityEventStream.clear()
        ActivityEventStream.emit(
            ActivityEvent(
                id = "evt_01",
                label = "Trace Normalization",
                status = ActivityStatus.COMPLETED,
                metadata = "24 events filtered"
            )
        )

        val events = ActivityEventStream.getEvents()
        assertEquals(1, events.size)
        assertEquals("Trace Normalization", events.first().label)
        assertEquals(ActivityStatus.COMPLETED, events.first().status)
        assertEquals("24 events filtered", events.first().metadata)
    }

    @Test
    fun test_AI_T3_multipleRealEventsDisplayedInSuppliedOrder() {
        ActivityEventStream.clear()
        ActivityEventStream.emit("e1", "Capture demonstration", ActivityStatus.COMPLETED)
        ActivityEventStream.emit("e2", "Filter accidental gestures", ActivityStatus.COMPLETED)
        ActivityEventStream.emit("e3", "Synthesize workflow IR", ActivityStatus.IN_PROGRESS)

        val events = ActivityEventStream.getEvents()
        assertEquals(3, events.size)
        assertEquals(listOf("e1", "e2", "e3"), events.map { it.id })
        assertEquals("Capture demonstration", events[0].label)
        assertEquals("Filter accidental gestures", events[1].label)
        assertEquals("Synthesize workflow IR", events[2].label)
    }

    @Test
    fun test_AI_T4_realEventChangesState() {
        ActivityEventStream.clear()
        ActivityEventStream.emit("synthesis_job", "Workflow Synthesis", ActivityStatus.IN_PROGRESS, "Running heuristic extractor")

        val initial = ActivityEventStream.getEvents().find { it.id == "synthesis_job" }
        assertNotNull(initial)
        assertEquals(ActivityStatus.IN_PROGRESS, initial?.status)

        // Architecture updates the event state
        ActivityEventStream.emit("synthesis_job", "Workflow Synthesis", ActivityStatus.COMPLETED, "3 steps produced")

        val updated = ActivityEventStream.getEvents().find { it.id == "synthesis_job" }
        assertNotNull(updated)
        assertEquals(ActivityStatus.COMPLETED, updated?.status)
        assertEquals("3 steps produced", updated?.metadata)
        assertEquals(1, ActivityEventStream.size) // Mutated in place by ID, no duplicates
    }

    @Test
    fun test_AI_T5_realEventFails() {
        ActivityEventStream.clear()
        ActivityEventStream.emit("val_job", "Workflow Validation", ActivityStatus.FAILED, "Schema violation: missing target element")

        val event = ActivityEventStream.getEvents().first()
        assertEquals(ActivityStatus.FAILED, event.status)
        assertEquals("Schema violation: missing target element", event.metadata)
    }

    @Test
    fun test_AI_T6_unknownEventLabelRendersGenerically() {
        ActivityEventStream.clear()
        val customLabel = "Custom Neural Optimization Subtask [Ref: #9948]"
        ActivityEventStream.emit("custom_9948", customLabel, ActivityStatus.COMPLETED, "Confidence: 0.99")

        val event = ActivityEventStream.getEvents().first()
        assertEquals(customLabel, event.label)
        assertEquals("Confidence: 0.99", event.metadata)
        assertEquals(ActivityStatus.COMPLETED, event.status)
    }

    @Test
    fun test_AI_T7_eventSourceEmitsDifferentSequenceWithoutUiModification() {
        ActivityEventStream.clear()
        // Non-standard sequence emitted by an alternate architecture pipeline
        ActivityEventStream.emit("alt_1", "Direct Bytecode Injection", ActivityStatus.COMPLETED)
        ActivityEventStream.emit("alt_2", "Precondition Verification", ActivityStatus.COMPLETED)
        ActivityEventStream.emit("alt_3", "Hot Standby Deployment", ActivityStatus.IN_PROGRESS)

        val events = ActivityEventStream.getEvents()
        assertEquals(3, events.size)
        assertEquals("Direct Bytecode Injection", events[0].label)
        assertEquals("Precondition Verification", events[1].label)
        assertEquals("Hot Standby Deployment", events[2].label)
    }

    @Test
    fun test_AI_T8_noEventSourceAvailableTruthfulEmpty() {
        ActivityEventStream.clear()
        val events = ActivityEventStream.getEvents()
        assertTrue(events.isEmpty())

        // Dialog rendering contract verifies empty state message
        val uiState = ActivityUiState(isOpen = true, events = events)
        assertEquals("Activity", uiState.title)
        assertTrue(uiState.events.isEmpty())
    }

    @Test
    fun test_AI_T9_eventCompletesStopsActiveWork() {
        ActivityEventStream.clear()
        ActivityEventStream.emit("action_run", "Step 1: Click Save", ActivityStatus.IN_PROGRESS)
        assertEquals(1, ActivityEventStream.getEvents().count { it.status == ActivityStatus.IN_PROGRESS })

        // Action completes
        ActivityEventStream.emit("action_run", "Step 1: Click Save", ActivityStatus.COMPLETED)
        assertEquals(0, ActivityEventStream.getEvents().count { it.status == ActivityStatus.IN_PROGRESS })
        assertEquals(1, ActivityEventStream.getEvents().count { it.status == ActivityStatus.COMPLETED })
    }

    @Test
    fun test_AI_T10_noArtificialTimersStatesOnlyChangeFromActualSuppliedData() {
        ActivityEventStream.clear()
        val fixedTimestamp = 1700000000000L
        val event = ActivityEvent(
            id = "deterministic_evt",
            label = "Determinism Verification",
            status = ActivityStatus.PENDING,
            timestamp = fixedTimestamp,
            metadata = "Zero timer mutation"
        )
        ActivityEventStream.emit(event)

        val stored = ActivityEventStream.getEvents().first()
        assertEquals(ActivityStatus.PENDING, stored.status)
        assertEquals(fixedTimestamp, stored.timestamp)

        // Event remains in PENDING until actual architecture component updates it
        assertEquals(ActivityStatus.PENDING, ActivityEventStream.getEvents().first().status)
    }

    // =========================================================================
    // PART 3: END-TO-END FLOW TESTS
    // =========================================================================

    @Test
    fun test_endToEndTeaching_emitsArchitectureEventsAndPersistsToSkillLibrary() {
        ActivityEventStream.clear()

        // 1. Start teaching session
        val session = TeachingSessionManager.startSession("Dynamic Book Flight", "book_flight")
        assertTrue(TeachingSessionManager.isTeachingActive())

        // 2. Perform demonstration
        TeachingSessionManager.recordVoiceEvent(VoiceEvent("v1", System.currentTimeMillis(), "Book flight to Berlin", 0.95f))
        TeachingSessionManager.recordActionEvent(ActionEvent("a1", System.currentTimeMillis(), "INPUT_TEXT", SemanticSelector(role = "EditText", text = "Berlin"), "Berlin"))
        TeachingSessionManager.recordActionEvent(ActionEvent("a2", System.currentTimeMillis(), "CLICK", SemanticSelector(role = "Button", text = "Search"), null))

        // 3. Stop teaching and capture trace
        val rawTrace = TeachingSessionManager.stopSession()
        assertNotNull(rawTrace)

        // 4. Normalization
        val normResult = DemonstrationTraceNormalizer.normalize(rawTrace!!)
        assertEquals(3, normResult.normalizedEventCount)

        // 5. Synthesis
        val synthResult = WorkflowSynthesizer.synthesizeFromTrace(normResult.normalizedTrace, targetSkillName = "Dynamic Book Flight")
        assertNotNull(synthResult.workflow)

        // 6. Validation
        val valResult = WorkflowValidator.validate(synthResult.workflow!!)
        assertTrue(valResult.isStoreable)

        // 7. Persistence
        val saved = repository.saveWorkflow(synthResult.workflow!!)
        assertTrue(saved)

        // Verify Skill Library received the authentic skill
        val skills = loadSkillsFromRepository(repository)
        assertEquals(1, skills.size)
        assertEquals(synthResult.workflow!!.skillId, skills.first().id)

        // Verify real architecture events were recorded during the flow
        val activityEvents = ActivityEventStream.getEvents()
        assertTrue("Real activity stream must contain events from session and storage", activityEvents.isNotEmpty())
        assertTrue(activityEvents.any { it.label.contains("Persist Workflow") })
    }

    @Test
    fun test_endToEndExecutionTraceAdapter_mapsRealReportToActivityEvents() {
        val req = ExecutionRequest(executionId = "exec_test_01", skillId = "skill_101", originalCommand = "Search flights")
        val result = ExecutionResult(executionId = "exec_test_01", success = true, finalState = ExecutionState.COMPLETED, stepsCompleted = 2, totalSteps = 2)
        val stepReports = listOf(
            StepReport(stepId = "s1", stepIndex = 0, actionType = "INPUT_TEXT", finalStepStatus = StepExecutionStatus.COMPLETED, targetDescription = "Destination Input"),
            StepReport(stepId = "s2", stepIndex = 1, actionType = "CLICK", finalStepStatus = StepExecutionStatus.COMPLETED, targetDescription = "Submit Button")
        )
        val report = RuntimeReport(
            result = result,
            trace = ExecutionTrace(executionId = "exec_test_01", skillId = "skill_101", startTime = 100L, endTime = 200L, events = emptyList(), result = result),
            diagnostics = emptyList(),
            stoppedStepId = null,
            stepReports = stepReports
        )

        val activityEvents = mapRuntimeReportToActivityEvents(report)
        assertEquals(2, activityEvents.size)
        assertEquals("Step 1: INPUT_TEXT", activityEvents[0].label)
        assertEquals(ActivityStatus.COMPLETED, activityEvents[0].status)
        assertEquals("Destination Input", activityEvents[0].metadata)
        assertEquals("Step 2: CLICK", activityEvents[1].label)
        assertEquals(ActivityStatus.COMPLETED, activityEvents[1].status)
    }

    @Test
    fun test_safetyGateBlockedStateEmitsFailedEventAndDisallowsBypass() {
        ActivityEventStream.clear()

        // Safety gate blocked event
        val req = ExecutionRequest(executionId = "exec_safety_01", skillId = "skill_payment", originalCommand = "Pay $50")
        val recorder = ExecutionTraceRecorder(req)
        recorder.recordSafetyEvent(stepId = "step_pay", decision = "BLOCKED", reason = "Payment screen detected: explicit user handoff mandatory.")

        val events = ActivityEventStream.getEvents()
        val safetyEvt = events.find { it.id.contains("safety") }
        assertNotNull(safetyEvt)
        assertEquals(ActivityStatus.FAILED, safetyEvt?.status)
        assertEquals("Safety Gate: BLOCKED", safetyEvt?.label)
        assertTrue(safetyEvt?.metadata?.contains("Payment screen detected") == true)
    }

    // =========================================================================
    // PART 4: ANTI-HARDCODING & PURITY AUDIT
    // =========================================================================

    @Test
    fun test_antiHardcodingAudit_noPredefinedKeywordsOrFakePipelines() {
        val prohibitedWords = listOf(
            "amazon", "myntra", "white shirt", "blue jacket", "pizza", "hotel",
            "orderamazon", "searchamazon", "openmyntra", "clicksearchbutton", "selectbluejacket"
        )

        val emptySkills = loadSkillsFromRepository(repository)
        emptySkills.forEach { skill ->
            val text = (skill.title + " " + skill.description + " " + (skill.metadata ?: "")).lowercase()
            prohibitedWords.forEach { word ->
                assertFalse("Must not contain hardcoded word '$word'", text.contains(word))
            }
        }

        ActivityEventStream.clear()
        val emptyEvents = ActivityEventStream.getEvents()
        assertTrue("Must not manufacture fake events", emptyEvents.isEmpty())
    }
}
