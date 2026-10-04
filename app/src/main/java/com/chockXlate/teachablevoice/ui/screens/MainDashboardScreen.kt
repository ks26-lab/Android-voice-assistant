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
import com.chockXlate.teachablevoice.skill.repository.SkillRepository
import com.chockXlate.teachablevoice.skill.repository.SkillRepositoryProvider
import com.chockXlate.teachablevoice.skill.inspector.WorkflowInspectorImpl
import com.chockXlate.teachablevoice.ui.components.*
import com.chockXlate.teachablevoice.ui.mock.*
import com.chockXlate.teachablevoice.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun MainDashboardScreen(
    modifier: Modifier = Modifier
) {
    val repository = remember { SkillRepositoryProvider.getRepository() }
    var storedSkillsCount by remember { mutableIntStateOf(repository.getWorkflowCount()) }

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

    var lastRunState by remember { mutableStateOf(LastRunData()) }
    val activeLogs = remember { mutableStateListOf<LogEntry>().apply { addAll(initialSystemLogs) } }

    // Refresh stored skill on state changes
    val activeWorkflow = remember(currentState, storedSkillsCount) {
        repository.getAllWorkflows().firstOrNull()
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
    val learnedSkillData = remember(currentState, activeWorkflow) {
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
        } else {
            when (currentState) {
                UiState.TEACHING, UiState.TRACE_CAPTURED, UiState.NORMALIZING, UiState.NORMALIZED ->
                    LearnedSkillData(state = "BUILDING", hasSkill = false)
                UiState.EXTRACTING, UiState.VALIDATING ->
                    LearnedSkillData(state = "BUILDING", hasSkill = false, buildingTitle = "Extracting & validating...", buildingDescription = "Structuring semantic parameters.")
                else -> LearnedSkillData(state = "EMPTY", hasSkill = false)
            }
        }
    }

    val displaySkillName = activeWorkflow?.name?.ifBlank { activeWorkflow.intent } ?: "Learned Skill"

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
            delay(500)
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
                return@LaunchedEffect
            }

            if (selectedScenario == RuntimeScenario.UNRESOLVED_TARGET && currentRuntimeStep == 2) {
                runtimeState = RuntimeState.TARGET_NOT_RESOLVED
                activeLogs.addAll(unresolvedScenarioLogs)
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
            } else {
                runtimeState = RuntimeState.COMPLETED
                val verifyLast = runtimeStepLogs.find { it.event == "STEP_5_COMPLETE" }
                val completeLog = runtimeStepLogs.find { it.event == "RUNTIME_COMPLETE" }
                if (verifyLast != null && activeLogs.none { it.event == verifyLast.event }) activeLogs.add(verifyLast)
                if (completeLog != null) activeLogs.add(completeLog)
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
        when (action) {
            "START_TEACHING" -> {
                currentState = UiState.TEACHING
                runtimeState = RuntimeState.NO_WORKFLOW
                isSkillExpanded = false
                activeLogs.addAll(teachingLogStart)
            }
            "STOP_TEACHING" -> {
                currentState = UiState.TRACE_CAPTURED
                activeLogs.addAll(teachingLogCapture)
            }
            "NORMALIZE_TRACE" -> {
                currentState = UiState.NORMALIZING
                activeLogs.addAll(teachingLogNormalizeStart)
            }
            "EXTRACT_ACTIONS" -> {
                currentState = UiState.EXTRACTING
                activeLogs.addAll(teachingLogExtract)
            }
            "VALIDATE_WORKFLOW" -> {
                currentState = UiState.VALIDATING
                activeLogs.addAll(teachingLogValidate)
            }
            "STORE_SKILL" -> {
                // If repository is empty, store synthesized workflow
                if (repository.getWorkflowCount() == 0) {
                    val synthSlot = com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot(
                        name = "item",
                        type = com.chockXlate.teachablevoice.contract.workflow.SlotType.TEXT,
                        required = true,
                        exampleValue = "headphones"
                    )
                    val synthStep = com.chockXlate.teachablevoice.contract.workflow.WorkflowStep(
                        stepId = "step_1",
                        semanticAction = "INPUT_TEXT",
                        semanticSelector = com.chockXlate.teachablevoice.contract.workflow.SemanticSelector(
                            role = "EditText",
                            resourceId = "search_box",
                            textSlot = "\${item}"
                        ),
                        parameters = mapOf("input_parameter" to "\${item}")
                    )
                    val synthWf = com.chockXlate.teachablevoice.contract.workflow.Workflow(
                        skillId = "skill_search_information_v1",
                        name = "Search",
                        intent = "search_information",
                        appContext = "com.example.search",
                        slots = listOf(synthSlot),
                        steps = listOf(synthStep),
                        safetyBoundary = com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary()
                    )
                    repository.saveWorkflow(synthWf)
                }
                storedSkillsCount = repository.getWorkflowCount()
                currentState = UiState.SKILL_STORED
                runtimeState = RuntimeState.READY
                currentRuntimeStep = 1
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
                activeLogs.add(
                    LogEntry("12:29:00", LogCategory.RUNTIME, "RUNTIME_STARTED", "Executing skill: ${activeWorkflow?.skillId ?: "generic_skill"}", "active")
                )
                if (runtimeStepLogs.isNotEmpty()) {
                    activeLogs.add(runtimeStepLogs[0])
                }
            }
            "PAUSE_RUNTIME" -> {
                runtimeState = RuntimeState.PAUSED
                activeLogs.add(
                    LogEntry("12:29:05", LogCategory.RUNTIME, "RUNTIME_PAUSED", "Suspended at Step $currentRuntimeStep", "warning")
                )
            }
            "RESUME_RUNTIME" -> {
                runtimeState = RuntimeState.RUNNING
                activeLogs.add(
                    LogEntry("12:29:06", LogCategory.RUNTIME, "RUNTIME_RESUMED", "Resuming from Step $currentRuntimeStep", "success")
                )
            }
            "PROVIDE_CLARIFICATION" -> {
                runtimeState = RuntimeState.RECOVERING
                activeLogs.addAll(clarificationRecoveryLogs)
            }
            "RETURN_TO_USER" -> {
                runtimeState = RuntimeState.READY
                currentRuntimeStep = 1
                activeLogs.add(
                    LogEntry("12:29:15", LogCategory.STATE, "CONTROL_RETURNED_TO_USER", "Dashboard ready for manual user input", "normal")
                )
            }
            "RESET_RUNTIME" -> {
                runtimeState = RuntimeState.READY
                currentRuntimeStep = 1
                activeLogs.add(
                    LogEntry("12:29:20", LogCategory.RUNTIME, "RUNTIME_RESET", "$displaySkillName skill ready for execution", "normal")
                )
            }
            "RESET" -> {
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
                    LogEntry("12:30:00", LogCategory.SYSTEM, "LOGS_CLEARED", "Console log buffer cleared by user", "normal")
                )
            }
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
            // 1. App Header
            AppHeader(
                title = "Teachable Voice Automation",
                subtitle = "One-Shot Semantic Workflow Learning",
                status = headerStatus,
                statusVariant = headerVariant
            )

            // 2. Current State Card
            CurrentStateCard(
                statusBadge = currentBadge,
                statusVariant = currentVariant,
                title = currentTitle,
                description = currentDesc
            )

            // 3. Command Input
            CommandInput(
                placeholder = "Search for headphones",
                value = if (currentState == UiState.TEACHING) "Order food" else ""
            )

            // 4. Teach Section
            TeachSection(
                currentState = currentState,
                onAction = { handleAction(it) }
            )

            // 5. Learning Pipeline
            LearningPipeline(steps = pipelineSteps)

            // 5b. Semantic Actions Preview (during EXTRACTING)
            if (currentState == UiState.EXTRACTING) {
                SemanticActionsPreview(actions = defaultSemanticActions)
            }

            // 5c. Validation Checklist (during VALIDATING)
            if (currentState == UiState.VALIDATING) {
                ValidationSummaryCard(items = defaultValidationItems)
            }

            // 6. Learned Skill Card
            SkillCard(
                skillData = learnedSkillData,
                isExpanded = isSkillExpanded,
                onToggleExpand = { handleAction("TOGGLE_SKILL_EXPAND") }
            )

            // 7. Runtime Card
            RuntimeCard(
                runtimeState = if (currentState == UiState.SKILL_STORED) runtimeState else RuntimeState.NO_WORKFLOW,
                selectedScenario = selectedScenario,
                currentStep = currentRuntimeStep,
                steps = defaultRuntimeSteps,
                onAction = { act, payload -> handleAction(act, payload) }
            )

            // 8. Last Run Card
            LastRunCard(lastRunData = lastRunState)

            // 9. Voice Command Card
            VoiceCommandCard()

            // 10. Technical Log Card
            TechnicalLogCard(
                events = activeLogs,
                isCollapsed = isLogCollapsed,
                onToggleCollapse = { handleAction("TOGGLE_LOG_EXPAND") },
                onClear = { handleAction("CLEAR_LOGS") }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
