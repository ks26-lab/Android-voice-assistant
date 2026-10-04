import { SectionHeader } from "./SectionHeader.js";

/**
 * ValidationSummaryCard Component
 * Displays compact validation checklist
 */
export function ValidationSummaryCard({ items = [] }) {
  if (!items || items.length === 0) return null;

  const container = document.createElement("section");
  container.className = "validation-section";

  const header = SectionHeader({ title: "Validation", badge: "Checks Complete" });
  container.appendChild(header);

  const card = document.createElement("div");
  card.className = "card validation-checklist";

  items.forEach((item) => {
    const row = document.createElement("div");
    row.className = "validation-item";

    const checkIcon = document.createElement("span");
    checkIcon.className = "validation-check-icon";
    checkIcon.textContent = "✓";

    const label = document.createElement("span");
    label.textContent = item.label;

    row.appendChild(checkIcon);
    row.appendChild(label);
    card.appendChild(row);
  });

  container.appendChild(card);
  return container;
}
