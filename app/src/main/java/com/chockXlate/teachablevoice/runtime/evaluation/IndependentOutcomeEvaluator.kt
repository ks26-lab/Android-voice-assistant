package com.chockXlate.teachablevoice.runtime.evaluation

import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.workflow.EvidenceType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.ui.UiObservation

/**
 * Pure, deterministic Independent Outcome Evaluator for complete workflow executions.
 * Never trusts ExecutionResult.success blindly.
 * Evaluates completed subtasks, final transition evidence, safety boundaries,
 * unresolved clarifications, and trace evidence.
 *
 * Invariant: Never dispatches actions. Pure evaluator.
 */
object IndependentOutcomeEvaluator {

    fun evaluate(
        workflow: Workflow,
        trace: ExecutionTrace,
        result: ExecutionResult,
        finalUi: UiObservation? = null,
        matcher: SemanticMatcher = SemanticMatcher()
    ): OutcomeEvaluation {
        val details = mutableListOf<String>()

        // 1. Clarification check
        if (result.finalState == ExecutionState.WAITING_FOR_USER || result.clarificationRequest != null) {
            return OutcomeEvaluation(
                schemaVersion = "1.0",
                assessment = OutcomeAssessment.INCOMPLETE,
                reason = "Execution is paused waiting for user clarification.",
                details = listOf("Unresolved clarification request: ${result.clarificationRequest?.reason ?: "waiting for user"}")
            )
        }

        // 2. Safety State & Handoff
        if (result.finalState == ExecutionState.PAUSED_FOR_HANDOFF || result.finalState == ExecutionState.PAUSED_FOR_SAFETY_CONFIRMATION) {
            return OutcomeEvaluation(
                schemaVersion = "1.0",
                assessment = OutcomeAssessment.INCOMPLETE,
                reason = "Execution paused for safety boundary or manual user handoff.",
                details = listOf(result.errorMessage ?: "Safety handoff active")
            )
        }

        if (result.finalState == ExecutionState.ABORTED) {
            return OutcomeEvaluation(
                schemaVersion = "1.0",
                assessment = OutcomeAssessment.INCOMPLETE,
                reason = "Execution was aborted prior to verified completion.",
                details = listOf(result.errorMessage ?: "Aborted by user or policy")
            )
        }

        // 3. Failed subtasks
        val progress = result.progress ?: trace.progress
        if (progress != null) {
            val failedSubtask = progress.subtasks.find { it.status == ProgressStatus.FAILED }
            if (failedSubtask != null) {
                return OutcomeEvaluation(
                    schemaVersion = "1.0",
                    assessment = OutcomeAssessment.FAILURE_SUPPORTED,
                    reason = "Required subtask '${failedSubtask.label}' failed: ${failedSubtask.failureReason ?: "unverified"}",
                    details = listOf("Subtask ID: ${failedSubtask.subtaskId}")
                )
            }
        }

        // 4. Step Count & False-Positive Success Checks
        if (result.totalSteps > 0 && result.stepsCompleted < result.totalSteps) {
            return if (result.success) {
                OutcomeEvaluation(
                    schemaVersion = "1.0",
                    assessment = OutcomeAssessment.UNCERTAIN,
                    reason = "Execution claimed success but only ${result.stepsCompleted}/${result.totalSteps} steps completed.",
                    details = listOf("Incomplete step execution contradicts success flag.")
                )
            } else {
                OutcomeEvaluation(
                    schemaVersion = "1.0",
                    assessment = OutcomeAssessment.FAILURE_SUPPORTED,
                    reason = "Execution failed: only ${result.stepsCompleted}/${result.totalSteps} steps completed.",
                    details = listOf(result.errorMessage ?: "Step execution incomplete")
                )
            }
        }

        // 5. Final Step and Transition Evidence Check
        val lastStep = workflow.steps.lastOrNull()
        val stateEvents = trace.events.filterIsInstance<TraceEvent.State>()

        if (lastStep != null && workflow.steps.isNotEmpty()) {
            val expectedEvidence = lastStep.expectedTransition.effectiveEvidence()

            // If the workflow required state changes but zero state transitions were captured in trace
            if (expectedEvidence.isNotEmpty() && stateEvents.isEmpty() && result.totalSteps > 0) {
                return OutcomeEvaluation(
                    schemaVersion = "1.0",
                    assessment = OutcomeAssessment.UNCERTAIN,
                    reason = "Internal success flag reported without observable state transition evidence in execution trace.",
                    details = listOf("Trace contains 0 StateEvents.")
                )
            }

            // If final UI observation is available, verify terminal evidence against it
            if (finalUi != null) {
                // Verify expected package
                val expectedPkg = lastStep.expectedTransition.expectedPackage ?: workflow.appContext
                if (expectedPkg.isNotBlank() && finalUi.state.appContext.isNotBlank() && finalUi.state.appContext != expectedPkg) {
                    return OutcomeEvaluation(
                        schemaVersion = "1.0",
                        assessment = OutcomeAssessment.UNCERTAIN,
                        reason = "Terminal UI package '${finalUi.state.appContext}' contradicts expected package '$expectedPkg'.",
                        details = listOf("Cross-app or background escape detected in terminal state.")
                    )
                }

                // Verify specific expected terminal elements
                for (req in expectedEvidence) {
                    when (req.type) {
                        EvidenceType.ELEMENT_APPEARED, EvidenceType.ELEMENT_EXISTS -> {
                            if (req.selector != null && !matcher.present(req.selector, finalUi)) {
                                return OutcomeEvaluation(
                                    schemaVersion = "1.0",
                                    assessment = OutcomeAssessment.UNCERTAIN,
                                    reason = "Final expected UI evidence was not observed in terminal UI state: ${req.description ?: "missing element"}",
                                    details = listOf("Expected element matching selector '${req.selector.text ?: req.selector.resourceId}' not present.")
                                )
                            }
                        }
                        EvidenceType.ELEMENT_DISAPPEARED, EvidenceType.ELEMENT_NOT_EXISTS -> {
                            if (req.selector != null && matcher.present(req.selector, finalUi)) {
                                return OutcomeEvaluation(
                                    schemaVersion = "1.0",
                                    assessment = OutcomeAssessment.UNCERTAIN,
                                    reason = "Element expected to disappear was still present in terminal UI state.",
                                    details = listOf("Disappearance requirement violated.")
                                )
                            }
                        }
                        EvidenceType.EXPECTED_PACKAGE -> {
                            if (req.expectedPackage != null && finalUi.state.appContext != req.expectedPackage) {
                                return OutcomeEvaluation(
                                    schemaVersion = "1.0",
                                    assessment = OutcomeAssessment.UNCERTAIN,
                                    reason = "Terminal package '${finalUi.state.appContext}' does not match required '${req.expectedPackage}'.",
                                    details = listOf("Contradictory terminal package.")
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        // 6. Subtask Coverage Check
        if (workflow.subtasks.isNotEmpty() && progress != null) {
            val missingSubtask = workflow.subtasks.find { !progress.completedSubtaskIds.contains(it.subtaskId) }
            if (missingSubtask != null) {
                return if (result.success) {
                    OutcomeEvaluation(
                        schemaVersion = "1.0",
                        assessment = OutcomeAssessment.UNCERTAIN,
                        reason = "Execution claimed success but required subtask '${missingSubtask.label}' was not completed.",
                        details = listOf("Subtask ${missingSubtask.subtaskId} missing from completed list.")
                    )
                } else {
                    OutcomeEvaluation(
                        schemaVersion = "1.0",
                        assessment = OutcomeAssessment.FAILURE_SUPPORTED,
                        reason = "Subtask '${missingSubtask.label}' was not completed.",
                        details = listOf("Subtask ${missingSubtask.subtaskId} not finished.")
                    )
                }
            }
        }

        if (!result.success) {
            return OutcomeEvaluation(
                schemaVersion = "1.0",
                assessment = OutcomeAssessment.FAILURE_SUPPORTED,
                reason = result.errorMessage ?: "Execution failed without verified completion.",
                details = details
            )
        }

        return OutcomeEvaluation(
            schemaVersion = "1.0",
            assessment = OutcomeAssessment.SUCCESS_SUPPORTED,
            reason = "All required workflow subtasks and terminal transition evidence verified.",
            details = listOf("Steps completed: ${result.stepsCompleted}/${result.totalSteps}"),
            subtasksEvaluated = workflow.subtasks.size,
            stepsEvaluated = result.stepsCompleted
        )
    }
}
