package com.chockXlate.teachablevoice.teach.bonus.clarification

import com.chockXlate.teachablevoice.contract.workflow.Workflow
import java.util.UUID

/**
 * Isolated Controller managing the Mid-Flow Clarification state machine and resume lifecycle.
 *
 * SAFETY PROPERTY:
 * Never restarts execution from step 1 when paused mid-flow.
 * Never accepts credentials or OTP inputs.
 * Enforces mandatory UI re-observation before resumption.
 */
class MidFlowClarificationController(
    private val validator: ClarificationValidator = ClarificationValidator()
) {

    private var currentState: ClarificationState = ClarificationState.RUNNING
    private var activeRequest: ClarificationRequest? = null
    private val boundSlots = mutableMapOf<String, String>()
    private val missingSlotQueue = mutableListOf<String>()
    private var currentResumePoint: ClarificationResumePoint? = null

    fun startWorkflowExecution(
        workflow: Workflow,
        suppliedSlots: Map<String, String>,
        startStepIndex: Int = 0
    ): MidFlowClarificationResult {
        boundSlots.clear()
        boundSlots.putAll(suppliedSlots)
        missingSlotQueue.clear()

        // 1. Identify missing required slots
        for (slot in workflow.slots) {
            if (slot.required && !slot.provenance.equals("constant", ignoreCase = true)) {
                val value = boundSlots[slot.name]
                if (value.isNullOrBlank()) {
                    missingSlotQueue.add(slot.name)
                }
            }
        }

        if (missingSlotQueue.isNotEmpty()) {
            return pauseForNextMissingSlot(workflow, startStepIndex)
        }

        currentState = ClarificationState.RUNNING
        return MidFlowClarificationResult(
            workflowId = workflow.skillId,
            finalState = ClarificationState.RUNNING,
            resolvedSlots = boundSlots.toMap(),
            resumedFromStepIndex = startStepIndex,
            totalStepsCompleted = startStepIndex,
            summaryReason = "All required slots supplied. Workflow proceeding normally."
        )
    }

    private fun pauseForNextMissingSlot(workflow: Workflow, stepIndex: Int): MidFlowClarificationResult {
        val nextMissing = missingSlotQueue.removeAt(0)
        val slotDef = workflow.slots.find { it.name == nextMissing }
        val expectedType = slotDef?.type ?: com.chockXlate.teachablevoice.contract.workflow.SlotType.TEXT

        val completedSteps = workflow.steps.take(stepIndex).map { it.stepId }
        val pausedStepId = workflow.steps.getOrNull(stepIndex)?.stepId ?: "step_$stepIndex"

        val resumePoint = ClarificationResumePoint(
            workflowId = workflow.skillId,
            pausedStepIndex = stepIndex,
            pausedStepId = pausedStepId,
            completedStepIds = completedSteps,
            boundSlots = boundSlots.toMap(),
            remainingMissingSlots = missingSlotQueue.toList()
        )

        currentResumePoint = resumePoint
        currentState = ClarificationState.WAITING_FOR_CLARIFICATION

        val prompt = "What ${nextMissing.replace('_', ' ')} should I use for '${workflow.name}'?"

        activeRequest = ClarificationRequest(
            requestId = UUID.randomUUID().toString(),
            workflowId = workflow.skillId,
            missingSlotName = nextMissing,
            questionPrompt = prompt,
            expectedSlotType = expectedType,
            resumePoint = resumePoint
        )

        return MidFlowClarificationResult(
            workflowId = workflow.skillId,
            finalState = ClarificationState.WAITING_FOR_CLARIFICATION,
            resolvedSlots = boundSlots.toMap(),
            resumedFromStepIndex = stepIndex,
            totalStepsCompleted = completedSteps.size,
            summaryReason = "Execution paused mid-flow at step index $stepIndex. Prompting user for missing slot '$nextMissing'."
        )
    }

    fun submitUserResponse(
        workflow: Workflow,
        userResponseText: String
    ): MidFlowClarificationResult {
        val request = activeRequest
        val resumePoint = currentResumePoint

        if (currentState != ClarificationState.WAITING_FOR_CLARIFICATION || request == null || resumePoint == null) {
            return MidFlowClarificationResult(
                workflowId = workflow.skillId,
                finalState = ClarificationState.FAILED,
                resolvedSlots = boundSlots.toMap(),
                resumedFromStepIndex = 0,
                totalStepsCompleted = 0,
                summaryReason = "No active clarification request pending."
            )
        }

        currentState = ClarificationState.VALIDATING_RESPONSE
        val slotDef = workflow.slots.find { it.name == request.missingSlotName }
        val expectedType = slotDef?.type ?: com.chockXlate.teachablevoice.contract.workflow.SlotType.TEXT

        val valResult = validator.validate(userResponseText, expectedType, request.missingSlotName)

        if (valResult.isCancel) {
            currentState = ClarificationState.CANCELLED
            activeRequest = null
            return MidFlowClarificationResult(
                workflowId = workflow.skillId,
                finalState = ClarificationState.CANCELLED,
                resolvedSlots = boundSlots.toMap(),
                resumedFromStepIndex = resumePoint.pausedStepIndex,
                totalStepsCompleted = resumePoint.completedStepIds.size,
                summaryReason = "User explicitly cancelled execution during mid-flow clarification."
            )
        }

        if (valResult.isSafetyBlocked) {
            currentState = ClarificationState.BLOCKED_SAFETY
            activeRequest = null
            return MidFlowClarificationResult(
                workflowId = workflow.skillId,
                finalState = ClarificationState.BLOCKED_SAFETY,
                resolvedSlots = boundSlots.toMap(),
                resumedFromStepIndex = resumePoint.pausedStepIndex,
                totalStepsCompleted = resumePoint.completedStepIds.size,
                summaryReason = valResult.errorMessage ?: "Safety boundary blocked sensitive input."
            )
        }

        if (!valResult.isValid || valResult.validatedValue == null) {
            currentState = ClarificationState.WAITING_FOR_CLARIFICATION
            return MidFlowClarificationResult(
                workflowId = workflow.skillId,
                finalState = ClarificationState.WAITING_FOR_CLARIFICATION,
                resolvedSlots = boundSlots.toMap(),
                resumedFromStepIndex = resumePoint.pausedStepIndex,
                totalStepsCompleted = resumePoint.completedStepIds.size,
                summaryReason = "Validation failed: ${valResult.errorMessage}. Remaining in WAITING_FOR_CLARIFICATION."
            )
        }

        // Response Validated: Bind slot value and prepare to resume
        boundSlots[request.missingSlotName] = valResult.validatedValue

        if (missingSlotQueue.isNotEmpty()) {
            return pauseForNextMissingSlot(workflow, resumePoint.pausedStepIndex)
        }

        currentState = ClarificationState.RESUMING
        activeRequest = null

        return MidFlowClarificationResult(
            workflowId = workflow.skillId,
            finalState = ClarificationState.RESUMING,
            resolvedSlots = boundSlots.toMap(),
            resumedFromStepIndex = resumePoint.pausedStepIndex, // Preserves step index (does NOT restart from 0)
            totalStepsCompleted = resumePoint.completedStepIds.size,
            requiresUiReobservation = true, // Mandatory re-observation before UI interaction
            summaryReason = "All missing slots resolved. Resuming execution from step index ${resumePoint.pausedStepIndex} ('${resumePoint.pausedStepId}')."
        )
    }

    fun getActiveRequest(): ClarificationRequest? = activeRequest
    fun getCurrentState(): ClarificationState = currentState
    fun getCurrentBoundSlots(): Map<String, String> = boundSlots.toMap()
}
