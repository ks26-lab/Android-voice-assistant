import { SectionHeader } from "./SectionHeader.js";
import { SecondaryButton } from "./SecondaryButton.js";

/**
 * VoiceCommandCard Component
 * Displays mock voice command controls: Record Type and Mic / Stop
 */
export function VoiceCommandCard({ controls = [] }) {
  const container = document.createElement("section");
  container.className = "voice-command-section";

  const header = SectionHeader({ title: "Voice Command" });
  container.appendChild(header);

  const card = document.createElement("div");
  card.className = "card";

  const btnRow = document.createElement("div");
  btnRow.className = "btn-row";

  const recordTypeBtn = SecondaryButton({
    label: controls[0]?.label || "Record Type",
    disabled: false
  });

  const micStopBtn = SecondaryButton({
    label: controls[1]?.label || "Mic / Stop",
    disabled: false
  });

  btnRow.appendChild(recordTypeBtn);
  btnRow.appendChild(micStopBtn);
  card.appendChild(btnRow);

  container.appendChild(card);

  return container;
}
