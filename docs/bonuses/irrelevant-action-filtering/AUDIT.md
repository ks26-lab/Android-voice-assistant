# AUDIT DOCUMENT: IRRELEVANT-ACTION FILTERING
**Samsung PRISM Theme 3 — Bonus Feature 1 (+3 Points)**

---

## 1. EXISTING TEACHING PIPELINE

The existing teaching pipeline captures user demonstrations during interactive voice and UI sessions:

```text
[ USER DEMONSTRATION ]
          │
          ├─► VoiceUtterance (VoiceCaptureController.kt)
          ├─► AccessibilityEvent (TeachableVoiceAccessibilityService.kt)
          └─► Window State (UiNodeNormalizer.kt)
          │
          ▼
[ TeachingSessionManager / TeachingSessionImpl ]
          │
          ▼
[ DemonstrationTrace (v1.0 Shared Contract) ]
          │
          ├── traceEvents: List<TraceEvent> (Voice, Action, State, Ui)
          ├── userActions: List<ActionEvent>
          ├── voiceEvents: List<VoiceEvent>
          ├── stateEvents: List<StateEvent>
          └── uiStates: List<UiState>
```

---

## 2. EXISTING FILTERING BEHAVIOUR

Currently, raw traces pass through:
1. `DemonstrationTraceNormalizer.kt`: Deduplicates rapid UI content updates and normalizes timestamps.
2. `DemonstrationFilter.kt`: Classifies events into `TASK_RELEVANT`, `NAVIGATION_CONTEXT`, `SYSTEM_NOISE`, and `UNCERTAIN`. It filters out own-app interactions (`com.chockXlate.teachablevoice`), launcher surfaces, and system windows.

---

## 3. EXISTING DEMONSTRATIONFILTER BEHAVIOUR

`DemonstrationFilter.kt` uses basic package matching and element identity checks:
- Re-attributes keyboard IME inputs to the primary target application.
- Filters assistant own-app events (`SYSTEM_NOISE`).
- Identifies launcher/recents transitions (`NAVIGATION_CONTEXT`).
- Marks anonymous containers without text or resource ID as `UNCERTAIN`.

---

## 4. EXISTING NORMALIZATION BEHAVIOUR

`DemonstrationTraceNormalizer.kt` performs syntactic cleaning:
- Merges consecutive text edit changes for the same element into a single input event.
- Removes duplicate state events with identical before/after fingerprint IDs.

---

## 5. EXISTING INTEGRATION POINTS

The existing learning pipeline consumes traces as follows:
- `SemanticActionExtractor.kt` receives `DemonstrationTrace` and `FilteredDemonstrationResult`.
- `IntentExtractor.kt`, `SlotExtractor.kt`, and `WorkflowSynthesizer.kt` consume the resulting semantic actions and normalized trace events.

---

## 6. WHY A SEPARATE BONUS LAYER CAN BE ADDED SAFELY

The existing architecture operates on immutable data contracts (`DemonstrationTrace`, `FilteredDemonstrationResult`). By implementing the **Bonus Irrelevant-Action Filtering System** as an isolated additive module in package `com.chockXlate.teachablevoice.teach.bonus.irrelevant`, we can:
1. Wrap raw or normalized `DemonstrationTrace` objects into `BonusFilteredDemonstration`.
2. Process events without modifying any existing production classes or schemas.
3. Allow the existing production pipeline to remain 100% untouched while providing an API (`BonusTeachingFilter.filter()`) for isolated validation and future opt-in integration.

---

## 7. FILES THAT MUST NOT BE MODIFIED

The following existing production and test files are **FROZEN** and MUST NOT be edited, refactored, renamed, or deleted:

- `app/src/main/java/com/chockXlate/teachablevoice/teach/capture/TeachingSessionManager.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/teach/capture/TeachingSessionImpl.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/teach/normalization/DemonstrationTraceNormalizer.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/teach/filter/DemonstrationFilter.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/learning/actions/SemanticActionExtractor.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/learning/intent/IntentExtractor.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/learning/slots/SlotExtractor.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/learning/synthesis/WorkflowSynthesizer.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/contract/trace/DemonstrationTrace.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/contract/trace/TraceEvent.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/contract/filter/DemonstrationFilterClassification.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/contract/workflow/Workflow.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/runtime/ExecutionEngine.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/safety/SafetyGate.kt`
