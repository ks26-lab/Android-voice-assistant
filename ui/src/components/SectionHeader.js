/**
 * SectionHeader Component
 * Renders consistent uppercase section titles
 */
export function SectionHeader({ title, badge = "" }) {
  const container = document.createElement("div");
  container.className = "section-header";

  const titleEl = document.createElement("h2");
  titleEl.className = "section-title";
  titleEl.textContent = title;

  container.appendChild(titleEl);

  if (badge) {
    const badgeEl = document.createElement("span");
    badgeEl.className = "section-badge";
    badgeEl.textContent = badge;
    container.appendChild(badgeEl);
  }

  return container;
}
