package com.chockXlate.teachablevoice.runtime

import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.workflow.Preconditions
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSubtask
import com.chockXlate.teachablevoice.execution.SemanticExecutor
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.recovery.RecoveryAction
import com.chockXlate.teachablevoice.runtime.recovery.RecoveryController
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.slots.SlotBinder
import com.chockXlate.teachablevoice.runtime.trace.ExecutionTraceRecorder
import com.chockXlate.teachablevoice.runtime.trace.FinalExecutionStatus
import com.chockXlate.teachablevoice.runtime.trace.RecoveryRecord
import com.chockXlate.teachablevoice.runtime.trace.RuntimeReport
import com.chockXlate.teachablevoice.runtime.trace.StepExecutionStatus
import com.chockXlate.teachablevoice.runtime.ui.ActionOutcome
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction
import com.chockXlate.teachablevoice.runtime.ui.UiDriver
import com.chockXlate.teachablevoice.runtime.verification.PreconditionEvaluator
import com.chockXlate.teachablevoice.runtime.verification.TransitionVerifier
import com.chockXlate.teachablevoice.runtime.verification.VerificationResult
import com.chockXlate.teachablevoice.runtime.verification.VerificationStatus
import com.chockXlate.teachablevoice.runtime.workflow.WorkflowResolver
import com.chockXlate.teachablevoice.safety.SafetyGate
import com.chockXlate.teachablevoice.skill.repository.SkillRepository

class ExecutionEngine(
    repository: SkillRepository,
    private val driver: UiDriver,
    private val matcher: SemanticMatcher = SemanticMatcher(),
    private val verifier: TransitionVerifier = TransitionVerifier(matcher),
    private val recovery: RecoveryController = RecoveryController(),
    private val maxPackageWaitMs: Long = 20_000L,
    private val packagePollIntervalMs: Long = 250L,
    private val maxPackageWaitAttempts: Int = 80
) {
    private val resolver = WorkflowResolver(repository)
    private val preconditions = PreconditionEvaluator(matcher)
    private val executor = SemanticExecutor(matcher)
    private var gate = SafetyGate()
    private var running = false
    @Volatile private var cancelled = false
    @Volatile var lastReport: RuntimeReport? = null
        private set

    fun getLatestReport(): RuntimeReport? = lastReport
    fun getLatestReportOrEmpty(): RuntimeReport = lastReport ?: RuntimeReport.empty()

    private class PausedExecutionState(
        val request: ExecutionRequest,
        val workflow: Workflow,
        val boundSlots: MutableMap<String, String>,
        val subtasks: List<WorkflowSubtask>,
        var pausedSubtaskIndex: Int,
        var pausedStepIndex: Int,
        val completedSubtaskIds: MutableList<String>,
        val completedStepIds: MutableList<String>,
        val subtaskProgressList: MutableList<SubtaskProgress>,
        val knownStates: MutableMap<String, String>,
        var clarificationRequest: ClarificationRequest?,
        val runGate: SafetyGate,
        val recorder: ExecutionTraceRecorder,
        val totalSteps: Int,
        var completedStepsCount: Int,
        val expectedPackage: String? = null
    )

    @Volatile private var activePausedState: PausedExecutionState? = null

    val isPaused: Boolean get() = activePausedState != null
    val pausedExecutionId: String? get() = activePausedState?.request?.executionId
    val pausedExpectedPackage: String? get() = activePausedState?.expectedPackage

    @Synchronized fun cancel() {
        cancelled = true
        gate.block("Execution was cancelled by the user.")
        activePausedState = null
    }

    /** Integration must call only from an explicit user action, after the prior run has returned.
     * Never resumes the stopped step: the next execute() starts and revalidates a new run. */
    @Synchronized fun acknowledgeHandoffForNewExecution(): Boolean {
        if (running) return false
        gate = SafetyGate()
        cancelled = false
        activePausedState = null
        return true
    }

    suspend fun execute(request: ExecutionRequest): RuntimeReport {
        val recorder = ExecutionTraceRecorder(request)
        recorder.setBoundSlots(request.boundSlots)
        val runGate = synchronized(this) {
            if (running) null else {
                val r = gate.reason()
                if (r != null && !isPermanentSafetyBlock(r)) {
                    gate = SafetyGate()
                }
                running = true
                gate
            }
        } ?: return recorder.finish(
            state = ExecutionState.FAILED,
            completed = 0,
            total = 0,
            reason = "Another execution is already active.",
            stoppedStep = null
        )

        activePausedState = null
        var completed = 0
        var total = 0
        var stepId: String? = null
        var currentSubtaskId: String? = null
        val completedSubtaskIds = mutableListOf<String>()
        val completedStepIds = mutableListOf<String>()
        val subtaskProgressList = mutableListOf<SubtaskProgress>()

        fun buildProgress(overallStatus: ProgressStatus, reason: String?): ExecutionProgress {
            return ExecutionProgress(
                schemaVersion = "1.0",
                executionId = request.executionId,
                currentSubtaskId = currentSubtaskId,
                completedSubtaskIds = completedSubtaskIds.toList(),
                currentStepId = stepId,
                completedStepIds = completedStepIds.toList(),
                subtasks = subtaskProgressList.toList(),
                overallStatus = overallStatus,
                pauseOrFailureReason = reason
            )
        }

        fun finish(state: ExecutionState, reason: String?, clarReq: ClarificationRequest? = null): RuntimeReport {
            val type = when (state) {
                ExecutionState.COMPLETED -> DecisionType.PROCEED
                ExecutionState.PAUSED_FOR_HANDOFF -> DecisionType.HANDOFF
                ExecutionState.ABORTED -> DecisionType.ABORT
                ExecutionState.WAITING_FOR_USER -> DecisionType.ASK_USER
                else -> DecisionType.STOP
            }
            if (state == ExecutionState.PAUSED_FOR_HANDOFF) runGate.block(reason ?: "User handoff is required.")
            recorder.decision(stepId, state, type, reason ?: "Every workflow step was verified.")
            val overallProgStatus = when (state) {
                ExecutionState.COMPLETED -> ProgressStatus.COMPLETED
                ExecutionState.WAITING_FOR_USER -> ProgressStatus.WAITING_FOR_USER
                ExecutionState.FAILED, ExecutionState.ABORTED, ExecutionState.PAUSED_FOR_HANDOFF -> ProgressStatus.FAILED
                else -> ProgressStatus.ACTIVE
            }
            val progress = buildProgress(overallProgStatus, reason)
            return recorder.finish(
                state = state,
                completed = completed,
                total = total,
                reason = reason,
                stoppedStep = if (state == ExecutionState.COMPLETED) null else stepId,
                progress = progress,
                clarificationRequest = clarReq
            ).also { lastReport = it }
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
            recorder.decision(
                null, ExecutionState.INITIATED, DecisionType.PROCEED,
                "Using current workflow content. The repository interface cannot verify the requested historical version."
            )
            runGate.policy.admission(workflow)?.let {
                recorder.recordSafetyEvent(null, "BLOCKED", it)
                return finish(ExecutionState.PAUSED_FOR_HANDOFF, it)
            }

            // Subtask structure (backward-compatible: if empty, wraps in single generic subtask)
            val subtasks = if (workflow.subtasks.isNotEmpty()) workflow.subtasks else listOf(
                WorkflowSubtask(
                    schemaVersion = "1.0",
                    subtaskId = "subtask_1_main",
                    label = "MAIN_WORKFLOW",
                    stepIds = workflow.steps.map { it.stepId },
                    preconditions = workflow.steps.firstOrNull()?.preconditions ?: Preconditions()
                )
            )

            // Initialize progress for all subtasks
            for (st in subtasks) {
                subtaskProgressList.add(
                    SubtaskProgress(
                        schemaVersion = "1.0",
                        subtaskId = st.subtaskId,
                        label = st.label,
                        status = ProgressStatus.PENDING,
                        stepIds = st.stepIds
                    )
                )
            }

            // Check missing required slots before execution
            val currentSlots = request.boundSlots.toMutableMap()
            val missingRequired = workflow.slots.filter { slot ->
                slot.required && (!currentSlots.containsKey(slot.name) || currentSlots[slot.name].isNullOrBlank())
            }
            if (missingRequired.isNotEmpty()) {
                val missing = missingRequired.first()
                val isSens = missing.name.lowercase().let {
                    it.contains("password") || it.contains("pin") || it.contains("otp") ||
                        it.contains("cvv") || it.contains("card") || it.contains("secret")
                }
                if (isSens) {
                    val sensMsg = "Missing sensitive credential parameter '${missing.name}'. Automated entry of credentials is strictly prohibited."
                    recorder.recordSafetyEvent(null, "BLOCKED", sensMsg)
                    recorder.recordUncertainty(
                        com.chockXlate.teachablevoice.runtime.trace.UncertaintyRecord(
                            executionId = request.executionId,
                            stepId = null,
                            uncertaintyType = UncertaintyType.SAFETY_BOUNDARY.name,
                            source = "SLOT_VALIDATOR",
                            decision = sensMsg,
                            clarificationId = null,
                            question = null,
                            candidateCount = 0,
                            finalDisposition = UncertaintyDisposition.HANDOFF.name
                        )
                    )
                    return finish(
                        ExecutionState.PAUSED_FOR_HANDOFF,
                        sensMsg
                    )
                }
                val clarReq = ClarificationRequest(
                    schemaVersion = "1.0",
                    executionId = request.executionId,
                    reason = "Missing required parameter '${missing.name}'.",
                    question = "Please provide the value for '${missing.name}':",
                    requiredSlot = missing.name,
                    pausedSubtaskId = subtasks.firstOrNull()?.subtaskId,
                    pausedStepId = workflow.steps.firstOrNull()?.stepId,
                    uncertaintyType = UncertaintyType.MISSING_SLOT
                )
                currentSubtaskId = subtasks.firstOrNull()?.subtaskId
                stepId = workflow.steps.firstOrNull()?.stepId
                if (subtaskProgressList.isNotEmpty()) {
                    subtaskProgressList[0] = subtaskProgressList[0].copy(status = ProgressStatus.WAITING_FOR_USER)
                }
                activePausedState = PausedExecutionState(
                    request = request,
                    workflow = workflow,
                    boundSlots = currentSlots,
                    subtasks = subtasks,
                    pausedSubtaskIndex = 0,
                    pausedStepIndex = 0,
                    completedSubtaskIds = completedSubtaskIds,
                    completedStepIds = completedStepIds,
                    subtaskProgressList = subtaskProgressList,
                    knownStates = mutableMapOf(),
                    clarificationRequest = clarReq,
                    runGate = runGate,
                    recorder = recorder,
                    totalSteps = workflow.steps.size,
                    completedStepsCount = 0,
                    expectedPackage = workflow.steps.firstOrNull()?.preconditions?.requiredPackage ?: workflow.appContext
                )
                recorder.recordUncertainty(
                    com.chockXlate.teachablevoice.runtime.trace.UncertaintyRecord(
                        executionId = request.executionId,
                        stepId = workflow.steps.firstOrNull()?.stepId,
                        uncertaintyType = UncertaintyType.MISSING_SLOT.name,
                        source = "SLOT_VALIDATOR",
                        decision = clarReq.reason,
                        clarificationId = clarReq.clarificationId,
                        question = clarReq.question,
                        candidateCount = 0,
                        finalDisposition = UncertaintyDisposition.CLARIFY.name
                    )
                )
                return finish(ExecutionState.WAITING_FOR_USER, clarReq.reason, clarReq)
            }

            val binding = SlotBinder.bind(workflow, currentSlots)
            binding.error?.let { return finish(ExecutionState.FAILED, it) }
            total = binding.steps.size
            recorder.setBoundSlots(currentSlots)
            binding.steps.forEachIndexed { idx, st ->
                recorder.startStep(
                    stepId = st.source.stepId,
                    stepIndex = idx,
                    actionType = st.action.name,
                    targetDescription = st.selector.text ?: st.selector.contentDescription ?: st.selector.resourceId ?: st.selector.role
                )
            }

            if (!driver.isReady()) {
                return finish(ExecutionState.FAILED, "Accessibility service is unavailable. Enable and connect it before execution.")
            }

            // Reject unsupported verification before performing any workflow side effects.
            for (step in binding.steps) {
                stepId = step.source.stepId
                verifier.unsupported(step.transition)?.let { return finish(ExecutionState.PAUSED_FOR_HANDOFF, it) }
                if (step.source.recoveryPolicy.schemaVersion != "1.0" ||
                    step.source.recoveryPolicy.maxRetries < 0 ||
                    step.source.recoveryPolicy.retryDelayMs < 0) {
                    return finish(ExecutionState.FAILED, "Invalid recovery policy.")
                }
            }

            val knownStates = mutableMapOf<String, String>()

            return executeSubtasks(
                subtasks = subtasks,
                startSubtaskIndex = 0,
                startStepIndex = 0,
                bindingSteps = binding.steps,
                workflow = workflow,
                request = request,
                runGate = runGate,
                recorder = recorder,
                knownStates = knownStates,
                completedSubtaskIds = completedSubtaskIds,
                completedStepIds = completedStepIds,
                subtaskProgressList = subtaskProgressList,
                initialCompleted = completed,
                total = total,
                currentSlots = currentSlots
            )
        } catch (_: Exception) {
            runGate.block("Runtime observation or execution failed. Completion cannot be established safely.")
            return finish(if (cancelled) ExecutionState.ABORTED else ExecutionState.PAUSED_FOR_HANDOFF, runGate.reason())
        } finally {
            synchronized(this) { running = false }
        }
    }

    suspend fun resume(executionId: String? = null): RuntimeReport {
        return resumeInternal(executionId = executionId, response = null)
    }

    suspend fun resume(response: ClarificationResponse): RuntimeReport {
        return resumeInternal(executionId = response.executionId, response = response)
    }

    private suspend fun resumeInternal(executionId: String?, response: ClarificationResponse?): RuntimeReport {
        val paused = synchronized(this) {
            val state = activePausedState
            if (state == null) {
                val err = if (response != null) "No execution is currently paused waiting for clarification."
                else "No execution is currently paused waiting for resume."
                return RuntimeReport(
                    result = ExecutionResult(
                        executionId = executionId ?: "unknown",
                        success = false,
                        finalState = ExecutionState.FAILED,
                        stepsCompleted = 0,
                        totalSteps = 0,
                        errorMessage = err
                    ),
                    trace = ExecutionTrace(
                        executionId = executionId ?: "unknown",
                        skillId = "unknown",
                        startTime = System.currentTimeMillis()
                    ),
                    diagnostics = emptyList(),
                    stoppedStepId = null
                ).also { lastReport = it }
            }
            if (executionId != null && executionId != state.request.executionId) {
                val err = if (response != null) "Clarification response execution ID does not match active paused execution."
                else "Execution ID does not match active paused execution."
                return RuntimeReport(
                    result = ExecutionResult(
                        executionId = executionId,
                        success = false,
                        finalState = ExecutionState.FAILED,
                        stepsCompleted = state.completedStepsCount,
                        totalSteps = state.totalSteps,
                        errorMessage = err
                    ),
                    trace = ExecutionTrace(
                        executionId = executionId,
                        skillId = state.request.skillId,
                        startTime = System.currentTimeMillis()
                    ),
                    diagnostics = emptyList(),
                    stoppedStepId = null
                ).also { lastReport = it }
            }
            // Check staleness (5 minute timeout)
            if (state.clarificationRequest != null && System.currentTimeMillis() - state.clarificationRequest!!.timestamp > 300_000L) {
                activePausedState = null
                return RuntimeReport(
                    result = ExecutionResult(
                        executionId = executionId ?: state.request.executionId,
                        success = false,
                        finalState = ExecutionState.FAILED,
                        stepsCompleted = state.completedStepsCount,
                        totalSteps = state.totalSteps,
                        errorMessage = "Clarification request has expired."
                    ),
                    trace = ExecutionTrace(
                        executionId = executionId ?: state.request.executionId,
                        skillId = state.request.skillId,
                        startTime = System.currentTimeMillis()
                    ),
                    diagnostics = emptyList(),
                    stoppedStepId = null
                ).also { lastReport = it }
            }
            running = true
            activePausedState = null
            state
        }

        val recorder = paused.recorder
        val runGate = if (paused.runGate.reason()?.let { isPermanentSafetyBlock(it) } != true) SafetyGate() else paused.runGate
        val workflow = paused.workflow
        val request = paused.request

        var selectedCandidateIndex: Int? = response?.selectedCandidateIndex

        if (response != null && paused.clarificationRequest != null) {
            val clarReq = paused.clarificationRequest!!
            val resolution = NaturalLanguageClarificationResolver.resolve(clarReq, response)
            when (resolution) {
                is ClarificationResolutionResult.SensitiveBlock -> {
                    runGate.block("Sensitive credentials provided in clarification. Automated execution terminated.")
                    recorder.recordUncertainty(
                        com.chockXlate.teachablevoice.runtime.trace.UncertaintyRecord(
                            executionId = paused.request.executionId,
                            stepId = paused.workflow.steps.getOrNull(paused.pausedStepIndex)?.stepId,
                            uncertaintyType = UncertaintyType.SAFETY_BOUNDARY.name,
                            source = "NATURAL_LANGUAGE_RESOLVER",
                            decision = resolution.reason,
                            clarificationId = clarReq.clarificationId,
                            question = clarReq.question,
                            candidateCount = 0,
                            resolution = "[REDACTED_SENSITIVE]",
                            finalDisposition = UncertaintyDisposition.HANDOFF.name
                        )
                    )
                    return recorder.finish(
                        state = ExecutionState.PAUSED_FOR_HANDOFF,
                        completed = paused.completedStepsCount,
                        total = paused.totalSteps,
                        reason = resolution.reason,
                        stoppedStep = paused.workflow.steps.getOrNull(paused.pausedStepIndex)?.stepId
                    ).also { lastReport = it }
                }
                is ClarificationResolutionResult.AmbiguousReference,
                is ClarificationResolutionResult.InvalidResponse -> {
                    val reason = if (resolution is ClarificationResolutionResult.AmbiguousReference) resolution.reason else (resolution as ClarificationResolutionResult.InvalidResponse).reason
                    val currentAttempt = clarReq.attemptCount
                    val maxAttempts = clarReq.maxAttempts
                    if (currentAttempt < maxAttempts) {
                        val updatedQuestion = if (resolution is ClarificationResolutionResult.AmbiguousReference) {
                            "Your choice was ambiguous. Please specify more clearly: ${clarReq.question}"
                        } else {
                            if (clarReq.candidateDescriptions.isNotEmpty()) {
                                "I didn't understand that. Please choose from: ${clarReq.candidateDescriptions.joinToString(", ")}"
                            } else {
                                "I didn't understand that. Please specify: ${clarReq.question}"
                            }
                        }
                        val updatedReq = clarReq.copy(
                            attemptCount = currentAttempt + 1,
                            question = updatedQuestion,
                            reason = reason
                        )
                        paused.clarificationRequest = updatedReq
                        synchronized(this) { activePausedState = paused; running = false }
                        recorder.recordUncertainty(
                            com.chockXlate.teachablevoice.runtime.trace.UncertaintyRecord(
                                executionId = paused.request.executionId,
                                stepId = paused.workflow.steps.getOrNull(paused.pausedStepIndex)?.stepId,
                                uncertaintyType = if (resolution is ClarificationResolutionResult.AmbiguousReference)
                                    UncertaintyType.AMBIGUOUS_REFERENCE.name else UncertaintyType.INVALID_RESPONSE.name,
                                source = "NATURAL_LANGUAGE_RESOLVER",
                                decision = reason,
                                clarificationId = updatedReq.clarificationId,
                                question = updatedReq.question,
                                candidateCount = updatedReq.candidateDescriptions.size,
                                attemptNumber = updatedReq.attemptCount,
                                finalDisposition = UncertaintyDisposition.CLARIFY.name
                            )
                        )
                        return recorder.finish(
                            state = ExecutionState.WAITING_FOR_USER,
                            completed = paused.completedStepsCount,
                            total = paused.totalSteps,
                            reason = updatedReq.reason,
                            stoppedStep = paused.workflow.steps.getOrNull(paused.pausedStepIndex)?.stepId,
                            clarificationRequest = updatedReq
                        ).also { lastReport = it }
                    } else {
                        // Budget exhausted -> HANDOFF
                        synchronized(this) { activePausedState = null; running = false }
                        runGate.block("Clarification attempt budget exhausted ($maxAttempts attempts). Handing off to user.")
                        recorder.recordUncertainty(
                            com.chockXlate.teachablevoice.runtime.trace.UncertaintyRecord(
                                executionId = paused.request.executionId,
                                stepId = paused.workflow.steps.getOrNull(paused.pausedStepIndex)?.stepId,
                                uncertaintyType = UncertaintyType.RECOVERY_EXHAUSTED.name,
                                source = "NATURAL_LANGUAGE_RESOLVER",
                                decision = "Clarification attempt budget exhausted ($maxAttempts attempts).",
                                clarificationId = clarReq.clarificationId,
                                question = clarReq.question,
                                candidateCount = clarReq.candidateDescriptions.size,
                                attemptNumber = currentAttempt,
                                finalDisposition = UncertaintyDisposition.HANDOFF.name
                            )
                        )
                        return recorder.finish(
                            state = ExecutionState.PAUSED_FOR_HANDOFF,
                            completed = paused.completedStepsCount,
                            total = paused.totalSteps,
                            reason = "Clarification attempt budget exhausted ($maxAttempts attempts). Handing off to user.",
                            stoppedStep = paused.workflow.steps.getOrNull(paused.pausedStepIndex)?.stepId
                        ).also { lastReport = it }
                    }
                }
                is ClarificationResolutionResult.ResolvedSlot -> {
                    paused.boundSlots[resolution.slot] = resolution.value
                    recorder.recordUncertainty(
                        com.chockXlate.teachablevoice.runtime.trace.UncertaintyRecord(
                            executionId = paused.request.executionId,
                            stepId = paused.workflow.steps.getOrNull(paused.pausedStepIndex)?.stepId,
                            uncertaintyType = UncertaintyType.MISSING_SLOT.name,
                            source = "NATURAL_LANGUAGE_RESOLVER",
                            decision = "Resolved slot '${resolution.slot}'.",
                            clarificationId = clarReq.clarificationId,
                            question = clarReq.question,
                            candidateCount = 0,
                            resolution = resolution.value,
                            resolutionConfidence = resolution.confidence,
                            finalDisposition = UncertaintyDisposition.CONTINUE.name
                        )
                    )
                }
                is ClarificationResolutionResult.ResolvedCandidate -> {
                    selectedCandidateIndex = resolution.index
                    recorder.recordUncertainty(
                        com.chockXlate.teachablevoice.runtime.trace.UncertaintyRecord(
                            executionId = paused.request.executionId,
                            stepId = paused.workflow.steps.getOrNull(paused.pausedStepIndex)?.stepId,
                            uncertaintyType = UncertaintyType.AMBIGUOUS_TARGET.name,
                            source = "NATURAL_LANGUAGE_RESOLVER",
                            decision = "Resolved candidate ${resolution.index}: ${resolution.description}",
                            clarificationId = clarReq.clarificationId,
                            question = clarReq.question,
                            candidateCount = clarReq.candidateDescriptions.size,
                            resolution = resolution.description,
                            resolutionConfidence = resolution.confidence,
                            finalDisposition = UncertaintyDisposition.CONTINUE.name
                        )
                    )
                }
            }
        }

        // Rebind steps with updated slots
        val binding = SlotBinder.bind(workflow, paused.boundSlots)
        if (binding.error != null) {
            return recorder.finish(
                state = ExecutionState.FAILED,
                completed = paused.completedStepsCount,
                total = paused.totalSteps,
                reason = binding.error,
                stoppedStep = null
            ).also { lastReport = it }
        }

        // Re-observe live UI and revalidate SafetyGate before resuming
        if (!driver.isReady()) {
            return recorder.finish(
                state = ExecutionState.FAILED,
                completed = paused.completedStepsCount,
                total = paused.totalSteps,
                reason = if (response != null) "Accessibility service disconnected while waiting for clarification."
                else "Accessibility service disconnected while waiting to resume.",
                stoppedStep = null
            ).also { lastReport = it }
        }

        val currentUi = driver.observe()
        if (currentUi == null) {
            synchronized(this) { activePausedState = paused; running = false }
            return recorder.finish(
                state = ExecutionState.PAUSED_FOR_HANDOFF,
                completed = paused.completedStepsCount,
                total = paused.totalSteps,
                reason = "No active inspectable window available upon resume.",
                stoppedStep = null
            ).also { lastReport = it }
        }

        // Stale UI candidate context validation
        if (paused.clarificationRequest?.candidateDescriptions?.isNotEmpty() == true) {
            val liveTexts = currentUi.state.allElements.mapNotNull { it.text ?: it.contentDescription ?: it.resourceId }
            val prevCandidates = paused.clarificationRequest!!.candidateDescriptions
            val stillPresent = prevCandidates.any { cand -> liveTexts.any { cand.contains(it, ignoreCase = true) } }
            if (!stillPresent && liveTexts.isNotEmpty()) {
                // Stale candidate context invalidated; fresh observation will re-evaluate on current UI
                selectedCandidateIndex = null
            }
        }

        if (paused.expectedPackage != null && (currentUi.state.appContext != paused.expectedPackage || isOwnApp(currentUi.state.appContext))) {
            synchronized(this) { activePausedState = paused; running = false }
            return recorder.finish(
                state = ExecutionState.PAUSED_FOR_HANDOFF,
                completed = paused.completedStepsCount,
                total = paused.totalSteps,
                reason = "The required app is not the active app. Open it before continuing.",
                stoppedStep = paused.workflow.steps.getOrNull(paused.pausedStepIndex)?.stepId
            ).also { lastReport = it }
        }

        try {
            return executeSubtasks(
                subtasks = paused.subtasks,
                startSubtaskIndex = paused.pausedSubtaskIndex,
                startStepIndex = paused.pausedStepIndex,
                bindingSteps = binding.steps,
                workflow = workflow,
                request = request,
                runGate = runGate,
                recorder = recorder,
                knownStates = paused.knownStates,
                completedSubtaskIds = paused.completedSubtaskIds,
                completedStepIds = paused.completedStepIds,
                subtaskProgressList = paused.subtaskProgressList,
                initialCompleted = paused.completedStepsCount,
                total = binding.steps.size,
                currentSlots = paused.boundSlots,
                selectedCandidateIndex = selectedCandidateIndex
            )
        } catch (_: Exception) {
            runGate.block("Runtime observation or execution failed upon resume.")
            return recorder.finish(
                state = if (cancelled) ExecutionState.ABORTED else ExecutionState.PAUSED_FOR_HANDOFF,
                completed = paused.completedStepsCount,
                total = paused.totalSteps,
                reason = runGate.reason(),
                stoppedStep = null
            ).also { lastReport = it }
        } finally {
            synchronized(this) { running = false }
        }
    }

    private suspend fun executeSubtasks(
        subtasks: List<WorkflowSubtask>,
        startSubtaskIndex: Int,
        startStepIndex: Int,
        bindingSteps: List<BoundStep>,
        workflow: Workflow,
        request: ExecutionRequest,
        runGate: SafetyGate,
        recorder: ExecutionTraceRecorder,
        knownStates: MutableMap<String, String>,
        completedSubtaskIds: MutableList<String>,
        completedStepIds: MutableList<String>,
        subtaskProgressList: MutableList<SubtaskProgress>,
        initialCompleted: Int,
        total: Int,
        currentSlots: MutableMap<String, String>,
        selectedCandidateIndex: Int? = null
    ): RuntimeReport {
        var completed = initialCompleted
        var stepId: String? = null
        var currentSubtaskId: String? = null

        fun buildProgress(overallStatus: ProgressStatus, reason: String?): ExecutionProgress {
            return ExecutionProgress(
                schemaVersion = "1.0",
                executionId = request.executionId,
                currentSubtaskId = currentSubtaskId,
                completedSubtaskIds = completedSubtaskIds.toList(),
                currentStepId = stepId,
                completedStepIds = completedStepIds.toList(),
                subtasks = subtaskProgressList.toList(),
                overallStatus = overallStatus,
                pauseOrFailureReason = reason
            )
        }

        fun finish(state: ExecutionState, reason: String?, clarReq: ClarificationRequest? = null): RuntimeReport {
            val type = when (state) {
                ExecutionState.COMPLETED -> DecisionType.PROCEED
                ExecutionState.PAUSED_FOR_HANDOFF -> DecisionType.HANDOFF
                ExecutionState.ABORTED -> DecisionType.ABORT
                ExecutionState.WAITING_FOR_USER -> DecisionType.ASK_USER
                else -> DecisionType.STOP
            }
            if (state == ExecutionState.PAUSED_FOR_HANDOFF) runGate.block(reason ?: "User handoff is required.")
            recorder.decision(stepId, state, type, reason ?: "Every workflow step was verified.")
            val overallProgStatus = when (state) {
                ExecutionState.COMPLETED -> ProgressStatus.COMPLETED
                ExecutionState.WAITING_FOR_USER -> ProgressStatus.WAITING_FOR_USER
                ExecutionState.FAILED, ExecutionState.ABORTED, ExecutionState.PAUSED_FOR_HANDOFF -> ProgressStatus.FAILED
                else -> ProgressStatus.ACTIVE
            }
            val progress = buildProgress(overallProgStatus, reason)
            return recorder.finish(
                state = state,
                completed = completed,
                total = total,
                reason = reason,
                stoppedStep = if (state == ExecutionState.COMPLETED) null else stepId,
                progress = progress,
                clarificationRequest = clarReq
            ).also { lastReport = it }
        }

        fun blocked(): RuntimeReport? = when {
            cancelled -> finish(ExecutionState.ABORTED, "Execution was cancelled by the user.")
            runGate.reason() != null -> finish(ExecutionState.PAUSED_FOR_HANDOFF, runGate.reason())
            else -> null
        }

        val stepMap = bindingSteps.associateBy { it.source.stepId }

        for (stIndex in startSubtaskIndex until subtasks.size) {
            val subtask = subtasks[stIndex]
            currentSubtaskId = subtask.subtaskId

            // DO NOT replay already completed subtasks
            if (subtask.subtaskId in completedSubtaskIds) {
                continue
            }

            val stepIdsInSubtask = subtask.stepIds
            val stepsInSubtask = stepIdsInSubtask.flatMap { id ->
                val exact = stepMap[id]
                if (exact != null) listOf(exact)
                else bindingSteps.filter { it.source.stepId.startsWith("${id}_") }
            }

            // Update subtask status to ACTIVE
            val pIdx = subtaskProgressList.indexOfFirst { it.subtaskId == subtask.subtaskId }
            if (pIdx >= 0) {
                subtaskProgressList[pIdx] = subtaskProgressList[pIdx].copy(status = ProgressStatus.ACTIVE)
            }

            val firstStepIdx = if (stIndex == startSubtaskIndex) startStepIndex else 0

            for (sIndex in firstStepIdx until stepsInSubtask.size) {
                val boundStep = stepsInSubtask[sIndex]
                val step = boundStep.copy(stateEvidence = knownStates.toMap())
                stepId = step.source.stepId

                // DO NOT repeat already completed steps
                if (step.source.stepId in completedStepIds) {
                    continue
                }

                var verified = false
                var scrollAttempts = 0
                var stepHadRecovery = false
                var stepHadClarification = false

                recorder.startStep(
                    stepId = step.source.stepId,
                    stepIndex = sIndex,
                    actionType = step.action.name,
                    targetDescription = step.selector.text ?: step.selector.contentDescription ?: step.selector.resourceId ?: step.selector.role
                )

                for (attempt in 0..recovery.retryLimit(step.source.recoveryPolicy)) {
                    blocked()?.let { return it }
                    if (!driver.isReady()) return finish(ExecutionState.FAILED, "Accessibility service disconnected.")
                    val expectedPackage = step.preconditions.requiredPackage
                        ?.takeIf { it.isNotBlank() && it != "unknown" && !isOwnApp(it) && !isSystemSurface(it) }
                        ?: workflow.appContext.takeIf { it.isNotBlank() && it != "unknown" && !isOwnApp(it) && !isSystemSurface(it) }
                        ?: step.preconditions.requiredPackage
                        ?: workflow.appContext

                    var before = driver.observe()

                    val isTargetForeground = before != null &&
                        (before.state.appContext == expectedPackage || isAppMatching(expectedPackage, before.state.appContext)) &&
                        !isOwnApp(before.state.appContext)

                    if (!isTargetForeground) {
                        recorder.decision(
                            stepId,
                            ExecutionState.WAITING_FOR_USER,
                            DecisionType.ASK_USER,
                            "The required app is not the active app. Open it before continuing."
                        )
                        if (pIdx >= 0) {
                            subtaskProgressList[pIdx] = subtaskProgressList[pIdx].copy(status = ProgressStatus.WAITING_FOR_USER)
                        }

                        activePausedState = PausedExecutionState(
                            request = request,
                            workflow = workflow,
                            boundSlots = currentSlots,
                            subtasks = subtasks,
                            pausedSubtaskIndex = stIndex,
                            pausedStepIndex = sIndex,
                            completedSubtaskIds = completedSubtaskIds,
                            completedStepIds = completedStepIds,
                            subtaskProgressList = subtaskProgressList,
                            knownStates = knownStates,
                            clarificationRequest = null,
                            runGate = runGate,
                            recorder = recorder,
                            totalSteps = total,
                            completedStepsCount = completed,
                            expectedPackage = expectedPackage
                        )

                        val waitStart = System.currentTimeMillis()
                        var waitAttempts = 0
                        var targetActive = false

                        while (waitAttempts < maxPackageWaitAttempts && (System.currentTimeMillis() - waitStart) <= maxPackageWaitMs) {
                            blocked()?.let { return it }
                            if (!driver.isReady()) return finish(ExecutionState.FAILED, "Accessibility service disconnected.")
                            waitAttempts++
                            driver.awaitChange(packagePollIntervalMs)
                            blocked()?.let { return it }
                            val nextObs = driver.observe()
                            if (nextObs != null && nextObs.state.appContext == expectedPackage && !isOwnApp(nextObs.state.appContext)) {
                                before = nextObs
                                targetActive = true
                                break
                            }
                        }

                        if (!targetActive) {
                            val failureReason = if (before == null) "No inspectable active window is available."
                            else "The required app is not the active app. Open it before continuing."
                            return finish(ExecutionState.PAUSED_FOR_HANDOFF, failureReason)
                        }

                        activePausedState = null
                        if (pIdx >= 0) {
                            subtaskProgressList[pIdx] = subtaskProgressList[pIdx].copy(status = ProgressStatus.ACTIVE)
                        }
                    }

                    recorder.decision(
                        stepId,
                        ExecutionState.MATCHING_STATE,
                        DecisionType.PROCEED,
                        "Observing current UI and checking preconditions."
                    )

                    if (before == null) return finish(ExecutionState.PAUSED_FOR_HANDOFF, "No inspectable active window is available.")
                    runGate.check(workflow.safetyBoundary, before, step)?.let { blockReason ->
                        recorder.recordSafetyEvent(stepId, "BLOCKED", blockReason)
                        recorder.completeStep(step.source.stepId, StepExecutionStatus.SAFETY_BLOCKED, blockReason)
                        recorder.recordUncertainty(
                            com.chockXlate.teachablevoice.runtime.trace.UncertaintyRecord(
                                executionId = request.executionId,
                                stepId = stepId,
                                uncertaintyType = UncertaintyType.SAFETY_BOUNDARY.name,
                                source = "SAFETY_GATE",
                                decision = blockReason,
                                clarificationId = null,
                                question = null,
                                candidateCount = 0,
                                finalDisposition = UncertaintyDisposition.HANDOFF.name
                            )
                        )
                        return finish(ExecutionState.PAUSED_FOR_HANDOFF, blockReason)
                    }
                    preconditions.evaluate(step.preconditions, before, workflow.appContext, step.stateEvidence)?.let {
                        return finish(ExecutionState.PAUSED_FOR_HANDOFF, it)
                    }
                    verifier.startingStateError(step, before)?.let { return finish(ExecutionState.PAUSED_FOR_HANDOFF, it) }

                    var match = matcher.match(step.selector, before, step.action)
                    recorder.recordTargetResolution(
                        stepId = stepId,
                        status = match.status.name,
                        confidence = match.best?.confidence ?: 0.0,
                        targetDescription = match.best?.let { "${it.element.role}: ${it.element.text ?: it.element.contentDescription ?: it.element.resourceId ?: ""}".trim() }
                    )

                    var effectiveStep = step
                    val candidateList = listOfNotNull(match.best, match.second)

                    // If resumed with a selected disambiguated candidate index
                    if ((match.status == MatchStatus.AMBIGUOUS || match.status == MatchStatus.WEAK) &&
                        selectedCandidateIndex != null && selectedCandidateIndex in candidateList.indices) {
                        val chosen = candidateList[selectedCandidateIndex]
                        val disambiguatedSelector = step.selector.copy(
                            role = chosen.element.role,
                            text = chosen.element.text ?: step.selector.text,
                            resourceId = chosen.element.resourceId ?: step.selector.resourceId,
                            contentDescription = chosen.element.contentDescription ?: step.selector.contentDescription
                        )
                        effectiveStep = step.copy(selector = disambiguatedSelector)
                        match = matcher.match(disambiguatedSelector, before, step.action)
                        if (match.status != MatchStatus.MATCHED) {
                            match = com.chockXlate.teachablevoice.runtime.matching.MatchResult(
                                status = MatchStatus.MATCHED,
                                best = chosen,
                                reason = "Target disambiguated by user clarification response."
                            )
                        }
                    }

                    if (match.status == MatchStatus.AMBIGUOUS || match.status == MatchStatus.WEAK) {
                        val candidates = candidateList.mapIndexed { idx, cand ->
                            val t = cand.element.text ?: cand.element.contentDescription ?: cand.element.resourceId ?: "Item ${idx + 1}"
                            ClarificationCandidate(
                                index = idx,
                                description = "${cand.element.role}: $t",
                                semanticRole = cand.element.role,
                                semanticText = t
                            )
                        }
                        val candidateDescs = candidates.map { it.description }
                        val targetTerm = step.selector.text ?: step.selector.role.ifBlank { "option" }
                        val question = if (candidates.size == 2) {
                            "I found two candidates matching '$targetTerm'. Which one do you want?"
                        } else {
                            "Multiple candidates matching '$targetTerm' were found on screen. Which one do you want?"
                        }
                        val clarReq = ClarificationRequest(
                            schemaVersion = "1.0",
                            executionId = request.executionId,
                            reason = match.reason,
                            question = question,
                            candidateDescriptions = candidateDescs,
                            candidates = candidates,
                            pausedSubtaskId = subtask.subtaskId,
                            pausedStepId = stepId,
                            uncertaintyType = if (match.status == MatchStatus.AMBIGUOUS) UncertaintyType.AMBIGUOUS_TARGET else UncertaintyType.WEAK_TARGET_AMBIGUITY
                        )
                        if (pIdx >= 0) {
                            subtaskProgressList[pIdx] = subtaskProgressList[pIdx].copy(status = ProgressStatus.WAITING_FOR_USER)
                        }
                        activePausedState = PausedExecutionState(
                            request = request,
                            workflow = workflow,
                            boundSlots = currentSlots,
                            subtasks = subtasks,
                            pausedSubtaskIndex = stIndex,
                            pausedStepIndex = sIndex,
                            completedSubtaskIds = completedSubtaskIds,
                            completedStepIds = completedStepIds,
                            subtaskProgressList = subtaskProgressList,
                            knownStates = knownStates,
                            clarificationRequest = clarReq,
                            runGate = runGate,
                            recorder = recorder,
                            totalSteps = total,
                            completedStepsCount = completed,
                            expectedPackage = expectedPackage
                        )
                        stepHadClarification = true
                        recorder.recordClarificationForStep(stepId)
                        recorder.completeStep(step.source.stepId, StepExecutionStatus.WAITING_FOR_USER, match.reason)
                        recorder.recordUncertainty(
                            com.chockXlate.teachablevoice.runtime.trace.UncertaintyRecord(
                                executionId = request.executionId,
                                stepId = stepId,
                                uncertaintyType = clarReq.uncertaintyType.name,
                                source = "SEMANTIC_MATCHER",
                                decision = match.reason,
                                clarificationId = clarReq.clarificationId,
                                question = clarReq.question,
                                candidateCount = candidates.size,
                                finalDisposition = UncertaintyDisposition.CLARIFY.name
                            )
                        )
                        recorder.decision(stepId, ExecutionState.WAITING_FOR_USER, DecisionType.ASK_USER, match.reason, match.best?.confidence ?: 0.0)
                        return finish(ExecutionState.WAITING_FOR_USER, match.reason, clarReq)
                    }

                    var attempted = false
                    var failure = match.reason
                    var lastVerification: VerificationResult? = null

                    if (match.status == MatchStatus.MATCHED) {
                        recorder.decision(stepId, ExecutionState.EXECUTING_STEP, DecisionType.EXECUTE, match.reason, match.best!!.confidence)
                        val action = executor.execute(driver, effectiveStep, before, workflow.appContext, workflow.safetyBoundary, runGate)
                        attempted = action.attempted
                        failure = action.reason
                        val actionOutcomeStr = if (action.accepted) "ACCEPTED" else if (action.attempted) "REJECTED" else "NOT_ATTEMPTED"
                        recorder.recordActionOutcome(stepId, actionOutcomeStr, action.reason)
                        if (attempted) {
                            val actionId = recorder.action(effectiveStep)
                            recorder.decision(stepId, ExecutionState.WAITING_TRANSITION, DecisionType.PROCEED, action.reason)
                            val verification = verifier.verify(driver, action.before ?: before, effectiveStep, workflow.safetyBoundary, runGate)
                            lastVerification = verification
                            recorder.recordVerification(stepId, verification)
                            recorder.recordStepVerificationOutcome(
                                stepId = stepId,
                                verificationStatus = verification.status.name,
                                beforeStateId = (action.before ?: before).state.stateId,
                                afterStateId = verification.after?.state?.stateId,
                                reason = verification.reason
                            )
                            verification.after?.let { recorder.transition(actionId, action.before ?: before, it) }
                            blocked()?.let { return it }
                            if (action.accepted && verification.verified) {
                                completed++
                                verified = true
                                completedStepIds.add(step.source.stepId)
                                step.transition.toState?.let { label ->
                                    knownStates[label] = TransitionVerifier.fingerprint(verification.after!!)
                                }
                                recorder.decision(stepId, ExecutionState.WAITING_TRANSITION, DecisionType.PROCEED, verification.reason, evidence = verification.evidenceResult)
                                recorder.completeStep(
                                    stepId = step.source.stepId,
                                    status = if (stepHadRecovery) StepExecutionStatus.RECOVERED else StepExecutionStatus.COMPLETED,
                                    reason = verification.reason
                                )
                                break
                            }
                            failure = if (!action.accepted) action.reason else verification.reason
                        }
                    }

                    blocked()?.let { return it }

                    // Closed-loop Recovery Evaluation (Phase 4.8)
                    val currentUi = driver.observe()
                    val rematch = if (currentUi != null) matcher.match(effectiveStep.selector, currentUi, effectiveStep.action) else null
                    val verificationForRecovery = lastVerification ?: VerificationResult(
                        verified = false,
                        reason = failure,
                        status = if (match.status != MatchStatus.MATCHED) VerificationStatus.NOT_VERIFIED else VerificationStatus.UNEXPECTED_TRANSITION
                    )

                    val next = recovery.decideRecovery(
                        policy = step.source.recoveryPolicy,
                        verification = verificationForRecovery,
                        actionOutcome = if (attempted) ActionOutcome(attempted, accepted = false, reason = failure) else null,
                        currentUi = currentUi,
                        retries = attempt,
                        targetMatch = rematch,
                        isSensitive = verificationForRecovery.isSensitive || currentUi?.state?.isSensitiveContext == true || currentUi?.credentialFieldPresent == true,
                        safetyBlocked = runGate.reason() != null,
                        scrollCount = scrollAttempts
                    )

                    stepHadRecovery = true
                    recorder.recordRecoveryForStep(stepId)
                    recorder.recordRecovery(
                        RecoveryRecord(
                            executionId = request.executionId,
                            workflowId = workflow.skillId,
                            workflowStepId = step.source.stepId,
                            attemptNumber = attempt + 1,
                            originalAction = step.action.name,
                            actionOutcome = if (attempted) "FAILED" else "NOT_ATTEMPTED",
                            verificationStatus = verificationForRecovery.status,
                            currentStateId = currentUi?.state?.stateId,
                            recoveryStrategy = step.source.recoveryPolicy.strategy.name,
                            recoveryReason = next.reason,
                            recoveryAction = next.action.name,
                            finalRecoveryStatus = next.action.name
                        )
                    )

                    when (next.action) {
                        RecoveryAction.RETRY, RecoveryAction.RERESOLVE_TARGET, RecoveryAction.RETRY_NON_SIDE_EFFECTING_STEP_IF_PROVEN_SAFE -> {
                            recorder.decision(stepId, ExecutionState.MATCHING_STATE, DecisionType.RECOVER, next.reason)
                            driver.awaitChange(next.delayMs)
                        }
                        RecoveryAction.REOBSERVE -> {
                            recorder.decision(stepId, ExecutionState.MATCHING_STATE, DecisionType.RECOVER, next.reason)
                            driver.awaitChange(next.delayMs)
                            val settledUi = driver.observe()
                            if (settledUi != null) {
                                val settledVerification = verifier.verifyStateTransition(before, effectiveStep, settledUi)
                                if (settledVerification.verified) {
                                    completed++
                                    verified = true
                                    completedStepIds.add(step.source.stepId)
                                    step.transition.toState?.let { label ->
                                        knownStates[label] = TransitionVerifier.fingerprint(settledUi)
                                    }
                                    recorder.recordStepVerificationOutcome(
                                        stepId = stepId,
                                        verificationStatus = settledVerification.status.name,
                                        beforeStateId = before.state.stateId,
                                        afterStateId = settledUi.state.stateId,
                                        reason = settledVerification.reason
                                    )
                                    recorder.completeStep(
                                        stepId = step.source.stepId,
                                        status = StepExecutionStatus.RECOVERED,
                                        reason = settledVerification.reason
                                    )
                                    recorder.decision(stepId, ExecutionState.WAITING_TRANSITION, DecisionType.PROCEED, settledVerification.reason, evidence = settledVerification.evidenceResult)
                                    break
                                }
                            }
                        }
                        RecoveryAction.SCROLL -> {
                            scrollAttempts++
                            recorder.decision(stepId, ExecutionState.MATCHING_STATE, DecisionType.RECOVER, next.reason)
                            val scrollStep = effectiveStep.copy(action = RuntimeAction.SCROLL, scrollDirection = "forward")
                            executor.execute(driver, scrollStep, currentUi ?: before, workflow.appContext, workflow.safetyBoundary, runGate)
                            driver.awaitChange(next.delayMs)
                        }
                        RecoveryAction.NAVIGATE_BACK -> {
                            recorder.decision(stepId, ExecutionState.MATCHING_STATE, DecisionType.RECOVER, next.reason)
                            val backStep = effectiveStep.copy(action = RuntimeAction.BACK)
                            executor.execute(driver, backStep, currentUi ?: before, workflow.appContext, workflow.safetyBoundary, runGate)
                            driver.awaitChange(next.delayMs)
                        }
                        RecoveryAction.ASK_USER -> {
                            val recoveryCandidates = listOfNotNull(rematch?.best, rematch?.second).mapIndexed { idx, cand ->
                                val t = cand.element.text ?: cand.element.contentDescription ?: cand.element.resourceId ?: "Item ${idx + 1}"
                                ClarificationCandidate(
                                    index = idx,
                                    description = "${cand.element.role}: $t",
                                    semanticRole = cand.element.role,
                                    semanticText = t
                                )
                            }
                            val candidateDescs = recoveryCandidates.map { it.description }
                            val targetTerm = effectiveStep.selector.text ?: effectiveStep.selector.role.ifBlank { "option" }
                            val clarReq = ClarificationRequest(
                                schemaVersion = "1.0",
                                executionId = request.executionId,
                                reason = next.reason,
                                question = "Target is ambiguous during recovery for '$targetTerm'. Which one do you want?",
                                candidateDescriptions = candidateDescs,
                                candidates = recoveryCandidates,
                                pausedSubtaskId = subtask.subtaskId,
                                pausedStepId = stepId,
                                uncertaintyType = UncertaintyType.AMBIGUOUS_TARGET
                            )
                            if (pIdx >= 0) {
                                subtaskProgressList[pIdx] = subtaskProgressList[pIdx].copy(status = ProgressStatus.WAITING_FOR_USER)
                            }
                            activePausedState = PausedExecutionState(
                                request = request,
                                workflow = workflow,
                                boundSlots = currentSlots,
                                subtasks = subtasks,
                                pausedSubtaskIndex = stIndex,
                                pausedStepIndex = sIndex,
                                completedSubtaskIds = completedSubtaskIds,
                                completedStepIds = completedStepIds,
                                subtaskProgressList = subtaskProgressList,
                                knownStates = knownStates,
                                clarificationRequest = clarReq,
                                runGate = runGate,
                                recorder = recorder,
                                totalSteps = total,
                                completedStepsCount = completed,
                                expectedPackage = expectedPackage
                            )
                            stepHadClarification = true
                            recorder.recordClarificationForStep(stepId)
                            recorder.completeStep(step.source.stepId, StepExecutionStatus.WAITING_FOR_USER, next.reason)
                            recorder.recordUncertainty(
                                com.chockXlate.teachablevoice.runtime.trace.UncertaintyRecord(
                                    executionId = request.executionId,
                                    stepId = stepId,
                                    uncertaintyType = UncertaintyType.AMBIGUOUS_TARGET.name,
                                    source = "RECOVERY_CONTROLLER",
                                    decision = next.reason,
                                    clarificationId = clarReq.clarificationId,
                                    question = clarReq.question,
                                    candidateCount = recoveryCandidates.size,
                                    finalDisposition = UncertaintyDisposition.CLARIFY.name
                                )
                            )
                            return finish(ExecutionState.WAITING_FOR_USER, next.reason, clarReq)
                        }
                        RecoveryAction.HANDOFF -> {
                            recorder.completeStep(step.source.stepId, StepExecutionStatus.HANDED_OFF, next.reason)
                            recorder.recordUncertainty(
                                com.chockXlate.teachablevoice.runtime.trace.UncertaintyRecord(
                                    executionId = request.executionId,
                                    stepId = stepId,
                                    uncertaintyType = if (verificationForRecovery.isSensitive || currentUi?.state?.isSensitiveContext == true || runGate.reason() != null)
                                        UncertaintyType.SAFETY_BOUNDARY.name else UncertaintyType.RECOVERY_EXHAUSTED.name,
                                    source = "RECOVERY_CONTROLLER",
                                    decision = next.reason,
                                    clarificationId = null,
                                    question = null,
                                    candidateCount = 0,
                                    finalDisposition = UncertaintyDisposition.HANDOFF.name
                                )
                            )
                            return finish(ExecutionState.PAUSED_FOR_HANDOFF, "$failure ${next.reason}")
                        }
                        RecoveryAction.ABORT -> {
                            recorder.completeStep(step.source.stepId, StepExecutionStatus.FAILED, next.reason)
                            recorder.recordUncertainty(
                                com.chockXlate.teachablevoice.runtime.trace.UncertaintyRecord(
                                    executionId = request.executionId,
                                    stepId = stepId,
                                    uncertaintyType = UncertaintyType.UNSUPPORTED_CAPABILITY.name,
                                    source = "RECOVERY_CONTROLLER",
                                    decision = next.reason,
                                    clarificationId = null,
                                    question = null,
                                    candidateCount = 0,
                                    finalDisposition = UncertaintyDisposition.ABORT.name
                                )
                            )
                            return finish(ExecutionState.ABORTED, "$failure ${next.reason}")
                        }
                    }
                }

                if (!verified) {
                    recorder.completeStep(
                        stepId = step.source.stepId,
                        status = when {
                            runGate.reason() != null -> StepExecutionStatus.SAFETY_BLOCKED
                            else -> StepExecutionStatus.FAILED
                        },
                        reason = "The bounded attempt budget was exhausted without verified completion."
                    )
                    if (pIdx >= 0) {
                        subtaskProgressList[pIdx] = subtaskProgressList[pIdx].copy(
                            status = ProgressStatus.FAILED,
                            failureReason = "The bounded attempt budget was exhausted without verified completion."
                        )
                    }
                    return finish(ExecutionState.PAUSED_FOR_HANDOFF, "The bounded attempt budget was exhausted without verified completion.")
                }
            }

            // Subtask completed successfully
            completedSubtaskIds.add(subtask.subtaskId)
            if (pIdx >= 0) {
                subtaskProgressList[pIdx] = subtaskProgressList[pIdx].copy(
                    status = ProgressStatus.COMPLETED,
                    completedStepIds = stepIdsInSubtask,
                    evidenceSummary = "Subtask '${subtask.label}' completed with verified transition evidence."
                )
            }
        }

        blocked()?.let { return it }
        return finish(ExecutionState.COMPLETED, null)
    }

    companion object {
        fun isPermanentSafetyBlock(reason: String): Boolean {
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

        fun isOwnApp(packageName: String?): Boolean {
            if (packageName.isNullOrBlank()) return false
            val pkg = packageName.lowercase()
            val own = "com.chockxlate.teachablevoice"
            return pkg == own || pkg.startsWith("$own.")
        }

        fun isSystemSurface(packageName: String?): Boolean {
            if (packageName.isNullOrBlank()) return false
            val pkg = packageName.lowercase()
            return pkg == "android" || pkg.startsWith("android.") ||
                pkg.startsWith("com.android.systemui") ||
                pkg.contains("launcher") || pkg.endsWith(".home") || pkg.contains(".home.") ||
                pkg.endsWith(".launcher3") || pkg.contains("nexuslauncher") || pkg.contains("quickstep") ||
                pkg.contains("recents") || pkg.contains("overview")
        }

        fun isAppMatching(expected: String, actual: String): Boolean {
            if (expected.equals(actual, ignoreCase = true)) return true
            val expNorm = expected.lowercase().removePrefix("com.").removePrefix("in.").removeSuffix(".android")
            val actNorm = actual.lowercase().removePrefix("com.").removePrefix("in.").removeSuffix(".android")
            return expNorm.contains(actNorm) || actNorm.contains(expNorm)
        }
    }
}
