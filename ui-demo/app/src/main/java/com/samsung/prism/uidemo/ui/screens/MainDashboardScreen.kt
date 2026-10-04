package com.samsung.prism.uidemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.samsung.prism.uidemo.ui.components.*
import com.samsung.prism.uidemo.ui.mock.*
import com.samsung.prism.uidemo.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MainDashboardScreen(
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var runtimeJob by remember { mutableStateOf<Job?>(null) }

    var uiState by remember { mutableStateOf(UiState.READY) }
    var runtimeState by remember { mutableStateOf(RuntimeState.NO_WORKFLOW) }
    var selectedScenario by remember { mutableStateOf(RuntimeScenario.SUCCESS) }
    var currentRuntimeStep by remember { mutableStateOf(1) }

    var isSkillExpanded by remember { mutableStateOf(false) }
    var isLogCollapsed by remember { mutableStateOf(true) }

    var activeLogs by remember { mutableStateOf(MockData.initialSystemLogs) }
    var lastRunState by remember { mutableStateOf(LastRunData()) }

    // Dynamic Pipeline Steps
    val pipelineSteps = remember(uiState) {
        when (uiState) {
            UiState.READY -> MockData.defaultPipelineSteps
            UiState.TEACHING -> listOf(
                PipelineStep("capture", "Capture", "active"),
                PipelineStep("trace", "Trace", "active"),
                PipelineStep("normalize", "Normalize", "inactive"),
                PipelineStep("extract", "Extract", "inactive"),
                PipelineStep("validate", "Validate", "inactive"),
                PipelineStep("store", "Store", "inactive")
            )
            UiState.TRACE_CAPTURED -> listOf(
                PipelineStep("capture", "Capture", "done"),
                PipelineStep("trace", "Trace", "done"),
                PipelineStep("normalize", "Normalize", "active"),
                PipelineStep("extract", "Extract", "inactive"),
                PipelineStep("validate", "Validate", "inactive"),
                PipelineStep("store", "Store", "inactive")
            )
            UiState.NORMALIZING -> listOf(
                PipelineStep("capture", "Capture", "done"),
                PipelineStep("trace", "Trace", "done"),
                PipelineStep("normalize", "Normalize", "active"),
                PipelineStep("extract", "Extract", "inactive"),
                PipelineStep("validate", "Validate", "inactive"),
                PipelineStep("store", "Store", "inactive")
            )
            UiState.NORMALIZED -> listOf(
                PipelineStep("capture", "Capture", "done"),
                PipelineStep("trace", "Trace", "done"),
                PipelineStep("normalize", "Normalize", "done"),
                PipelineStep("extract", "Extract", "active"),
                PipelineStep("validate", "Validate", "inactive"),
                PipelineStep("store", "Store", "inactive")
            )
            UiState.EXTRACTING -> listOf(
                PipelineStep("capture", "Capture", "done"),
                PipelineStep("trace", "Trace", "done"),
                PipelineStep("normalize", "Normalize", "done"),
                PipelineStep("extract", "Extract", "active"),
                PipelineStep("validate", "Validate", "inactive"),
                PipelineStep("store", "Store", "inactive")
            )
            UiState.VALIDATING -> listOf(
                PipelineStep("capture", "Capture", "done"),
                PipelineStep("trace", "Trace", "done"),
                PipelineStep("normalize", "Normalize", "done"),
                PipelineStep("extract", "Extract", "done"),
                PipelineStep("validate", "Validate", "active"),
                PipelineStep("store", "Store", "inactive")
            )
            UiState.SKILL_STORED -> listOf(
                PipelineStep("capture", "Capture", "done"),
                PipelineStep("trace", "Trace", "done"),
                PipelineStep("normalize", "Normalize", "done"),
                PipelineStep("extract", "Extract", "done"),
                PipelineStep("validate", "Validate", "done"),
                PipelineStep("store", "Store", "done")
            )
        }
    }

    // Dynamic Learned Skill Data
    val learnedSkillData = remember(uiState) {
        when (uiState) {
            UiState.READY -> LearnedSkillData(state = "EMPTY", hasSkill = false)
            UiState.TEACHING -> LearnedSkillData(
                state = "BUILDING",
                hasSkill = false,
                buildingTitle = "Building skill...",
                buildingDescription = "Semantic structure being prepared from stream."
            )
            UiState.TRACE_CAPTURED -> LearnedSkillData(
                state = "BUILDING",
                hasSkill = false,
                buildingTitle = "Building skill...",
                buildingDescription = "Raw event trace captured (14 events)."
            )
            UiState.NORMALIZING -> LearnedSkillData(
                state = "BUILDING",
                hasSkill = false,
                buildingTitle = "Building skill...",
                buildingDescription = "Synthesizing normalized interaction graph."
            )
            UiState.NORMALIZED -> LearnedSkillData(
                state = "BUILDING",
                hasSkill = false,
                buildingTitle = "Building skill...",
                buildingDescription = "Trace normalized (5 candidate actions)."
            )
            UiState.EXTRACTING -> LearnedSkillData(
                state = "BUILDING",
                hasSkill = false,
                buildingTitle = "Building skill...",
                buildingDescription = "5 semantic actions detected, 4 parameter slots bound."
            )
            UiState.VALIDATING -> LearnedSkillData(
                state = "VALIDATING",
                hasSkill = false,
                buildingTitle = "Validating skill...",
                buildingDescription = "Running invariant checks & safety validation."
            )
            UiState.SKILL_STORED -> LearnedSkillData(
                state = "STORED",
                hasSkill = true,
                name = "Order Food",
                technicalId = "skill.order_food.v1",
                status = "VALIDATED",
                intent = "order_food"
            )
        }
    }

    // Dynamic Header Status and Current State
    val (headerStatus, headerVariant, stateTitle, stateDesc) = when {
        uiState == UiState.SKILL_STORED && runtimeState == RuntimeState.RUNNING ->
            Quad("RUNNING", "learning", "Executing Order Food", "Step $currentRuntimeStep of 5: ${MockData.runtimeMockSteps.getOrNull(currentRuntimeStep - 1)?.description ?: ""}")
        uiState == UiState.SKILL_STORED && runtimeState == RuntimeState.STARTING ->
            Quad("STARTING", "learning", "Executing Order Food", "Preparing runtime execution...")
        uiState == UiState.SKILL_STORED && runtimeState == RuntimeState.PAUSED ->
            Quad("WAITING", "paused", "Runtime paused", "Execution suspended at step $currentRuntimeStep of 5.")
        uiState == UiState.SKILL_STORED && runtimeState == RuntimeState.USER_HANDOFF ->
            Quad("USER HANDOFF", "handoff", "User control required", "Automation stopped at Payment. User confirmation needed.")
        uiState == UiState.SKILL_STORED && runtimeState == RuntimeState.CLARIFICATION_NEEDED ->
            Quad("WAITING", "waiting", "Target could not be resolved", "Please clarify which restaurant target you intended.")
        uiState == UiState.SKILL_STORED && runtimeState == RuntimeState.RECOVERING ->
            Quad("RECOVERING", "learning", "Attempting target resolution", "Retrying target alignment with user selection.")
        uiState == UiState.SKILL_STORED && runtimeState == RuntimeState.TARGET_NOT_RESOLVED ->
            Quad("TARGET NOT RESOLVED", "unresolved", "Target could not be resolved", "Automation paused. User input required.")
        uiState == UiState.SKILL_STORED && runtimeState == RuntimeState.COMPLETED ->
            Quad("COMPLETED", "completed", "Workflow completed", "All 5 semantic actions executed successfully.")
        uiState == UiState.SKILL_STORED ->
            Quad("READY", "ready", "Learned skill ready", "One-shot workflow ready for voice-driven semantic execution.")
        uiState == UiState.TEACHING ->
            Quad("TEACHING", "teaching", "Recording demonstration", "Perform the workflow in the target app.")
        uiState == UiState.TRACE_CAPTURED ->
            Quad("TRACE CAPTURED", "warning", "Demonstration captured", "Trace ready for normalization.")
        uiState == UiState.NORMALIZING ->
            Quad("NORMALIZING", "learning", "Normalizing demonstration trace", "Filtering noise and canonicalizing UI interactions.")
        uiState == UiState.NORMALIZED ->
            Quad("NORMALIZED", "warning", "Trace normalized", "Ready to extract semantic actions and intent parameters.")
        uiState == UiState.EXTRACTING ->
            Quad("LEARNING", "learning", "Extracting semantic actions", "Converting demonstrated interactions into semantic actions.")
        uiState == UiState.VALIDATING ->
            Quad("VALIDATING", "learning", "Validating learned workflow", "Verifying trace completeness and semantic safety boundaries.")
        else ->
            Quad("READY", "ready", "No active workflow", "Ready to teach or execute a learned workflow.")
    }

    // Runtime execution advance runner
    fun startRuntimeExecution(fromStep: Int = 1) {
        runtimeJob?.cancel()
        runtimeJob = coroutineScope.launch {
            var step = fromStep
            while (step <= 5) {
                currentRuntimeStep = step

                // Scenario check: Safety Handoff at Step 3
                if (selectedScenario == RuntimeScenario.SAFETY_HANDOFF && step == 3) {
                    runtimeState = RuntimeState.USER_HANDOFF
                    activeLogs = activeLogs + MockData.runtimeStepLogs[1] + MockData.handoffScenarioLogs
                    lastRunState = LastRunData(
                        hasHistory = true,
                        outcome = "HANDOFF",
                        status = "HANDOFF",
                        skillName = "Order Food",
                        completedSteps = 3,
                        totalSteps = 5,
                        stoppedAt = "Payment"
                    )
                    return@launch
                }

                // Scenario check: Clarification at Step 2
                if (selectedScenario == RuntimeScenario.CLARIFICATION && step == 2) {
                    runtimeState = RuntimeState.CLARIFICATION_NEEDED
                    activeLogs = activeLogs + MockData.runtimeStepLogs[0] + MockData.clarificationAmbiguousLogs
                    return@launch
                }

                // Scenario check: Unresolved Target at Step 2
                if (selectedScenario == RuntimeScenario.UNRESOLVED_TARGET && step == 2) {
                    runtimeState = RuntimeState.TARGET_NOT_RESOLVED
                    activeLogs = activeLogs + MockData.runtimeStepLogs[0] + MockData.unresolvedScenarioLogs
                    lastRunState = LastRunData(
                        hasHistory = true,
                        outcome = "TARGET_NOT_RESOLVED",
                        status = "UNRESOLVED",
                        skillName = "Order Food",
                        target = "Restaurant"
                    )
                    return@launch
                }

                // Normal execution step log
                val stepActionLog = MockData.runtimeStepLogs.getOrNull((step - 1) * 2)
                val stepVerifyLog = MockData.runtimeStepLogs.getOrNull((step - 1) * 2 + 1)
                if (stepActionLog != null) activeLogs = activeLogs + stepActionLog
                delay(950)
                if (stepVerifyLog != null) activeLogs = activeLogs + stepVerifyLog

                step++
            }

            // All steps finished
            runtimeState = RuntimeState.COMPLETED
            val completeLog = MockData.runtimeStepLogs.lastOrNull()
            if (completeLog != null) activeLogs = activeLogs + completeLog
            lastRunState = LastRunData(
                hasHistory = true,
                outcome = "SUCCESS",
                status = "SUCCESS",
                skillName = "Order Food",
                stepsCount = 5,
                failuresCount = 0,
                duration = "12.4 s"
            )
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
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
            statusBadge = headerStatus,
            title = stateTitle,
            description = stateDesc,
            statusVariant = headerVariant
        )

        // 3. Command Section
        CommandInput(
            value = when (uiState) {
                UiState.READY -> ""
                UiState.SKILL_STORED -> "Order 2 pizzas to 123 Main St"
                else -> "Order food"
            },
            placeholder = "Search for headphones"
        )

        // 4. Teach Section
        TeachSection(
            uiState = uiState,
            onStartTeaching = {
                runtimeJob?.cancel()
                uiState = UiState.TEACHING
                runtimeState = RuntimeState.NO_WORKFLOW
                isSkillExpanded = false
                activeLogs = activeLogs + MockData.teachingStartLogs
            },
            onStopTeaching = {
                uiState = UiState.TRACE_CAPTURED
                activeLogs = activeLogs + MockData.traceCapturedLogs
            },
            onNormalizeTrace = {
                uiState = UiState.NORMALIZING
                activeLogs = activeLogs + MockData.normalizeStartLogs
                coroutineScope.launch {
                    delay(700)
                    if (uiState == UiState.NORMALIZING) {
                        uiState = UiState.NORMALIZED
                        activeLogs = activeLogs + MockData.normalizeDoneLogs
                    }
                }
            },
            onExtractActions = {
                uiState = UiState.EXTRACTING
                activeLogs = activeLogs + MockData.extractLogs
            },
            onValidateWorkflow = {
                uiState = UiState.VALIDATING
                activeLogs = activeLogs + MockData.validateLogs
            },
            onStoreSkill = {
                uiState = UiState.SKILL_STORED
                runtimeState = RuntimeState.READY
                activeLogs = activeLogs + MockData.storeLogs
            },
            onReset = {
                runtimeJob?.cancel()
                uiState = UiState.READY
                runtimeState = RuntimeState.NO_WORKFLOW
                currentRuntimeStep = 1
                isSkillExpanded = false
                isLogCollapsed = true
                lastRunState = LastRunData()
                activeLogs = MockData.initialSystemLogs
            }
        )

        // 5. Learning Pipeline
        LearningPipeline(steps = pipelineSteps)

        // 5b. Semantic Actions Preview (during EXTRACTING)
        if (uiState == UiState.EXTRACTING) {
            SemanticActionsPreview(actions = MockData.semanticActionsDetected)
        }

        // 5c. Validation Checklist (during VALIDATING)
        if (uiState == UiState.VALIDATING) {
            ValidationSummaryCard(items = MockData.validationItems)
        }

        // 6. Learned Skill Card
        SkillCard(
            skillData = learnedSkillData,
            isExpanded = isSkillExpanded,
            onToggleExpand = { isSkillExpanded = !isSkillExpanded }
        )

        // 7. Runtime Card
        RuntimeCard(
            runtimeState = runtimeState,
            hasWorkflow = uiState == UiState.SKILL_STORED,
            currentStep = currentRuntimeStep,
            selectedScenario = selectedScenario,
            onSelectScenario = { selectedScenario = it },
            onStartRuntime = {
                runtimeState = RuntimeState.STARTING
                currentRuntimeStep = 1
                activeLogs = activeLogs + LogEvent("12:29:00", "RUNTIME", "RUNTIME_STARTED", "Executing skill: order_food", "active")
                coroutineScope.launch {
                    delay(500)
                    runtimeState = RuntimeState.RUNNING
                    startRuntimeExecution(1)
                }
            },
            onPauseRuntime = {
                runtimeJob?.cancel()
                runtimeState = RuntimeState.PAUSED
                activeLogs = activeLogs + LogEvent("12:29:05", "RUNTIME", "RUNTIME_PAUSED", "Suspended at Step $currentRuntimeStep / 5", "warning")
            },
            onResumeRuntime = {
                runtimeState = RuntimeState.RUNNING
                activeLogs = activeLogs + LogEvent("12:29:06", "RUNTIME", "RUNTIME_RESUMED", "Resuming from Step $currentRuntimeStep / 5", "success")
                startRuntimeExecution(currentRuntimeStep)
            },
            onProvideClarification = { choice ->
                runtimeState = RuntimeState.RECOVERING
                activeLogs = activeLogs + MockData.clarificationRecoveryLogs
                coroutineScope.launch {
                    delay(650)
                    runtimeState = RuntimeState.RUNNING
                    startRuntimeExecution(2)
                }
            },
            onReturnToUser = {
                runtimeJob?.cancel()
                runtimeState = RuntimeState.READY
                currentRuntimeStep = 1
                activeLogs = activeLogs + LogEvent("12:29:15", "STATE", "CONTROL_RETURNED_TO_USER", "Dashboard ready for manual user input", "normal")
            },
            onResetRuntime = {
                runtimeJob?.cancel()
                runtimeState = RuntimeState.READY
                currentRuntimeStep = 1
                activeLogs = activeLogs + LogEvent("12:29:20", "RUNTIME", "RUNTIME_RESET", "Order Food skill ready for execution", "normal")
            }
        )

        // 8. Last Run Card
        LastRunCard(lastRunData = lastRunState)

        // 9. Voice Command Section
        VoiceCommandCard()

        // 10. Technical Log Section
        TechnicalLogCard(
            logs = activeLogs,
            isCollapsed = isLogCollapsed,
            onToggleCollapse = { isLogCollapsed = !isLogCollapsed },
            onClearLogs = {
                activeLogs = listOf(
                    LogEvent("12:30:00", "SYSTEM", "LOGS_CLEARED", "Console log buffer cleared by user", "normal")
                )
            }
        )

        Spacer(modifier = Modifier.height(20.dp))
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
