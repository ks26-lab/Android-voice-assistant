import { StatusIndicator } from "./StatusIndicator.js";

/**
 * CurrentStateCard Component
 * Displays the current high-level state of the voice assistant
 */
export function CurrentStateCard({ statusBadge, title, description }) {
  const card = document.createElement("section");
  card.className = "card current-state-card";

  const topRow = document.createElement("div");
  topRow.className = "card-top-row";

  const label = document.createElement("span");
  label.className = "card-label";
  label.textContent = "CURRENT STATE";

  const badge = StatusIndicator({ status: statusBadge, variant: "ready" });

  topRow.appendChild(label);
  topRow.appendChild(badge);

  const primaryText = document.createElement("div");
  primaryText.className = "state-primary-text";
  primaryText.textContent = title;

  const secondaryText = document.createElement("div");
  secondaryText.className = "state-secondary-text";
  secondaryText.textContent = description;

  card.appendChild(topRow);
  card.appendChild(primaryText);
  card.appendChild(secondaryText);

  return card;
}
