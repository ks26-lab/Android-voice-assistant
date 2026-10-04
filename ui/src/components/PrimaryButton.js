/**
 * PrimaryButton Component
 * Highlights the primary user action (Restrained Purple Accent)
 */
export function PrimaryButton({ label, disabled = false, onClick, icon = "" }) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "btn btn-primary";
  button.disabled = disabled;
  
  if (icon) {
    const iconSpan = document.createElement("span");
    iconSpan.innerHTML = icon;
    button.appendChild(iconSpan);
  }

  const labelSpan = document.createElement("span");
  labelSpan.textContent = label;
  button.appendChild(labelSpan);

  if (onClick && !disabled) {
    button.addEventListener("click", onClick);
  }

  return button;
}
