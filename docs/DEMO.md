# Demo: under five minutes

## Before presenting

Build/install, manually enable Accessibility, grant microphone permission, and rehearse on a harmless accessible app. Keep both apps in Recents. Avoid launcher taps while recording: the first external action establishes the package. Pick a short search workflow with a stable accessible field and a control that visibly changes state. If recognition is unavailable, disclose the typed fallback. Do not claim third-party task completion from a unit test.

## Sequence

- **0:00–0:30 — Thesis.** “This learns semantic controls from a demonstration, rather than replaying screen coordinates. Meaning is currently parsed with deterministic rules; uncertain outcomes stop.”
- **0:30–1:30 — Teach once.** Start teaching, speak a simple supported task such as a search, switch through Recents and demonstrate the harmless interaction. Return and stop. Show the actual trace.
- **1:30–2:15 — Inspect/save.** Show the learned selectors and provenance. Explicitly select the observed query as variable; save. No second example is fabricated.
- **2:15–3:15 — Changed command.** Restore the target starting screen. Enter a supported paraphrase with a different value, press Execute, switch within five seconds. Show the new value/action and the verification report.
- **3:15–4:15 — Safe uncertainty.** Use an unknown command or a missing required value. Show that no execution request is accepted. A login/payment screen should hand off, never be automated; do not enter credentials as a demonstration.
- **4:15–5:00 — Evidence and limits.** Show LAST RUN and EVALUATION. Distinguish emulator fixture execution, actual third-party rehearsal, and unverified voice/provider behavior.

If a run stops, show its reason. Resolve manually, explicitly reset, and submit a new run from the initial screen. Never hide a failed transition or claim the expected result occurred.

## Repeatable live fixture test

This test temporarily supplies generic native controls from **androidTest**, then uses the actual production driver. It does not install a production workflow or bypass Accessibility permission.

```sh
./gradlew assembleDebug :app:assembleDebugAndroidTest --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Manually enable Accessibility now, if Android cleared it after reinstall.
adb shell am instrument -w -e class com.chockXlate.teachablevoice.LiveAccessibilitySmokeTest com.chockXlate.teachablevoice.test/androidx.test.runner.AndroidJUnitRunner
```

Tests skip if the service is unavailable; a skip is not a pass. Gradle connected-device testing may uninstall packages and clear the setting afterward. Prefer the direct command above for a rehearsed emulator. Afterward relaunch the normal Activity.
