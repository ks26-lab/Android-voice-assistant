import { SectionHeader } from "./SectionHeader.js";

/**
 * LearningPipeline Component
 * Visual step pipeline displaying: Capture → Trace → Normalize → Extract → Validate → Store
 * States supported per stage: 'inactive' | 'active' | 'done'
 */
export function LearningPipeline({ steps = [] }) {
  const container = document.createElement("section");
  container.className = "pipeline-section";

  const header = SectionHeader({ title: "Learning pipeline" });
  container.appendChild(header);

  const pipelineBox = document.createElement("div");
  pipelineBox.className = "pipeline-container";

  steps.forEach((step, index) => {
    const stepEl = document.createElement("div");
    const isDone = step.status === "done";
    const isActive = step.status === "active";
    
    stepEl.className = `pipeline-step ${isDone ? "is-done" : isActive ? "is-active" : "is-inactive"}`;

    const nodeEl = document.createElement("div");
    nodeEl.className = `pipeline-node ${step.status || "inactive"}`;
    nodeEl.textContent = isDone ? "✓" : `${index + 1}`;

    const labelEl = document.createElement("span");
    labelEl.className = "pipeline-label";
    labelEl.textContent = step.label;

    stepEl.appendChild(nodeEl);
    stepEl.appendChild(labelEl);
    pipelineBox.appendChild(stepEl);

    // Add arrow divider between steps if not last
    if (index < steps.length - 1) {
      const arrowEl = document.createElement("div");
      arrowEl.className = "pipeline-arrow";
      arrowEl.textContent = "›";
      pipelineBox.appendChild(arrowEl);
    }
  });

  container.appendChild(pipelineBox);

  return container;
}
