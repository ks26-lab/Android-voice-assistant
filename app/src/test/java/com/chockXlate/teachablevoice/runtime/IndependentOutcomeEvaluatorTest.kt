package com.chockXlate.teachablevoice.runtime

import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.runtime.evaluation.IndependentOutcomeEvaluator
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class IndependentOutcomeEvaluatorTest {

    private val app = "com.test.app"

    private fun state(vararg elements: UiElement, pkg: String = app) = UiObservation(
        UiState(
            stateId = UUID.randomUUID().toString(),
            timestamp = 1000L,
            appContext = pkg,
            allElements = elements.toList()
        )
    )

    private fun dummyStateEvent(actionId: String = "act_1") = TraceEvent.State(
        eventId = "st_ev_1",
        timestamp = 1050L,
        stateEvent = StateEvent(
            stateEventId = "st_ev_1",
            timestamp = 1050L,
            causeActionId = actionId,
            beforeState = UiState(stateId = "s_before", timestamp = 1000L, appContext = app),
            afterState = UiState(stateId = "s_after", timestamp = 1050L, appContext = app)
        )
    )

    @Test
    fun testAllRequiredSubtasksVerifiedProducesSuccessSupported() {
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Submit"),
            expectedTransition = ExpectedTransition(
                expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.ELEMENT_APPEARED, selector = SemanticSelector(role = "TextView", text = "Success")))
            )
        )
        val subtask = WorkflowSubtask(subtaskId = "sub_1", label = "SUBMIT", stepIds = listOf("s1"))
        val workflow = Workflow(skillId = "wf1", name = "Wf1", intent = "intent", appContext = app, steps = listOf(step), subtasks = listOf(subtask))

        val progress = ExecutionProgress(
            executionId = "exec_1",
            completedSubtaskIds = listOf("sub_1"),
            completedStepIds = listOf("s1"),
            subtasks = listOf(SubtaskProgress(subtaskId = "sub_1", label = "SUBMIT", status = ProgressStatus.COMPLETED)),
            overallStatus = ProgressStatus.COMPLETED
        )
        val result = ExecutionResult(
            executionId = "exec_1",
            success = true,
            finalState = ExecutionState.COMPLETED,
            stepsCompleted = 1,
            totalSteps = 1,
            progress = progress
        )
        val trace = ExecutionTrace(
            executionId = "exec_1",
            skillId = "wf1",
            startTime = 1000L,
            events = listOf(dummyStateEvent("s1")),
            result = result,
            progress = progress
        )

        val finalUi = state(UiElement(elementId = "e_ok", role = "TextView", text = "Success"))

        val eval = IndependentOutcomeEvaluator.evaluate(workflow, trace, result, finalUi)
        assertEquals(OutcomeAssessment.SUCCESS_SUPPORTED, eval.assessment)
        assertTrue(eval.reason.contains("verified"))
    }

    @Test
    fun testInternalSuccessFlagButMissingFinalEvidenceReturnsUncertain() {
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Submit"),
            expectedTransition = ExpectedTransition(
                expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.ELEMENT_APPEARED, selector = SemanticSelector(role = "TextView", text = "Order Confirmed")))
            )
        )
        val workflow = Workflow(skillId = "wf_fraud", name = "Fraud", intent = "test", appContext = app, steps = listOf(step))

        // Result claims success, but trace has NO state events at all
        val result = ExecutionResult(
            executionId = "exec_fraud",
            success = true,
            finalState = ExecutionState.COMPLETED,
            stepsCompleted = 1,
            totalSteps = 1
        )
        val trace = ExecutionTrace(
            executionId = "exec_fraud",
            skillId = "wf_fraud",
            startTime = 1000L,
            events = emptyList(), // Zero state evidence!
            result = result
        )

        val eval = IndependentOutcomeEvaluator.evaluate(workflow, trace, result, null)
        // Must NOT blindly trust success flag!
        assertNotEquals(OutcomeAssessment.SUCCESS_SUPPORTED, eval.assessment)
        assertEquals(OutcomeAssessment.UNCERTAIN, eval.assessment)
        assertTrue(eval.reason.contains("without observable state transition evidence"))
    }

    @Test
    fun testUnresolvedClarificationReturnsIncomplete() {
        val workflow = Workflow(skillId = "wf_clar", name = "Clar", intent = "clar", appContext = app)
        val clarReq = ClarificationRequest(executionId = "exec_c", reason = "Ambiguous target", question = "Pick one")
        val result = ExecutionResult(
            executionId = "exec_c",
            success = false,
            finalState = ExecutionState.WAITING_FOR_USER,
            stepsCompleted = 0,
            totalSteps = 2,
            clarificationRequest = clarReq
        )
        val trace = ExecutionTrace(executionId = "exec_c", skillId = "wf_clar", startTime = 1000L, result = result)

        val eval = IndependentOutcomeEvaluator.evaluate(workflow, trace, result)
        assertEquals(OutcomeAssessment.INCOMPLETE, eval.assessment)
        assertTrue(eval.reason.contains("waiting for user clarification"))
    }

    @Test
    fun testSafetyHandoffReturnsIncomplete() {
        val workflow = Workflow(skillId = "wf_sec", name = "Sec", intent = "sec", appContext = app)
        val result = ExecutionResult(
            executionId = "exec_sec",
            success = false,
            finalState = ExecutionState.PAUSED_FOR_HANDOFF,
            stepsCompleted = 1,
            totalSteps = 3,
            errorMessage = "Credential boundary reached."
        )
        val trace = ExecutionTrace(executionId = "exec_sec", skillId = "wf_sec", startTime = 1000L, result = result)

        val eval = IndependentOutcomeEvaluator.evaluate(workflow, trace, result)
        assertEquals(OutcomeAssessment.INCOMPLETE, eval.assessment)
        assertTrue(eval.reason.contains("safety boundary or manual user handoff"))
    }

    @Test
    fun testRequiredFailedSubtaskReturnsFailureSupported() {
        val subtask = WorkflowSubtask(subtaskId = "sub_fail", label = "CHECKOUT", stepIds = listOf("step_pay"))
        val workflow = Workflow(skillId = "wf_fail", name = "Fail", intent = "fail", appContext = app, subtasks = listOf(subtask))

        val progress = ExecutionProgress(
            executionId = "exec_fail",
            subtasks = listOf(SubtaskProgress(subtaskId = "sub_fail", label = "CHECKOUT", status = ProgressStatus.FAILED, failureReason = "Payment blocked"))
        )
        val result = ExecutionResult(
            executionId = "exec_fail",
            success = false,
            finalState = ExecutionState.FAILED,
            stepsCompleted = 0,
            totalSteps = 1,
            progress = progress,
            errorMessage = "Subtask failed"
        )
        val trace = ExecutionTrace(executionId = "exec_fail", skillId = "wf_fail", startTime = 1000L, result = result, progress = progress)

        val eval = IndependentOutcomeEvaluator.evaluate(workflow, trace, result)
        assertEquals(OutcomeAssessment.FAILURE_SUPPORTED, eval.assessment)
        assertTrue(eval.reason.contains("Required subtask 'CHECKOUT' failed"))
    }

    @Test
    fun testContradictoryTerminalEvidenceReturnsUncertain() {
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Finish"),
            expectedTransition = ExpectedTransition(
                expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.EXPECTED_PACKAGE, expectedPackage = "com.expected.app"))
            )
        )
        val workflow = Workflow(skillId = "wf_contra", name = "Contra", intent = "test", appContext = "com.expected.app", steps = listOf(step))

        val result = ExecutionResult(
            executionId = "exec_contra",
            success = true,
            finalState = ExecutionState.COMPLETED,
            stepsCompleted = 1,
            totalSteps = 1
        )
        val trace = ExecutionTrace(
            executionId = "exec_contra",
            skillId = "wf_contra",
            startTime = 1000L,
            events = listOf(dummyStateEvent("s1")),
            result = result
        )

        // Final UI is on foreign package com.unknown.adware instead of com.expected.app
        val finalUi = state(UiElement(elementId = "e1", role = "TextView", text = "Ad"), pkg = "com.unknown.adware")

        val eval = IndependentOutcomeEvaluator.evaluate(workflow, trace, result, finalUi)
        assertEquals(OutcomeAssessment.UNCERTAIN, eval.assessment)
        assertTrue(eval.reason.contains("contradicts expected package"))
    }
}
