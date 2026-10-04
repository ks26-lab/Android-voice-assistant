/**
 * StatusIndicator Component
 * Renders a small system-status indicator pill with colored dot and text
 */
export function StatusIndicator({ status = "READY", variant = "ready" }) {
  const container = document.createElement("div");
  container.className = `status-indicator ${variant}`;
  
  const dot = document.createElement("span");
  dot.className = "status-dot";
  
  const text = document.createElement("span");
  text.textContent = status;
  
  container.appendChild(dot);
  container.appendChild(text);
  return container;
}
