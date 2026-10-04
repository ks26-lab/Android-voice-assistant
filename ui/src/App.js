import { UI_STATES, RUNTIME_STATES, RUNTIME_SCENARIOS, mockStateDefinitions, runtimeMockSteps } from "./mock/dashboardMock.js";
import {
  initialSystemLogs,
  teachingLogSequence,
  runtimeStepLogs,
  handoffScenarioLogs,
  clarificationScenarioLogs,
  unresolvedScenarioLogs
} from "./mock/technicalLogMock.js";
import { AppHeader } from "./components/AppHeader.js";
import { CurrentStateCard } from "./components/CurrentStateCard.js";
import { CommandInput } from "./components/CommandInput.js";
import { TeachSection } from "./components/TeachSection.js";
import { LearningPipeline } from "./components/LearningPipeline.js";
import { SemanticActionsPreview } from "./components/SemanticActionsPreview.js";
import { ValidationSummaryCard } from "./components/ValidationSummaryCard.js";
import { SkillCard } from "./components/SkillCard.js";
import { RuntimeCard } from "./components/RuntimeCard.js";
import { LastRunCard } from "./components/LastRunCard.js";
import { VoiceCommandCard } from "./components/VoiceCommandCard.js";
import { TechnicalLogCard } from "./components/TechnicalLogCard.js";

/**
 * App Root Component
 * Manages UI presentation state for Phases 1-6:
 * Foundation -> Teaching/Learning -> Learned Skill -> Runtime Execution -> Safety/Recovery -> Technical Log & Debug Inspection
 */
export function App() {
  let currentStateKey = UI_STATES.READY;
  let isSkillExpanded = false;
  let isLogCollapsed = true;

  // Runtime Execution State
  let runtimeState = RUNTIME_STATES.NO_WORKFLOW;
  let selectedScenario = RUNTIME_SCENARIOS.SUCCESS;
  let currentRuntimeStep = 1;
  let runtimeTimer = null;

  // Last Run presentation state
  let lastRunState = {
    hasHistory: false,
    emptyTitle: "No previous run",
    emptyDescription: "Run history will appear here."
  };

  // Structured Technical Logs stream
  let activeLogs = [...initialSystemLogs];

  const viewport = document.createElement("div");
  viewport.className = "app-viewport";

  const container = document.createElement("main");
  container.className = "mobile-container";
  viewport.appendChild(container);

  function advanceRuntimeStep() {
    // Check scenario triggers at specific steps
    if (selectedScenario === RUNTIME_SCENARIOS.SAFETY_HANDOFF && currentRuntimeStep === 3) {
      // Step 3 triggers Safety Handoff at Payment
      runtimeState = RUNTIME_STATES.USER_HANDOFF;
      const stepVerifyLog = runtimeStepLogs.find(l => l.event === "STEP_2_COMPLETE");
      if (stepVerifyLog && !activeLogs.some(l => l.event === stepVerifyLog.event)) {
        activeLogs.push(stepVerifyLog);
      }
      activeLogs.push(...handoffScenarioLogs);

      lastRunState = {
        hasHistory: true,
        outcome: "HANDOFF",
        status: "HANDOFF",
        skillName: "Order Food",
        completedSteps: 3,
        totalSteps: 5,
        stoppedAt: "Payment",
        reason: "User control required"
      };

      render();
      return;
    }

    if (selectedScenario === RUNTIME_SCENARIOS.CLARIFICATION && currentRuntimeStep === 2) {
      // Step 2 triggers Ambiguous Target Clarification
      runtimeState = RUNTIME_STATES.CLARIFICATION_NEEDED;
      const stepVerifyLog = runtimeStepLogs.find(l => l.event === "STEP_1_COMPLETE");
      if (stepVerifyLog && !activeLogs.some(l => l.event === stepVerifyLog.event)) {
        activeLogs.push(stepVerifyLog);
      }
      activeLogs.push(...clarificationScenarioLogs.AMBIGUOUS);

      render();
      return;
    }

    if (selectedScenario === RUNTIME_SCENARIOS.UNRESOLVED_TARGET && currentRuntimeStep === 2) {
      // Step 2 triggers Target Not Resolved
      runtimeState = RUNTIME_STATES.TARGET_NOT_RESOLVED;
      const stepVerifyLog = runtimeStepLogs.find(l => l.event === "STEP_1_COMPLETE");
      if (stepVerifyLog && !activeLogs.some(l => l.event === stepVerifyLog.event)) {
        activeLogs.push(stepVerifyLog);
      }
      activeLogs.push(...unresolvedScenarioLogs);

      lastRunState = {
        hasHistory: true,
        outcome: "TARGET_NOT_RESOLVED",
        status: "UNRESOLVED",
        skillName: "Order Food",
        target: "Restaurant"
      };

      render();
      return;
    }

    if (currentRuntimeStep < runtimeMockSteps.length) {
      currentRuntimeStep++;
      // Find corresponding log entries for this step
      const stepActionLog = runtimeStepLogs.find(l => l.event === `STEP_${currentRuntimeStep}_${runtimeMockSteps[currentRuntimeStep - 1].action}`);
      const stepVerifyLog = runtimeStepLogs.find(l => l.event === `STEP_${currentRuntimeStep - 1}_COMPLETE`);
      if (stepVerifyLog && !activeLogs.some(l => l.event === stepVerifyLog.event)) {
        activeLogs.push(stepVerifyLog);
      }
      if (stepActionLog) {
        activeLogs.push(stepActionLog);
      }
      render();

      runtimeTimer = setTimeout(advanceRuntimeStep, 950);
    } else {
      // Completed all steps
      runtimeState = RUNTIME_STATES.COMPLETED;
      const verifyLast = runtimeStepLogs.find(l => l.event === "STEP_5_COMPLETE");
      const completeLog = runtimeStepLogs.find(l => l.event === "RUNTIME_COMPLETE");
      if (verifyLast && !activeLogs.some(l => l.event === verifyLast.event)) activeLogs.push(verifyLast);
      if (completeLog) activeLogs.push(completeLog);

      lastRunState = {
        hasHistory: true,
        outcome: "SUCCESS",
        status: "SUCCESS",
        skillName: "Order Food",
        stepsCount: 5,
        failuresCount: 0,
        duration: "12.4 s"
      };

      render();
    }
  }

  function handleAction(actionType, payload) {
    if (runtimeTimer) {
      clearTimeout(runtimeTimer);
      runtimeTimer = null;
    }

    switch (actionType) {
      case "START_TEACHING":
        currentStateKey = UI_STATES.TEACHING;
        runtimeState = RUNTIME_STATES.NO_WORKFLOW;
        isSkillExpanded = false;
        activeLogs.push(...teachingLogSequence.START);
        render();
        break;

      case "STOP_TEACHING":
        currentStateKey = UI_STATES.TRACE_CAPTURED;
        activeLogs.push(...teachingLogSequence.CAPTURE);
        render();
        break;

      case "NORMALIZE_TRACE":
        currentStateKey = UI_STATES.NORMALIZING;
        activeLogs.push(...teachingLogSequence.NORMALIZE_START);
        render();
        setTimeout(() => {
          if (currentStateKey === UI_STATES.NORMALIZING) {
            currentStateKey = UI_STATES.NORMALIZED;
            activeLogs.push(...teachingLogSequence.NORMALIZE_DONE);
            render();
          }
        }, 700);
        break;

      case "EXTRACT_ACTIONS":
        currentStateKey = UI_STATES.EXTRACTING;
        activeLogs.push(...teachingLogSequence.EXTRACT);
        render();
        break;

      case "VALIDATE_WORKFLOW":
        currentStateKey = UI_STATES.VALIDATING;
        activeLogs.push(...teachingLogSequence.VALIDATE);
        render();
        break;

      case "STORE_SKILL":
        currentStateKey = UI_STATES.SKILL_STORED;
        runtimeState = RUNTIME_STATES.READY;
        activeLogs.push(...teachingLogSequence.STORE);
        render();
        break;

      case "TOGGLE_SKILL_EXPAND":
        isSkillExpanded = !isSkillExpanded;
        render();
        break;

      case "TOGGLE_LOG_EXPAND":
        isLogCollapsed = !isLogCollapsed;
        render();
        break;

      case "CLEAR_LOGS":
        activeLogs = [
          {
            time: "12:30:00",
            category: "SYSTEM",
            event: "LOGS_CLEARED",
            detail: "Console log buffer cleared by user",
            type: "normal"
          }
        ];
        render();
        break;

      case "SELECT_SCENARIO":
        selectedScenario = payload;
        render();
        break;

      // Phase 4 & 5 Runtime Actions
      case "START_RUNTIME":
        runtimeState = RUNTIME_STATES.STARTING;
        currentRuntimeStep = 1;
        activeLogs.push(
          {
            time: "12:29:00",
            category: "RUNTIME",
            event: "RUNTIME_STARTED",
            detail: "Executing skill: order_food",
            type: "active"
          },
          runtimeStepLogs[0]
        );
        render();

        runtimeTimer = setTimeout(() => {
          runtimeState = RUNTIME_STATES.RUNNING;
          render();
          runtimeTimer = setTimeout(advanceRuntimeStep, 950);
        }, 500);
        break;

      case "PAUSE_RUNTIME":
        runtimeState = RUNTIME_STATES.PAUSED;
        activeLogs.push({
          time: "12:29:05",
          category: "RUNTIME",
          event: "RUNTIME_PAUSED",
          detail: `Suspended at Step ${currentRuntimeStep} / 5`,
          type: "warning"
        });
        render();
        break;

      case "RESUME_RUNTIME":
        runtimeState = RUNTIME_STATES.RUNNING;
        activeLogs.push({
          time: "12:29:06",
          category: "RUNTIME",
          event: "RUNTIME_RESUMED",
          detail: `Resuming from Step ${currentRuntimeStep} / 5`,
          type: "success"
        });
        render();
        runtimeTimer = setTimeout(advanceRuntimeStep, 950);
        break;

      case "PROVIDE_CLARIFICATION":
        runtimeState = RUNTIME_STATES.RECOVERING;
        activeLogs.push(...clarificationScenarioLogs.RECOVERY);
        render();

        runtimeTimer = setTimeout(() => {
          runtimeState = RUNTIME_STATES.RUNNING;
          render();
          runtimeTimer = setTimeout(advanceRuntimeStep, 950);
        }, 650);
        break;

      case "RETURN_TO_USER":
        runtimeState = RUNTIME_STATES.READY;
        currentRuntimeStep = 1;
        activeLogs.push({
          time: "12:29:15",
          category: "STATE",
          event: "CONTROL_RETURNED_TO_USER",
          detail: "Dashboard ready for manual user input",
          type: "normal"
        });
        render();
        break;

      case "RESET_RUNTIME":
        runtimeState = RUNTIME_STATES.READY;
        currentRuntimeStep = 1;
        activeLogs.push({
          time: "12:29:20",
          category: "RUNTIME",
          event: "RUNTIME_RESET",
          detail: "Order Food skill ready for execution",
          type: "normal"
        });
        render();
        break;

      case "RESET":
        currentStateKey = UI_STATES.READY;
        runtimeState = RUNTIME_STATES.NO_WORKFLOW;
        currentRuntimeStep = 1;
        isSkillExpanded = false;
        isLogCollapsed = true;
        lastRunState = {
          hasHistory: false,
          emptyTitle: "No previous run",
          emptyDescription: "Run history will appear here."
        };
        activeLogs = [...initialSystemLogs];
        render();
        break;

      default:
        break;
    }
  }

  function render() {
    container.innerHTML = "";
    const currentMock = mockStateDefinitions[currentStateKey] || mockStateDefinitions[UI_STATES.READY];

    // Dynamic Header & State info based on Phase 4 & 5 runtime execution
    let headerStatus = currentMock.header.status;
    let headerVariant = currentMock.header.statusVariant;
    let currentStateBadge = currentMock.currentState.statusBadge;
    let currentStateVariant = currentMock.currentState.statusVariant;
    let currentStateTitle = currentMock.currentState.title;
    let currentStateDesc = currentMock.currentState.description;

    if (currentStateKey === UI_STATES.SKILL_STORED) {
      if (runtimeState === RUNTIME_STATES.STARTING || runtimeState === RUNTIME_STATES.RUNNING) {
        headerStatus = "RUNNING";
        headerVariant = "running";
        currentStateBadge = "RUNNING";
        currentStateVariant = "running";
        currentStateTitle = "Executing Order Food";
        currentStateDesc = `Step ${currentRuntimeStep} of 5: ${runtimeMockSteps[currentRuntimeStep - 1]?.description || ""}`;
      } else if (runtimeState === RUNTIME_STATES.PAUSED) {
        headerStatus = "WAITING";
        headerVariant = "paused";
        currentStateBadge = "PAUSED";
        currentStateVariant = "paused";
        currentStateTitle = "Runtime paused";
        currentStateDesc = `Execution suspended at step ${currentRuntimeStep} of 5.`;
      } else if (runtimeState === RUNTIME_STATES.USER_HANDOFF) {
        headerStatus = "USER HANDOFF";
        headerVariant = "handoff";
        currentStateBadge = "USER HANDOFF";
        currentStateVariant = "handoff";
        currentStateTitle = "User control required";
        currentStateDesc = "Automation stopped at Payment. User confirmation needed.";
      } else if (runtimeState === RUNTIME_STATES.CLARIFICATION_NEEDED) {
        headerStatus = "WAITING";
        headerVariant = "waiting";
        currentStateBadge = "CLARIFICATION NEEDED";
        currentStateVariant = "waiting";
        currentStateTitle = "Target could not be resolved";
        currentStateDesc = "Please clarify which restaurant target you intended.";
      } else if (runtimeState === RUNTIME_STATES.RECOVERING) {
        headerStatus = "RECOVERING";
        headerVariant = "recovering";
        currentStateBadge = "RECOVERING";
        currentStateVariant = "recovering";
        currentStateTitle = "Attempting target resolution";
        currentStateDesc = "Retrying target alignment with user selection.";
      } else if (runtimeState === RUNTIME_STATES.TARGET_NOT_RESOLVED) {
        headerStatus = "TARGET NOT RESOLVED";
        headerVariant = "unresolved";
        currentStateBadge = "TARGET NOT RESOLVED";
        currentStateVariant = "unresolved";
        currentStateTitle = "Target could not be resolved";
        currentStateDesc = "Automation paused. User input required.";
      } else if (runtimeState === RUNTIME_STATES.COMPLETED) {
        headerStatus = "COMPLETED";
        headerVariant = "completed";
        currentStateBadge = "COMPLETED";
        currentStateVariant = "completed";
        currentStateTitle = "Workflow completed";
        currentStateDesc = "All 5 semantic actions executed successfully.";
      }
    }

    // 1. Application Header
    const header = AppHeader({
      title: currentMock.header.title,
      subtitle: currentMock.header.subtitle,
      status: headerStatus
    });
    const statusPill = header.querySelector(".status-indicator");
    if (statusPill && headerVariant) {
      statusPill.className = `status-indicator ${headerVariant}`;
    }
    container.appendChild(header);

    // 2. Current State Card
    const currentState = CurrentStateCard({
      statusBadge: currentStateBadge,
      title: currentStateTitle,
      description: currentStateDesc
    });
    const stateBadge = currentState.querySelector(".status-indicator");
    if (stateBadge && currentStateVariant) {
      stateBadge.className = `status-indicator ${currentStateVariant}`;
    }
    container.appendChild(currentState);

    // 3. Command Section
    const command = CommandInput({
      placeholder: currentMock.command.placeholder,
      value: currentMock.command.value
    });
    container.appendChild(command);

    // 4. Teach Section
    const teach = TeachSection({
      teachConfig: currentMock.teach,
      onAction: handleAction
    });
    container.appendChild(teach);

    // 5. Learning Pipeline
    const pipeline = LearningPipeline({
      steps: currentMock.pipeline
    });
    container.appendChild(pipeline);

    // 5b. Semantic Actions Preview (during EXTRACTING)
    if (currentMock.semanticActionsPreview && currentStateKey === UI_STATES.EXTRACTING) {
      const semanticPreview = SemanticActionsPreview({
        actions: currentMock.semanticActionsPreview
      });
      if (semanticPreview) container.appendChild(semanticPreview);
    }

    // 5c. Validation Checklist (during VALIDATING)
    if (currentMock.validationSummary && currentStateKey === UI_STATES.VALIDATING) {
      const validationSummary = ValidationSummaryCard({
        items: currentMock.validationSummary
      });
      if (validationSummary) container.appendChild(validationSummary);
    }

    // 6. Learned Skill Section
    const skill = SkillCard({
      skillData: currentMock.learnedSkill,
      isExpanded: isSkillExpanded,
      onToggleExpand: () => handleAction("TOGGLE_SKILL_EXPAND")
    });
    container.appendChild(skill);

    // 7. Runtime Section (Phase 4 & Phase 5 dynamic state)
    const runtimeConfig = {
      state: runtimeState,
      selectedScenario,
      hasWorkflow: currentStateKey === UI_STATES.SKILL_STORED,
      skillName: "Order Food",
      currentStep: currentRuntimeStep,
      totalSteps: runtimeMockSteps.length,
      currentAction: runtimeMockSteps[currentRuntimeStep - 1],
      steps: runtimeMockSteps,
      stoppedAt: "Payment",
      handoffReason: "Sensitive action requires user confirmation."
    };

    const runtime = RuntimeCard({
      runtimeConfig,
      onAction: handleAction
    });
    container.appendChild(runtime);

    // 8. Last Run Section (Phase 4 & Phase 5 outcomes)
    const lastRun = LastRunCard({
      lastRunData: lastRunState
    });
    container.appendChild(lastRun);

    // 9. Voice Command Section
    const voiceCommand = VoiceCommandCard({
      controls: currentMock.voiceCommand.controls
    });
    container.appendChild(voiceCommand);

    // 10. Technical Log Section (Phase 6 Collapsed/Expanded)
    const technicalLog = TechnicalLogCard({
      events: activeLogs,
      isCollapsed: isLogCollapsed,
      onToggleCollapse: () => handleAction("TOGGLE_LOG_EXPAND"),
      onClear: () => handleAction("CLEAR_LOGS")
    });
    container.appendChild(technicalLog);
  }

  render();
  return viewport;
}
