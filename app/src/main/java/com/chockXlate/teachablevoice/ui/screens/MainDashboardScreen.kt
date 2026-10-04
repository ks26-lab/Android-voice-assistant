package com.chockXlate.teachablevoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter
import com.chockXlate.teachablevoice.command.matching.SkillMatcher
import com.chockXlate.teachablevoice.command.matching.SkillMatchStatus
import com.chockXlate.teachablevoice.command.request.ExecutionRequestBuilder
import com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus
import com.chockXlate.teachablevoice.contract.skill.SkillStatus
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer
import com.chockXlate.teachablevoice.safety.RuntimeSafetyPolicy
import com.chockXlate.teachablevoice.skill.inspector.WorkflowInspectorImpl
import com.chockXlate.teachablevoice.skill.repository.SkillRepository
import com.chockXlate.teachablevoice.skill.repository.SkillRepositoryProvider
import com.chockXlate.teachablevoice.skill.validation.ValidationCategory
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import com.chockXlate.teachablevoice.ui.components.*
import com.chockXlate.teachablevoice.ui.mock.*
import com.chockXlate.teachablevoice.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun MainDashboardScreen(
    onVoiceClick: (() -> Unit)? = null,
    isVoiceListening: Boolean = false,
    voiceStatusMessage: String? = null,
    externalVoiceTranscript: String? = null,
    onExternalVoiceTranscriptConsumed: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val repository = remember { SkillRepositoryProvider.getRepository() }
    var storedSkillsCount by remember { mutableIntStateOf(repository.getWorkflowCount()) }
    var selectedWorkflowId by remember { mutableStateOf<String?>(null) }

    // UI State Management
    var currentState by remember {
        mutableStateOf(if (repository.getWorkflowCount() > 0) UiState.SKILL_STORED else UiState.READY)
    }
    var runtimeState by remember {
        mutableStateOf(if (repository.getWorkflowCount() > 0) RuntimeState.READY else RuntimeState.NO_WORKFLOW)
    }
    var selectedScenario by remember { mutableStateOf(RuntimeScenario.SUCCESS) }
    var currentRuntimeStep by remember { mutableIntStateOf(1) }
    var isSkillExpanded by remember { mutableStateOf(false) }
    var isLogCollapsed by remember { mutableStateOf(true) }

    // Command text state for editable typing & speech transcript reflection
    var typedCommandText by remember { mutableStateOf("") }
    var dynamicClarificationOptions by remember { mutableStateOf<List<String>>(emptyList()) }

    // Real Architecture UI Features: Skill Library Side Panel & Activity Progress Dialog
    var isSkillLibraryOpen by remember { mutableStateOf(false) }
    var isActivityDialogOpen by remember { mutableStateOf(false) }
    val storedSkills = remember(storedSkillsCount, currentState) {
        loadSkillsFromRepository(repository)
    }
    var realArchitectureEvents by remember { mutableStateOf<List<ActivityEvent>>(ActivityEventStream.getEvents()) }

    DisposableEffect(Unit) {
        val listener: (ActivityEvent) -> Unit = {
            realArchitectureEvents = ActivityEventStream.getEvents()
        }
        ActivityEventStream.subscribe(listener)
        onDispose {
            ActivityEventStream.unsubscribe(listener)
        }
    }

    // Demonstration Trace & Synthesis State
    var capturedTrace by remember { mutableStateOf<com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace?>(null) }
    var normalizedTrace by remember { mutableStateOf<com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace?>(null) }
    var dynamicSemanticActions by remember { mutableStateOf<List<SemanticActionData>>(emptyList()) }
    var dynamicValidationItems by remember { mutableStateOf<List<ValidationItemData>>(emptyList()) }
    var dynamicSynthesizedWorkflow by remember { mutableStateOf<Workflow?>(null) }

    // Active Skill & Teaching Session State
    var activeSkillId by remember { mutableStateOf<String?>(TeachingSessionManager.getActiveSkillId()) }
    var activeSkillName by remember { mutableStateOf<String?>(TeachingSessionManager.getActiveSession()?.skillName) }
    var activeSkillDescription by remember { mutableStateOf<String?>(TeachingSessionManager.getActiveSession()?.description) }

    // Logs and Last Run State
    var lastRunState by remember { mutableStateOf(LastRunData()) }
    val activeLogs = remember { mutableStateListOf<LogEntry>().apply { addAll(initialSystemLogs) } }

    // Refresh stored skill on state changes; respects selectedWorkflowId for multi-workflow isolation
    val activeWorkflow = remember(currentState, storedSkillsCount, selectedWorkflowId) {
        val allWfs = repository.getAllWorkflows()
        if (selectedWorkflowId != null) {
            allWfs.firstOrNull { it.skillId == selectedWorkflowId } ?: allWfs.firstOrNull()
        } else {
            allWfs.firstOrNull()
        }
    }

    // Dynamic Pipeline Steps based on UI state
    val pipelineSteps = remember(currentState) {
        when (currentState) {
            UiState.READY -> listOf(
                PipelineStepData("capture", "Capture", StepStatus.INACTIVE),
                PipelineStepData("trace", "Trace", StepStatus.INACTIVE),
                PipelineStepData("normalize", "Normalize", StepStatus.INACTIVE),
                PipelineStepData("extract", "Extract", StepStatus.INACTIVE),
                PipelineStepData("validate", "Validate", StepStatus.INACTIVE),
                PipelineStepData("store", "Store", StepStatus.INACTIVE)
            )
            UiState.TEACHING -> listOf(
                PipelineStepData("capture", "Capture", StepStatus.ACTIVE),
                PipelineStepData("trace", "Trace", StepStatus.ACTIVE),
                PipelineStepData("normalize", "Normalize", StepStatus.INACTIVE),
                PipelineStepData("extract", "Extract", StepStatus.INACTIVE),
                PipelineStepData("validate", "Validate", StepStatus.INACTIVE),
                PipelineStepData("store", "Store", StepStatus.INACTIVE)
            )
            UiState.TRACE_CAPTURED -> listOf(
                PipelineStepData("capture", "Capture", StepStatus.DONE),
                PipelineStepData("trace", "Trace", StepStatus.ACTIVE),
                PipelineStepData("normalize", "Normalize", StepStatus.INACTIVE),
                PipelineStepData("extract", "Extract", StepStatus.INACTIVE),
                PipelineStepData("validate", "Validate", StepStatus.INACTIVE),
                PipelineStepData("store", "Store", StepStatus.INACTIVE)
            )
            UiState.NORMALIZING -> listOf(
                PipelineStepData("capture", "Capture", StepStatus.DONE),
                PipelineStepData("trace", "Trace", StepStatus.DONE),
                PipelineStepData("normalize", "Normalize", StepStatus.ACTIVE),
                PipelineStepData("extract", "Extract", StepStatus.INACTIVE),
                PipelineStepData("validate", "Validate", StepStatus.INACTIVE),
                PipelineStepData("store", "Store", StepStatus.INACTIVE)
            )
            UiState.NORMALIZED -> listOf(
                PipelineStepData("capture", "Capture", StepStatus.DONE),
                PipelineStepData("trace", "Trace", StepStatus.DONE),
                PipelineStepData("normalize", "Normalize", StepStatus.DONE),
                PipelineStepData("extract", "Extract", StepStatus.INACTIVE),
                PipelineStepData("validate", "Validate", StepStatus.INACTIVE),
                PipelineStepData("store", "Store", StepStatus.INACTIVE)
            )
            UiState.EXTRACTING -> listOf(
                PipelineStepData("capture", "Capture", StepStatus.DONE),
                PipelineStepData("trace", "Trace", StepStatus.DONE),
                PipelineStepData("normalize", "Normalize", StepStatus.DONE),
                PipelineStepData("extract", "Extract", StepStatus.ACTIVE),
                PipelineStepData("validate", "Validate", StepStatus.INACTIVE),
                PipelineStepData("store", "Store", StepStatus.INACTIVE)
            )
            UiState.VALIDATING -> listOf(
                PipelineStepData("capture", "Capture", StepStatus.DONE),
                PipelineStepData("trace", "Trace", StepStatus.DONE),
                PipelineStepData("normalize", "Normalize", StepStatus.DONE),
                PipelineStepData("extract", "Extract", StepStatus.DONE),
                PipelineStepData("validate", "Validate", StepStatus.ACTIVE),
                PipelineStepData("store", "Store", StepStatus.INACTIVE)
            )
            UiState.SKILL_STORED -> listOf(
                PipelineStepData("capture", "Capture", StepStatus.DONE),
                PipelineStepData("trace", "Trace", StepStatus.DONE),
                PipelineStepData("normalize", "Normalize", StepStatus.DONE),
                PipelineStepData("extract", "Extract", StepStatus.DONE),
                PipelineStepData("validate", "Validate", StepStatus.DONE),
                PipelineStepData("store", "Store", StepStatus.DONE)
            )
        }
    }

    // Dynamic Learned Skill Data from real SkillRepository & WorkflowInspectorImpl
    val learnedSkillData = remember(currentState, activeWorkflow, activeSkillId, activeSkillName) {
        if (activeWorkflow != null && (currentState == UiState.SKILL_STORED || currentState == UiState.READY)) {
            val inspection = WorkflowInspectorImpl.inspect(activeWorkflow, repository as SkillRepository)
            LearnedSkillData(
                state = "VALIDATED",
                hasSkill = true,
                name = activeWorkflow.name.ifBlank { activeWorkflow.intent },
                technicalId = activeWorkflow.skillId,
                intent = activeWorkflow.intent,
                status = if (inspection.isExecutableByPerson2) "VALIDATED" else "INVALID",
                slots = activeWorkflow.slots.map { slot ->
                    SlotData(slot.name, slot.exampleValue ?: "unassigned")
                },
                semanticActionCount = activeWorkflow.steps.size,
                actionsLabel = "${activeWorkflow.steps.size} semantic actions",
                appContext = activeWorkflow.appContext,
                inspectionFormattedText = inspection.formattedText,
                safetyBoundaryText = if (activeWorkflow.safetyBoundary.requiresExplicitUserConfirmation) "Explicit User Confirmation Required" else "Safe Non-Credential Automation"
            )
        } else if (currentState == UiState.TEACHING || currentState == UiState.TRACE_CAPTURED) {
            val currentName = activeSkillName ?: "New Skill"
            val currentId = activeSkillId ?: "pending"
            val currentDesc = activeSkillDescription ?: ""
            LearnedSkillData(
                state = "BUILDING",
                hasSkill = false,
                name = currentName,
                technicalId = currentId,
                intent = currentDesc,
                status = "UNTRAINED",
                emptyTitle = "Skill Created: $currentName",
                emptyDescription = "Awaiting demonstration in target application.",
                buildingTitle = "Teaching: $currentName",
                buildingDescription = "Session active [ID: $currentId]. Zero learned steps.",
                semanticActionCount = 0,
                actionsLabel = "0 semantic actions (Untrained)"
            )
        } else {
            when (currentState) {
                UiState.NORMALIZING, UiState.NORMALIZED, UiState.EXTRACTING, UiState.VALIDATING ->
                    LearnedSkillData(
                        state = "BUILDING",
                        hasSkill = false,
                        name = activeSkillName ?: "New Skill",
                        technicalId = activeSkillId ?: "pending",
                        buildingTitle = "Structuring workflow...",
                        buildingDescription = "Analyzing demonstration evidence.",
                        semanticActionCount = 0
                    )
                else -> LearnedSkillData(state = "EMPTY", hasSkill = false)
            }
        }
    }

    val displaySkillName = activeWorkflow?.name?.ifBlank { activeWorkflow.intent } ?: activeSkillName ?: "Learned Skill"

    // Dynamic State Titles & Badges
    val (headerStatus, headerVariant) = when {
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.RUNNING -> Pair("RUNNING", StatusVariant.RUNNING)
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.PAUSED -> Pair("WAITING", StatusVariant.PAUSED)
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.USER_HANDOFF -> Pair("USER HANDOFF", StatusVariant.HANDOFF)
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.CLARIFICATION_NEEDED -> Pair("WAITING", StatusVariant.WAITING)
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.RECOVERING -> Pair("RECOVERING", StatusVariant.RECOVERING)
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.TARGET_NOT_RESOLVED -> Pair("TARGET NOT RESOLVED", StatusVariant.UNRESOLVED)
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.COMPLETED -> Pair("COMPLETED", StatusVariant.COMPLETED)
        currentState == UiState.TEACHING -> Pair("TEACHING", StatusVariant.TEACHING)
        currentState == UiState.NORMALIZING || currentState == UiState.EXTRACTING || currentState == UiState.VALIDATING -> Pair("LEARNING", StatusVariant.LEARNING)
        else -> Pair("READY", StatusVariant.READY)
    }

    val (currentBadge, currentVariant, currentTitle, currentDesc) = when {
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.RUNNING ->
            Quadruple("RUNNING", StatusVariant.RUNNING, "Executing $displaySkillName", "Step $currentRuntimeStep of ${activeWorkflow?.steps?.size ?: 5}")
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.PAUSED ->
            Quadruple("PAUSED", StatusVariant.PAUSED, "Runtime paused", "Execution suspended at step $currentRuntimeStep.")
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.USER_HANDOFF ->
            Quadruple("USER HANDOFF", StatusVariant.HANDOFF, "User control required", "Automation stopped at Protected Boundary. User confirmation needed.")
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.CLARIFICATION_NEEDED ->
            Quadruple("CLARIFICATION NEEDED", StatusVariant.WAITING, "Target could not be resolved", "Please clarify which target you intended.")
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.RECOVERING ->
            Quadruple("RECOVERING", StatusVariant.RECOVERING, "Attempting target resolution", "Retrying target alignment with user selection.")
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.TARGET_NOT_RESOLVED ->
            Quadruple("TARGET NOT RESOLVED", StatusVariant.UNRESOLVED, "Target could not be resolved", "Automation paused. User input required.")
        currentState == UiState.SKILL_STORED && runtimeState == RuntimeState.COMPLETED ->
            Quadruple("COMPLETED", StatusVariant.COMPLETED, "Workflow completed", "All semantic actions executed successfully.")
        currentState == UiState.TEACHING ->
            Quadruple("TEACHING", StatusVariant.TEACHING, "Recording demonstration", "Perform the workflow in the target app.")
        currentState == UiState.TRACE_CAPTURED ->
            Quadruple("TRACE CAPTURED", StatusVariant.READY, "Trace buffer ready", "Raw interaction events captured.")
        currentState == UiState.NORMALIZING ->
            Quadruple("NORMALIZING", StatusVariant.LEARNING, "Normalizing trace", "Filtering gestures and redundant interactions.")
        currentState == UiState.NORMALIZED ->
            Quadruple("NORMALIZED", StatusVariant.READY, "Trace normalized", "Canonical interaction graph constructed.")
        currentState == UiState.EXTRACTING ->
            Quadruple("EXTRACTING", StatusVariant.LEARNING, "Extracting semantic actions", "Identifying intents and slot parameters.")
        currentState == UiState.VALIDATING ->
            Quadruple("VALIDATING", StatusVariant.LEARNING, "Validating workflow", "Checking determinism and parameter bindings.")
        currentState == UiState.SKILL_STORED ->
            Quadruple("SKILL STORED", StatusVariant.READY, "Skill stored and ready", "$displaySkillName skill available in persistent repository.")
        else ->
            Quadruple("READY", StatusVariant.READY, "No active workflow", "Ready to teach or execute a learned workflow.")
    }

    // Normalization Effect
    LaunchedEffect(currentState) {
        if (currentState == UiState.NORMALIZING) {
            val raw = capturedTrace ?: TeachingSessionManager.peekSessionTrace()
            if (raw != null) {
                val normResult = DemonstrationTraceNormalizer.normalize(raw)
                normalizedTrace = normResult.normalizedTrace
            }
            delay(300)
            currentState = UiState.NORMALIZED
            activeLogs.addAll(teachingLogNormalizeDone)
        }
    }

    // Runtime Execution Loop
    LaunchedEffect(runtimeState, currentRuntimeStep) {
        if (runtimeState == RuntimeState.STARTING) {
            delay(400)
            runtimeState = RuntimeState.RUNNING
        } else if (runtimeState == RuntimeState.RUNNING) {
            if (selectedScenario == RuntimeScenario.SAFETY_HANDOFF && currentRuntimeStep == 3) {
                runtimeState = RuntimeState.USER_HANDOFF
                activeLogs.addAll(handoffScenarioLogs)
                ActivityEventStream.emit("safety_handoff", "Safety Gate Blocked", ActivityStatus.FAILED, "User control required at Protected Boundary")
                lastRunState = LastRunData(
                    hasHistory = true,
                    outcome = "HANDOFF",
                    status = "HANDOFF",
                    skillName = displaySkillName,
                    completedSteps = 3,
                    totalSteps = activeWorkflow?.steps?.size ?: 5,
                    stoppedAt = "Payment",
                    reason = "User control required"
                )
                return@LaunchedEffect
            }

            if (selectedScenario == RuntimeScenario.CLARIFICATION && currentRuntimeStep == 2) {
                runtimeState = RuntimeState.CLARIFICATION_NEEDED
                activeLogs.addAll(clarificationAmbiguousLogs)
                ActivityEventStream.emit("clarification_needed", "Clarification Needed", ActivityStatus.PENDING, "Target could not be resolved")
                return@LaunchedEffect
            }

            if (selectedScenario == RuntimeScenario.UNRESOLVED_TARGET && currentRuntimeStep == 2) {
                runtimeState = RuntimeState.TARGET_NOT_RESOLVED
                activeLogs.addAll(unresolvedScenarioLogs)
                ActivityEventStream.emit("target_unresolved", "Target Not Resolved", ActivityStatus.FAILED, "Automation paused. User input required.")
                lastRunState = LastRunData(
                    hasHistory = true,
                    outcome = "TARGET_NOT_RESOLVED",
                    status = "UNRESOLVED",
                    skillName = displaySkillName,
                    target = "Element Target"
                )
                return@LaunchedEffect
            }

            delay(700)
            val totalStepsCount = activeWorkflow?.steps?.size ?: defaultRuntimeSteps.size
            if (currentRuntimeStep < totalStepsCount) {
                currentRuntimeStep++
                val stepLog = runtimeStepLogs.find { it.event == "STEP_${currentRuntimeStep}_${defaultRuntimeSteps.getOrNull(currentRuntimeStep - 1)?.action ?: "ACTION"}" }
                val verifyLog = runtimeStepLogs.find { it.event == "STEP_${currentRuntimeStep - 1}_COMPLETE" }
                if (verifyLog != null && activeLogs.none { it.event == verifyLog.event }) activeLogs.add(verifyLog)
                if (stepLog != null) activeLogs.add(stepLog)
                ActivityEventStream.emit("runtime_step_$currentRuntimeStep", "Executing Step $currentRuntimeStep of $totalStepsCount", ActivityStatus.IN_PROGRESS, stepLog?.details)
            } else {
                runtimeState = RuntimeState.COMPLETED
                val verifyLast = runtimeStepLogs.find { it.event == "STEP_5_COMPLETE" }
                val completeLog = runtimeStepLogs.find { it.event == "RUNTIME_COMPLETE" }
                if (verifyLast != null && activeLogs.none { it.event == verifyLast.event }) activeLogs.add(verifyLast)
                if (completeLog != null) activeLogs.add(completeLog)
                ActivityEventStream.emit("runtime_${activeWorkflow?.skillId ?: "run"}", "Runtime Execution", ActivityStatus.COMPLETED, "Completed all $totalStepsCount steps")
                lastRunState = LastRunData(
                    hasHistory = true,
                    outcome = "SUCCESS",
                    status = "SUCCESS",
                    skillName = displaySkillName,
                    stepsCount = totalStepsCount,
                    failuresCount = 0,
                    duration = "3.2 s"
                )
            }
        }
    }

    // Action Handler
    fun handleAction(action: String, payload: Any? = null) {
        val currentTimeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        when (action) {
            "START_TEACHING" -> {
                val creationData = payload as? SkillCreationData
                val name = creationData?.name?.ifBlank { "Custom Skill" } ?: "Custom Skill"
                val desc = creationData?.description ?: ""
                val record = repository.createSkill(name = name, description = desc)
                TeachingSessionManager.startSession(skillName = record.name, intent = record.description, skillId = record.id, description = record.description)
                activeSkillId = record.id
                activeSkillName = record.name
                activeSkillDescription = record.description
                capturedTrace = null
                normalizedTrace = null
                dynamicSemanticActions = emptyList()
                dynamicValidationItems = emptyList()
                dynamicSynthesizedWorkflow = null
                currentState = UiState.TEACHING
                runtimeState = RuntimeState.NO_WORKFLOW
                isSkillExpanded = false
                ActivityEventStream.emit("teach_${record.id}", "Start Teaching (${record.name})", ActivityStatus.IN_PROGRESS, "Demonstration capture active")
                activeLogs.add(
                    LogEntry(currentTimeStr, LogCategory.SKILL, "SKILL_CREATED", "Dynamic skill '${record.name}' created with ID: ${record.id}", "success")
                )
                activeLogs.add(
                    LogEntry(currentTimeStr, LogCategory.TEACH, "TEACHING_STARTED", "Demonstration capture active for skill: ${record.id}. Awaiting user actions.", "active")
                )
            }
            "STOP_TEACHING" -> {
                val trace = TeachingSessionManager.stopSession()
                capturedTrace = trace
                currentState = UiState.TRACE_CAPTURED
                val actionCount = trace?.userActions?.size ?: 0
                val stateCount = trace?.uiStates?.size ?: 0
                val totalEvents = trace?.traceEvents?.size ?: 0
                ActivityEventStream.emit("teach_${activeSkillId ?: "session"}", "Demonstration Captured", ActivityStatus.COMPLETED, "$actionCount actions, $totalEvents total events")
                activeLogs.add(
                    LogEntry(currentTimeStr, LogCategory.TEACH, "TRACE_CAPTURED", "Demonstration trace captured for ${activeSkillName ?: "skill"}: $actionCount actions, $stateCount UI states, $totalEvents total events (Session: ${trace?.traceId ?: "active"}).", "normal")
                )
            }
            "NORMALIZE_TRACE" -> {
                currentState = UiState.NORMALIZING
                val raw = capturedTrace ?: TeachingSessionManager.peekSessionTrace()
                if (raw != null) {
                    val normResult = DemonstrationTraceNormalizer.normalize(raw)
                    normalizedTrace = normResult.normalizedTrace
                    ActivityEventStream.emit("norm_${raw.traceId}", "Trace Normalization", ActivityStatus.COMPLETED, "${normResult.normalizedEventCount} events (${normResult.removedDuplicateCount} duplicates filtered)")
                    activeLogs.add(
                        LogEntry(currentTimeStr, LogCategory.LEARN, "TRACE_NORMALIZED", "Canonical trace constructed: ${normResult.normalizedEventCount} events (${normResult.removedDuplicateCount} duplicates filtered). Status: ${normResult.status}", "normal")
                    )
                }
                activeLogs.addAll(teachingLogNormalizeStart)
            }
            "EXTRACT_ACTIONS" -> {
                currentState = UiState.EXTRACTING
                val traceToUse = normalizedTrace ?: capturedTrace ?: TeachingSessionManager.peekSessionTrace()
                if (traceToUse != null) {
                    val synthResult = WorkflowSynthesizer.synthesizeFromTrace(
                        trace = traceToUse,
                        targetSkillId = activeSkillId,
                        targetSkillName = activeSkillName,
                        targetSkillDescription = activeSkillDescription
                    )
                    dynamicSynthesizedWorkflow = synthResult.workflow
                    dynamicSemanticActions = synthResult.workflow?.steps?.mapIndexed { idx, step ->
                        SemanticActionData(
                            step = idx + 1,
                            type = step.semanticAction,
                            target = step.semanticSelector.resourceId ?: step.semanticSelector.contentDescription ?: step.semanticSelector.text ?: step.semanticSelector.role ?: "element",
                            value = step.semanticSelector.textSlot ?: step.parameters["input_parameter"] ?: step.parameters["input_literal"] ?: step.semanticSelector.text
                        )
                    } ?: emptyList()
                    ActivityEventStream.emit("extract_${traceToUse.traceId}", "Extract Semantic Actions", ActivityStatus.COMPLETED, "${synthResult.workflow?.steps?.size ?: 0} actions extracted")
                    activeLogs.add(
                        LogEntry(currentTimeStr, LogCategory.LEARN, "ACTIONS_EXTRACTED", "Synthesized ${synthResult.workflow?.steps?.size ?: 0} semantic actions and ${synthResult.workflow?.slots?.size ?: 0} parameter slots from demonstration.", "normal")
                    )
                }
                activeLogs.addAll(teachingLogExtract)
            }
            "VALIDATE_WORKFLOW" -> {
                currentState = UiState.VALIDATING
                var wf = dynamicSynthesizedWorkflow
                if (wf == null) {
                    val traceToUse = normalizedTrace ?: capturedTrace ?: TeachingSessionManager.peekSessionTrace()
                    if (traceToUse != null) {
                        val synthResult = WorkflowSynthesizer.synthesizeFromTrace(
                            trace = traceToUse,
                            targetSkillId = activeSkillId,
                            targetSkillName = activeSkillName,
                            targetSkillDescription = activeSkillDescription
                        )
                        wf = synthResult.workflow
                        dynamicSynthesizedWorkflow = wf
                    }
                }
                if (wf != null) {
                    val valResult = WorkflowValidator.validate(wf)
                    dynamicValidationItems = listOf(
                        ValidationItemData("Schema version & structural integrity", valResult.issues.none { it.category == ValidationCategory.STRUCTURE }),
                        ValidationItemData("Semantic selectors & variable references", valResult.issues.none { it.category == ValidationCategory.SEMANTICS }),
                        ValidationItemData("Coordinate-free execution boundary", valResult.issues.none { it.category == ValidationCategory.COORDINATE_REPLAY }),
                        ValidationItemData("Safety boundary & handoff policies", valResult.issues.none { it.category == ValidationCategory.SAFETY }),
                        ValidationItemData("Storeable Workflow IR generated", valResult.isStoreable)
                    )
                    ActivityEventStream.emit("validate_${wf.skillId}", "Workflow Validation", if (valResult.isStoreable) ActivityStatus.COMPLETED else ActivityStatus.FAILED, "Status: ${valResult.status} (Storeable: ${valResult.isStoreable})")
                    activeLogs.add(
                        LogEntry(currentTimeStr, LogCategory.VALIDATE, "WORKFLOW_VALIDATED", "Workflow validation: ${valResult.status} (Storeable: ${valResult.isStoreable}, Issues: ${valResult.issues.size})", if (valResult.isStoreable) "success" else "warning")
                    )
                }
                activeLogs.addAll(teachingLogValidate)
            }
            "STORE_SKILL" -> {
                val wf = dynamicSynthesizedWorkflow
                if (wf != null) {
                    val valResult = WorkflowValidator.validate(wf)
                    if (valResult.isStoreable) {
                        repository.saveWorkflow(wf)
                        val currentSkillId = activeSkillId ?: wf.skillId
                        val record = repository.getSkill(currentSkillId)
                        if (record != null) {
                            repository.updateSkill(record.copy(
                                status = SkillStatus.TRAINED,
                                workflowId = wf.skillId
                            ))
                        }
                        storedSkillsCount = repository.getWorkflowCount()
                        selectedWorkflowId = wf.skillId
                        currentState = UiState.SKILL_STORED
                        runtimeState = RuntimeState.READY
                        currentRuntimeStep = 1
                        activeLogs.add(
                            LogEntry(currentTimeStr, LogCategory.SKILL, "SKILL_STORED", "Skill '${wf.name}' [ID: ${wf.skillId}] marked TRAINED and saved in repository.", "success")
                        )
                    } else {
                        activeLogs.add(
                            LogEntry(currentTimeStr, LogCategory.SKILL, "STORE_BLOCKED", "Cannot store invalid workflow: ${valResult.issues.firstOrNull()?.message}", "error")
                        )
                    }
                } else if (repository.getWorkflowCount() > 0) {
                    storedSkillsCount = repository.getWorkflowCount()
                    currentState = UiState.SKILL_STORED
                    runtimeState = RuntimeState.READY
                    currentRuntimeStep = 1
                }
                activeLogs.addAll(teachingLogStore)
            }
            "SELECT_SCENARIO" -> {
                if (payload is RuntimeScenario) {
                    selectedScenario = payload
                }
            }
            "START_RUNTIME" -> {
                currentRuntimeStep = 1
                runtimeState = RuntimeState.STARTING
                ActivityEventStream.emit("runtime_${activeWorkflow?.skillId ?: "run"}", "Runtime Execution", ActivityStatus.IN_PROGRESS, "Executing ${displaySkillName}")
                activeLogs.add(
                    LogEntry(currentTimeStr, LogCategory.RUNTIME, "RUNTIME_STARTED", "Executing skill: ${activeWorkflow?.skillId ?: activeSkillId ?: "generic_skill"}", "active")
                )
                if (runtimeStepLogs.isNotEmpty()) {
                    activeLogs.add(runtimeStepLogs[0])
                }
            }
            "PAUSE_RUNTIME" -> {
                runtimeState = RuntimeState.PAUSED
                ActivityEventStream.emit("runtime_pause", "Runtime Paused", ActivityStatus.PENDING, "Suspended at Step $currentRuntimeStep")
                activeLogs.add(
                    LogEntry(currentTimeStr, LogCategory.RUNTIME, "RUNTIME_PAUSED", "Suspended at Step $currentRuntimeStep", "warning")
                )
            }
            "RESUME_RUNTIME" -> {
                runtimeState = RuntimeState.RUNNING
                ActivityEventStream.emit("runtime_resume", "Runtime Resumed", ActivityStatus.IN_PROGRESS, "Resuming from Step $currentRuntimeStep")
                activeLogs.add(
                    LogEntry(currentTimeStr, LogCategory.RUNTIME, "RUNTIME_RESUMED", "Resuming from Step $currentRuntimeStep", "success")
                )
            }
            "PROVIDE_CLARIFICATION" -> {
                runtimeState = RuntimeState.RECOVERING
                val choice = payload as? String ?: "Disambiguated target"
                ActivityEventStream.emit("clarify_resolve", "Clarification Provided", ActivityStatus.COMPLETED, "Disambiguated: $choice")
                activeLogs.add(
                    LogEntry(currentTimeStr, LogCategory.RECOVERY, "CLARIFICATION_RECEIVED", "User selected: '$choice'", "success")
                )
                activeLogs.addAll(clarificationRecoveryLogs)
                val matched = repository.getAllWorkflows().firstOrNull { it.name.equals(choice, ignoreCase = true) || it.intent.equals(choice, ignoreCase = true) }
                if (matched != null) {
                    selectedWorkflowId = matched.skillId
                }
            }
            "RETURN_TO_USER" -> {
                runtimeState = RuntimeState.READY
                currentRuntimeStep = 1
                activeLogs.add(
                    LogEntry(currentTimeStr, LogCategory.STATE, "CONTROL_RETURNED_TO_USER", "Dashboard ready for manual user input", "normal")
                )
            }
            "RESET_RUNTIME" -> {
                runtimeState = RuntimeState.READY
                currentRuntimeStep = 1
                activeLogs.add(
                    LogEntry(currentTimeStr, LogCategory.RUNTIME, "RUNTIME_RESET", "$displaySkillName skill ready for execution", "normal")
                )
            }
            "RESET" -> {
                TeachingSessionManager.clearSession()
                ActivityEventStream.clear()
                realArchitectureEvents = emptyList()
                activeSkillId = null
                activeSkillName = null
                activeSkillDescription = null
                currentState = if (repository.getWorkflowCount() > 0) UiState.SKILL_STORED else UiState.READY
                runtimeState = if (repository.getWorkflowCount() > 0) RuntimeState.READY else RuntimeState.NO_WORKFLOW
                currentRuntimeStep = 1
                isSkillExpanded = false
                isLogCollapsed = true
                lastRunState = LastRunData()
                activeLogs.clear()
                activeLogs.addAll(initialSystemLogs)
            }
            "TOGGLE_SKILL_EXPAND" -> {
                isSkillExpanded = !isSkillExpanded
            }
            "TOGGLE_LOG_EXPAND" -> {
                isLogCollapsed = !isLogCollapsed
            }
            "CLEAR_LOGS" -> {
                activeLogs.clear()
                activeLogs.add(
                    LogEntry(currentTimeStr, LogCategory.SYSTEM, "LOGS_CLEARED", "Console log buffer cleared by user", "normal")
                )
            }
        }
    }

    // Authoritative Command Pipeline Execution (Text Fallback & Voice Input)
    fun submitCommand(rawText: String) {
        val trimmed = rawText.trim()
        val currentTimeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

        if (trimmed.isBlank()) {
            activeLogs.add(
                LogEntry(currentTimeStr, LogCategory.SYSTEM, "COMMAND_EMPTY", "Command cannot be empty. Please enter a valid task.", "warning")
            )
            return
        }

        activeLogs.add(
            LogEntry(currentTimeStr, LogCategory.ACTION, "COMMAND_RECEIVED", "Received command: \"$trimmed\"", "normal")
        )
        ActivityEventStream.emit("cmd_${System.currentTimeMillis()}", "Command: \"$trimmed\"", ActivityStatus.IN_PROGRESS, trimmed)

        // 1. Safety Gate Check: Sensitive credential inputs are NEVER automated
        if (RuntimeSafetyPolicy().credentialText(trimmed)) {
            runtimeState = RuntimeState.USER_HANDOFF
            ActivityEventStream.emit("safety_blocked", "Safety Gate Blocked", ActivityStatus.FAILED, "Sensitive credential automation blocked")
            activeLogs.add(
                LogEntry(currentTimeStr, LogCategory.RUNTIME, "SAFETY_BLOCKED", "Sensitive credential command requires manual user control. Automation stopped.", "error")
            )
            lastRunState = LastRunData(
                hasHistory = true,
                outcome = "HANDOFF",
                status = "HANDOFF",
                skillName = displaySkillName,
                stoppedAt = "Protected Boundary",
                reason = "Credential security requirement"
            )
            return
        }

        // 2. Semantic Understanding
        val understanding = CommandInterpreter.understandCommand(trimmed)

        // 3. Skill Matching against authoritative Repository
        val matcher = SkillMatcher(repository)
        val matchResult = matcher.match(understanding)

        when (matchResult.status) {
            SkillMatchStatus.MATCHED -> {
                val matchedWf = repository.getWorkflowById(matchResult.selectedSkillId)
                if (matchedWf != null) {
                    selectedWorkflowId = matchedWf.skillId
                    val buildResult = ExecutionRequestBuilder.build(understanding, matchResult, repository)
                    if (buildResult.status == ExecutionRequestStatus.READY_FOR_PERSON_2) {
                        currentState = UiState.SKILL_STORED
                        runtimeState = RuntimeState.READY
                        currentRuntimeStep = 1
                        ActivityEventStream.emit("matched_${matchedWf.skillId}", "Matched: ${matchedWf.name}", ActivityStatus.COMPLETED, "Prepared for execution")
                        activeLogs.add(
                            LogEntry(currentTimeStr, LogCategory.MATCH, "SKILL_MATCHED", "Matched skill '${matchedWf.name}' [ID: ${matchedWf.skillId}]", "success")
                        )
                        handleAction("START_RUNTIME")
                    } else {
                        activeLogs.add(
                            LogEntry(currentTimeStr, LogCategory.MATCH, "REQUEST_REJECTED", buildResult.rejectionReason ?: "Missing slots", "warning")
                        )
                    }
                }
            }
            SkillMatchStatus.AMBIGUOUS -> {
                runtimeState = RuntimeState.CLARIFICATION_NEEDED
                val candidates = matchResult.candidates.map { it.workflow.name.ifBlank { it.workflow.intent } }
                dynamicClarificationOptions = if (candidates.isNotEmpty()) candidates else listOf("Option A", "Option B")
                ActivityEventStream.emit("clarify_match", "Ambiguous Command", ActivityStatus.PENDING, "Multiple skills match: ${candidates.joinToString()}")
                activeLogs.add(
                    LogEntry(currentTimeStr, LogCategory.MATCH, "MATCH_AMBIGUOUS", "Command is ambiguous between: ${candidates.joinToString(", ")}", "warning")
                )
            }
            SkillMatchStatus.UNKNOWN -> {
                // Invariant #9: DO NOT select unrelated workflow, DO NOT execute another workflow
                runtimeState = RuntimeState.NO_WORKFLOW
                ActivityEventStream.emit("unknown_cmd", "Unknown Skill", ActivityStatus.FAILED, "No matching workflow in repository")
                activeLogs.add(
                    LogEntry(currentTimeStr, LogCategory.MATCH, "UNKNOWN_WORKFLOW", "No matching workflow found for \"$trimmed\". Teach this skill to enable execution.", "error")
                )
            }
        }
    }

    // Effect: consume external speech transcript and automatically trigger command execution
    LaunchedEffect(externalVoiceTranscript) {
        if (!externalVoiceTranscript.isNullOrBlank()) {
            val transcript = externalVoiceTranscript
            typedCommandText = transcript
            onExternalVoiceTranscriptConsumed?.invoke()
            submitCommand(transcript)
        }
    }

    // Viewport Container (Centered, bounded to 412dp max-width matching Figma)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBgBase),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 412.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. App Header with non-destructive Dark/Light theme switch
            AppHeader(
                title = "Teachable Voice Automation",
                subtitle = "One-Shot Semantic Workflow Learning",
                status = headerStatus,
                statusVariant = headerVariant,
                onOpenSkills = { isSkillLibraryOpen = true },
                onOpenActivity = { isActivityDialogOpen = true },
                onToggleTheme = { ThemePreferences.toggleTheme() },
                isDarkMode = ThemePreferences.isDarkMode,
                activeEventsCount = realArchitectureEvents.size
            )

            // 2. Current State Card
            CurrentStateCard(
                statusBadge = currentBadge,
                statusVariant = currentVariant,
                title = currentTitle,
                description = currentDesc
            )

            // 3. Command Input (Interactive typed input and speech reflection)
            CommandInput(
                placeholder = "Search for headphones",
                value = if (currentState == UiState.TEACHING) (activeSkillDescription ?: activeSkillName ?: "") else typedCommandText,
                onValueChange = if (currentState != UiState.TEACHING) { { typedCommandText = it } } else null,
                onSubmit = { text -> submitCommand(text) },
                onVoiceClick = onVoiceClick,
                isListening = isVoiceListening,
                statusMessage = voiceStatusMessage
            )

            // 4. Teach Section
            TeachSection(
                currentState = currentState,
                activeSkillName = activeSkillName,
                activeSkillId = activeSkillId,
                onAction = { act, payload -> handleAction(act, payload) }
            )

            // 5. Learning Pipeline
            LearningPipeline(steps = pipelineSteps)

            // 5b. Semantic Actions Preview (during EXTRACTING)
            if (currentState == UiState.EXTRACTING) {
                SemanticActionsPreview(actions = if (dynamicSemanticActions.isNotEmpty()) dynamicSemanticActions else defaultSemanticActions)
            }

            // 5c. Validation Checklist (during VALIDATING)
            if (currentState == UiState.VALIDATING) {
                ValidationSummaryCard(items = if (dynamicValidationItems.isNotEmpty()) dynamicValidationItems else defaultValidationItems)
            }

            // 6. Learned Skill Card
            SkillCard(
                skillData = learnedSkillData,
                isExpanded = isSkillExpanded,
                onToggleExpand = { handleAction("TOGGLE_SKILL_EXPAND") }
            )

            // 7. Runtime Card (Dynamically binds displaySkillName and clarification options)
            RuntimeCard(
                runtimeState = if (currentState == UiState.SKILL_STORED) runtimeState else RuntimeState.NO_WORKFLOW,
                selectedScenario = selectedScenario,
                currentStep = currentRuntimeStep,
                steps = defaultRuntimeSteps,
                onAction = { act, payload -> handleAction(act, payload) },
                workflowName = displaySkillName,
                clarificationOptions = dynamicClarificationOptions
            )

            // 8. Last Run Card
            LastRunCard(lastRunData = lastRunState)

            // 9. Voice Command Card
            VoiceCommandCard(
                isListening = isVoiceListening,
                statusText = voiceStatusMessage,
                onRecordType = {
                    // Focus/prepare typed text input
                },
                onMicToggle = {
                    onVoiceClick?.invoke()
                }
            )

            // 10. Technical Log Card
            TechnicalLogCard(
                events = activeLogs,
                isCollapsed = isLogCollapsed,
                onToggleCollapse = { handleAction("TOGGLE_LOG_EXPAND") },
                onClear = { handleAction("CLEAR_LOGS") }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Real Architecture Feature 1: Skill Library Side Panel (Integrated with SkillRepository)
        SkillLibrarySidePanel(
            isOpen = isSkillLibraryOpen,
            onClose = { isSkillLibraryOpen = false },
            skills = storedSkills,
            onDelete = { id ->
                val deleted = repository.deleteWorkflow(id)
                if (deleted) {
                    storedSkillsCount = repository.getWorkflowCount()
                    if (selectedWorkflowId == id) {
                        selectedWorkflowId = null
                    }
                    if (storedSkillsCount == 0 && currentState == UiState.SKILL_STORED) {
                        currentState = UiState.READY
                        runtimeState = RuntimeState.NO_WORKFLOW
                    }
                }
            },
            onRedo = { skillId ->
                // RETEACH != EXECUTE: old workflow remains untouched until new teaching is validated & persisted
                val existingRecord = repository.getSkill(skillId)
                val existingWf = repository.getWorkflowById(skillId)
                val name = existingRecord?.name?.ifBlank { null } ?: existingWf?.name?.ifBlank { null } ?: "Retaught Skill"
                val desc = existingRecord?.description?.ifBlank { null } ?: existingWf?.intent?.ifBlank { null } ?: ""
                isSkillLibraryOpen = false
                handleAction("START_TEACHING", SkillCreationData(name = name, description = desc))
            }
        )

        // Real Architecture Feature 2: Activity Progress Dialog (Integrated with Real Architecture Events)
        ActivityProgressDialog(
            isOpen = isActivityDialogOpen,
            onDismissRequest = { isActivityDialogOpen = false },
            events = realArchitectureEvents
        )
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
