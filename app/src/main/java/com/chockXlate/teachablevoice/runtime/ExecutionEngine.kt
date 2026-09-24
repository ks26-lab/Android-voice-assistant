package com.chockXlate.teachablevoice.runtime

import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.execution.SemanticExecutor
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.recovery.RecoveryAction
import com.chockXlate.teachablevoice.runtime.recovery.RecoveryController
import com.chockXlate.teachablevoice.runtime.slots.SlotBinder
import com.chockXlate.teachablevoice.runtime.trace.ExecutionTraceRecorder
import com.chockXlate.teachablevoice.runtime.trace.RuntimeReport
import com.chockXlate.teachablevoice.runtime.ui.UiDriver
import com.chockXlate.teachablevoice.runtime.verification.PreconditionEvaluator
import com.chockXlate.teachablevoice.runtime.verification.TransitionVerifier
import com.chockXlate.teachablevoice.runtime.workflow.WorkflowResolver
import com.chockXlate.teachablevoice.safety.SafetyGate
import com.chockXlate.teachablevoice.skill.repository.SkillRepository

class ExecutionEngine(
    repository: SkillRepository,
    private val driver: UiDriver,
    private val matcher: SemanticMatcher = SemanticMatcher(),
    private val verifier: TransitionVerifier = TransitionVerifier(matcher),
    private val recovery: RecoveryController = RecoveryController()
) {
    private val resolver = WorkflowResolver(repository)
    private val preconditions = PreconditionEvaluator(matcher)
    private val executor = SemanticExecutor(matcher)
    private var gate = SafetyGate()
    private var running = false
    @Volatile private var cancelled = false
    @Volatile var lastReport: RuntimeReport? = null
        private set

    @Synchronized fun cancel() {
        cancelled = true
        gate.block("Execution was cancelled by the user.")
    }

    /** Integration must call only from an explicit user action, after the prior run has returned.
     * Never resumes the stopped step: the next execute() starts and revalidates a new run. */
    @Synchronized fun acknowledgeHandoffForNewExecution(): Boolean {
        if (running) return false
        gate = SafetyGate()
        cancelled = false
        return true
    }

    suspend fun execute(request: ExecutionRequest): RuntimeReport {
        val recorder = ExecutionTraceRecorder(request)
        val runGate = synchronized(this) {
            if (running) null else { running = true; gate }
        } ?: return recorder.finish(ExecutionState.FAILED, 0, 0, "Another execution is already active.", null)
        var completed = 0
        var total = 0
        var stepId: String? = null
        fun finish(state: ExecutionState, reason: String?): RuntimeReport {
            val type = when (state) {
                ExecutionState.COMPLETED -> DecisionType.PROCEED
                ExecutionState.PAUSED_FOR_HANDOFF -> DecisionType.HANDOFF
                ExecutionState.ABORTED -> DecisionType.ABORT
                else -> DecisionType.STOP
            }
            if (state == ExecutionState.PAUSED_FOR_HANDOFF) runGate.block(reason ?: "User handoff is required.")
            recorder.decision(stepId, state, type, reason ?: "Every workflow step was verified.")
            return recorder.finish(state, completed, total, reason, if (state == ExecutionState.COMPLETED) null else stepId).also { lastReport = it }
        }
        fun blocked(): RuntimeReport? = when {
            cancelled -> finish(ExecutionState.ABORTED, "Execution was cancelled by the user.")
            runGate.reason() != null -> finish(ExecutionState.PAUSED_FOR_HANDOFF, runGate.reason())
            else -> null
        }
        try {
            blocked()?.let { return it }
            recorder.decision(null, ExecutionState.INITIATED, DecisionType.PROCEED, "Validating runtime request.")
            ExecutionRequestValidator.validate(request)?.let { return finish(ExecutionState.FAILED, it) }
            val resolution = resolver.resolve(request)
            val workflow = resolution.workflow ?: return finish(ExecutionState.FAILED, resolution.error)
            total = workflow.steps.size
            recorder.decision(null, ExecutionState.INITIATED, DecisionType.PROCEED,
                "Using current workflow content. The repository interface cannot verify the requested historical version.")
            runGate.policy.admission(workflow)?.let { return finish(ExecutionState.PAUSED_FOR_HANDOFF, it) }
            val binding = SlotBinder.bind(workflow, request.boundSlots)
            binding.error?.let { return finish(ExecutionState.FAILED, it) }
            if (!driver.isReady()) return finish(ExecutionState.FAILED, "Accessibility service is unavailable. Enable and connect it before execution.")
            // Reject unsupported verification before performing any workflow side effects.
            for (step in binding.steps) {
                stepId = step.source.stepId
                verifier.unsupported(step.transition)?.let { return finish(ExecutionState.PAUSED_FOR_HANDOFF, it) }
                if (step.source.recoveryPolicy.schemaVersion != "1.0" || step.source.recoveryPolicy.maxRetries < 0 || step.source.recoveryPolicy.retryDelayMs < 0) {
                    return finish(ExecutionState.FAILED, "Invalid recovery policy.")
                }
            }
            val knownStates = mutableMapOf<String, String>()
            for (boundStep in binding.steps) {
                val step = boundStep.copy(stateEvidence = knownStates.toMap())
                stepId = step.source.stepId
                var verified = false
                for (attempt in 0..recovery.retryLimit(step.source.recoveryPolicy)) {
                    blocked()?.let { return it }
                    if (!driver.isReady()) return finish(ExecutionState.FAILED, "Accessibility service disconnected.")
                    recorder.decision(stepId, ExecutionState.MATCHING_STATE, DecisionType.PROCEED, "Observing current UI and checking preconditions.")
                    val before = driver.observe() ?: return finish(ExecutionState.PAUSED_FOR_HANDOFF, "No inspectable active window is available.")
                    runGate.check(workflow.safetyBoundary, before, step)?.let { return finish(ExecutionState.PAUSED_FOR_HANDOFF, it) }
                    preconditions.evaluate(step.preconditions, before, workflow.appContext, step.stateEvidence)?.let {
                        return finish(ExecutionState.PAUSED_FOR_HANDOFF, it)
                    }
                    verifier.startingStateError(step, before)?.let { return finish(ExecutionState.PAUSED_FOR_HANDOFF, it) }
                    val match = matcher.match(step.selector, before, step.action)
                    if (match.status == MatchStatus.AMBIGUOUS || match.status == MatchStatus.WEAK) {
                        recorder.decision(stepId, ExecutionState.PAUSED_FOR_HANDOFF, DecisionType.ASK_USER, match.reason, match.best?.confidence ?: 0.0)
                        return finish(ExecutionState.PAUSED_FOR_HANDOFF, match.reason)
                    }
                    var attempted = false
                    var failure = match.reason
                    if (match.status == MatchStatus.MATCHED) {
                        recorder.decision(stepId, ExecutionState.EXECUTING_STEP, DecisionType.EXECUTE, match.reason, match.best!!.confidence)
                        val action = executor.execute(driver, step, before, workflow.appContext, workflow.safetyBoundary, runGate)
                        attempted = action.attempted
                        failure = action.reason
                        if (attempted) {
                            val actionId = recorder.action(step)
                            recorder.decision(stepId, ExecutionState.WAITING_TRANSITION, DecisionType.PROCEED, action.reason)
                            // Even a false performAction result requires re-observation; it is not proof of no effect.
                            val verification = verifier.verify(driver, action.before ?: before, step, workflow.safetyBoundary, runGate)
                            verification.after?.let { recorder.transition(actionId, action.before ?: before, it) }
                            blocked()?.let { return it }
                            if (action.accepted && verification.verified) {
                                completed++
                                verified = true
                                step.transition.toState?.let { label ->
                                    knownStates[label] = TransitionVerifier.fingerprint(verification.after!!)
                                }
                                recorder.decision(stepId, ExecutionState.WAITING_TRANSITION, DecisionType.PROCEED, verification.reason)
                                break
                            }
                            failure = if (!action.accepted) action.reason else verification.reason
                        }
                    }
                    blocked()?.let { return it }
                    val next = recovery.decide(step.source.recoveryPolicy, attempt, attempted)
                    when (next.action) {
                        RecoveryAction.RETRY -> {
                            recorder.decision(stepId, ExecutionState.MATCHING_STATE, DecisionType.RECOVER, next.reason)
                            driver.awaitChange(next.delayMs)
                        }
                        RecoveryAction.HANDOFF -> return finish(ExecutionState.PAUSED_FOR_HANDOFF, "$failure ${next.reason}")
                        RecoveryAction.ABORT -> return finish(ExecutionState.ABORTED, "$failure ${next.reason}")
                    }
                }
                if (!verified) return finish(ExecutionState.PAUSED_FOR_HANDOFF, "The bounded attempt budget was exhausted without verified completion.")
            }
            blocked()?.let { return it }
            return finish(ExecutionState.COMPLETED, null)
        } catch (_: Exception) {
            // Exception messages can contain user input. Do not propagate them into reports.
            runGate.block("Runtime observation or execution failed. Completion cannot be established safely.")
            return finish(if (cancelled) ExecutionState.ABORTED else ExecutionState.PAUSED_FOR_HANDOFF, runGate.reason())
        } finally {
            synchronized(this) { running = false }
        }
    }
}
