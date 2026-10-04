import { SectionHeader } from "./SectionHeader.js";

/**
 * SemanticActionsPreview Component
 * Displays compact detected semantic actions (SEARCH, SELECT, SET_VALUE, CONFIRM)
 */
export function SemanticActionsPreview({ actions = [] }) {
  if (!actions || actions.length === 0) return null;

  const container = document.createElement("section");
  container.className = "semantic-actions-section";

  const header = SectionHeader({ title: "Semantic actions detected", badge: `${actions.length} steps` });
  container.appendChild(header);

  const card = document.createElement("div");
  card.className = "card semantic-actions-card";

  actions.forEach((act) => {
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

    card.appendChild(item);
  });

  container.appendChild(card);
  return container;
}
