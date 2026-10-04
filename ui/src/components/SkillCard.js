import { SectionHeader } from "./SectionHeader.js";

/**
 * SkillCard Component
 * Implements the full lifecycle of the Learned Skill card:
 * 1. Empty State (No learned skill yet)
 * 2. Building / Validating State (Processing)
 * 3. Populated Validated State with expandable Inspect Skill details
 */
export function SkillCard({ skillData, isExpanded = false, onToggleExpand }) {
  const container = document.createElement("section");
  container.className = "learned-skill-section";

  const header = SectionHeader({ title: "Learned Skill" });
  container.appendChild(header);

  const card = document.createElement("div");
  card.className = "card";

  // State 1: Empty
  if (!skillData || skillData.state === "EMPTY" || !skillData.hasSkill) {
    if (skillData && (skillData.state === "BUILDING" || skillData.state === "VALIDATING")) {
      // State 2: Building / Processing
      card.classList.add("skill-building-card");

      const titleEl = document.createElement("div");
      titleEl.className = "skill-building-title";

      const pulse = document.createElement("span");
      pulse.className = "skill-building-pulse";

      const text = document.createElement("span");
      text.textContent = skillData.buildingTitle || "Building skill...";

      titleEl.appendChild(pulse);
      titleEl.appendChild(text);

      const descEl = document.createElement("div");
      descEl.className = "empty-state-subtext";
      descEl.textContent = skillData.buildingDescription || "Semantic structure being prepared.";

      card.appendChild(titleEl);
      card.appendChild(descEl);
    } else {
      // Standard Empty State
      const titleEl = document.createElement("div");
      titleEl.className = "empty-state-text";
      titleEl.textContent = skillData?.emptyTitle || "No learned skill yet";

      const descEl = document.createElement("div");
      descEl.className = "empty-state-subtext";
      descEl.textContent = skillData?.emptyDescription || "Teach a workflow once to create a reusable skill.";

      card.appendChild(titleEl);
      card.appendChild(descEl);
    }
  } else {
    // State 3: Populated / Validated / Stored
    card.classList.add("skill-populated-card");

    // Header Row: Title & Validated Badge
    const headerRow = document.createElement("div");
    headerRow.className = "skill-header-row";

    const titleWrap = document.createElement("div");
    titleWrap.className = "skill-title-wrap";

    const title = document.createElement("h3");
    title.className = "skill-title";
    title.textContent = skillData.name || "Order Food";

    if (skillData.technicalId) {
      const techId = document.createElement("span");
      techId.className = "skill-technical-id";
      techId.textContent = skillData.technicalId;
      titleWrap.appendChild(title);
      titleWrap.appendChild(techId);
    } else {
      titleWrap.appendChild(title);
    }

    const badge = document.createElement("span");
    badge.className = "skill-validated-badge";
    badge.textContent = skillData.status || "VALIDATED";

    headerRow.appendChild(titleWrap);
    headerRow.appendChild(badge);
    card.appendChild(headerRow);

    // Intent Section
    const intentGroup = document.createElement("div");
    intentGroup.className = "skill-meta-group";

    const intentLabel = document.createElement("span");
    intentLabel.className = "skill-meta-label";
    intentLabel.textContent = "Intent";

    const intentValue = document.createElement("span");
    intentValue.className = "skill-intent-value";
    intentValue.textContent = skillData.intent || "order_food";

    intentGroup.appendChild(intentLabel);
    intentGroup.appendChild(intentValue);
    card.appendChild(intentGroup);

    // Slots Section
    if (skillData.slots && skillData.slots.length > 0) {
      const slotsGroup = document.createElement("div");
      slotsGroup.className = "skill-meta-group";

      const slotsLabel = document.createElement("span");
      slotsLabel.className = "skill-meta-label";
      slotsLabel.textContent = "Slots";

      const slotsWrap = document.createElement("div");
      slotsWrap.className = "skill-slots-wrap";

      skillData.slots.forEach((slot) => {
        const slotName = typeof slot === "string" ? slot : slot.name;
        const slotChip = document.createElement("span");
        slotChip.className = "skill-slot-chip";
        slotChip.textContent = `[ ${slotName} ]`;
        slotsWrap.appendChild(slotChip);
      });

      slotsGroup.appendChild(slotsLabel);
      slotsGroup.appendChild(slotsWrap);
      card.appendChild(slotsGroup);
    }

    // Footer Row: Action Summary & Inspect Toggle
    const footerRow = document.createElement("div");
    footerRow.className = "skill-footer-row";

    const actionsSummary = document.createElement("div");
    actionsSummary.className = "skill-actions-summary";
    actionsSummary.textContent = skillData.actionsLabel || `${skillData.semanticActionCount || 5} semantic actions`;

    const inspectBtn = document.createElement("button");
    inspectBtn.type = "button";
    inspectBtn.className = "skill-inspect-toggle";
    inspectBtn.innerHTML = isExpanded ? `Hide Details ▴` : `↗ Inspect Skill ▾`;
    if (onToggleExpand) {
      inspectBtn.addEventListener("click", onToggleExpand);
    }

    footerRow.appendChild(actionsSummary);
    footerRow.appendChild(inspectBtn);
    card.appendChild(footerRow);

    // Expanded Details View
    if (isExpanded) {
      const expandedDetails = document.createElement("div");
      expandedDetails.className = "skill-expanded-details";

      // Slot parameter example mapping
      if (Array.isArray(skillData.slots) && skillData.slots[0]?.example) {
        const examplesGroup = document.createElement("div");
        examplesGroup.className = "skill-meta-group";

        const examplesLabel = document.createElement("span");
        examplesLabel.className = "skill-meta-label";
        examplesLabel.textContent = "Parameter Examples";

        const exampleList = document.createElement("div");
        exampleList.className = "slot-example-list";

        skillData.slots.forEach((s) => {
          const item = document.createElement("div");
          item.className = "slot-example-item";

          const sName = document.createElement("span");
          sName.className = "slot-example-name";
          sName.textContent = `${s.name}`;

          const sVal = document.createElement("span");
          sVal.className = "slot-example-val";
          sVal.textContent = `→ ${s.example}`;

          item.appendChild(sName);
          item.appendChild(sVal);
          exampleList.appendChild(item);
        });

        examplesGroup.appendChild(examplesLabel);
        examplesGroup.appendChild(exampleList);
        expandedDetails.appendChild(examplesGroup);
      }

      // Semantic Action Steps
      if (skillData.semanticActions && skillData.semanticActions.length > 0) {
        const actionsGroup = document.createElement("div");
        actionsGroup.className = "skill-meta-group";

        const actionsLabel = document.createElement("span");
        actionsLabel.className = "skill-meta-label";
        actionsLabel.textContent = "Semantic Sequence";

        const actionsList = document.createElement("div");
        actionsList.className = "semantic-actions-card";

        skillData.semanticActions.forEach((act) => {
          const item = document.createElement("div");
          item.className = "action-step-item";

          const left = document.createElement("div");
          left.style.display = "flex";
          left.style.alignItems = "center";
          left.style.gap = "8px";

          const stepNumber = document.createElement("span");
          stepNumber.style.color = "var(--color-text-muted)";
          stepNumber.style.fontSize = "11px";
          stepNumber.textContent = `${act.step}.`;

          const tag = document.createElement("span");
          tag.className = "action-type-tag";
          tag.textContent = act.type;

          const target = document.createElement("span");
          target.className = "action-target-label";
          target.textContent = act.target;

          left.appendChild(stepNumber);
          left.appendChild(tag);
          left.appendChild(target);
          item.appendChild(left);

          if (act.value) {
            const val = document.createElement("span");
            val.className = "action-value-tag";
            val.textContent = act.value;
            item.appendChild(val);
          }

          actionsList.appendChild(item);
        });

        actionsGroup.appendChild(actionsLabel);
        actionsGroup.appendChild(actionsList);
        expandedDetails.appendChild(actionsGroup);
      }

      card.appendChild(expandedDetails);
    }
  }

  container.appendChild(card);
  return container;
}
