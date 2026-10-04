import { StatusIndicator } from "./StatusIndicator.js";

/**
 * AppHeader Component
 * Compact top header with title, subtitle, and system status indicator
 */
export function AppHeader({ title, subtitle, status }) {
  const header = document.createElement("header");
  header.className = "app-header";

  const textGroup = document.createElement("div");
  textGroup.className = "header-text-group";

  const titleEl = document.createElement("h1");
  titleEl.className = "header-title";
  titleEl.textContent = title;

  const subtitleEl = document.createElement("p");
  subtitleEl.className = "header-subtitle";
  subtitleEl.textContent = subtitle;

  textGroup.appendChild(titleEl);
  textGroup.appendChild(subtitleEl);

  const statusEl = StatusIndicator({ status, variant: "ready" });

  header.appendChild(textGroup);
  header.appendChild(statusEl);

  return header;
}
