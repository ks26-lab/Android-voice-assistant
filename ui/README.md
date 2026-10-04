# Teachable Voice Automation — Standalone UI (Phases 1–7 Complete)

This directory contains the completely isolated, standalone UI foundation, interactive learning pipeline, rich learned skill representation, runtime execution lifecycle, safety handoff scenarios, Technical Log & Debug Event Inspection, and responsive validation for **Teachable Voice Automation: One-Shot Semantic Workflow Learning**.

## Architecture & Isolation Rules

- **Zero Integration**: Decoupled from existing Android backend, AccessibilityService, runtime, execution engine, learning engine, safety engine, and skill store.
- **Local Presentation State**: Driven by `src/mock/dashboardMock.js`, `src/mock/technicalLogMock.js`, and local UI event state transitions.
- **Visual Source of Truth**: Faithfully reflects the Figma reference design (dark charcoal theme `#0A0C10`, restrained purple accent `#7C5CFF`, compact Android viewport 360–412px, clean typography, monospace technical logs).
- **Responsive Validation**: Verified for 360px, 390px, and 412px Android portrait screens with no horizontal overflow, responsive buttons, and fluid typography.

---

## Technical Log & Debug Inspection (Phase 6)

### Two-Level Information Architecture
1. **User Level**: Clean, uncluttered dashboard showing high-level state, action progress, and outcome results.
2. **Technical Level**: Monospace event inspector with timestamps, event categories, and sub-event details, accessible via `[ Show details ▾ ]`.

### Supported Event Categories (14 Categories)
- `STATE`, `SYSTEM` (Neutral / subtle styling)
- `TEACH`, `TRACE`, `NORMALIZE`, `LEARN`, `ACTION`, `RECOVERY` (Purple / active accent)
- `VALIDATE`, `SKILL`, `VERIFY`, `RUNTIME` (Emerald / green accent)
- `WAITING` (Amber accent)
- `SAFETY` (Amber / warning accent)
- `ERROR` (Red accent)

### Collapsed vs. Expanded View
- **Collapsed View (Default)**: Single-line minimal summary showing `Latest event: SYSTEM_READY` with `[ Show details ]`. Keeps the mobile viewport compact.
- **Expanded View**: Inline scrollable console with event count badge (`X events`), `[ Clear ]` button, `[ Hide details ]`, `LATEST` event row tag, and auto-scroll to the newest event.

---

## Supported Interactive UI States (Phases 1–6)

1. **READY**: Initial state. `Start Teaching` is active; `Stop Teaching` is disabled. Technical log shows initial system ready events.
2. **TEACHING**: `Recording demonstration` state with active `Stop Teaching` button.
3. **TRACE_CAPTURED**: Demonstration buffer captured. Shows `Normalize Trace` action.
4. **NORMALIZING**: Simulated UI transition showing trace normalization.
5. **NORMALIZED**: Trace normalized. Shows `Extract Semantic Actions` action.
6. **EXTRACTING**: Displays detected semantic actions list (`SEARCH`, `SELECT`, `SET_VALUE`, `SELECT`, `CONFIRM`).
7. **VALIDATING**: Displays validation summary checks (4/4 passed).
8. **SKILL_STORED / RUNTIME READY**:
   - Populated Learned Skill card: **Order Food** with `VALIDATED` badge, `order_food` intent, parameter slot pills, and `5 semantic actions` (`↗ Inspect Skill` toggle).
   - **Runtime Card**: Updates to **Order Food** / `Ready to execute` with active `[ Execute Runtime ]` button and scenario selector (`Normal`, `Safety Stop`, `Clarify`, `Unresolved`).
9. **RUNTIME EXECUTION (SUCCESS)**:
   - Steps 1 → 5 (`SEARCH` → `SELECT` → `SET_VALUE` → `SELECT` → `CONFIRM`).
   - Outcome: `✓ COMPLETED` (12.4s). Last Run: `✓ SUCCESS`.
10. **SAFETY HANDOFF**:
    - Status: `⚠ USER HANDOFF` (Stopped at `Payment` • `Sensitive action requires user confirmation`).
    - Last Run: `⚠ HANDOFF` (3 / 5 steps).
11. **CLARIFICATION NEEDED & RECOVERY**:
    - Ambiguity options `[ Pizza Palace ]` and `[ Domino's ]`.
    - Selecting an option triggers `RECOVERING` followed by resuming to `COMPLETED`.
12. **TARGET NOT RESOLVED**:
    - Status: `⚠ TARGET NOT RESOLVED` (`Automation paused. User input required.`).

---

## Directory Structure

```
ui/
├── public/
│   └── favicon.svg
├── src/
│   ├── components/
│   │   ├── AppHeader.js
│   │   ├── CommandInput.js
│   │   ├── CurrentStateCard.js
│   │   ├── LastRunCard.js
│   │   ├── LearningPipeline.js
│   │   ├── PrimaryButton.js
│   │   ├── RuntimeCard.js
│   │   ├── SecondaryButton.js
│   │   ├── SectionHeader.js
│   │   ├── SemanticActionsPreview.js
│   │   ├── SkillCard.js
│   │   ├── StatusIndicator.js
│   │   ├── TeachSection.js
│   │   ├── TechnicalLogCard.js
│   │   └── VoiceCommandCard.js
│   ├── mock/
│   │   ├── dashboardMock.js
│   │   └── technicalLogMock.js
│   ├── styles/
│   │   ├── global.css
│   │   ├── layout.css
│   │   └── tokens.css
│   ├── App.js
│   └── main.js
├── index.html
├── package.json
├── vite.config.js
└── README.md
```

---

## Acceptance Test Walk-Through

### Test 1: Collapsed & Expanded Toggle
1. Open `index.html` in browser -> Technical Log shows single-line collapsed preview: `Latest: SYSTEM_READY` `[ Show details ▾ ]`.
2. Click **`[ Show details ▾ ]`** -> Expands inline to show timestamps (`12:28:41`), category tags (`SYSTEM`, `STATE`, `WAITING`), and event details.
3. Click **`[ Hide details ▴ ]`** -> Seamlessly collapses back without full-page shift.

### Test 2: Teaching Log Stream
1. Run through **Start Teaching** → **Stop Teaching** → **Normalize** → **Extract** → **Validate** → **Store**.
2. Open Technical Log: Verify events stream from `TEACHING_STARTED` to `SKILL_STORED` with the latest event highlighted.

### Test 3: Runtime & Scenario Log Streams
1. Select **Safety Stop** scenario and execute runtime:
   - View structured logs: `SENSITIVE_STATE_DETECTED`, `USER_HANDOFF_REQUIRED`, `AUTOMATION_STOPPED`.
2. Select **Clarify** scenario and execute runtime:
   - View structured logs: `TARGET_NOT_UNIQUE`, `CLARIFICATION_REQUIRED`, `CLARIFICATION_RECEIVED`, `TARGET_RESOLVED`, `RUNTIME_RESUMED`.
3. Click **`[ Clear ]`** in expanded log to test buffer clearing.
