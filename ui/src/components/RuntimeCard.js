import { SectionHeader } from "./SectionHeader.js";
import { PrimaryButton } from "./PrimaryButton.js";
import { SecondaryButton } from "./SecondaryButton.js";
import { RUNTIME_STATES, RUNTIME_SCENARIOS } from "../mock/dashboardMock.js";

/**
 * RuntimeCard Component
 * Implements complete visual runtime states:
 * NO_WORKFLOW -> READY -> STARTING -> RUNNING -> PAUSED -> USER_HANDOFF -> CLARIFICATION_NEEDED -> RECOVERING -> TARGET_NOT_RESOLVED -> COMPLETED
 */
export function RuntimeCard({ runtimeConfig, onAction }) {
  const container = document.createElement("section");
  container.className = "runtime-section";

  const header = SectionHeader({ title: "Runtime" });
  container.appendChild(header);

  const card = document.createElement("div");
  card.className = "card";

  const state = runtimeConfig?.state || (runtimeConfig?.hasWorkflow ? RUNTIME_STATES.READY : RUNTIME_STATES.NO_WORKFLOW);

  // 1. NO_WORKFLOW / Standard Empty State
  if (state === RUNTIME_STATES.NO_WORKFLOW) {
    const msgEl = document.createElement("div");
    msgEl.className = "empty-state-text";
    msgEl.textContent = runtimeConfig?.title || "No workflow available";
    card.appendChild(msgEl);

    if (runtimeConfig?.statusMessage) {
      const subMsgEl = document.createElement("div");
      subMsgEl.className = "empty-state-subtext";
      subMsgEl.textContent = runtimeConfig.statusMessage;
      card.appendChild(subMsgEl);
    }

    const btnStack = document.createElement("div");
    btnStack.className = "btn-row-stacked";

    const executeBtn = PrimaryButton({ label: "Execute Runtime", disabled: true });
    const resumeBtn = SecondaryButton({ label: "Resume Runtime", disabled: true });

    btnStack.appendChild(executeBtn);
    btnStack.appendChild(resumeBtn);
    card.appendChild(btnStack);
  }

  // 2. READY State (with unobtrusive scenario selector for evaluation)
  else if (state === RUNTIME_STATES.READY) {
    // Unobtrusive Scenario Selector
    const selector = document.createElement("div");
    selector.className = "runtime-scenario-selector";

    const scenarios = [
      { id: RUNTIME_SCENARIOS.SUCCESS, label: "Normal" },
      { id: RUNTIME_SCENARIOS.SAFETY_HANDOFF, label: "Safety Stop" },
      { id: RUNTIME_SCENARIOS.CLARIFICATION, label: "Clarify" },
      { id: RUNTIME_SCENARIOS.UNRESOLVED_TARGET, label: "Unresolved" }
    ];

    scenarios.forEach((sc) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = `scenario-pill ${runtimeConfig.selectedScenario === sc.id ? "active" : ""}`;
      btn.textContent = sc.label;
      btn.addEventListener("click", () => onAction("SELECT_SCENARIO", sc.id));
      selector.appendChild(btn);
    });
    card.appendChild(selector);

    const msgEl = document.createElement("div");
    msgEl.className = "empty-state-text";
    msgEl.textContent = runtimeConfig.skillName || "Order Food";
    card.appendChild(msgEl);

    const subMsgEl = document.createElement("div");
    subMsgEl.className = "empty-state-subtext";
    subMsgEl.textContent = runtimeConfig.statusMessage || "Ready to execute";
    card.appendChild(subMsgEl);

    const btnStack = document.createElement("div");
    btnStack.className = "btn-row-stacked";

    const executeBtn = PrimaryButton({
      label: "Execute Runtime",
      disabled: false,
      onClick: () => onAction("START_RUNTIME")
    });

    const resumeBtn = SecondaryButton({
      label: "Resume Runtime",
      disabled: true
    });

    btnStack.appendChild(executeBtn);
    btnStack.appendChild(resumeBtn);
    card.appendChild(btnStack);

    // Utility actions
    const utilityRow = document.createElement("div");
    utilityRow.className = "btn-actions-compact";

    const lastRunBtn = document.createElement("button");
    lastRunBtn.type = "button";
    lastRunBtn.className = "btn-link-subtle";
    lastRunBtn.textContent = "Last Run";

    const resetBtn = document.createElement("button");
    resetBtn.type = "button";
    resetBtn.className = "btn-link-subtle";
    resetBtn.textContent = "Reset";
    resetBtn.addEventListener("click", () => onAction("RESET_RUNTIME"));

    utilityRow.appendChild(lastRunBtn);
    utilityRow.appendChild(resetBtn);
    card.appendChild(utilityRow);
  }

  // 3. STARTING State
  else if (state === RUNTIME_STATES.STARTING) {
    card.classList.add("runtime-running-card");

    const msgEl = document.createElement("div");
    msgEl.className = "empty-state-text";
    msgEl.textContent = runtimeConfig.skillName || "Order Food";
    card.appendChild(msgEl);

    const subMsgEl = document.createElement("div");
    subMsgEl.className = "empty-state-subtext";
    subMsgEl.textContent = "Preparing runtime execution...";
    card.appendChild(subMsgEl);

    const btnStack = document.createElement("div");
    btnStack.className = "btn-row-stacked";

    const startBtn = PrimaryButton({ label: "Starting...", disabled: true });
    btnStack.appendChild(startBtn);
    card.appendChild(btnStack);
  }

  // 4. RUNNING State
  else if (state === RUNTIME_STATES.RUNNING) {
    card.classList.add("runtime-running-card");

    const topRow = document.createElement("div");
    topRow.className = "card-top-row";

    const titleEl = document.createElement("span");
    titleEl.className = "state-primary-text";
    titleEl.style.fontSize = "15px";
    titleEl.textContent = runtimeConfig.skillName || "Order Food";

    const statusPill = document.createElement("span");
    statusPill.className = "status-indicator running";
    statusPill.innerHTML = `<span class="status-dot"></span><span>Step ${runtimeConfig.currentStep || 1} of ${runtimeConfig.totalSteps || 5}</span>`;

    topRow.appendChild(titleEl);
    topRow.appendChild(statusPill);
    card.appendChild(topRow);

    // Step Progress Chips
    const chipsContainer = document.createElement("div");
    chipsContainer.className = "runtime-progress-chips";

    const allSteps = runtimeConfig.steps || [];
    allSteps.forEach((s, idx) => {
      const chip = document.createElement("div");
      const isDone = (idx + 1) < runtimeConfig.currentStep;
      const isActive = (idx + 1) === runtimeConfig.currentStep;

      chip.className = `runtime-chip ${isDone ? "done" : isActive ? "active" : ""}`;
      chip.textContent = isDone ? `${s.action} ✓` : isActive ? `${s.action} ●` : s.action;
      chipsContainer.appendChild(chip);
    });
    card.appendChild(chipsContainer);

    // Current Action Box
    const actionBox = document.createElement("div");
    actionBox.className = "runtime-current-action-box";

    const actionLabel = document.createElement("span");
    actionLabel.className = "runtime-action-label";
    actionLabel.textContent = `CURRENT ACTION • STEP ${runtimeConfig.currentStep} / ${runtimeConfig.totalSteps}`;

    const actionHeader = document.createElement("div");
    actionHeader.className = "runtime-action-header";

    const actionName = document.createElement("span");
    actionName.className = "runtime-action-name";
    actionName.textContent = runtimeConfig.currentAction?.action || "SEARCH";

    const actionDesc = document.createElement("span");
    actionDesc.className = "runtime-action-desc";
    actionDesc.textContent = runtimeConfig.currentAction?.description || "Executing action...";

    actionHeader.appendChild(actionName);
    actionBox.appendChild(actionLabel);
    actionBox.appendChild(actionHeader);
    actionBox.appendChild(actionDesc);
    card.appendChild(actionBox);

    // Button Row: Pause and Reset
    const btnRow = document.createElement("div");
    btnRow.className = "btn-row";

    const pauseBtn = SecondaryButton({
      label: "Pause",
      onClick: () => onAction("PAUSE_RUNTIME")
    });

    const resetBtn = SecondaryButton({
      label: "Reset",
      onClick: () => onAction("RESET_RUNTIME")
    });

    btnRow.appendChild(pauseBtn);
    btnRow.appendChild(resetBtn);
    card.appendChild(btnRow);
  }

  // 5. PAUSED State
  else if (state === RUNTIME_STATES.PAUSED) {
    const topRow = document.createElement("div");
    topRow.className = "card-top-row";

    const titleEl = document.createElement("span");
    titleEl.className = "state-primary-text";
    titleEl.style.fontSize = "15px";
    titleEl.textContent = runtimeConfig.skillName || "Order Food";

    const statusPill = document.createElement("span");
    statusPill.className = "status-indicator paused";
    statusPill.innerHTML = `<span class="status-dot"></span><span>PAUSED (${runtimeConfig.currentStep} / ${runtimeConfig.totalSteps})</span>`;

    topRow.appendChild(titleEl);
    topRow.appendChild(statusPill);
    card.appendChild(topRow);

    const subMsgEl = document.createElement("div");
    subMsgEl.className = "empty-state-subtext";
    subMsgEl.textContent = `Execution paused at step ${runtimeConfig.currentStep} (${runtimeConfig.currentAction?.action || ""})`;
    card.appendChild(subMsgEl);

    const btnStack = document.createElement("div");
    btnStack.className = "btn-row-stacked";

    const resumeBtn = PrimaryButton({
      label: "Resume Runtime",
      onClick: () => onAction("RESUME_RUNTIME")
    });

    const resetBtn = SecondaryButton({
      label: "Reset",
      onClick: () => onAction("RESET_RUNTIME")
    });

    btnStack.appendChild(resumeBtn);
    btnStack.appendChild(resetBtn);
    card.appendChild(btnStack);
  }

  // 6. USER_HANDOFF State (Phase 5)
  else if (state === RUNTIME_STATES.USER_HANDOFF) {
    card.classList.add("runtime-handoff-card");

    const topRow = document.createElement("div");
    topRow.className = "card-top-row";

    const titleEl = document.createElement("span");
    titleEl.className = "state-primary-text";
    titleEl.style.fontSize = "15px";
    titleEl.textContent = runtimeConfig.skillName || "Order Food";

    const statusPill = document.createElement("span");
    statusPill.className = "status-indicator handoff";
    statusPill.innerHTML = `<span>⚠</span><span>USER HANDOFF</span>`;

    topRow.appendChild(titleEl);
    topRow.appendChild(statusPill);
    card.appendChild(topRow);

    const handoffBox = document.createElement("div");
    handoffBox.className = "runtime-handoff-box";

    const hTitle = document.createElement("div");
    hTitle.className = "runtime-handoff-title";
    hTitle.innerHTML = `<span>Stopped at:</span> <span>${runtimeConfig.stoppedAt || "Payment"}</span>`;

    const hMeta = document.createElement("div");
    hMeta.className = "runtime-handoff-meta";
    hMeta.innerHTML = `<strong>Reason:</strong> ${runtimeConfig.handoffReason || "Sensitive action requires user confirmation."}`;

    handoffBox.appendChild(hTitle);
    handoffBox.appendChild(hMeta);
    card.appendChild(handoffBox);

    const btnStack = document.createElement("div");
    btnStack.className = "btn-row-stacked";

    const returnBtn = document.createElement("button");
    returnBtn.type = "button";
    returnBtn.className = "btn btn-warning";
    returnBtn.textContent = "Continue Manually";
    returnBtn.addEventListener("click", () => onAction("RETURN_TO_USER"));

    const resetBtn = SecondaryButton({
      label: "Reset",
      onClick: () => onAction("RESET_RUNTIME")
    });

    btnStack.appendChild(returnBtn);
    btnStack.appendChild(resetBtn);
    card.appendChild(btnStack);
  }

  // 7. CLARIFICATION_NEEDED / WAITING State (Phase 5)
  else if (state === RUNTIME_STATES.CLARIFICATION_NEEDED) {
    card.classList.add("runtime-clarify-card");

    const topRow = document.createElement("div");
    topRow.className = "card-top-row";

    const titleEl = document.createElement("span");
    titleEl.className = "state-primary-text";
    titleEl.style.fontSize = "15px";
    titleEl.textContent = runtimeConfig.skillName || "Order Food";

    const statusPill = document.createElement("span");
    statusPill.className = "status-indicator waiting";
    statusPill.innerHTML = `<span class="status-dot"></span><span>WAITING FOR USER</span>`;

    topRow.appendChild(titleEl);
    topRow.appendChild(statusPill);
    card.appendChild(topRow);

    const handoffBox = document.createElement("div");
    handoffBox.className = "runtime-handoff-box";
    handoffBox.style.borderColor = "var(--color-border-medium)";

    const hTitle = document.createElement("div");
    hTitle.className = "runtime-handoff-title";
    hTitle.style.color = "var(--color-text-accent)";
    hTitle.innerHTML = `<span>Clarification Needed:</span> <span>Target Ambiguity</span>`;

    const hMeta = document.createElement("div");
    hMeta.className = "runtime-handoff-meta";
    hMeta.textContent = "Which restaurant should I use?";

    handoffBox.appendChild(hTitle);
    handoffBox.appendChild(hMeta);

    // Clarification Options
    const choicesGroup = document.createElement("div");
    choicesGroup.className = "clarification-choices-group";

    const choicesRow = document.createElement("div");
    choicesRow.className = "clarification-btn-row";

    const opt1 = document.createElement("button");
    opt1.type = "button";
    opt1.className = "clarification-option-btn";
    opt1.textContent = "Pizza Palace";
    opt1.addEventListener("click", () => onAction("PROVIDE_CLARIFICATION", "Pizza Palace"));

    const opt2 = document.createElement("button");
    opt2.type = "button";
    opt2.className = "clarification-option-btn";
    opt2.textContent = "Domino's";
    opt2.addEventListener("click", () => onAction("PROVIDE_CLARIFICATION", "Domino's"));

    choicesRow.appendChild(opt1);
    choicesRow.appendChild(opt2);
    choicesGroup.appendChild(choicesRow);
    handoffBox.appendChild(choicesGroup);

    card.appendChild(handoffBox);

    const btnStack = document.createElement("div");
    btnStack.className = "btn-row-stacked";

    const clarifyBtn = PrimaryButton({
      label: "Clarify / Continue",
      onClick: () => onAction("PROVIDE_CLARIFICATION", "Pizza Palace")
    });

    const resetBtn = SecondaryButton({
      label: "Reset",
      onClick: () => onAction("RESET_RUNTIME")
    });

    btnStack.appendChild(clarifyBtn);
    btnStack.appendChild(resetBtn);
    card.appendChild(btnStack);
  }

  // 8. RECOVERING State (Phase 5)
  else if (state === RUNTIME_STATES.RECOVERING) {
    card.classList.add("runtime-running-card");

    const topRow = document.createElement("div");
    topRow.className = "card-top-row";

    const titleEl = document.createElement("span");
    titleEl.className = "state-primary-text";
    titleEl.style.fontSize = "15px";
    titleEl.textContent = runtimeConfig.skillName || "Order Food";

    const statusPill = document.createElement("span");
    statusPill.className = "status-indicator recovering";
    statusPill.innerHTML = `<span class="status-dot"></span><span>RECOVERING</span>`;

    topRow.appendChild(titleEl);
    topRow.appendChild(statusPill);
    card.appendChild(topRow);

    const subMsgEl = document.createElement("div");
    subMsgEl.className = "empty-state-subtext";
    subMsgEl.textContent = "Attempting to resolve target again...";
    card.appendChild(subMsgEl);
  }

  // 9. TARGET_NOT_RESOLVED State (Phase 5)
  else if (state === RUNTIME_STATES.TARGET_NOT_RESOLVED) {
    card.classList.add("runtime-handoff-card");

    const topRow = document.createElement("div");
    topRow.className = "card-top-row";

    const titleEl = document.createElement("span");
    titleEl.className = "state-primary-text";
    titleEl.style.fontSize = "15px";
    titleEl.textContent = runtimeConfig.skillName || "Order Food";

    const statusPill = document.createElement("span");
    statusPill.className = "status-indicator unresolved";
    statusPill.innerHTML = `<span>⚠</span><span>TARGET NOT RESOLVED</span>`;

    topRow.appendChild(titleEl);
    topRow.appendChild(statusPill);
    card.appendChild(topRow);

    const handoffBox = document.createElement("div");
    handoffBox.className = "runtime-handoff-box";

    const hTitle = document.createElement("div");
    hTitle.className = "runtime-handoff-title";
    hTitle.style.color = "#f87171";
    hTitle.innerHTML = `<span>Target:</span> <span>Restaurant</span>`;

    const hMeta = document.createElement("div");
    hMeta.className = "runtime-handoff-meta";
    hMeta.innerHTML = `Could not uniquely identify the requested target.<br>Automation paused. User input required.`;

    handoffBox.appendChild(hTitle);
    handoffBox.appendChild(hMeta);
    card.appendChild(handoffBox);

    const btnStack = document.createElement("div");
    btnStack.className = "btn-row-stacked";

    const returnBtn = document.createElement("button");
    returnBtn.type = "button";
    returnBtn.className = "btn btn-destructive";
    returnBtn.textContent = "Return to User";
    returnBtn.addEventListener("click", () => onAction("RETURN_TO_USER"));

    const resetBtn = SecondaryButton({
      label: "Reset",
      onClick: () => onAction("RESET_RUNTIME")
    });

    btnStack.appendChild(returnBtn);
    btnStack.appendChild(resetBtn);
    card.appendChild(btnStack);
  }

  // 10. COMPLETED State
  else if (state === RUNTIME_STATES.COMPLETED) {
    const topRow = document.createElement("div");
    topRow.className = "card-top-row";

    const titleEl = document.createElement("span");
    titleEl.className = "state-primary-text";
    titleEl.style.fontSize = "15px";
    titleEl.textContent = runtimeConfig.skillName || "Order Food";

    const statusPill = document.createElement("span");
    statusPill.className = "status-indicator completed";
    statusPill.innerHTML = `<span class="status-dot"></span><span>✓ COMPLETED</span>`;

    topRow.appendChild(titleEl);
    topRow.appendChild(statusPill);
    card.appendChild(topRow);

    const successBox = document.createElement("div");
    successBox.className = "runtime-success-box";

    const successTitle = document.createElement("div");
    successTitle.className = "runtime-success-title";
    successTitle.innerHTML = `<span>✓</span><span>SUCCESS (5 / 5 steps executed)</span>`;

    const metricsRow = document.createElement("div");
    metricsRow.className = "runtime-metrics-row";
    metricsRow.innerHTML = `<span>5 steps executed</span><span>•</span><span>0 failures</span><span>•</span><span>12.4s</span>`;

    successBox.appendChild(successTitle);
    successBox.appendChild(metricsRow);
    card.appendChild(successBox);

    const btnStack = document.createElement("div");
    btnStack.className = "btn-row-stacked";

    const reExecuteBtn = PrimaryButton({
      label: "Execute Runtime Again",
      onClick: () => onAction("START_RUNTIME")
    });

    btnStack.appendChild(reExecuteBtn);
    card.appendChild(btnStack);

    const utilityRow = document.createElement("div");
    utilityRow.className = "btn-actions-compact";

    const lastRunBtn = document.createElement("button");
    lastRunBtn.type = "button";
    lastRunBtn.className = "btn-link-subtle";
    lastRunBtn.textContent = "Last Run";

    const resetBtn = document.createElement("button");
    resetBtn.type = "button";
    resetBtn.className = "btn-link-subtle";
    resetBtn.textContent = "Reset";
    resetBtn.addEventListener("click", () => onAction("RESET_RUNTIME"));

    utilityRow.appendChild(lastRunBtn);
    utilityRow.appendChild(resetBtn);
    card.appendChild(utilityRow);
  }

  container.appendChild(card);
  return container;
}
