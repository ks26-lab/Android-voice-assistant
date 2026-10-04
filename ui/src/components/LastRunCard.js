import { SectionHeader } from "./SectionHeader.js";

/**
 * LastRunCard Component
 * Displays execution history summary:
 * - Empty state (No previous run)
 * - Populated SUCCESS state
 * - Populated HANDOFF state
 * - Populated TARGET_NOT_RESOLVED state
 */
export function LastRunCard({ lastRunData }) {
  const container = document.createElement("section");
  container.className = "last-run-section";

  const header = SectionHeader({ title: "Last Run" });
  container.appendChild(header);

  const card = document.createElement("div");
  card.className = "card";

  if (!lastRunData || !lastRunData.hasHistory) {
    const titleEl = document.createElement("div");
    titleEl.className = "empty-state-text";
    titleEl.textContent = lastRunData?.emptyTitle || "No previous run";

    const descEl = document.createElement("div");
    descEl.className = "empty-state-subtext";
    descEl.textContent = lastRunData?.emptyDescription || "Run history will appear here.";

    card.appendChild(titleEl);
    card.appendChild(descEl);
  } else {
    card.classList.add("last-run-populated");

    const headerRow = document.createElement("div");
    headerRow.className = "last-run-header";

    const title = document.createElement("span");
    title.className = "last-run-title";
    title.textContent = lastRunData.skillName || "Order Food";

    const badge = document.createElement("span");
    const isHandoff = lastRunData.outcome === "HANDOFF" || lastRunData.status === "HANDOFF";
    const isError = lastRunData.outcome === "TARGET_NOT_RESOLVED";

    badge.className = `last-run-badge ${isHandoff ? "handoff" : isError ? "handoff" : "success"}`;
    badge.innerHTML = isHandoff ? `<span>⚠ HANDOFF</span>` : isError ? `<span>⚠ UNRESOLVED</span>` : `<span>✓ SUCCESS</span>`;

    headerRow.appendChild(title);
    headerRow.appendChild(badge);
    card.appendChild(headerRow);

    const metrics = document.createElement("div");
    metrics.className = "last-run-metrics";

    if (isHandoff) {
      metrics.innerHTML = `
        <span>Stopped at: ${lastRunData.stoppedAt || "Payment"}</span>
        <span class="last-run-dot-divider">•</span>
        <span>${lastRunData.completedSteps || 3} / ${lastRunData.totalSteps || 5} steps</span>
      `;
    } else if (isError) {
      metrics.innerHTML = `
        <span>Target: ${lastRunData.target || "Restaurant"}</span>
        <span class="last-run-dot-divider">•</span>
        <span>Unresolved</span>
      `;
    } else {
      metrics.innerHTML = `
        <span>${lastRunData.stepsCount || 5} steps executed</span>
        <span class="last-run-dot-divider">•</span>
        <span>${lastRunData.failuresCount || 0} failures</span>
        <span class="last-run-dot-divider">•</span>
        <span>${lastRunData.duration || "12.4 s"}</span>
      `;
    }

    card.appendChild(metrics);
  }

  container.appendChild(card);
  return container;
}
