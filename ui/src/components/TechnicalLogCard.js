import { SectionHeader } from "./SectionHeader.js";

/**
 * TechnicalLogCard Component
 * Implements clean two-level information disclosure:
 * 1. Collapsed State (Default): Compact single-line summary with latest event & [ Show details ▾ ]
 * 2. Expanded State: Full monospace event inspection stream with categories, timestamps & auto-scroll
 */
export function TechnicalLogCard({ events = [], isCollapsed = true, onToggleCollapse, onClear }) {
  const container = document.createElement("section");
  container.className = "technical-log-section";

  const header = SectionHeader({ title: "Technical Log" });
  container.appendChild(header);

  const card = document.createElement("div");
  card.className = "log-card";

  const latestEvent = events && events.length > 0 ? events[events.length - 1] : null;

  // 1. COLLAPSED VIEW
  if (isCollapsed) {
    const previewRow = document.createElement("div");
    previewRow.className = "log-collapsed-preview";

    const textGroup = document.createElement("div");
    textGroup.className = "log-preview-text";

    const label = document.createElement("span");
    label.className = "log-preview-label";
    label.textContent = "Latest event:";

    const eventName = document.createElement("span");
    eventName.className = "log-preview-event";
    eventName.textContent = latestEvent?.event || "SYSTEM_READY";

    textGroup.appendChild(label);
    textGroup.appendChild(eventName);

    const toggleBtn = document.createElement("button");
    toggleBtn.type = "button";
    toggleBtn.className = "log-toggle-btn";
    toggleBtn.textContent = "[ Show details ]";
    if (onToggleCollapse) {
      toggleBtn.addEventListener("click", onToggleCollapse);
    }

    previewRow.appendChild(textGroup);
    previewRow.appendChild(toggleBtn);
    card.appendChild(previewRow);
  }

  // 2. EXPANDED VIEW
  else {
    const headerRow = document.createElement("div");
    headerRow.className = "log-header-row";

    const left = document.createElement("div");
    left.className = "log-header-left";

    const title = document.createElement("span");
    title.className = "log-title";
    title.textContent = "TECHNICAL LOG";

    const countBadge = document.createElement("span");
    countBadge.className = "log-count-badge";
    countBadge.textContent = `${events.length} events`;

    left.appendChild(title);
    left.appendChild(countBadge);

    const actions = document.createElement("div");
    actions.className = "log-header-actions";

    if (onClear) {
      const clearBtn = document.createElement("button");
      clearBtn.type = "button";
      clearBtn.className = "log-action-btn";
      clearBtn.textContent = "Clear";
      clearBtn.addEventListener("click", onClear);
      actions.appendChild(clearBtn);
    }

    const hideBtn = document.createElement("button");
    hideBtn.type = "button";
    hideBtn.className = "log-toggle-btn";
    hideBtn.textContent = "[ Hide details ]";
    if (onToggleCollapse) {
      hideBtn.addEventListener("click", onToggleCollapse);
    }
    actions.appendChild(hideBtn);

    headerRow.appendChild(left);
    headerRow.appendChild(actions);
    card.appendChild(headerRow);

    // Event Stream
    const streamContainer = document.createElement("div");
    streamContainer.className = "log-content-stream";

    events.forEach((item, index) => {
      const isLatest = index === events.length - 1;
      const row = document.createElement("div");
      row.className = `log-entry-row ${isLatest ? "latest" : ""}`;

      const timeEl = document.createElement("span");
      timeEl.className = "log-time";
      timeEl.textContent = item.time || "12:28:41";

      const catEl = document.createElement("span");
      const catClass = (item.category || "STATE").toLowerCase();
      catEl.className = `log-category-pill ${catClass}`;
      catEl.textContent = item.category || "STATE";

      const bodyEl = document.createElement("div");
      bodyEl.className = "log-event-body";

      const nameRow = document.createElement("div");
      nameRow.style.display = "flex";
      nameRow.style.alignItems = "center";
      nameRow.style.gap = "4px";

      const nameEl = document.createElement("span");
      nameEl.className = "log-event-name";
      nameEl.textContent = item.event || item;
      nameRow.appendChild(nameEl);

      if (isLatest) {
        const latestTag = document.createElement("span");
        latestTag.className = "log-latest-tag";
        latestTag.textContent = "LATEST";
        nameRow.appendChild(latestTag);
      }

      bodyEl.appendChild(nameRow);

      if (item.detail) {
        const detailEl = document.createElement("span");
        detailEl.className = "log-event-detail";
        detailEl.textContent = item.detail;
        bodyEl.appendChild(detailEl);
      }

      row.appendChild(timeEl);
      row.appendChild(catEl);
      row.appendChild(bodyEl);
      streamContainer.appendChild(row);
    });

    card.appendChild(streamContainer);

    // Auto-scroll to newest event
    setTimeout(() => {
      if (streamContainer) {
        streamContainer.scrollTop = streamContainer.scrollHeight;
      }
    }, 20);
  }

  container.appendChild(card);
  return container;
}
