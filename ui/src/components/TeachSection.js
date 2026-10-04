import { SectionHeader } from "./SectionHeader.js";
import { PrimaryButton } from "./PrimaryButton.js";
import { SecondaryButton } from "./SecondaryButton.js";

/**
 * TeachSection Component
 * Dynamically renders the teaching action controls based on the current UI state
 */
export function TeachSection({ teachConfig, onAction }) {
  const container = document.createElement("section");
  container.className = "teach-section";

  const header = SectionHeader({ title: "Teach" });
  container.appendChild(header);

  const buttonRow = document.createElement("div");
  buttonRow.className = "btn-row";

  if (teachConfig.primaryAction) {
    let btn;
    if (teachConfig.primaryAction.variant === "destructive") {
      btn = document.createElement("button");
      btn.type = "button";
      btn.className = "btn btn-destructive";
      btn.textContent = teachConfig.primaryAction.label;
      btn.disabled = !!teachConfig.primaryAction.disabled;
      if (teachConfig.primaryAction.action) {
        btn.addEventListener("click", () => onAction(teachConfig.primaryAction.action));
      }
    } else {
      btn = PrimaryButton({
        label: teachConfig.primaryAction.label,
        disabled: !!teachConfig.primaryAction.disabled,
        onClick: teachConfig.primaryAction.action ? () => onAction(teachConfig.primaryAction.action) : null
      });
    }
    buttonRow.appendChild(btn);
  }

  if (teachConfig.secondaryAction) {
    const secBtn = SecondaryButton({
      label: teachConfig.secondaryAction.label,
      disabled: !!teachConfig.secondaryAction.disabled,
      onClick: teachConfig.secondaryAction.action ? () => onAction(teachConfig.secondaryAction.action) : null
    });
    buttonRow.appendChild(secBtn);
  }

  container.appendChild(buttonRow);
  return container;
}
