import { SectionHeader } from "./SectionHeader.js";

/**
 * CommandInput Component
 * Renders the command input section with a subtle microphone action
 */
export function CommandInput({ placeholder = "Search for headphones", value = "" }) {
  const container = document.createElement("section");
  container.className = "command-section";

  const header = SectionHeader({ title: "Command" });
  container.appendChild(header);

  const inputWrapper = document.createElement("div");
  inputWrapper.className = "command-input-wrapper";

  const input = document.createElement("input");
  input.type = "text";
  input.className = "command-input-field";
  input.placeholder = placeholder;
  input.value = value;
  input.readOnly = true; // Visual only for Phase 1

  const micBtn = document.createElement("button");
  micBtn.type = "button";
  micBtn.className = "command-mic-btn";
  micBtn.setAttribute("aria-label", "Microphone");
  micBtn.innerHTML = `
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
      <path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z"/>
      <path d="M19 10v2a7 7 0 0 1-14 0v-2"/>
      <line x1="12" x2="12" y1="19" y2="22"/>
    </svg>
  `;

  inputWrapper.appendChild(input);
  inputWrapper.appendChild(micBtn);

  container.appendChild(inputWrapper);

  return container;
}
