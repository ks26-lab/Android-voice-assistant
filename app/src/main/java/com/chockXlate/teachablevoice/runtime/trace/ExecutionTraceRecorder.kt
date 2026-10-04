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
data class RecoveryRecord(
    val executionId: String,
    val workflowId: String? = null,
    val workflowStepId: String,
    val attemptNumber: Int,
    val originalAction: String?,
    val actionOutcome: String?,
    val verificationStatus: com.chockXlate.teachablevoice.runtime.verification.VerificationStatus?,
    val currentStateId: String?,
    val recoveryStrategy: String,
    val recoveryReason: String,
    val recoveryAction: String?,
    val recoveryActionOutcome: String? = null,
    val recoveryVerificationStatus: com.chockXlate.teachablevoice.runtime.verification.VerificationStatus? = null,
    val finalRecoveryStatus: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class UncertaintyRecord(
    val executionId: String,
    val stepId: String?,
    val uncertaintyType: String,
    val source: String,
    val decision: String,
    val clarificationId: String?,
    val question: String?,
    val candidateCount: Int,
    val resolution: String? = null,
    val resolutionConfidence: Double = 1.0,
    val attemptNumber: Int = 1,
    val finalDisposition: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class FinalExecutionStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    HANDOFF,
    CANCELLED,
    SAFETY_BLOCKED,
    CLARIFICATION_REQUIRED,
    RECOVERY_EXHAUSTED,
    UNSUPPORTED,
    NO_EXECUTION
}

enum class StepExecutionStatus {
    COMPLETED,
    FAILED,
    RECOVERED,
    WAITING_FOR_USER,
    HANDED_OFF,
    SAFETY_BLOCKED,
    SKIPPED
}

data class StepReport(
    val stepId: String,
    val stepIndex: Int,
    val actionType: String,
    val targetDescription: String? = null,
    val targetResolutionStatus: String? = null,
    val targetConfidence: Double = 1.0,
    val actionOutcome: String? = null,
    val verificationStatus: String? = null,
    val beforeStateId: String? = null,
    val afterStateId: String? = null,
    val recoveryCount: Int = 0,
    val clarificationCount: Int = 0,
    val finalStepStatus: StepExecutionStatus,
    val reason: String? = null
)

data class SafetyEventRecord(
    val stepId: String? = null,
    val decision: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class RuntimeReport(
    val result: ExecutionResult,
    val trace: ExecutionTrace,
    val diagnostics: List<RuntimeDiagnostic>,
    val stoppedStepId: String?,
    val recoveryRecords: List<RecoveryRecord> = emptyList(),
    val uncertaintyRecords: List<UncertaintyRecord> = emptyList(),
    val executionId: String = result.executionId,
    val workflowId: String? = trace.skillId,
    val workflowVersion: String? = result.schemaVersion,
    val originalCommand: String? = null,
    val finalStatus: FinalExecutionStatus = determineFinalStatus(result, recoveryRecords, uncertaintyRecords),
    val startedAt: Long = trace.startTime,
    val completedAt: Long = trace.endTime ?: 0L,
    val durationMs: Long = result.durationMs,
    val stepsTotal: Int = result.totalSteps,
    val stepsCompleted: Int = result.stepsCompleted,
    val stepsFailed: Int = if (result.success) 0 else (result.totalSteps - result.stepsCompleted).coerceAtLeast(0),
    val recoveryCount: Int = recoveryRecords.size,
    val clarificationCount: Int = uncertaintyRecords.count {
        it.uncertaintyType.contains("AMBIGU") || it.uncertaintyType.contains("SLOT") || it.clarificationId != null
    },
    val handoffRequired: Boolean = result.finalState == ExecutionState.PAUSED_FOR_HANDOFF || uncertaintyRecords.any { it.finalDisposition == "HANDOFF" },
    val safetyBlocked: Boolean = uncertaintyRecords.any { it.uncertaintyType == "SAFETY_BOUNDARY" } ||
        (result.errorMessage?.let { isPermanentSafetyBlockCheck(it) } == true),
    val finalStep: String? = stoppedStepId ?: result.progress?.currentStepId,
    val finalReason: String? = result.errorMessage,
    val stepReports: List<StepReport> = emptyList(),
    val boundSlots: Map<String, String> = emptyMap(),
    val safetyEvents: List<SafetyEventRecord> = emptyList(),
    val humanExplanation: String = "",
    val spokenSummary: String = ""
) {
    val isPartialSuccess: Boolean get() = stepsCompleted > 0 && stepsCompleted < stepsTotal

    fun buildHumanExplanation(): String {
        val sb = StringBuilder()
        sb.appendLine("Execution Summary for Run '$executionId':")
        if (!originalCommand.isNullOrBlank()) {
            sb.appendLine("• Command: \"$originalCommand\"")
        }
        workflowId?.let { sb.appendLine("• Selected Workflow: $it (version ${workflowVersion ?: "1"})") }
        if (boundSlots.isNotEmpty()) {
            val bindings = boundSlots.entries.joinToString(", ") { "${it.key}=${it.value}" }
            sb.appendLine("• Bound Parameters: $bindings")
        }
        sb.appendLine("• Final Outcome: $finalStatus (${if (result.success) "Succeeded" else "Stopped"})")
        sb.appendLine("• Progress: $stepsCompleted of $stepsTotal steps completed")
        if (recoveryCount > 0) {
            sb.appendLine("• Recovery: Yes ($recoveryCount attempt${if (recoveryCount > 1) "s" else ""})")
        } else {
            sb.appendLine("• Recovery: None required")
        }
        if (clarificationCount > 0 || handoffRequired) {
            val intervention = if (clarificationCount > 0 && handoffRequired) "Clarification asked & handoff required"
            else if (clarificationCount > 0) "User clarification resolved ambiguity"
            else "User handoff requested"
            sb.appendLine("• User Intervention: $intervention")
        } else {
            sb.appendLine("• User Intervention: None")
        }
        if (safetyBlocked) {
            sb.appendLine("• Safety Gate: BLOCKED (${finalReason ?: "Safety boundary active"})")
        } else {
            sb.appendLine("• Safety Gate: Safe (No safety block)")
        }
        if (!finalReason.isNullOrBlank() && !result.success) {
            sb.appendLine("• Reason: $finalReason")
        }
        return sb.toString().trimEnd()
    }

    fun buildSpokenSummary(): String {
        return when (finalStatus) {
            FinalExecutionStatus.SUCCESS -> {
                if (recoveryCount > 0) "Done. The workflow completed successfully after $recoveryCount recovery attempt${if (recoveryCount > 1) "s" else ""}."
                else "Done. The workflow completed successfully."
            }
            FinalExecutionStatus.SAFETY_BLOCKED -> {
                "Paused for your safety because a protected or sensitive screen was detected."
            }
            FinalExecutionStatus.CLARIFICATION_REQUIRED -> {
                "I need a quick clarification to continue."
            }
            FinalExecutionStatus.HANDOFF -> {
                "I've paused automation and handed control over to you: ${finalReason ?: "User action required."}"
            }
            FinalExecutionStatus.RECOVERY_EXHAUSTED -> {
                "I was unable to complete the task because the target could not be verified after repeated attempts."
            }
            FinalExecutionStatus.PARTIAL_SUCCESS -> {
                "Partially completed $stepsCompleted of $stepsTotal steps before stopping."
            }
            FinalExecutionStatus.CANCELLED -> {
                "Workflow execution was cancelled."
            }
            FinalExecutionStatus.UNSUPPORTED -> {
                "This action is not supported on the current screen."
            }
            FinalExecutionStatus.NO_EXECUTION -> {
                "No workflow has been executed yet."
            }
            FinalExecutionStatus.FAILED -> {
                "The workflow stopped at step ${finalStep ?: "unknown"}: ${finalReason ?: "Execution failed."}"
            }
        }
    }

    companion object {
        private fun isPermanentSafetyBlockCheck(reason: String): Boolean {
            val lower = reason.lowercase()
            return lower.contains("credential") ||
                lower.contains("login") ||
                lower.contains("password") ||
                lower.contains("pin") ||
                lower.contains("payment") ||
                lower.contains("monetary") ||
                lower.contains("explicit user handoff") ||
                lower.contains("cannot be established safely") ||
                lower.contains("strictly prohibited")
        }

        fun determineFinalStatus(
            result: ExecutionResult,
            recoveryRecords: List<RecoveryRecord> = emptyList(),
            uncertaintyRecords: List<UncertaintyRecord> = emptyList(),
            safetyEvents: List<SafetyEventRecord> = emptyList()
        ): FinalExecutionStatus {
            if (result.executionId == "NO_EXECUTION") return FinalExecutionStatus.NO_EXECUTION
            if (result.finalState == ExecutionState.COMPLETED && result.success) return FinalExecutionStatus.SUCCESS

            if (result.finalState == ExecutionState.ABORTED) {
                val err = result.errorMessage?.lowercase() ?: ""
                if (err.contains("cancel")) return FinalExecutionStatus.CANCELLED
                if (err.contains("unsupported") || uncertaintyRecords.any { it.uncertaintyType == "UNSUPPORTED_CAPABILITY" }) {
                    return FinalExecutionStatus.UNSUPPORTED
                }
                return FinalExecutionStatus.FAILED
            }

            if (result.finalState == ExecutionState.WAITING_FOR_USER) {
                return FinalExecutionStatus.CLARIFICATION_REQUIRED
            }

            if (safetyEvents.any { it.decision == "BLOCKED" } ||
                uncertaintyRecords.any { it.uncertaintyType == "SAFETY_BOUNDARY" } ||
                (result.errorMessage?.let { isPermanentSafetyBlockCheck(it) } == true)) {
                return FinalExecutionStatus.SAFETY_BLOCKED
            }

            val err = result.errorMessage?.lowercase() ?: ""
            if (err.contains("budget was exhausted") ||
                uncertaintyRecords.any { it.uncertaintyType == "RECOVERY_EXHAUSTED" } ||
                err.contains("recovery exhausted") ||
                err.contains("exhausted")) {
                return FinalExecutionStatus.RECOVERY_EXHAUSTED
            }

            if (err.contains("unsupported")) {
                return FinalExecutionStatus.UNSUPPORTED
            }

            if (result.finalState == ExecutionState.PAUSED_FOR_HANDOFF) {
                return FinalExecutionStatus.HANDOFF
            }

            if (result.stepsCompleted > 0 && result.stepsCompleted < result.totalSteps) {
                return FinalExecutionStatus.PARTIAL_SUCCESS
            }

            return FinalExecutionStatus.FAILED
        }

        fun empty(): RuntimeReport {
            val emptyResult = ExecutionResult(
                executionId = "NO_EXECUTION",
                success = false,
                finalState = ExecutionState.INITIATED,
                stepsCompleted = 0,
                totalSteps = 0,
                errorMessage = "No execution has been performed."
            )
            val emptyTrace = ExecutionTrace(
                executionId = "NO_EXECUTION",
                skillId = "none",
                startTime = 0L,
                endTime = 0L,
                events = emptyList(),
                result = emptyResult
            )
            return RuntimeReport(
                result = emptyResult,
                trace = emptyTrace,
                diagnostics = emptyList(),
                stoppedStepId = null,
                recoveryRecords = emptyList(),
                uncertaintyRecords = emptyList(),
                executionId = "NO_EXECUTION",
                workflowId = null,
                workflowVersion = null,
                originalCommand = null,
                finalStatus = FinalExecutionStatus.NO_EXECUTION,
                startedAt = 0L,
                completedAt = 0L,
                durationMs = 0L,
                stepsTotal = 0,
                stepsCompleted = 0,
                stepsFailed = 0,
                recoveryCount = 0,
                clarificationCount = 0,
                handoffRequired = false,
                safetyBlocked = false,
                finalStep = null,
                finalReason = "No execution has been performed.",
                stepReports = emptyList(),
                boundSlots = emptyMap(),
                safetyEvents = emptyList(),
                humanExplanation = "No execution has taken place.",
                spokenSummary = "No workflow has been executed yet."
            )
        }
    }
}

class StepTraceTracker(
    val stepId: String,
    val stepIndex: Int,
    var actionType: String,
    var targetDescription: String? = null,
    var targetResolutionStatus: String? = null,
    var targetConfidence: Double = 1.0,
    var actionOutcome: String? = null,
    var verificationStatus: String? = null,
    var beforeStateId: String? = null,
    var afterStateId: String? = null,
    var recoveryCount: Int = 0,
    var clarificationCount: Int = 0,
    var finalStepStatus: StepExecutionStatus = StepExecutionStatus.FAILED,
    var reason: String? = null
) {
    fun toStepReport(): StepReport {
        return StepReport(
            stepId = stepId,
            stepIndex = stepIndex,
            actionType = actionType,
            targetDescription = targetDescription?.let { ExecutionTraceRecorder.redactSensitiveText(it) },
            targetResolutionStatus = targetResolutionStatus,
            targetConfidence = targetConfidence,
            actionOutcome = actionOutcome,
            verificationStatus = verificationStatus,
            beforeStateId = beforeStateId,
            afterStateId = afterStateId,
            recoveryCount = recoveryCount,
            clarificationCount = clarificationCount,
            finalStepStatus = finalStepStatus,
            reason = reason?.let { ExecutionTraceRecorder.redactSensitiveText(it) }
        )
    }
}

/** All UI/input text is omitted, including benign text, so later screens cannot leak secrets. */
class ExecutionTraceRecorder(private val request: ExecutionRequest) {
    private val startTime = System.currentTimeMillis()
    private val startNanos = System.nanoTime()
    private val events = mutableListOf<TraceEvent>()
    private val diagnostics = mutableListOf<RuntimeDiagnostic>()
    private val recoveryRecords = mutableListOf<RecoveryRecord>()
    private val uncertaintyRecords = mutableListOf<UncertaintyRecord>()
    private val stepTrackers = mutableMapOf<String, StepTraceTracker>()
    private val safetyEvents = mutableListOf<SafetyEventRecord>()
    private val boundSlots = mutableMapOf<String, String>()

    fun setBoundSlots(slots: Map<String, String>) {
        boundSlots.clear()
        boundSlots.putAll(slots)
    }

    fun recordSafetyEvent(stepId: String?, decision: String, reason: String) {
        val safeReason = redactSensitiveText(reason)
        safetyEvents.add(SafetyEventRecord(stepId = stepId, decision = decision, reason = safeReason))
        com.chockXlate.teachablevoice.ui.components.ActivityEventStream.emit(
            id = "safety_${stepId ?: "gate"}",
            label = "Safety Gate: $decision",
            status = if (decision == "BLOCKED") com.chockXlate.teachablevoice.ui.components.ActivityStatus.FAILED else com.chockXlate.teachablevoice.ui.components.ActivityStatus.COMPLETED,
            metadata = safeReason
        )
        decision(
            stepId = stepId,
            state = ExecutionState.PAUSED_FOR_HANDOFF,
            type = DecisionType.HANDOFF,
            reason = "[SAFETY_$decision] $safeReason"
        )
    }

    fun startStep(stepId: String, stepIndex: Int, actionType: String, targetDescription: String? = null) {
        val tracker = stepTrackers.getOrPut(stepId) {
            StepTraceTracker(
                stepId = stepId,
                stepIndex = stepIndex,
                actionType = actionType,
                targetDescription = targetDescription
            )
        }
        tracker.actionType = actionType
        if (targetDescription != null) tracker.targetDescription = targetDescription
        com.chockXlate.teachablevoice.ui.components.ActivityEventStream.emit(
            id = "step_$stepId",
            label = "Step ${stepIndex + 1}: $actionType",
            status = com.chockXlate.teachablevoice.ui.components.ActivityStatus.IN_PROGRESS,
            metadata = targetDescription
        )
    }

    fun recordTargetResolution(stepId: String, status: String, confidence: Double, targetDescription: String? = null) {
        val tracker = stepTrackers[stepId] ?: return
        tracker.targetResolutionStatus = status
        tracker.targetConfidence = confidence
        if (targetDescription != null) tracker.targetDescription = targetDescription
    }

    fun recordActionOutcome(stepId: String, outcome: String, reason: String? = null) {
        val tracker = stepTrackers[stepId] ?: return
        tracker.actionOutcome = outcome
        if (reason != null && tracker.reason == null) tracker.reason = reason
    }

    fun recordStepVerificationOutcome(
        stepId: String,
        verificationStatus: String,
        beforeStateId: String?,
        afterStateId: String?,
        reason: String?
    ) {
        val tracker = stepTrackers[stepId] ?: return
        tracker.verificationStatus = verificationStatus
        tracker.beforeStateId = beforeStateId
        tracker.afterStateId = afterStateId
        if (reason != null) tracker.reason = reason
    }

    fun recordRecoveryForStep(stepId: String) {
        val tracker = stepTrackers[stepId] ?: return
        tracker.recoveryCount++
    }

    fun recordClarificationForStep(stepId: String) {
        val tracker = stepTrackers[stepId] ?: return
        tracker.clarificationCount++
    }

    fun completeStep(stepId: String, status: StepExecutionStatus, reason: String? = null) {
        val tracker = stepTrackers[stepId] ?: return
        tracker.finalStepStatus = status
        if (reason != null) tracker.reason = reason
        val actStatus = when (status) {
            StepExecutionStatus.COMPLETED -> com.chockXlate.teachablevoice.ui.components.ActivityStatus.COMPLETED
            StepExecutionStatus.FAILED,
            StepExecutionStatus.SAFETY_BLOCKED -> com.chockXlate.teachablevoice.ui.components.ActivityStatus.FAILED
            else -> com.chockXlate.teachablevoice.ui.components.ActivityStatus.PENDING
        }
        com.chockXlate.teachablevoice.ui.components.ActivityEventStream.emit(
            id = "step_$stepId",
            label = "Step ${tracker.stepIndex + 1}: ${tracker.actionType}",
            status = actStatus,
            metadata = reason ?: tracker.targetDescription
        )
    }

    private fun buildStepReports(
        completedCount: Int,
        totalCount: Int,
        stoppedStepId: String?,
        finalState: ExecutionState
    ): List<StepReport> {
        return stepTrackers.values.sortedBy { it.stepIndex }.map { tracker ->
            if (tracker.finalStepStatus == StepExecutionStatus.FAILED && tracker.actionOutcome == null && tracker.stepIndex >= completedCount) {
                if (tracker.stepId == stoppedStepId) {
                    val st = when (finalState) {
                        ExecutionState.WAITING_FOR_USER -> StepExecutionStatus.WAITING_FOR_USER
                        ExecutionState.PAUSED_FOR_HANDOFF -> {
                            if (safetyEvents.any { it.stepId == tracker.stepId && it.decision == "BLOCKED" }) StepExecutionStatus.SAFETY_BLOCKED
                            else StepExecutionStatus.HANDED_OFF
                        }
                        else -> StepExecutionStatus.FAILED
                    }
                    tracker.finalStepStatus = st
                } else if (tracker.stepIndex > completedCount) {
                    tracker.finalStepStatus = StepExecutionStatus.SKIPPED
                }
            }
            tracker.toStepReport()
        }
    }

    fun recordUncertainty(record: UncertaintyRecord) {
        val safeRecord = record.copy(
            question = record.question?.let { redactSensitiveText(it) },
            decision = redactSensitiveText(record.decision),
            resolution = record.resolution?.let { redactSensitiveText(it) }
        )
        uncertaintyRecords.add(safeRecord)
        decision(
            stepId = safeRecord.stepId,
            state = when (safeRecord.finalDisposition) {
                "CONTINUE" -> ExecutionState.MATCHING_STATE
                "CLARIFY" -> ExecutionState.WAITING_FOR_USER
                "HANDOFF" -> ExecutionState.PAUSED_FOR_HANDOFF
                "ABORT" -> ExecutionState.ABORTED
                else -> ExecutionState.MATCHING_STATE
            },
            type = when (safeRecord.finalDisposition) {
                "CONTINUE" -> DecisionType.PROCEED
                "CLARIFY" -> DecisionType.ASK_USER
                "HANDOFF" -> DecisionType.HANDOFF
                "ABORT" -> DecisionType.ABORT
                else -> DecisionType.STOP
            },
            reason = "[UNCERTAINTY_${safeRecord.uncertaintyType}] ${safeRecord.source} -> ${safeRecord.finalDisposition}: ${safeRecord.decision}",
            confidence = safeRecord.resolutionConfidence
        )
    }

    fun recordRecovery(record: RecoveryRecord) {
        recoveryRecords.add(record)
        decision(
            stepId = record.workflowStepId,
            state = ExecutionState.MATCHING_STATE,
            type = DecisionType.RECOVER,
            reason = "[RECOVERY_${record.recoveryStrategy}] Attempt ${record.attemptNumber}: ${record.recoveryReason} -> ${record.finalRecoveryStatus}",
            confidence = 1.0
        )
    }

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

    fun recordVerification(
        stepId: String,
        verification: com.chockXlate.teachablevoice.runtime.verification.VerificationResult
    ) {
        val decisionType = if (verification.verified) DecisionType.PROCEED else DecisionType.ABORT
        val execState = if (verification.verified) ExecutionState.WAITING_TRANSITION else ExecutionState.FAILED
        diagnostics.add(RuntimeDiagnostic(
            stepId = stepId,
            state = execState,
            decision = ExecutionDecision(
                decisionId = UUID.randomUUID().toString(),
                type = decisionType,
                reason = "[${verification.status}] ${verification.reason}",
                confidence = verification.confidence
            ),
            verificationEvidence = verification.evidenceResult
        ))
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
        val safeReason = reason?.let { redactSensitiveText(it) }
        val duration = (System.nanoTime() - startNanos) / 1_000_000
        val result = ExecutionResult(
            executionId = request.executionId,
            success = state == ExecutionState.COMPLETED && completed == total && total > 0,
            finalState = state,
            stepsCompleted = completed,
            totalSteps = total,
            errorMessage = safeReason,
            durationMs = duration,
            progress = progress,
            clarificationRequest = clarificationRequest
        )
        val safeCommand = request.originalCommand?.let { redactSensitiveText(it) }
        val safeSlots = boundSlots.mapValues { redactSensitiveText(it.value) }
        val stepReportsList = buildStepReports(completed, total, stoppedStep, state)
        val finalStatus = RuntimeReport.determineFinalStatus(result, recoveryRecords, uncertaintyRecords, safetyEvents)

        val report = RuntimeReport(
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
            stoppedStepId = stoppedStep,
            recoveryRecords = recoveryRecords.toList(),
            uncertaintyRecords = uncertaintyRecords.toList(),
            executionId = request.executionId,
            workflowId = request.skillId,
            workflowVersion = result.schemaVersion,
            originalCommand = safeCommand,
            finalStatus = finalStatus,
            startedAt = startTime,
            completedAt = System.currentTimeMillis(),
            durationMs = duration,
            stepsTotal = total,
            stepsCompleted = completed,
            stepsFailed = if (result.success) 0 else (total - completed).coerceAtLeast(0),
            recoveryCount = recoveryRecords.size,
            clarificationCount = uncertaintyRecords.count {
                it.uncertaintyType.contains("AMBIGU") || it.uncertaintyType.contains("SLOT") || it.clarificationId != null
            },
            handoffRequired = state == ExecutionState.PAUSED_FOR_HANDOFF || uncertaintyRecords.any { it.finalDisposition == "HANDOFF" },
            safetyBlocked = safetyEvents.any { it.decision == "BLOCKED" } ||
                uncertaintyRecords.any { it.uncertaintyType == "SAFETY_BOUNDARY" } ||
                (reason?.let { isPermanentSafetyBlockCheck(it) } == true),
            finalStep = stoppedStep ?: progress?.currentStepId,
            finalReason = safeReason,
            stepReports = stepReportsList,
            boundSlots = safeSlots,
            safetyEvents = safetyEvents.toList()
        )

        return report.copy(
            humanExplanation = report.buildHumanExplanation(),
            spokenSummary = report.buildSpokenSummary()
        )
    }

    companion object {
        fun isPermanentSafetyBlockCheck(reason: String): Boolean {
            val lower = reason.lowercase()
            return lower.contains("credential") ||
                lower.contains("login") ||
                lower.contains("password") ||
                lower.contains("pin") ||
                lower.contains("payment") ||
                lower.contains("monetary") ||
                lower.contains("explicit user handoff") ||
                lower.contains("cannot be established safely") ||
                lower.contains("strictly prohibited")
        }

        fun redactSensitiveText(text: String): String {
            val cardRegex = Regex("\\b(?:\\d[ -]*?){13,19}\\b")
            var sanitized = text.replace(cardRegex, "[REDACTED_CARD]")
            val sensitiveWordRegex = Regex("(?i)\\b(password|pin|cvv|otp|secret|token|credential)\\s*[:=\\s]\\s*\\S+")
            sanitized = sanitized.replace(sensitiveWordRegex, "[REDACTED_SECRET]")
            return sanitized
        }
    }

    private fun redact(state: UiState): UiState = state.copy(
        rootElement = null,
        allElements = state.allElements.map { it.copy(
            text = null, textSlot = null, contentDescription = null, resourceId = null,
            nearbyText = null, relativePosition = null, bounds = null, children = emptyList()
        ) }
    )
}
