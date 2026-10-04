# AUDIT DOCUMENT: CROSS-APP GENERALIZATION
**Samsung PRISM Theme 3 — Bonus Feature 2 (+4 Points)**

---

## 1. EXISTING APPLICATION IDENTITY
Currently, application identity is stored in:
- `Workflow.appContext`: String containing the Android package name of the application where teaching occurred (e.g., `com.example.searchapp`).
- `DemonstrationTrace.appContext`: Package name captured during teaching.
- `UiState.appContext` / `ActionEvent.packageName`: Runtime observation metadata.

---

## 2. COUPLING ANALYSIS IN WORKFLOW IR
In `Workflow.kt`, `appContext` stores the teaching package name. However:
- Individual `WorkflowStep` objects contain `semanticSelector: SemanticSelector`, which stores pure semantic attributes (`role`, `text`, `textSlot`, `contentDescription`, `resourceId`, `parentRole`, `ancestorRole`, `nearbyText`, `relativePosition`).
- `SemanticMatcher.kt` scores elements based on text, contentDescription, role, and resource ID. If `resourceId` differs across apps, matching score drops unless semantic adaptation/generalization evidence is calculated.

---

## 3. SEMANTIC SELECTOR ATTRIBUTE BREAKDOWN
- **Role**: Widget class (e.g., `EditText`, `Button`, `TextView`).
- **Text / Content Description**: Primary human-readable labels.
- **Resource ID**: Android resource identifier (`com.pkg:id/search_btn`). Often package-specific.
- **Parent / Ancestor / Nearby Text**: Structural and relational context.
- **Relative Position**: Spatial/layout relation.

---

## 4. RUNTIME MATCHING BEHAVIOR
`SemanticMatcher.kt` matches a `SemanticSelector` against `UiElement`s within a single UI observation. It relies heavily on exact resourceId or exact string matches. It does NOT analyze multi-step transferability, slot preservation, target state compatibility, or safety boundaries across different app contexts.

---

## 5. TRANSFERABLE VS APPLICATION-SPECIFIC PARTS
- **Application-Specific**: `appContext` package name, package-prefixed `resourceId` strings.
- **Task-Semantic (Transferable)**: Step action sequence (`INPUT_TEXT`, `CLICK`), semantic roles, text slots, intent goals ("search item"), structural context, and workflow ordering.

---

## 6. WHY SEPARATE BONUS MODULE IS SAFE
By creating package `com.chockXlate.teachablevoice.teach.bonus.crossapp`, we can:
1. Implement `CrossAppGeneralizationEngine`, `CrossAppSemanticMatcher`, `CrossAppCompatibilityAnalyzer`, and `CrossAppGeneralizationResult` without altering existing runtime files or contracts.
2. Treat `appContext` package name strictly as environment metadata rather than workflow identity.
3. Enforce safety checks so cross-app adaptation never bypasses `SafetyGate` or sensitive screens (payment, credentials, OTP).
4. Provide a standalone, isolated adapter API (`CrossAppGeneralizationEngine.analyze(...)`) for validation.

---

## 7. FROZEN FILES (MUST NOT BE MODIFIED)
The following production files are **FROZEN** and MUST NOT be edited:
- `app/src/main/java/com/chockXlate/teachablevoice/contract/workflow/Workflow.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/contract/workflow/SemanticSelector.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/runtime/matching/SemanticMatcher.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/runtime/ExecutionEngine.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/safety/SafetyGate.kt`
- `app/src/main/java/com/chockXlate/teachablevoice/teach/bonus/irrelevant/*` (Page 1 Bonus 1)
