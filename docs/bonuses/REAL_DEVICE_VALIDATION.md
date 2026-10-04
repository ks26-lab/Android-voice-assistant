# REAL-DEVICE VALIDATION PLAN
**Samsung PRISM Theme 3 — On-Device Validation Guide**

---

## TEST A: IRRELEVANT ACTION FILTERING (BONUS 1)
1. **Start Live Session**: Launch assistant on Android hardware. Initiate voice teaching mode: *"Learn search item"*.
2. **Perform Workflow + Noise**:
   - Open target search screen.
   - Accidentally scroll up/down twice (`TraceEvent.Action` / `SCROLL` without state change).
   - Tap search input field (`TraceEvent.Action` / `INPUT_TEXT`).
   - Enter text.
   - Accidental focus elsewhere.
   - Select search result.
3. **Trace Observation**: Stop teaching session.
4. **Verification**: Inspect `BonusRuntimeCoordinator.getDiagnosticEvents()`. Confirm `Bonus1_Filtering` log shows `originalEventCount`, `retainedEventCount`, and `removedEventCount`. Verify filtered events are strictly noise events while meaningful steps remain intact.

---

## TEST B: CROSS-APP GENERALIZATION (BONUS 2)
1. **Source Teaching**: Teach a workflow *"Search item"* in Application A (e.g. `com.app.alpha`).
2. **Target Application Execution**: Open Application B (e.g. `com.app.beta`) presenting a similar search field.
3. **Live Accessibility Extraction**: Pass live `UiObservation` hierarchy to `BonusRuntimeCoordinator.evaluateCrossAppGeneralization()`.
4. **Verification**: Confirm `TransferabilityDecision.TRANSFERABLE` is generated based on semantic role and text token overlap without hardcoded app rules. Confirm `executionAuthorized = false` (no un-gated action dispatch).

---

## TEST C: MID-FLOW CLARIFICATION (BONUS 3)
1. **Start Execution**: Trigger workflow *"Search item"* without supplying `item_query` parameter.
2. **Pause Verification**: Confirm assistant pauses execution at step index 1 (`WAITING_FOR_CLARIFICATION`). Confirm speech prompt *"What item query should I use?"* is spoken.
3. **Live User Voice Input**: User speaks *"wireless headphones"*.
4. **Validation & Resume**: Confirm input is validated, bound to `item_query`, and execution resumes from step index 1 without restarting from step 0. Confirm UI is re-observed before continuing.
