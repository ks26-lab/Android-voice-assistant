/**
 * SecondaryButton Component
 * For secondary or inactive actions
 */
export function SecondaryButton({ label, disabled = false, onClick, icon = "" }) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "btn btn-secondary";
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
