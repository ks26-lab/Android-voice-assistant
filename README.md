# One-Shot Semantic Workflow Learning for Android

Samsung PRISM Gen AI Hackathon 3.0 prototype: teach an Android interaction once, inspect the learned semantic workflow, then replay it with a new command and changed values. The workflow is learned from Accessibility evidence; the executor has no application-specific scripts.

**Current scope:** deterministic semantic extraction and a limited English command grammar, not an LLM or unrestricted natural-language understanding. One demonstration cannot establish which values vary: the save dialog asks the teacher to explicitly select variable slots. Unsupported meaning, ambiguous controls, inaccessible screens, and sensitive boundaries stop automation.

## Production path

Voice/typed description + real taps → DemonstrationTrace → normalization → semantic actions/intent/slots → single-demonstration review → Workflow → validation → shared in-memory SkillRepository → new command → matching → ExecutionRequest → deterministic ExecutionEngine → fresh Accessibility tree → safety gate → action → re-observation/verification → result and trace.

Person 1 owns learning through ExecutionRequest. Person 2 owns resolution, binding, execution, verification, recovery and reporting. Shared contracts contain no Android node objects. [Architecture](docs/ARCHITECTURE.md) explains the actual wiring.

## Why semantic execution

Selectors contain naturally observed labels, descriptions, resource IDs and hierarchy context. They do not replay tap coordinates. The runtime rejects ambiguous candidates and reacquires live nodes for every action. Android accepting an action is not success: each step must produce observable transition evidence. A changed UI is weaker evidence than an explicit expected control, and the report identifies that distinction.

## Safety

A deterministic gate checks workflow restrictions, password metadata and visible credential/payment semantics immediately before dispatch and during verification. Handoff/cancellation latches the engine; later requests cannot act until explicit reset and fresh validation. Activity recreation does not reset the latch. Payments and credentials require manual control; reset does not grant an exemption.

Teaching capture refuses recognized credential screens before reading editable values. Credential-related utterances are discarded/redacted. Runtime traces omit input/UI text. Detection depends on accessible metadata and a finite vocabulary; do not teach secrets. Unknown language/content is not certified safe.

## Build and setup

Requirements: JDK 17, Android SDK platform 34, Android SDK build tools, Android device/emulator API 26+, USB debugging for adb. Gradle wrapper downloads Gradle 8.5 and Maven dependencies on first use. No backend, API key, database or signing secret is needed.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
./gradlew clean :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --console=plain
python3 tools/test_person2.py
./gradlew assembleDebug --console=plain
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n com.chockXlate.teachablevoice/.app.TeachingDemoActivity
```

Alternatively set `sdk.dir` in your own ignored `local.properties`. The focused Python harness uses dependencies populated by Gradle and SDK 34; it is not a replacement for the full build. `assembleRelease` produces an **unsigned** release APK; distribution signing is outside this repository.

Enable **Settings → Accessibility → downloaded apps → Teachable Voice Automation** manually. The app also has an Accessibility Settings button. Grant microphone permission when pressing **MIC / STOP**. Speech uses the installed Android SpeechRecognizer; offline recognition is preferred, but provider availability/language/network behavior is device-dependent. Typed recording remains available.

## Teach and replay

1. Enter a skill name and task description. Start teaching, then speak with MIC / STOP or type a transcript and press RECORD TYPED.
2. Switch directly to the target app using Recents. Perform a short, harmless, accessible workflow. The first external interaction establishes the teaching package; cross-app teaching is not supported.
3. Return and STOP TEACHING. Inspect normalization/actions if useful, then VALIDATE & STORE. Select only values you intend future commands to supply; unchecked values are fixed. No synthetic demonstration or sample skill is inserted.
4. Return the target app to its learned initial screen. In this app, enter the new command, then EXECUTE RUNTIME. Switch to the target app within the five-second countdown.
5. Return to inspect LAST RUN. It shows execution/skill IDs, completed steps, stopped step, state, reason, confidence and duration. After handoff, resolve the issue manually and explicitly RESET before submitting a new command.

The command grammar supports selected ordering phrases and `search [for] …`, `find …`, `look up …`. Other canonical intents exist but do not all have complete slot extraction. Arbitrary custom intents/paraphrases are **not** guaranteed. Missing required values remain missing; unknown skills require teaching. Similar skills may require clarification.

## Validation and limits

See [evaluation evidence](docs/EVALUATION.md), [five-minute demo](docs/DEMO.md), [judge questions](docs/JUDGE_QA.md), and [runtime details](docs/PERSON2_RUNTIME.md).

The store and last result live only in the app process. Historical workflow versions cannot be retrieved by the frozen repository interface. Scroll execution requires an explicit direction; teaching does not infer it. Popup dismissal/back navigation require manual intervention. Non-accessible canvases and multilingual commands are not supported generically. Main-thread node traversal is bounded, and polling uses nonblocking delays.

Do not version `local.properties`, `.gradle/`, `.idea/`, `**/build/`, APKs or `compile-errors.txt`. Keep the wrapper scripts/JAR/properties and source resources in the repository.
