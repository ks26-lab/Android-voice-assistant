# Teachable Voice Automation
## One-Shot Semantic Workflow Learning for Android

**Samsung PRISM Gen AI Hackathon 3.0 — Theme 3: Real-Time Agents**

Teachable Voice Automation is an Android-native assistant that learns a reusable workflow from a single UI demonstration. Instead of replaying screen coordinates or relying on app-specific APIs, it captures Android Accessibility evidence, filters navigation/system noise, extracts semantic actions, infers intent and variable slots, synthesizes an inspectable Workflow IR, and passes learned skills to a deterministic runtime with state verification and safety boundaries.

> **Core idea:** Teach the assistant a workflow once, then invoke the learned workflow using a new or paraphrased command with changed values.

## Drive Link
https://drive.google.com/drive/folders/1Xdtom1lW-tKq9wqBhIA_BtVk9r3PjxEG?usp=sharing

## Key Features

- One-shot workflow learning from Android UI demonstrations
- Android Accessibility-based observation and execution
- Semantic action extraction instead of coordinate recording
- Demonstration filtering for launcher, recents, own-app, and transition noise
- Per-action package provenance
- Intent and canonical slot inference
- Parameterized Workflow IR such as `${item}`
- Inspectable Skill Store
- Changed-slot and paraphrased command understanding
- Semantic UI target matching
- Evidence-based transition verification
- Bounded recovery and explicit user handoff
- Credential/payment safety boundaries
- No coordinate replay
- Execution traces and outcome reporting
- Fail-closed handling of unknown, ambiguous, unsafe, or unverifiable situations

## Example

Teaching command:

```text
Search for headphones
```

Learned representation:

```text
Intent: search_information

Slot:
  item
  Type: TEXT
  Role: VARIABLE
  Reference: ${item}

Procedure:
  INPUT_TEXT
  Target: semantic EditText
  Parameter: ${item}
```

A later command:

```text
Search for phone case
```

can bind:

```text
item = "phone case"
```

to the same learned workflow rather than storing another hard-coded recording.

## Architecture

```text
Voice / Typed Task + Android Accessibility Events
                       |
                       v
              Demonstration Capture
                       |
                       v
               DemonstrationTrace
                       |
                       v
          Normalization + Noise Filtering
                       |
                       v
        Semantic Action / Target Extraction
                       |
                       v
             Intent + Slot Inference
                       |
                       v
          Constant / Variable Alignment
                       |
                       v
         Workflow Synthesis + Validation
                       |
                       v
                 Workflow IR
                       |
                       v
                  Skill Store
                       |
                 New Command
                       |
                       v
        Command Understanding + Matching
                       |
                       v
              ExecutionRequest
                       |
                       v
      Slot Binding + Current UI Observation
                       |
                       v
       Semantic Target / State Matching
                       |
                       v
          Confidence + Safety Gates
                       |
                       v
      Deterministic Accessibility Executor
                       |
                       v
             Transition Verification
                       |
          Verified / Recover / Ask /
              Handoff / Safe Stop
                       |
                       v
        ExecutionTrace + ExecutionResult
```

The architecture separates **semantic learning/reasoning** from **deterministic execution**. Learned meaning determines what should happen; runtime state verification and safety policy determine whether an action is allowed to happen.

## Teaching Workflow

1. Enter a skill name and task description.
2. Tap **START TEACHING**.
3. Switch to another Android app and demonstrate the workflow.
4. Return and tap **STOP TEACHING**.
5. Normalize the captured trace.
6. Extract semantic actions.
7. Infer intent and slots.
8. Synthesize and validate Workflow IR.
9. Confirm variable values.
10. Store and inspect the learned skill.

## Replay Workflow

1. Provide a new voice or typed command.
2. Interpret it into canonical intent and slots.
3. Match it against learned skills.
4. Reject unknown or ambiguous matches instead of guessing.
5. Build an `ExecutionRequest`.
6. Bind new slot values.
7. Observe the current Android UI.
8. Verify app/state preconditions.
9. Resolve targets semantically.
10. Pass actions through the safety policy.
11. Execute via Android Accessibility.
12. Re-observe and verify transitions.
13. Recover, clarify, hand off, or stop when evidence is insufficient.
14. Produce an inspectable execution result and trace.

## Safety Model

Safety is enforced below semantic planning.

The runtime is designed to:
- block credential/password/PIN/OTP-sensitive automation;
- stop or hand control to the user at protected boundaries;
- avoid storing protected text;
- prevent continued automated actions while a hard safety boundary is active;
- reject ambiguous skill matches;
- reject unsupported or unbindable variables;
- pause when target application/state evidence cannot be verified;
- avoid blind coordinate-based replay.

**Design principle: maximum reliable autonomy, not maximum autonomy.**

## Requirements

Recommended environment:
- Android Studio
- Android SDK / Platform Tools (`adb`)
- Compatible JDK
- Android physical device or emulator
- Accessibility Service support
- USB debugging for physical-device development
- Python 3 for the Person-2 test harness

The included Gradle wrapper is used, so a separate Gradle installation is not required.

## Clone and Build

```bash
git clone https://github.com/umika27/Android-voice-assistant.git
cd Android-voice-assistant
./gradlew clean :app:assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

or:

```bash
./gradlew installDebug
```

## Accessibility Setup

After installation:

1. Open Android **Settings**.
2. Navigate to **Accessibility**.
3. Find **Teachable Voice Automation**.
4. Enable its Accessibility Service.
5. Return to the application.

Android may require Accessibility to be re-enabled after application data is cleared.

## Reproducible Demo

1. Launch Teachable Voice Automation.
2. Enter:

```text
Skill name: Search
Task description: Search for headphones
```

3. Tap **START TEACHING**.
4. Switch to a supported Android search UI.
5. Focus the search field and enter `headphones`.
6. Return to Teachable Voice Automation.
7. Tap **STOP TEACHING**.
8. Normalize the trace.
9. Extract semantic actions.
10. Validate and store the skill.
11. Mark `item` as the variable when prompted.
12. Inspect the stored skill.

Expected learned representation includes:

```text
Intent: search_information
Slot: item
Type: TEXT
Role: VARIABLE
Reference: ${item}
Validation: VALID
Stored: STORED
Coordinate Replay: NO
Credential automation: BLOCKED
```

Then provide:

```text
Search for phone case
```

The command-understanding pipeline should derive:

```text
intent = search_information
item = phone case
```

If the target application or required semantic starting state cannot be verified, runtime may request explicit user handoff instead of blindly replaying an action.

## Tests

Android unit tests:

```bash
./gradlew testDebugUnitTest --console=plain
```

Person-2/runtime harness:

```bash
python3 tools/test_person2.py
```

APK build:

```bash
./gradlew :app:assembleDebug --console=plain
```

Regression coverage includes teaching capture, external-app package provenance, navigation/system noise filtering, semantic action extraction, canonical intent/slot alignment, workflow synthesis, replay admission, command parsing, slot binding, state evidence, transition verification, ambiguity handling, runtime safety, and handoff behavior.

## Important Design Constraints

The implementation intentionally avoids:
- hard-coded screen coordinates;
- pre-baked benchmark workflows;
- app-specific SDK automation;
- deep-link shortcuts as a substitute for learned execution;
- web fallbacks as a substitute for Android interaction;
- blind screenshot-to-LLM-to-tap loops;
- credential/payment automation;
- silently choosing between ambiguous learned skills.

UI bounds may exist as runtime observations, but are not treated as learned workflow identity.

## Project Structure

```text
app/src/main/java/com/chockXlate/teachablevoice/

├── app/          # Application/demo UI
├── command/      # Command interpretation and request creation
├── contract/     # Shared contracts / Workflow IR
├── learning/     # Intent, slots, synthesis, provenance
├── runtime/      # Binding, execution, recovery, verification
├── safety/       # Runtime safety policy/gates
├── skill/        # Skill validation/repository
└── teach/        # Demonstration capture/filtering
```

## Core Contracts

Important contracts include:
- `DemonstrationTrace`
- `UiState`
- `UiElement`
- `Workflow`
- `WorkflowStep`
- `WorkflowSubtask`
- `SemanticSelector`
- `ExpectedTransition`
- `SafetyBoundary`
- `ExecutionRequest`
- `ExecutionDecision`
- `ExecutionState`
- `ExecutionProgress`
- `ExecutionTrace`
- `ExecutionResult`

These form the boundary between learning and deterministic execution.

## Failure Handling

Failure is a valid runtime outcome. The system may:

```text
EXECUTE
RECOVER
ASK_USER
HANDOFF
STOP
```

instead of fabricating missing values or blindly continuing.

Examples:
- target application is not foreground;
- semantic starting-state evidence does not match;
- UI target confidence is insufficient;
- required slot is missing;
- multiple compatible skills exist;
- transition outcome cannot be verified;
- a credential/payment boundary is encountered.

## Current Prototype Limitations

This is a hackathon research prototype, not a production Android assistant.

- Accessibility quality varies across third-party apps and custom views.
- Dynamic/custom screens may expose insufficient stable semantic evidence.
- Replay can require explicit user handoff to establish the correct foreground app/state.
- Speech recognition depends on Android speech-recognition availability; typed command input is available as a fallback.
- Learned skills currently operate within supported canonical intent/slot grammar.
- Runtime intentionally refuses execution when confidence, state evidence, ambiguity, or safety policy prevents reliable automation.

## Docker

**Docker is not required for this Android-native prototype.** The APK is built directly with the included Gradle project. There is no separate server component required to reproduce the demonstrated Android workflow-learning pipeline.

## Reproducibility Summary

For evaluation:

1. Clone the repository.
2. Build with the included Gradle wrapper.
3. Install the APK.
4. Enable the Accessibility Service.
5. Teach a workflow.
6. Inspect the generated Workflow IR.
7. Submit a changed/paraphrased command.
8. Inspect runtime decisions and the execution trace.

## Hackathon Submission

**Samsung PRISM Gen AI Hackathon 3.0 — Theme 3: Real-Time Agents**

Project: **Teachable Voice Automation — One-Shot Semantic Workflow Learning for Android**

The final judged commit must be tagged exactly:

```text
PRISM_GENAI_HACKATHON_Y2026
```

All artifacts referenced by the final submission should be present in that tagged commit as required by the hackathon instructions.

## Final Release Checklist

- [ ] Source code pushed
- [ ] Clean-clone build verified
- [ ] Accessibility setup documented
- [ ] Unit tests pass
- [ ] Person-2 runtime harness passes
- [ ] APK verified
- [ ] README included
- [ ] PPT/PDF included
- [ ] Demo video included or referenced as required
- [ ] Documentation included
- [ ] No credentials/secrets committed
- [ ] Final commit reviewed
- [ ] Final commit tagged `PRISM_GENAI_HACKATHON_Y2026`
-  Tag pushed

After all final artifacts are committed:

```bash
git tag PRISM_GENAI_HACKATHON_Y2026
git push origin PRISM_GENAI_HACKATHON_Y2026
```

## Disclaimer

This prototype uses Android Accessibility capabilities for user-authorized workflow learning and execution. It is intended for hackathon/research demonstration purposes. Sensitive credential and payment interactions are deliberately outside the automated execution boundary.
