package com.chockXlate.teachablevoice.runtime.trace

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import java.util.UUID

data class RuntimeDiagnostic(
    val stepId: String?,
    val state: ExecutionState,
    val decision: ExecutionDecision,
    val verificationEvidence: com.chockXlate.teachablevoice.runtime.verification.EvidenceEvaluationResult? = null
)
data class RuntimeReport(
    val result: ExecutionResult,
    val trace: ExecutionTrace,
    val diagnostics: List<RuntimeDiagnostic>,
    val stoppedStepId: String?
)

/** All UI/input text is omitted, including benign text, so later screens cannot leak secrets. */
class ExecutionTraceRecorder(private val request: ExecutionRequest) {
    private val startTime = System.currentTimeMillis()
    private val startNanos = System.nanoTime()
    private val events = mutableListOf<TraceEvent>()
    private val diagnostics = mutableListOf<RuntimeDiagnostic>()

    fun decision(
        stepId: String?,
        state: ExecutionState,
        type: DecisionType,
        reason: String,
        confidence: Double = 1.0,
        evidence: com.chockXlate.teachablevoice.runtime.verification.EvidenceEvaluationResult? = null
    ) {
        diagnostics.add(RuntimeDiagnostic(stepId, state, ExecutionDecision(
            decisionId = UUID.randomUUID().toString(), type = type, reason = reason, confidence = confidence
        ), evidence))
    }

    fun action(step: BoundStep): String {
        val id = UUID.randomUUID().toString()
        val time = System.currentTimeMillis()
        val event = ActionEvent(actionId = id, timestamp = time, actionType = step.action.name,
            semanticSelector = SemanticSelector(), inputData = null)
        events.add(TraceEvent.Action(id, time, event))
        return id
    }

    fun transition(actionId: String, before: UiObservation, after: UiObservation) {
        val id = UUID.randomUUID().toString()
        val time = System.currentTimeMillis()
        events.add(TraceEvent.State(id, time, StateEvent(
            stateEventId = id, timestamp = time, beforeState = redact(before.state),
            afterState = redact(after.state), causeActionId = actionId
        )))
    }

    fun finish(
        state: ExecutionState,
        completed: Int,
        total: Int,
        reason: String?,
        stoppedStep: String?,
        progress: ExecutionProgress? = null,
        clarificationRequest: ClarificationRequest? = null
    ): RuntimeReport {
        val result = ExecutionResult(
            executionId = request.executionId,
            success = state == ExecutionState.COMPLETED && completed == total && total > 0,
            finalState = state,
            stepsCompleted = completed,
            totalSteps = total,
            errorMessage = reason,
            durationMs = (System.nanoTime() - startNanos) / 1_000_000,
            progress = progress,
            clarificationRequest = clarificationRequest
        )
        return RuntimeReport(
            result = result,
            trace = ExecutionTrace(
                executionId = request.executionId,
                skillId = request.skillId,
                startTime = startTime,
                endTime = System.currentTimeMillis(),
                events = events.toList(),
                result = result,
                progress = progress
            ),
            diagnostics = diagnostics.toList(),
            stoppedStepId = stoppedStep
        )
    }

    private fun redact(state: UiState): UiState = state.copy(
        rootElement = null,
        allElements = state.allElements.map { it.copy(
            text = null, textSlot = null, contentDescription = null, resourceId = null,
            nearbyText = null, relativePosition = null, bounds = null, children = emptyList()
        ) }
    )
}
