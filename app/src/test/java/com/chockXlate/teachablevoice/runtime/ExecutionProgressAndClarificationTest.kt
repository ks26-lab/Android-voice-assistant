package com.chockXlate.teachablevoice.runtime

import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.recovery.RecoveryAction
import com.chockXlate.teachablevoice.runtime.recovery.RecoveryController
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.ui.ActionOutcome
import com.chockXlate.teachablevoice.runtime.ui.UiDriver
import com.chockXlate.teachablevoice.runtime.verification.PreconditionEvaluator
import com.chockXlate.teachablevoice.runtime.verification.TransitionVerifier
import com.chockXlate.teachablevoice.safety.SafetyGate
import com.chockXlate.teachablevoice.skill.repository.SkillRepository
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class ExecutionProgressAndClarificationTest {

    private val app = "test.generic.app"

    private fun ui(vararg elements: UiElement) = UiObservation(
        UiState(
            stateId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            appContext = app,
            allElements = elements.toList()
        )
    )

    private class Store(var workflow: Workflow?) : SkillRepository {
        override fun getWorkflowById(skillId: String) = workflow?.takeIf { it.skillId == skillId }
        override fun getAllWorkflows() = listOfNotNull(workflow)
        override fun saveWorkflow(workflow: Workflow): Boolean { this.workflow = workflow; return true }
        override fun deleteWorkflow(skillId: String): Boolean { workflow = null; return true }
    }

    private class FakeDriver(var screen: UiObservation) : UiDriver {
        var ready = true
        var actionsDispatched = 0
        val executedSteps = mutableListOf<String>()
        var effect: (BoundStep) -> Unit = {}
        override fun isReady() = ready
        override suspend fun observe(): UiObservation? = if (ready) screen else null
        override suspend fun awaitChange(delayMs: Long) {}
        override suspend fun execute(
            step: BoundStep,
            expectedPackage: String,
            boundary: SafetyBoundary,
            matcher: SemanticMatcher,
            gate: SafetyGate
        ): ActionOutcome {
            val before = screen
            gate.check(boundary, before, step)?.let { return ActionOutcome(false, reason = it, before = before) }
            PreconditionEvaluator(matcher).evaluate(step.preconditions, before, expectedPackage, step.stateEvidence)?.let {
                return ActionOutcome(false, reason = it, before = before)
            }
            TransitionVerifier(matcher).startingStateError(step, before)?.let {
                return ActionOutcome(false, reason = it, before = before)
            }
            val match = matcher.match(step.selector, before, step.action)
            if (match.status != MatchStatus.MATCHED) return ActionOutcome(false, reason = match.reason, before = before)
            val result = gate.dispatch(boundary, before, step) {
                actionsDispatched++
                executedSteps.add(step.source.stepId)
                effect(step)
                true
            }
            return ActionOutcome(result.attempted, result.accepted, result.reason, before)
        }
    }

    private fun <T> runSync(block: suspend () -> T): T {
        var completion: Result<T>? = null
        val done = CountDownLatch(1)
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completion = result; done.countDown() }
        })
        check(done.await(5, TimeUnit.SECONDS)) { "Test coroutine did not complete" }
        return completion!!.getOrThrow()
    }

    @Test
    fun testMissingRequiredSlotGeneratesClarificationAndResumes() {
        val slot = WorkflowSlot(name = "item_name", type = SlotType.TEXT, required = true)
        val step = WorkflowStep(
            stepId = "step_input",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/input"),
            parameters = mapOf("input_parameter" to "\${item_name}"),
            expectedTransition = ExpectedTransition(
                expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.GENERIC_STATE_CHANGE))
            )
        )
        val subtask = WorkflowSubtask(
            subtaskId = "subtask_1",
            label = "SEARCH_ITEM",
            stepIds = listOf("step_input")
        )
        val wf = Workflow(
            skillId = "skill_order",
            name = "Order Workflow",
            intent = "order",
            appContext = app,
            slots = listOf(slot),
            steps = listOf(step),
            subtasks = listOf(subtask)
        )

        val driver = FakeDriver(ui(UiElement(elementId = "e1", role = "EditText", resourceId = "id/input", isEditable = true)))
        driver.effect = {
            driver.screen = ui(UiElement(elementId = "e1_after", role = "EditText", resourceId = "id/input", text = "Veggie Pizza", isEditable = true))
        }
        val engine = ExecutionEngine(Store(wf), driver)

        // 1. Initial request without the required slot
        val req = ExecutionRequest(executionId = "exec_100", skillId = "skill_order", boundSlots = emptyMap())
        val report = runSync { engine.execute(req) }

        // Must pause in WAITING_FOR_USER
        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertNotNull(report.result.clarificationRequest)
        assertEquals("item_name", report.result.clarificationRequest!!.requiredSlot)
        assertEquals(ProgressStatus.WAITING_FOR_USER, report.result.progress?.overallStatus)
        assertEquals(0, driver.actionsDispatched)

        // 2. Provide clarification response
        val response = ClarificationResponse(
            executionId = "exec_100",
            providedSlotValue = "Veggie Pizza"
        )
        val resumeReport = runSync { engine.resume(response) }

        // Must complete successfully
        assertTrue(resumeReport.result.success)
        assertEquals(ExecutionState.COMPLETED, resumeReport.result.finalState)
        assertEquals(ProgressStatus.COMPLETED, resumeReport.result.progress?.overallStatus)
        assertEquals(1, driver.actionsDispatched)
        assertEquals(listOf("step_input"), driver.executedSteps)
    }

    @Test
    fun testAmbiguousTargetGeneratesClarificationAndResumes() {
        val step = WorkflowStep(
            stepId = "step_pick",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Select"),
            expectedTransition = ExpectedTransition(
                expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.GENERIC_STATE_CHANGE))
            )
        )
        val subtask = WorkflowSubtask(
            subtaskId = "st_pick",
            label = "SELECTION",
            stepIds = listOf("step_pick")
        )
        val wf = Workflow(
            skillId = "skill_ambiguous",
            name = "Select Workflow",
            intent = "select",
            appContext = app,
            steps = listOf(step),
            subtasks = listOf(subtask)
        )

        // Two buttons on screen -> AMBIGUOUS match
        val driver = FakeDriver(ui(
            UiElement(elementId = "b1", role = "Button", text = "Select", resourceId = "item_1", isClickable = true),
            UiElement(elementId = "b2", role = "Button", text = "Select", resourceId = "item_2", isClickable = true)
        ))
        driver.effect = {
            driver.screen = ui(UiElement(elementId = "after_state", role = "TextView", text = "Selected"))
        }
        val engine = ExecutionEngine(Store(wf), driver)

        val req = ExecutionRequest(executionId = "exec_amb", skillId = "skill_ambiguous")
        val report = runSync { engine.execute(req) }

        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertNotNull(report.result.clarificationRequest)
        assertTrue(report.result.clarificationRequest!!.candidateDescriptions.isNotEmpty())

        // Resume by selecting candidate 0
        val resumeReport = runSync {
            engine.resume(ClarificationResponse(executionId = "exec_amb", selectedCandidateIndex = 0))
        }

        assertTrue(resumeReport.result.success)
        assertEquals(ExecutionState.COMPLETED, resumeReport.result.finalState)
        assertEquals(1, driver.actionsDispatched)
    }

    @Test
    fun testWrongExecutionIdRejected() {
        val slot = WorkflowSlot(name = "q", type = SlotType.TEXT, required = true)
        val step = WorkflowStep(
            stepId = "step_1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Go")
        )
        val wf = Workflow(skillId = "skill_wrong", name = "Test", intent = "test", appContext = app, slots = listOf(slot), steps = listOf(step))
        val driver = FakeDriver(ui(UiElement(elementId = "e", role = "Button", text = "Go", isClickable = true)))
        val engine = ExecutionEngine(Store(wf), driver)

        runSync { engine.execute(ExecutionRequest(executionId = "correct_id", skillId = "skill_wrong")) }

        // Send response with wrong ID
        val res = runSync { engine.resume(ClarificationResponse(executionId = "wrong_id", providedSlotValue = "val")) }
        assertFalse(res.result.success)
        assertTrue(res.result.errorMessage!!.contains("does not match active paused execution"))
    }

    @Test
    fun testCompletedSubtasksNotRepeatedOnResume() {
        val step1 = WorkflowStep(
            stepId = "s1_done",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Step 1"),
            expectedTransition = ExpectedTransition(expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.GENERIC_STATE_CHANGE)))
        )
        val step2 = WorkflowStep(
            stepId = "s2_ambiguous",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Pick Option"),
            expectedTransition = ExpectedTransition(expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.GENERIC_STATE_CHANGE)))
        )
        val subtask1 = WorkflowSubtask(subtaskId = "subtask_1", label = "SUBTASK_1", stepIds = listOf("s1_done"))
        val subtask2 = WorkflowSubtask(subtaskId = "subtask_2", label = "SUBTASK_2", stepIds = listOf("s2_ambiguous"))

        val wf = Workflow(
            skillId = "skill_multi_subtask",
            name = "Multi",
            intent = "multi",
            appContext = app,
            steps = listOf(step1, step2),
            subtasks = listOf(subtask1, subtask2)
        )

        // Screen initially has Step 1 button
        val driver = FakeDriver(ui(
            UiElement(elementId = "btn1", role = "Button", text = "Step 1", isClickable = true)
        ))
        // When step 1 executes, screen changes to show two ambiguous Pick Option buttons for subtask 2
        driver.effect = { step ->
            if (step.source.stepId == "s1_done") {
                driver.screen = ui(
                    UiElement(elementId = "opt_1", role = "Button", text = "Pick Option", resourceId = "opt_1", isClickable = true),
                    UiElement(elementId = "opt_2", role = "Button", text = "Pick Option", resourceId = "opt_2", isClickable = true)
                )
            } else {
                driver.screen = ui(
                    UiElement(elementId = "final_state", role = "TextView", text = "All Complete")
                )
            }
        }
        val engine = ExecutionEngine(Store(wf), driver)

        // 1. Initial run: subtask 1 executes and completes, then subtask 2 hits ambiguity and pauses
        val report = runSync { engine.execute(ExecutionRequest(executionId = "exec_sub", skillId = "skill_multi_subtask")) }
        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertEquals(1, driver.actionsDispatched)
        assertEquals(listOf("s1_done"), driver.executedSteps)
        assertEquals("subtask_2", report.result.clarificationRequest?.pausedSubtaskId)
        assertEquals(listOf("subtask_1"), report.result.progress?.completedSubtaskIds)

        // 2. Resume subtask 2 by selecting candidate 0
        val resumeReport = runSync {
            engine.resume(ClarificationResponse(executionId = "exec_sub", selectedCandidateIndex = 0))
        }

        assertTrue(resumeReport.result.success)
        assertEquals(2, driver.actionsDispatched)
        // Ensure s1_done was only executed ONCE (never replayed upon resume)
        assertEquals(1, driver.executedSteps.count { it == "s1_done" })
        assertEquals(1, driver.executedSteps.count { it == "s2_ambiguous" })
        assertEquals(listOf("subtask_1", "subtask_2"), resumeReport.result.progress?.completedSubtaskIds)
    }

    @Test
    fun testSensitiveClarificationNeverAsksOrAcceptsCredentials() {
        val sensitiveSlot = WorkflowSlot(name = "user_password", type = SlotType.TEXT, required = true)
        val step = WorkflowStep(stepId = "s_pwd", semanticAction = "INPUT_TEXT", semanticSelector = SemanticSelector())
        val wf = Workflow(skillId = "skill_sec", name = "Sec", intent = "sec", appContext = app, slots = listOf(sensitiveSlot), steps = listOf(step))

        val driver = FakeDriver(ui())
        val engine = ExecutionEngine(Store(wf), driver)

        // Must NOT pause for clarification; must PAUSE_FOR_HANDOFF immediately for security
        val report = runSync { engine.execute(ExecutionRequest(executionId = "sec_1", skillId = "skill_sec")) }
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertNull(report.result.clarificationRequest)
        val err = report.result.errorMessage.orEmpty()
        assertTrue(err.contains("credential", ignoreCase = true) || err.contains("prohibited", ignoreCase = true))
    }

    @Test
    fun testUncertainSideEffectNeverRepeatsInRecovery() {
        val recovery = RecoveryController()
        val policy = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)

        // If action was attempted, uncertain side effect MUST NOT retry
        val decision = recovery.decideSubtaskRecovery(
            policy = policy,
            retries = 0,
            actionAttempted = true
        )
        assertEquals(RecoveryAction.HANDOFF, decision.action)
        assertTrue(decision.reason.contains("duplicate a side effect"))
    }

    @Test
    fun testSubtaskLocalRecoveryHandlesTargetTemporarilyAbsent() {
        val recovery = RecoveryController()
        val policy = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)

        // Target absent before action dispatch
        val decision = recovery.decideSubtaskRecovery(
            policy = policy,
            retries = 0,
            actionAttempted = false,
            targetAbsentOrAmbiguous = true
        )
        assertEquals(RecoveryAction.RERESOLVE_TARGET, decision.action)
        assertTrue(decision.reason.contains("re-resolve"))
    }

    @Test
    fun testSafetyBoundaryPreventsRecoveryAction() {
        val recovery = RecoveryController()
        val policy = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)

        val decision = recovery.decideSubtaskRecovery(
            policy = policy,
            retries = 0,
            actionAttempted = false,
            safetyBlocked = true
        )
        assertEquals(RecoveryAction.HANDOFF, decision.action)
        assertTrue(decision.reason.contains("Safety boundary"))
    }

    @Test
    fun testRecoveryBudgetExhaustionHandoffs() {
        val recovery = RecoveryController()
        val policy = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)

        val decision = recovery.decideSubtaskRecovery(
            policy = policy,
            retries = 2,
            actionAttempted = false
        )
        assertEquals(RecoveryAction.HANDOFF, decision.action)
        assertTrue(decision.reason.contains("exhausted"))
    }
}
