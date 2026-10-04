# DESIGN DOCUMENT: MID-FLOW CLARIFICATION
**Samsung PRISM Theme 3 — Bonus Feature 3 (+3 Points)**

---

## 1. ARCHITECTURAL OVERVIEW

The Bonus Mid-Flow Clarification System provides a deterministic state machine enabling the assistant to pause execution mid-flow when a required slot value is missing, prompt the user for clarification, validate the user's response, and resume execution from the exact point of pause without restarting the workflow.

```text
                  [ Workflow Execution Started ]
                                │
                                ▼
                       [ Slot Value Check ]
                                │
                     ┌──────────┴──────────┐
                     │ Slot Missing        │ All Slots Present
                     ▼                     ▼
          [ PAUSE EXECUTION ]      [ RUNNING State ]
       (Capture ResumePoint)
                 │
                 ▼
    [ WAITING_FOR_CLARIFICATION ]
                 │ (User Response Input)
                 ▼
     [ VALIDATING_RESPONSE ]
                 │
    ┌────────────┼────────────┬────────────────┐
    │ Valid      │ Invalid    │ Cancel ("stop")│ Sensitive Credential
    ▼            ▼            ▼                ▼
[ RESUMING ] [ RE-PROMPT ] [ CANCELLED ]   [ BLOCKED_SAFETY ]
    │
    ▼
[ Mandatory UI Re-observation & Step Resume ]
```

---

## 2. STATE MACHINE LIFECYCLE

- **`RUNNING`**: Execution proceeding normally.
- **`WAITING_FOR_CLARIFICATION`**: Paused mid-flow at `ClarificationResumePoint`. User prompted for specific missing slot.
- **`VALIDATING_RESPONSE`**: Evaluating response against `SlotType` and safety policies.
- **`RESUMING`**: Slot bound. Execution resuming from saved step index.
- **`CANCELLED`**: User said "cancel" / "stop". Execution terminated cleanly.
- **`BLOCKED_SAFETY`**: User provided credential/OTP/PIN. Safety boundary triggered.

---

## 3. RESUME POINT PRESERVATION

`ClarificationResumePoint` records:
- `workflowId`: Active workflow identifier.
- `pausedStepIndex`: Exact zero-indexed step where execution paused.
- `pausedStepId`: Logical step ID paused at.
- `completedStepIds`: List of already completed steps (preventing re-execution).
- `boundSlots`: Map of resolved slot values.

---

## 4. SAFETY & BOUNDARY PROTECTION

1. **Credential & OTP Protection**: If user response contains password, PIN, OTP, CVV, or SSN patterns, `ClarificationValidator` returns `isSafetyBlocked = true`, setting state to `BLOCKED_SAFETY`.
2. **Mandatory UI Re-observation**: `requiresUiReobservation = true` is enforced upon resumption to guarantee UI stability before resuming interactions.
