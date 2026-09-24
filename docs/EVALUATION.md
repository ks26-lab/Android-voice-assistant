# Evaluation evidence — final working-tree audit

Evidence is scoped: passing a synthetic contract/driver test is not a passing third-party food or shopping demo. No test-specific app logic is shipped in production.

## Reproducible checks

- `./gradlew clean :app:compileDebugKotlin --console=plain` — successful.
- `./gradlew :app:testDebugUnitTest --console=plain` — **409 tests, 409 passed, 0 failed, 0 ignored** (396 existing + 13 added in this pass).
- `JAVA_HOME=/path/to/jdk-17 python3 tools/test_person2.py` — **61 passed, 0 failed**; these are a subset of the Gradle tests, not additional coverage counts.
- `./gradlew assembleDebug assembleRelease :app:assembleDebugAndroidTest --console=plain` — successful; release is unsigned.
- Emulator `emulator-5554`, Pixel 6 AVD/API 36: APK installed and launcher Activity started successfully. No app crash in the observed launch log.
- Real SpeechRecognizer path implemented; microphone transcription is **NOT LIVE VERIFIED**.
- Live Accessibility instrumentation: pending manual service enablement. The first Gradle connected run skipped both tests because the service was unavailable; skipped tests are not passes. Direct adb test results must supersede this line only after an observed run.

## T1–T14

| Test | Classification | Evidence / remaining scope |
|---|---|---|
| T1 teach food workflow | IMPLEMENTED / LIVE TEST NEEDED | Capture→synthesis→validation→shared store is wired; learning tests and single-example confirmation pass; no real food-app teaching observed. |
| T2 exact replay | AUTOMATED TESTED | `testB_validExecutionRequest_executesSemanticStepOnDriver`, runtime verification tests; actual taught external app still needs rehearsal. |
| T3 paraphrases | AUTOMATED TESTED | CommandInterpreter tests and `searchParaphrasesBindExplicitQueryAndKeepMissingQueryMissing`; finite English grammar only. |
| T4 changed item | AUTOMATED TESTED | `testM_changedBoundSlot_propagatesToDriverAction` and changed-label/text binding tests use supplied values. |
| T5 quantity | AUTOMATED TESTED | `threeStepWorkflowBindsTextQuantityAndAddressInOrder` verifies INTEGER binding and ordered execution. |
| T6 address | AUTOMATED TESTED | Same three-step test verifies ADDRESS-like new value reaches the driver. |
| T7 changed screen / recovery or question | AUTOMATED TESTED | Matcher ambiguity/missing-target tests and bounded recovery/timeout tests stop safely; app layout variation needs live rehearsal. |
| T8 second shopping workflow | IMPLEMENTED / LIVE TEST NEEDED | `testL_twoIndependentWorkflows_isolatedResolution` proves separate repository resolution, not a live shopping demonstration. |
| T9 changed shopping item | IMPLEMENTED / LIVE TEST NEEDED | Generic changed-text binding is tested; no shopping-app command→UI run observed. |
| T10 stuck / logged out / unsupported language | AUTOMATED TESTED | Wrong package, unsupported condition, credential handoff and unknown-intent paths stop; broad Hindi understanding is not implemented. |
| T11 credentials/payment | AUTOMATED TESTED | Credential/payment/transfer tests, mid-run handoff, sticky lock and redaction tests; finite semantic detection, not universal certification. |
| T12 unknown command | AUTOMATED TESTED | Unknown skill/intent and “Book a cab to airport” checks produce no unrelated execution. |
| T13 ambiguity | AUTOMATED TESTED | Request ambiguity and close semantic candidates reject execution; no arbitrary tie-breaking. |
| T14 last run | AUTOMATED TESTED | `testK_executionTraceAndResultCorrectness`; production LAST RUN renders IDs, counts, state, stopped step, confidence, reason and duration. |

## New regression tests

`ReleaseReadinessTest`:
- explicitConfirmationUsesOneRealExampleWithoutInventingValues
- confirmationCannotInventAnUndemonstratedSlot
- confirmationPreservesConflictingEvidence
- capturedStateTransitionsUseConsistentLogicalLabelsNotSnapshotIds
- confirmedVariableClickBindsChangedLabelWithoutTextInputParameters
- credentialValueWithGenericSlotNameIsRedactedFromWorkflow
- credentialUtteranceAndAudioUriAreNotRetained
- typedPrefixesProduceOneFinalSemanticInputAction
- searchParaphrasesBindExplicitQueryAndKeepMissingQueryMissing

`RuntimeTest` additions:
- threeStepWorkflowBindsTextQuantityAndAddressInOrder
- sensitiveSecondScreenBlocksNextStepAndEverySubsequentRun
- constantInputValueIsNotMistakenForCurrentFieldIdentity
- financialTransferConfirmationRequiresHandoffWithoutAction

`LiveAccessibilitySmokeTest` (androidTest only):
- liveThreeStepTextClickAndTransitionVerification
- liveProtectedFieldHandoffAndStickyLock

No existing tests were removed or weakened. The pre-existing uncommitted additions to EndToEndIntegrationTest were preserved unchanged.

## Source/contract audit

- Frozen `contract/` files: **no changes**, compared with pre-pass hashes and Git diff.
- Production `performAction`: exactly **one**, `runtime/ui/AccessibilityUiDriver.kt:87`, inside SafetyGate dispatch.
- No requested vendor names (Zomato/Swiggy/Domino/Amazon/Flipkart/Uber/Ola), app-specific execution branches, target-app resource-ID literals, coordinate gestures, deeplinks or web fallbacks in production.
- Bounds occur in the UI snapshot/normalizer as metadata; runtime fingerprint and trace omit them. Other coordinate matches are validator/inspector rejection checks or explanatory comments. Case-insensitive “bounds” also matches benign `BoundStep`/`boundSlots` identifiers.
- HTTP occurrences in production XML are Android namespace declarations, not network operations. No production ACTION_VIEW.
- `Person1MockRuntime` remains as a legacy, explicitly labeled preview formatter used by its existing test; production TraceViewer no longer calls it. No production fake execution. “fake driver” occurs in UiDriver documentation only.
- No TODO/FIXME/NotImplemented/UnsupportedOperationException/placeholder matches in production.
- Intent vocabulary includes existing provider-name words such as “whatsapp”/“google”; these classify language only, not package branches or execution scripts. Natural-language coverage remains limited.
- Warnings: deprecated node recycling (needed for older supported Android APIs), unused parameters/variables and redundant casts; no remaining compiler errors. JDK 24 emits extra Kotlin-tooling warnings; use JDK 17.

## Remaining demo checks

Rehearse one full external-app teach/save/paraphrase/changed-value cycle, then a second workflow. Verify real microphone recognition, keyboard and package transitions, same-screen input fields, UI hierarchy variation, report visibility after switching apps, and explicit reset after a sensitive handoff. Do not describe these as complete until observed.
