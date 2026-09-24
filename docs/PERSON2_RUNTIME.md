# Person 2 runtime

Person 2 begins at `ExecutionRequest`. Teaching, learning, matching, request construction,
repository implementation, and frozen contracts remain owned by Person 1. No new dependency
or Gradle configuration is required.

## Integration

Production `RuntimeSession` owns one process-scoped engine using the **same** repository from
`SkillRepositoryProvider` that teaching populates. `TeachingDemoActivity` builds a fresh request
from the displayed command, allows five seconds to switch to the target app, and displays the
result through `TraceViewer`. The handoff latch and last report survive Activity recreation.

The minimal embedding API remains:

```kotlin
val engine = ExecutionEngine(sharedSkillRepository, AccessibilityUiDriver())

// Called from an existing suspend-capable application scope:
val report = engine.execute(executionRequest)
showResult(report.result, report.stoppedStepId, report.diagnostics)

// Stop control may call from any thread:
engine.cancel()

// Only an EXPLICIT user action after manual intervention may open a new run:
if (engine.acknowledgeHandoffForNewExecution()) {
    // Submit a new ExecutionRequest through the normal entry point.
    // No stopped step is resumed automatically.
}
```

The API uses Kotlin's standard suspend primitives. The Android adapter marshals node access
to the main looper and waits using `Handler.postDelayed`, without blocking sleeps or a new
coroutine-library dependency. The app exposes explicit cancellation and reset; it does not silently cancel or reset when the
Activity moves to the background to let the user switch to the target app. There is no automatic
process-death resume. The production request preview is clearly marked as non-executing.

Runtime observations bypass the teaching-only event callback. Runtime refuses execution while
teaching is active, preventing replay from contaminating demonstrations. Teaching additionally guards credential capture and excludes the controller app itself. On interruption/unbind the runtime fails
closed; reconnecting the service establishes readiness again.

## Execution

1. Validate the request and resolve current workflow content from the injected repository.
2. Validate slot values, constants, supported action parameters, and reference conventions.
3. Check service readiness and reject unsupported verification before any side effect.
4. Observe the active window, check safety/preconditions, and score semantic candidates.
5. Reacquire a fresh live tree inside the Android driver; repeat safety, precondition, and
   target matching checks; perform one accessibility action under the safety gate.
6. Re-observe after dispatch, including when Android declines the request. Verify bounded
   transition evidence; only accepted **and verified** actions increment completed steps.
7. Recover before dispatch where safe, or return a specific handoff/abort. Emit frozen
   `ExecutionTrace` and `ExecutionResult`, plus local diagnostics and stopped-step ID.

Supported actions: CLICK/TAP, INPUT_TEXT, LONG_PRESS, SCROLL with an explicit `direction`
parameter of `forward` or `backward`. Execution uses ACTION_CLICK, ACTION_SET_TEXT,
ACTION_LONG_CLICK, and accessibility scroll actions. No coordinates, gestures, app launching,
deeplinks, or web fallback are used. Unknown actions and unknown parameters fail admission.

CLICK can use the nearest enabled/visible actionable parent within two levels. Other actions
require support on the matched node itself. LONG_PRESS requires the live action list. Duplicate
semantic candidates are never resolved by traversal order, even when they might share a parent.

## Slot compatibility

`SlotBinder` accepts bare names, `${name}`, and `{name}` in reference fields. It understands
`textSlot`, `input_parameter`, `input_literal`, and the legacy `parameters["text"]` convention.
Conflicting input sources, undeclared/missing slots, invalid numeric/boolean values, and changed
constants are rejected. Required variables never fall back to demonstration examples.

For INPUT_TEXT, a `textSlot` supplies the new value and removes literal text from target identity.
It does not require a field's old text to equal its new input. The same adjustment is made to a
matching required-element precondition. Verification requires the uniquely matched editable
field to expose the requested value after the action; unrelated UI changes are insufficient.

The Person 1 convention `required=false` means constant. Optional variable slots and enum
allowed-value lists are not represented in the frozen schema. The runtime does not invent them.

## Matching and verification

Scoring uses resource ID, exact text, description, role, ancestry, and optional context. Resource,
text, and role contradictions are rejected. Containment earns only partial evidence. A primary
exact anchor is required to reach the default execution threshold of 0.85. Candidates within
0.12 of one another require clarification. Role-only matching cannot execute.

Preconditions support package and element presence. Activity and unknown custom conditions
return handoff. `INITIAL_STATE` requires an explicit matching package and required element.
Other state labels require a semantic fingerprint established by an earlier **verified**
transition's `toState`. Labels alone never prove state. Synthesis now uses consistent workflow labels for recorded
transitions rather than snapshot UUIDs; unsupported legacy labels still require handoff.

Transition verification supports expected package, element appearance, and disappearance.
Appearance/disappearance requires evidence on both sides of the action. A package already
matching before the action is insufficient. When explicit transition evidence is absent, a
semantic-state change is accepted as **weaker verification**, explicitly identified in diagnostics.
This proves UI change, not remote/business transaction completion. Snapshot UUIDs, timestamps,
bounds, and focus-only changes are excluded. Arbitrary `verification` expressions and unknown
transition types are unsupported and rejected before execution.

Polling defaults to 100 ms, capped at 10 seconds per transition (configuration is itself bounded).
Snapshots are traversed only up to 2,000 nodes/64 levels. Incomplete or unavailable trees fail
closed. Only the active accessible window is observed; inaccessible custom canvases, invisible
controls, cross-window dialogs, and richer spatial relations may require user intervention.

## Safety and recovery

All production `performAction` calls are inside `AccessibilityUiDriver` and inside
`SafetyGate.dispatch`. The gate synchronizes the final check and dispatch. A block is latched:
subsequent steps, recovery, and later requests on the engine cannot issue actions. Cancellation
also latches the gate. There is no automatic unlock. An explicit user-triggered new-execution
acknowledgment is refused while a run is active; the next run restarts validation from step one.

Safety scans workflow metadata and current visible semantics, including credential keywords,
password flags/input types/hints, authentication, payment/checkout, and declared sensitive
keywords. Unknown restricted-action vocabularies and monetary limits without amount/currency
evidence produce handoff. Confirmation-required workflows are handed to the user; this layer
does not treat a confirmation dialog as permission to automate credentials or payment.

Editable text reads are deferred until the whole observed screen passes credential detection.
Protected-field contents are never read. All text, descriptions, resource IDs, input values,
and bounds are omitted from stored runtime evidence, including benign values. Exceptions are
reported using fixed messages. There is no runtime logging of UI/input text. Detection remains
dependent on exposed accessibility semantics and a finite keyword vocabulary; arbitrary language
or inaccessible content is not claimed to be understood. Hindi credential/payment keywords are
included, but translation or multilingual command understanding belongs upstream.

RETRY_STEP re-observes and re-matches **only before any action dispatch**, with at most three
retries and at most two seconds per retry delay. Once an action was dispatched, a timeout or
decline cannot trigger another automated action, because completion may be uncertain. This is
intentionally more conservative than attempting to classify every irreversible action.

HANDOFF_TO_USER and ABORT are implemented directly. DISMISS_POPUP_AND_RETRY and
NAVIGATE_BACK_AND_RETRY produce specific manual handoffs: the frozen recovery contract provides
no safe popup target, navigation destination, or unsaved-state evidence. The runtime does not
guess a dismiss button or perform a potentially destructive Back action.

## Frozen-contract limitations

- `SkillRepository` cannot atomically resolve workflow content and version or retrieve historical
  revisions. Runtime uses current content and records that the requested version could not be
  checked. Callers must share the populated repository and avoid concurrent workflow mutation.
- Workflow safety is metadata, not a capability token; runtime always repeats its own checks.
- `ExecutionTrace.events` cannot contain `ExecutionDecision`. Supported action/state events go
  into the trace; decisions live in Person-2-local `RuntimeDiagnostic` entries in `RuntimeReport`.
- Redacted trace events describe an attempted action or observed transition, not proof of success.
  The result and diagnostics distinguish acceptance, verification, and handoff.
- `lastReport` is in memory. There is no process-death resume, persistence, automatic replay,
  or deduplication based on Person 1's potentially repeated execution IDs.
- Android UI can change asynchronously even during a fresh traversal. API acceptance is never
  treated as completed execution; verification and handoff are necessary. Device validation is
  still required for each accessibility implementation/OEM behavior.

## Validation and demonstration

Normal project validation:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --offline --no-daemon
```

The full project compiles and its tests run; there is no longer a Person 1 compilation blocker.
The additional focused harness compiles actual runtime, Android adapter, contracts and capture
dependencies against SDK 34 using the populated Gradle cache, in a temporary directory:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home python3 tools/test_person2.py
```

On other machines set JAVA_HOME to an installed JDK 17. Set ANDROID_HOME or local.properties
to an SDK containing android-34. Missing cached dependencies are reported without downloading.

The JVM closed-loop tests demonstrate changed INPUT_TEXT and CLICK/TAP through request,
injected store, binding, matching, fake dispatch, re-observation, verification, and truthful result.
They also cover ambiguity, credential/payment handoff, safety races/lock persistence,
timeouts, bounded recovery, state evidence, cancellation, unsupported inputs, and redaction.
These tests are not an on-device accessibility demonstration.

The production bridge is connected. Rehearse an actual taught workflow on the intended device;
see [DEMO](DEMO.md) and [EVALUATION](EVALUATION.md) for commands, measured results, and boundaries
between fake-driver evidence, real Android fixture tests, and third-party application claims.
