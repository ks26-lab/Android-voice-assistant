package com.chockXlate.teachablevoice.learning

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.filter.DemonstrationFilterClassification
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.learning.actions.SemanticAction
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.learning.actions.SemanticActionType
import com.chockXlate.teachablevoice.learning.alignment.AlignmentResult
import com.chockXlate.teachablevoice.learning.inference.InferenceResult
import com.chockXlate.teachablevoice.learning.intent.Intent
import com.chockXlate.teachablevoice.learning.intent.IntentExtractionResult
import com.chockXlate.teachablevoice.learning.slots.SlotExtractionResult
import com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer
import com.chockXlate.teachablevoice.teach.filter.DemonstrationFilter
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter
import com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy
import com.chockXlate.teachablevoice.command.matching.SkillMatcher
import com.chockXlate.teachablevoice.command.request.ExecutionRequestBuilder
import com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationAlignment
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset
import com.chockXlate.teachablevoice.learning.inference.ConstantVariableInference
import com.chockXlate.teachablevoice.learning.inference.SingleDemonstrationConfirmation
import com.chockXlate.teachablevoice.learning.intent.IntentExtractor
import com.chockXlate.teachablevoice.learning.slots.SlotExtractor
import com.chockXlate.teachablevoice.learning.synthesis.SynthesisStatus
import com.chockXlate.teachablevoice.runtime.slots.SlotBinder
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.skill.validation.ReplayAdmission
import com.chockXlate.teachablevoice.skill.validation.ValidationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalProvenanceAndFilterRegressionTest {

    private fun dummyIntentResult(intentName: String = "test_task"): IntentExtractionResult =
        IntentExtractionResult(
            schemaVersion = "1.0",
            intent = Intent(
                intentId = "int_1",
                canonicalName = intentName,
                confidence = 0.95,
                confidenceLevel = "HIGH"
            ),
            confidence = 0.95,
            evidenceSummary = "Test intent evidence"
        )

    private fun dummyInferenceResult(intentName: String = "test_task"): InferenceResult =
        InferenceResult(
            schemaVersion = "1.0",
            intentName = intentName,
            demonstrationsAnalyzedCount = 1,
            slotInferences = emptyList()
        )

    private fun dummyAlignmentResult(intentName: String = "test_task", demoIds: List<String> = listOf("demo1")): AlignmentResult =
        AlignmentResult(
            schemaVersion = "1.0",
            primaryIntent = intentName,
            alignedDemonstrationIds = demoIds
        )

    @Test
    fun test1_arbitraryExternalPackageSurvivesExtraction() {
        val action = ActionEvent(
            actionId = "act_ext_1",
            timestamp = 1000L,
            actionType = "CLICK",
            packageName = "com.example.target",
            semanticSelector = SemanticSelector(role = "Button", text = "Submit")
        )
        val trace = DemonstrationTrace(
            traceId = "tr_ext_1",
            timestamp = 1000L,
            appContext = "com.chockXlate.teachablevoice", // Started / stopped in our app
            traceEvents = listOf(TraceEvent.Action("act_ext_1", 1000L, action)),
            userActions = listOf(action)
        )

        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val filterRes = DemonstrationFilter.filter(norm.normalizedTrace)
        val extracted = SemanticActionExtractor.extract(norm.normalizedTrace, filterRes)

        assertEquals(1, extracted.size)
        assertEquals("com.example.target", extracted.first().target?.packageName)
        assertEquals("Submit", extracted.first().target?.text)
    }

    @Test
    fun test2_settingsNavigationSequenceSurvivesExtractionAndSynthesis() {
        // Physical device reproduction:
        // 1. Launcher/recents transition
        // 2. Click "Network & internet" in com.android.settings
        // 3. Click "Internet" in com.android.settings
        // 4. Return click in com.chockXlate.teachablevoice
        // 5. Anonymous FrameLayout click
        val launcherAction = ActionEvent(
            actionId = "a_recents",
            timestamp = 1000L,
            actionType = "CLICK",
            packageName = "com.google.android.apps.nexuslauncher",
            semanticSelector = SemanticSelector(role = "TextView", text = "Settings")
        )
        val action1 = ActionEvent(
            actionId = "a_settings_net",
            timestamp = 2000L,
            actionType = "CLICK",
            packageName = "com.android.settings",
            semanticSelector = SemanticSelector(role = "TextView", text = "Network & internet", resourceId = "android:id/title")
        )
        val action2 = ActionEvent(
            actionId = "a_settings_internet",
            timestamp = 3000L,
            actionType = "CLICK",
            packageName = "com.android.settings",
            semanticSelector = SemanticSelector(role = "TextView", text = "Internet", resourceId = "android:id/title")
        )
        val ownAppAction = ActionEvent(
            actionId = "a_own_app",
            timestamp = 4000L,
            actionType = "CLICK",
            packageName = "com.chockXlate.teachablevoice",
            semanticSelector = SemanticSelector(role = "Button", text = "Stop Teaching")
        )
        val anonFrame = ActionEvent(
            actionId = "a_anon_frame",
            timestamp = 5000L,
            actionType = "CLICK",
            packageName = "com.android.settings",
            semanticSelector = SemanticSelector(role = "android.widget.FrameLayout")
        )

        val trace = DemonstrationTrace(
            traceId = "tr_settings_nav",
            timestamp = 1000L,
            appContext = "com.chockXlate.teachablevoice", // ended in our app
            traceEvents = listOf(
                TraceEvent.Action("a_recents", 1000L, launcherAction),
                TraceEvent.Action("a_settings_net", 2000L, action1),
                TraceEvent.Action("a_settings_internet", 3000L, action2),
                TraceEvent.Action("a_own_app", 4000L, ownAppAction),
                TraceEvent.Action("a_anon_frame", 5000L, anonFrame)
            ),
            userActions = listOf(launcherAction, action1, action2, ownAppAction, anonFrame)
        )

        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val filterRes = DemonstrationFilter.filter(norm.normalizedTrace)
        val extracted = SemanticActionExtractor.extract(norm.normalizedTrace, filterRes)

        // Exactly 2 genuine task actions must be extracted
        assertEquals(2, extracted.size)
        assertEquals("Network & internet", extracted[0].target?.text)
        assertEquals("com.android.settings", extracted[0].target?.packageName)
        assertEquals("Internet", extracted[1].target?.text)
        assertEquals("com.android.settings", extracted[1].target?.packageName)

        // Synthesis must preserve package on both steps and workflow
        val synth = WorkflowSynthesizer.synthesize(
            intentResult = dummyIntentResult("open_internet_settings"),
            semanticActions = extracted,
            slotResult = SlotExtractionResult(intentName = "open_internet_settings"),
            alignmentResult = dummyAlignmentResult("open_internet_settings", listOf("tr_settings_nav")),
            inferenceResult = dummyInferenceResult("open_internet_settings"),
            trace = norm.normalizedTrace,
            filterResult = filterRes
        )

        assertNotNull(synth.workflow)
        val wf = synth.workflow!!
        assertEquals("com.android.settings", wf.appContext)
        assertEquals(2, wf.steps.size)
        assertEquals("com.android.settings", wf.steps[0].preconditions.requiredPackage)
        assertEquals("com.android.settings", wf.steps[1].preconditions.requiredPackage)
    }

    @Test
    fun test3_ownPackageActionsAreNeverExtracted() {
        val ownAction = ActionEvent(
            actionId = "a_own",
            timestamp = 1000L,
            actionType = "CLICK",
            packageName = "com.chockXlate.teachablevoice",
            semanticSelector = SemanticSelector(role = "Button", text = "Record Voice")
        )
        val trace = DemonstrationTrace(
            traceId = "tr_own",
            timestamp = 1000L,
            appContext = "com.chockXlate.teachablevoice",
            traceEvents = listOf(TraceEvent.Action("a_own", 1000L, ownAction)),
            userActions = listOf(ownAction)
        )

        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val filterRes = DemonstrationFilter.filter(norm.normalizedTrace)
        val extracted = SemanticActionExtractor.extract(norm.normalizedTrace, filterRes)

        assertTrue("Own-package actions must never be extracted", extracted.isEmpty())
    }

    @Test
    fun test4_launcherAndRecentsTransitionNoiseExcluded() {
        val launcherAction = ActionEvent(
            actionId = "a_launcher",
            timestamp = 1000L,
            actionType = "CLICK",
            packageName = "com.google.android.apps.nexuslauncher",
            semanticSelector = SemanticSelector(role = "TextView", text = "App Drawer")
        )
        val quickstepAction = ActionEvent(
            actionId = "a_quickstep",
            timestamp = 1100L,
            actionType = "CLICK",
            packageName = "com.android.quickstep",
            semanticSelector = SemanticSelector(role = "Overview", text = "Recent Apps")
        )
        val targetAction = ActionEvent(
            actionId = "a_target",
            timestamp = 2000L,
            actionType = "CLICK",
            packageName = "com.example.target",
            semanticSelector = SemanticSelector(role = "Button", text = "Search")
        )

        val trace = DemonstrationTrace(
            traceId = "tr_launcher",
            timestamp = 1000L,
            appContext = "com.example.target",
            traceEvents = listOf(
                TraceEvent.Action("a_launcher", 1000L, launcherAction),
                TraceEvent.Action("a_quickstep", 1100L, quickstepAction),
                TraceEvent.Action("a_target", 2000L, targetAction)
            ),
            userActions = listOf(launcherAction, quickstepAction, targetAction)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        val extracted = SemanticActionExtractor.extract(trace, filterRes)

        assertEquals(1, extracted.size)
        assertEquals("a_target", extracted.first().actionId)
        assertEquals("com.example.target", extracted.first().target?.packageName)
    }

    @Test
    fun test5_anonymousFrameLayoutClicksAreExcluded() {
        val anonFrame = ActionEvent(
            actionId = "a_frame",
            timestamp = 1000L,
            actionType = "CLICK",
            packageName = "com.example.target",
            semanticSelector = SemanticSelector(
                role = "android.widget.FrameLayout",
                text = null,
                contentDescription = null,
                resourceId = null
            )
        )
        val trace = DemonstrationTrace(
            traceId = "tr_anon",
            timestamp = 1000L,
            appContext = "com.example.target",
            traceEvents = listOf(TraceEvent.Action("a_frame", 1000L, anonFrame)),
            userActions = listOf(anonFrame)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        assertEquals(DemonstrationFilterClassification.UNCERTAIN, filterRes.allEvents.first().classification)
        val extracted = SemanticActionExtractor.extract(trace, filterRes)
        assertTrue("Anonymous FrameLayout clicks without identity must be excluded", extracted.isEmpty())
    }

    @Test
    fun test6_iconOnlyButtonWithContentDescriptionRetained() {
        val iconAction = ActionEvent(
            actionId = "a_icon_desc",
            timestamp = 1000L,
            actionType = "CLICK",
            packageName = "com.example.target",
            semanticSelector = SemanticSelector(
                role = "ImageButton",
                text = null,
                contentDescription = "Navigate Back",
                resourceId = null
            )
        )
        val trace = DemonstrationTrace(
            traceId = "tr_icon_desc",
            timestamp = 1000L,
            appContext = "com.example.target",
            traceEvents = listOf(TraceEvent.Action("a_icon_desc", 1000L, iconAction)),
            userActions = listOf(iconAction)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        val extracted = SemanticActionExtractor.extract(trace, filterRes)

        assertEquals(1, extracted.size)
        assertEquals("Navigate Back", extracted.first().target?.contentDescription)
        assertEquals("com.example.target", extracted.first().target?.packageName)
    }

    @Test
    fun test7_iconOnlyButtonWithResourceIdRetained() {
        val resAction = ActionEvent(
            actionId = "a_icon_res",
            timestamp = 1000L,
            actionType = "CLICK",
            packageName = "com.example.target",
            semanticSelector = SemanticSelector(
                role = "ImageView",
                text = null,
                contentDescription = null,
                resourceId = "com.example.target:id/filter_icon"
            )
        )
        val trace = DemonstrationTrace(
            traceId = "tr_icon_res",
            timestamp = 1000L,
            appContext = "com.example.target",
            traceEvents = listOf(TraceEvent.Action("a_icon_res", 1000L, resAction)),
            userActions = listOf(resAction)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        val extracted = SemanticActionExtractor.extract(trace, filterRes)

        assertEquals(1, extracted.size)
        assertEquals("com.example.target:id/filter_icon", extracted.first().target?.resourceId)
        assertEquals("com.example.target", extracted.first().target?.packageName)
    }

    @Test
    fun test8_crossAppWorkflowPreservesDistinctPackages() {
        val actApp1 = ActionEvent(
            actionId = "act_app1",
            timestamp = 1000L,
            actionType = "CLICK",
            packageName = "com.example.app1",
            semanticSelector = SemanticSelector(role = "Button", text = "Export")
        )
        val actApp2 = ActionEvent(
            actionId = "act_app2",
            timestamp = 2000L,
            actionType = "CLICK",
            packageName = "com.example.app2",
            semanticSelector = SemanticSelector(role = "Button", text = "Import")
        )
        val state1 = StateEvent(
            stateEventId = "st1",
            timestamp = 1050L,
            causeActionId = "act_app1",
            beforeState = UiState(stateId = "s1", timestamp = 1000L, appContext = "com.example.app1"),
            afterState = UiState(stateId = "s2", timestamp = 1050L, appContext = "com.example.app2")
        )
        val state2 = StateEvent(
            stateEventId = "st2",
            timestamp = 2050L,
            causeActionId = "act_app2",
            beforeState = UiState(stateId = "s2", timestamp = 2000L, appContext = "com.example.app2"),
            afterState = UiState(stateId = "s3", timestamp = 2050L, appContext = "com.example.app2")
        )

        val trace = DemonstrationTrace(
            traceId = "tr_cross_app",
            timestamp = 1000L,
            appContext = "com.example.app1",
            traceEvents = listOf(
                TraceEvent.Action("act_app1", 1000L, actApp1),
                TraceEvent.State("st1", 1050L, state1),
                TraceEvent.Action("act_app2", 2000L, actApp2),
                TraceEvent.State("st2", 2050L, state2)
            ),
            userActions = listOf(actApp1, actApp2),
            stateEvents = listOf(state1, state2)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        val extracted = SemanticActionExtractor.extract(trace, filterRes)

        assertEquals(2, extracted.size)
        assertEquals("com.example.app1", extracted[0].target?.packageName)
        assertEquals("com.example.app2", extracted[1].target?.packageName)

        val synth = WorkflowSynthesizer.synthesize(
            intentResult = dummyIntentResult("cross_app_transfer"),
            semanticActions = extracted,
            slotResult = SlotExtractionResult(intentName = "cross_app_transfer"),
            alignmentResult = dummyAlignmentResult("cross_app_transfer", listOf("tr_cross_app")),
            inferenceResult = dummyInferenceResult("cross_app_transfer"),
            trace = trace,
            filterResult = filterRes
        )

        assertNotNull(synth.workflow)
        val wf = synth.workflow!!
        assertEquals("com.example.app1", wf.steps[0].preconditions.requiredPackage)
        assertEquals("com.example.app2", wf.steps[1].preconditions.requiredPackage)
    }

    @Test
    fun test9_inputTextActionRetainsPackageAndInput() {
        val inputAction = ActionEvent(
            actionId = "a_input",
            timestamp = 1000L,
            actionType = "INPUT_TEXT",
            packageName = "com.example.editor",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.editor:id/input"),
            inputData = "Test Query 123"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_input",
            timestamp = 1000L,
            appContext = "com.example.editor",
            traceEvents = listOf(TraceEvent.Action("a_input", 1000L, inputAction)),
            userActions = listOf(inputAction)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        val extracted = SemanticActionExtractor.extract(trace, filterRes)

        assertEquals(1, extracted.size)
        val act = extracted.first()
        assertEquals(SemanticActionType.INPUT_TEXT, act.actionType)
        assertEquals("Test Query 123", act.inputValue)
        assertEquals("com.example.editor", act.target?.packageName)
    }

    @Test
    fun test10_normalizerDiscoversExternalAppContextWhenTraceAppContextIsOwnPackage() {
        val action = ActionEvent(
            actionId = "a_ext",
            timestamp = 1000L,
            actionType = "CLICK",
            packageName = "com.example.notes",
            semanticSelector = SemanticSelector(role = "Button", text = "New Note")
        )
        val rawTrace = DemonstrationTrace(
            traceId = "tr_norm_disc",
            timestamp = 1000L,
            appContext = "com.chockXlate.teachablevoice", // Corrupted or started/stopped in assistant
            traceEvents = listOf(TraceEvent.Action("a_ext", 1000L, action)),
            userActions = listOf(action)
        )

        val norm = DemonstrationTraceNormalizer.normalize(rawTrace)
        assertEquals("com.example.notes", norm.normalizedTrace.appContext)
    }

    @Test
    fun test11_provenanceGraphLinksExternalPackage() {
        val action = ActionEvent(
            actionId = "a_prov_target",
            timestamp = 1000L,
            actionType = "CLICK",
            packageName = "com.example.targetapp",
            semanticSelector = SemanticSelector(role = "Button", text = "Proceed", resourceId = "com.example.targetapp:id/proceed")
        )
        val trace = DemonstrationTrace(
            traceId = "tr_prov_ext",
            timestamp = 1000L,
            appContext = "com.example.targetapp",
            traceEvents = listOf(TraceEvent.Action("a_prov_target", 1000L, action)),
            userActions = listOf(action)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        val extracted = SemanticActionExtractor.extract(trace, filterRes)

        val synth = WorkflowSynthesizer.synthesize(
            intentResult = dummyIntentResult("proceed_task"),
            semanticActions = extracted,
            slotResult = SlotExtractionResult(intentName = "proceed_task"),
            alignmentResult = dummyAlignmentResult("proceed_task", listOf("tr_prov_ext")),
            inferenceResult = dummyInferenceResult("proceed_task"),
            trace = trace,
            filterResult = filterRes
        )

        assertNotNull(synth.workflow)
        val prov = synth.workflow!!.provenanceGraph
        assertNotNull(prov)
        val link = prov!!.stepLinks.firstOrNull()
        assertNotNull(link)
        assertEquals("com.example.targetapp:id/proceed", link!!.target?.resourceId)
        assertTrue(link.target?.rationale?.contains("com.example.targetapp") == true)
        assertFalse(link.target?.rationale?.contains("com.chockXlate.teachablevoice") == true)
    }

    @Test
    fun test12_actionWithoutPackageResolvesFromMatchingUiEvent() {
        // Accessibility events sometimes create actions where action.packageName wasn't directly stamped,
        // but matching UiEvent carries the package
        val actionWithoutPkg = ActionEvent(
            actionId = "a_no_pkg",
            timestamp = 1000L,
            actionType = "CLICK",
            packageName = null,
            semanticSelector = SemanticSelector(role = "Button", text = "Save")
        )
        val uiEventWithPkg = UiEvent(
            eventId = "a_no_pkg",
            timestamp = 1000L,
            accessibilityEventType = "TYPE_VIEW_CLICKED",
            packageName = "com.example.inferredpkg"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_ui_pkg",
            timestamp = 1000L,
            appContext = "unknown",
            traceEvents = listOf(
                TraceEvent.Ui("a_no_pkg", 1000L, uiEventWithPkg),
                TraceEvent.Action("a_no_pkg", 1000L, actionWithoutPkg)
            ),
            userActions = listOf(actionWithoutPkg)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        val extracted = SemanticActionExtractor.extract(trace, filterRes)

        assertEquals(1, extracted.size)
        assertEquals("com.example.inferredpkg", extracted.first().target?.packageName)
    }

    @Test
    fun test13_externalEditableInputTextWithResourceIdSurvives() {
        // Checklist 1: external editable INPUT_TEXT with resourceId survives
        val action = ActionEvent(
            actionId = "a_input_res",
            timestamp = 1000L,
            actionType = "INPUT_TEXT",
            packageName = "com.google.android.googlequicksearchbox",
            semanticSelector = SemanticSelector(
                role = "EditText",
                resourceId = "com.google.android.googlequicksearchbox:id/search_box_text"
            ),
            inputData = "headphones"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_input_res",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            traceEvents = listOf(TraceEvent.Action("a_input_res", 1000L, action)),
            userActions = listOf(action)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        assertTrue(filterRes.isTaskRelevant("a_input_res"))
        val extracted = SemanticActionExtractor.extract(trace, filterRes)

        assertEquals(1, extracted.size)
        assertEquals("headphones", extracted.first().inputValue)
        assertEquals("com.google.android.googlequicksearchbox:id/search_box_text", extracted.first().target?.resourceId)
        assertEquals("com.google.android.googlequicksearchbox", extracted.first().target?.packageName)
    }

    @Test
    fun test14_externalEditableInputTextWithContentDescriptionSurvives() {
        // Checklist 2: external editable INPUT_TEXT with contentDescription survives
        val action = ActionEvent(
            actionId = "a_input_desc",
            timestamp = 1000L,
            actionType = "INPUT_TEXT",
            packageName = "com.google.android.googlequicksearchbox",
            semanticSelector = SemanticSelector(
                role = "EditText",
                contentDescription = "Search query"
            ),
            inputData = "headphones"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_input_desc",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            traceEvents = listOf(TraceEvent.Action("a_input_desc", 1000L, action)),
            userActions = listOf(action)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        assertTrue(filterRes.isTaskRelevant("a_input_desc"))
        val extracted = SemanticActionExtractor.extract(trace, filterRes)

        assertEquals(1, extracted.size)
        assertEquals("headphones", extracted.first().inputValue)
        assertEquals("Search query", extracted.first().target?.contentDescription)
        assertEquals("com.google.android.googlequicksearchbox", extracted.first().target?.packageName)
    }

    @Test
    fun test15_editableFieldWithoutVisibleTextSurvives() {
        // Checklist 3: editable field without visible text survives
        val action = ActionEvent(
            actionId = "a_input_clean",
            timestamp = 1000L,
            actionType = "INPUT_TEXT",
            packageName = "com.google.android.googlequicksearchbox",
            semanticSelector = SemanticSelector(
                role = "EditText",
                text = null,
                contentDescription = null,
                resourceId = null
            ),
            inputData = "headphones"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_input_clean",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            traceEvents = listOf(TraceEvent.Action("a_input_clean", 1000L, action)),
            userActions = listOf(action)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        assertTrue(filterRes.isTaskRelevant("a_input_clean"))
        val extracted = SemanticActionExtractor.extract(trace, filterRes)

        assertEquals(1, extracted.size)
        assertEquals("headphones", extracted.first().inputValue)
        assertEquals("EditText", extracted.first().target?.role)
        assertEquals("com.google.android.googlequicksearchbox", extracted.first().target?.packageName)
    }

    @Test
    fun test16_inputPackageProvenanceRemainsTargetAppNotIme() {
        // Checklist 4: input package provenance remains target app, not IME
        val actionFromIme = ActionEvent(
            actionId = "a_ime_text",
            timestamp = 1000L,
            actionType = "INPUT_TEXT",
            packageName = "com.google.android.inputmethod.latin", // Keyboard window package
            semanticSelector = SemanticSelector(role = "EditText"),
            inputData = "headphones"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_ime_prov",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox", // Target app
            traceEvents = listOf(TraceEvent.Action("a_ime_text", 1000L, actionFromIme)),
            userActions = listOf(actionFromIme)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        assertTrue("Input action must be task relevant", filterRes.isTaskRelevant("a_ime_text"))
        val extracted = SemanticActionExtractor.extract(trace, filterRes)

        assertEquals(1, extracted.size)
        assertEquals("headphones", extracted.first().inputValue)
        assertEquals("com.google.android.googlequicksearchbox", extracted.first().target?.packageName)
        assertFalse("Package must not be the IME", extracted.first().target?.packageName?.contains("inputmethod") == true)
    }

    @Test
    fun test17_inputPayloadReachesSlotInference() {
        // Checklist 5: input payload reaches slot inference
        val action = ActionEvent(
            actionId = "a_search_slot",
            timestamp = 1000L,
            actionType = "INPUT_TEXT",
            packageName = "com.google.android.googlequicksearchbox",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box"),
            inputData = "headphones"
        )
        val voiceEv = com.chockXlate.teachablevoice.contract.event.VoiceEvent(
            eventId = "v_search",
            timestamp = 900L,
            transcript = "Search for headphones"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_slot_inf",
            timestamp = 900L,
            appContext = "com.google.android.googlequicksearchbox",
            voiceEvents = listOf(voiceEv),
            traceEvents = listOf(
                TraceEvent.Voice("v_search", 900L, voiceEv),
                TraceEvent.Action("a_search_slot", 1000L, action)
            ),
            userActions = listOf(action)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        val extracted = SemanticActionExtractor.extract(trace, filterRes)
        assertEquals(1, extracted.size)

        val intentRes = dummyIntentResult("search_item")
        val slotRes = com.chockXlate.teachablevoice.learning.slots.SlotExtractor.extract(trace, extracted, intentRes)
        assertNotNull(slotRes)
        val headphoneSlot = slotRes.extractedSlots.find { it.rawValue.equals("headphones", ignoreCase = true) }
        assertNotNull("Slot with headphones value must be extracted", headphoneSlot)
    }

    @Test
    fun test18_keyboardClickEventsDoNotBecomeWorkflowActions() {
        // Checklist 6: keyboard click events do not become workflow actions
        val keyClickH = ActionEvent(
            actionId = "a_key_h",
            timestamp = 1000L,
            actionType = "CLICK",
            packageName = "com.google.android.inputmethod.latin",
            semanticSelector = SemanticSelector(role = "View", text = "h")
        )
        val keyClickE = ActionEvent(
            actionId = "a_key_e",
            timestamp = 1100L,
            actionType = "CLICK",
            packageName = "com.google.android.inputmethod.latin",
            semanticSelector = SemanticSelector(role = "View", text = "e")
        )
        val textInput = ActionEvent(
            actionId = "a_text_final",
            timestamp = 1200L,
            actionType = "INPUT_TEXT",
            packageName = "com.google.android.googlequicksearchbox",
            semanticSelector = SemanticSelector(role = "EditText"),
            inputData = "he"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_key_clicks",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            traceEvents = listOf(
                TraceEvent.Action("a_key_h", 1000L, keyClickH),
                TraceEvent.Action("a_key_e", 1100L, keyClickE),
                TraceEvent.Action("a_text_final", 1200L, textInput)
            ),
            userActions = listOf(keyClickH, keyClickE, textInput)
        )

        val filterRes = DemonstrationFilter.filter(trace)
        assertEquals(DemonstrationFilterClassification.NAVIGATION_CONTEXT, filterRes.allEvents.find { it.eventId == "a_key_h" }?.classification)
        assertEquals(DemonstrationFilterClassification.NAVIGATION_CONTEXT, filterRes.allEvents.find { it.eventId == "a_key_e" }?.classification)
        assertTrue(filterRes.isTaskRelevant("a_text_final"))

        val extracted = SemanticActionExtractor.extract(trace, filterRes)
        assertEquals(1, extracted.size)
        assertEquals("a_text_final", extracted.first().actionId)
        assertEquals(SemanticActionType.INPUT_TEXT, extracted.first().actionType)
    }

    @Test
    fun test19_searchLikeExternalPackageIsNotClassifiedAsSystemSolelyByName() {
        // Checklist 11: search-like external package is not classified as system solely by name
        val packages = listOf(
            "com.google.android.googlequicksearchbox",
            "org.mozilla.firefox",
            "com.android.chrome",
            "com.example.searchbox",
            "com.example.browserapp"
        )
        for (pkg in packages) {
            assertFalse("$pkg must not be system surface", DemonstrationFilter.isSystemSurface(pkg))
            assertFalse("$pkg must not be launcher surface", DemonstrationFilter.isLauncherSurface(pkg))
        }
    }

    @Test
    fun test20_credentialInputTextRemainsBlockedOrRedacted() {
        // Checklist 13: credential INPUT_TEXT remains blocked/redacted
        // Sensitive text elements must be detected as sensitive by TeachingPrivacyGuard
        val passwordElement = UiElement(
            elementId = "pwd_elem",
            role = "android.widget.EditText",
            text = "password",
            isEditable = true
        )
        assertTrue(com.chockXlate.teachablevoice.app.service.TeachingPrivacyGuard.isSensitiveTargetElement(passwordElement))
    }

    @Test
    fun test21_safetyGateUntouchedAndFunctional() {
        // Checklist 14: SafetyGate untouched
        val gate = com.chockXlate.teachablevoice.safety.SafetyGate()
        assertEquals(null, gate.reason())
        gate.block("Emergency stop triggered")
        assertEquals("Emergency stop triggered", gate.reason())
    }

    @Test
    fun test22_searchItemWorkflowInGoogleQuickSearchBox() {
        // Physical reproduction: 10 keystrokes of 'headphones' in Google search box
        val letters = listOf("h", "he", "hea", "head", "headp", "headph", "headpho", "headphon", "headphone", "headphones")
        val actionEvents = letters.mapIndexed { idx, prefix ->
            ActionEvent(
                actionId = "act_type_$idx",
                timestamp = 1000L + (idx * 100L),
                actionType = "INPUT_TEXT",
                packageName = "com.google.android.googlequicksearchbox",
                semanticSelector = SemanticSelector(role = "EditText"),
                inputData = prefix
            )
        }
        val traceEvents = actionEvents.map { TraceEvent.Action(it.actionId, it.timestamp, it) }
        val trace = DemonstrationTrace(
            traceId = "tr_search_headphones",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            traceEvents = traceEvents,
            userActions = actionEvents
        )

        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val filterRes = DemonstrationFilter.filter(norm.normalizedTrace)
        val extracted = SemanticActionExtractor.extract(norm.normalizedTrace, filterRes)

        // All 10 contiguous keystrokes on the same search field collapse into exactly 1 final action
        assertEquals(1, extracted.size)
        val finalAction = extracted.first()
        assertEquals(SemanticActionType.INPUT_TEXT, finalAction.actionType)
        assertEquals("headphones", finalAction.inputValue)
        assertEquals("com.google.android.googlequicksearchbox", finalAction.target?.packageName)

        // Synthesize workflow
        val synth = WorkflowSynthesizer.synthesize(
            intentResult = dummyIntentResult("search_headphones"),
            semanticActions = extracted,
            slotResult = SlotExtractionResult(intentName = "search_headphones"),
            alignmentResult = dummyAlignmentResult("search_headphones", listOf("tr_search_headphones")),
            inferenceResult = dummyInferenceResult("search_headphones"),
            trace = norm.normalizedTrace,
            filterResult = filterRes
        )

        assertNotNull(synth.workflow)
        val wf = synth.workflow!!
        assertEquals("com.google.android.googlequicksearchbox", wf.appContext)
        assertEquals(1, wf.steps.size)
        assertEquals("com.google.android.googlequicksearchbox", wf.steps[0].preconditions.requiredPackage)
        assertEquals("INPUT_TEXT", wf.steps[0].semanticAction)
        assertEquals("headphones", wf.steps[0].parameters["input_literal"])
    }

    @Test
    fun test23_searchWorkflowDemonstrationSynthesizesExecutableWorkflowWithItemSlot() {
        val voiceEv = VoiceEvent(eventId = "v_search", timestamp = 1000L, transcript = "Search for headphones")
        val inputAction = ActionEvent(
            actionId = "a_type_search",
            timestamp = 1100L,
            actionType = "INPUT_TEXT",
            packageName = "com.google.android.googlequicksearchbox",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box"),
            inputData = "headphones"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_search_flow",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            voiceEvents = listOf(voiceEv),
            traceEvents = listOf(
                TraceEvent.Voice("v_search", 1000L, voiceEv),
                TraceEvent.Action("a_type_search", 1100L, inputAction)
            ),
            userActions = listOf(inputAction)
        )

        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val filterRes = DemonstrationFilter.filter(norm.normalizedTrace)
        val actions = SemanticActionExtractor.extract(norm.normalizedTrace, filterRes)
        val intentRes = IntentExtractor.extract(norm.normalizedTrace, actions)
        assertEquals("search_information", intentRes.intent.canonicalName)

        val slotRes = SlotExtractor.extract(norm.normalizedTrace, actions, intentRes)
        assertEquals(1, slotRes.extractedSlots.size)
        assertEquals("item", slotRes.extractedSlots.single().name)
        assertEquals("headphones", slotRes.extractedSlots.single().rawValue)

        val demoDataset = DemonstrationDataset(trace.traceId, trace.traceId, intentRes, slotRes)
        val alignment = DemonstrationAlignment.align(listOf(demoDataset))
        val initialInference = ConstantVariableInference.infer(alignment)
        val confirmedInference = SingleDemonstrationConfirmation.confirm(initialInference, setOf("item"))

        val synthRes = WorkflowSynthesizer.synthesize(
            intentResult = intentRes,
            semanticActions = actions,
            slotResult = slotRes,
            alignmentResult = alignment,
            inferenceResult = confirmedInference,
            trace = norm.normalizedTrace,
            filterResult = filterRes
        )

        assertTrue(synthRes.diagnostics.toString(), synthRes.isExecutable)
        assertEquals(SynthesisStatus.VALID, synthRes.status)
        assertNotNull(synthRes.workflow)
        val wf = synthRes.workflow!!
        assertEquals("search_information", wf.intent)
        assertEquals(1, wf.slots.size)
        assertEquals("item", wf.slots.single().name)
        assertTrue(wf.slots.single().required)
    }

    @Test
    fun test24_workflowIrStepParameterReferencesItem() {
        val voiceEv = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Search for headphones")
        val inputAction = ActionEvent(
            actionId = "a1",
            timestamp = 1100L,
            actionType = "INPUT_TEXT",
            packageName = "com.google.android.googlequicksearchbox",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box"),
            inputData = "headphones"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_step_param",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            voiceEvents = listOf(voiceEv),
            traceEvents = listOf(
                TraceEvent.Voice("v1", 1000L, voiceEv),
                TraceEvent.Action("a1", 1100L, inputAction)
            ),
            userActions = listOf(inputAction)
        )
        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val actions = SemanticActionExtractor.extract(norm.normalizedTrace)
        val intentRes = IntentExtractor.extract(norm.normalizedTrace, actions)
        val slotRes = SlotExtractor.extract(norm.normalizedTrace, actions, intentRes)
        val alignment = DemonstrationAlignment.align(listOf(DemonstrationDataset(trace.traceId, trace.traceId, intentRes, slotRes)))
        val inference = SingleDemonstrationConfirmation.confirm(ConstantVariableInference.infer(alignment), setOf("item"))
        val synthRes = WorkflowSynthesizer.synthesize(intentRes, actions, slotRes, alignment, inference, norm.normalizedTrace)

        val wf = synthRes.workflow!!
        val step = wf.steps.single()
        assertEquals("\${item}", step.parameters["input_parameter"])
        assertEquals("\${item}", step.semanticSelector.textSlot)
        assertNull(step.semanticSelector.text)
        assertNull(step.parameters["input_literal"])
        assertFalse("Step must not reference input_text", step.parameters.values.any { it.contains("input_text") })
    }

    @Test
    fun test25_replayAdmissionValidatesWithoutErrors() {
        val voiceEv = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Search for headphones")
        val inputAction = ActionEvent(
            actionId = "a1",
            timestamp = 1100L,
            actionType = "INPUT_TEXT",
            packageName = "com.google.android.googlequicksearchbox",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box"),
            inputData = "headphones"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_adm",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            voiceEvents = listOf(voiceEv),
            traceEvents = listOf(
                TraceEvent.Voice("v1", 1000L, voiceEv),
                TraceEvent.Action("a1", 1100L, inputAction)
            ),
            userActions = listOf(inputAction)
        )
        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val actions = SemanticActionExtractor.extract(norm.normalizedTrace)
        val intentRes = IntentExtractor.extract(norm.normalizedTrace, actions)
        val slotRes = SlotExtractor.extract(norm.normalizedTrace, actions, intentRes)
        val alignment = DemonstrationAlignment.align(listOf(DemonstrationDataset(trace.traceId, trace.traceId, intentRes, slotRes)))
        val inference = SingleDemonstrationConfirmation.confirm(ConstantVariableInference.infer(alignment), setOf("item"))
        val synthRes = WorkflowSynthesizer.synthesize(intentRes, actions, slotRes, alignment, inference, norm.normalizedTrace)
        val wf = synthRes.workflow!!

        val problems = ReplayAdmission.problems(wf)
        assertTrue("ReplayAdmission problems must be empty but got: $problems", problems.isEmpty())

        val validation = ReplayAdmission.validate(wf)
        assertTrue(validation.isStoreable)
        assertEquals(ValidationStatus.VALID, validation.status)
    }

    @Test
    fun test26_searchForPhoneCaseBindsNewQuery() {
        val voiceEv = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Search for headphones")
        val inputAction = ActionEvent(
            actionId = "a1",
            timestamp = 1100L,
            actionType = "INPUT_TEXT",
            packageName = "com.google.android.googlequicksearchbox",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box"),
            inputData = "headphones"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_bind",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            voiceEvents = listOf(voiceEv),
            traceEvents = listOf(
                TraceEvent.Voice("v1", 1000L, voiceEv),
                TraceEvent.Action("a1", 1100L, inputAction)
            ),
            userActions = listOf(inputAction)
        )
        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val actions = SemanticActionExtractor.extract(norm.normalizedTrace)
        val intentRes = IntentExtractor.extract(norm.normalizedTrace, actions)
        val slotRes = SlotExtractor.extract(norm.normalizedTrace, actions, intentRes)
        val alignment = DemonstrationAlignment.align(listOf(DemonstrationDataset(trace.traceId, trace.traceId, intentRes, slotRes)))
        val inference = SingleDemonstrationConfirmation.confirm(ConstantVariableInference.infer(alignment), setOf("item"))
        val synthRes = WorkflowSynthesizer.synthesize(intentRes, actions, slotRes, alignment, inference, norm.normalizedTrace)
        val wf = synthRes.workflow!!

        val repo = LocalSkillRepository()
        assertTrue(repo.saveReplayableWorkflow(wf))

        // New command "Search for phone case"
        val cmd = "Search for phone case"
        val understood = CommandInterpreter.understandCommand(cmd)
        assertEquals("search_information", understood.intent.canonicalName)
        assertEquals(1, understood.slots.size)
        assertEquals("item", understood.slots.single().name)
        assertEquals("phone case", understood.slots.single().rawValue)

        val match = SkillMatcher(repo).match(understood)
        assertEquals(com.chockXlate.teachablevoice.command.matching.SkillMatchStatus.MATCHED, match.status)

        val buildResult = ExecutionRequestBuilder.build(understood, match, repo)
        assertEquals(ExecutionRequestStatus.READY_FOR_PERSON_2, buildResult.status)
        assertNotNull(buildResult.executionRequest)
        assertEquals("phone case", buildResult.executionRequest!!.boundSlots["item"])

        val bound = SlotBinder.bind(wf, buildResult.executionRequest!!.boundSlots).steps.single()
        assertEquals("phone case", bound.inputText)
    }

    @Test
    fun test27_exactReplayAndSupportedParaphrasesParseCanonically() {
        val testPhrases = listOf(
            "Search for headphones" to "headphones",
            "Search headphones" to "headphones",
            "Find headphones" to "headphones",
            "Look up headphones" to "headphones",
            "Lookup headphones" to "headphones",
            "search for phone case" to "phone case",
            "find wireless charger" to "wireless charger"
        )
        for ((phrase, expectedQuery) in testPhrases) {
            val understood = CommandInterpreter.understandCommand(phrase)
            assertEquals("Phrase '$phrase' must map to search_information", "search_information", understood.intent.canonicalName)
            val itemSlot = understood.slots.find { it.name == "item" }
            assertNotNull("Phrase '$phrase' must extract slot 'item'", itemSlot)
            assertEquals("Phrase '$phrase' must extract '$expectedQuery'", expectedQuery, itemSlot?.rawValue)
        }
    }

    @Test
    fun test28_unsupportedOrAmbiguousVariablesRemainFailClosed() {
        val badWorkflow = Workflow(
            skillId = "skill_bad_slot",
            name = "Search Workflow",
            intent = "search_information",
            appContext = "com.google.android.googlequicksearchbox",
            slots = listOf(WorkflowSlot(name = "input_text", type = SlotType.TEXT, required = true, exampleValue = "headphones")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", textSlot = "\${input_text}"),
                    parameters = mapOf("input_parameter" to "\${input_text}")
                )
            )
        )
        val problems = ReplayAdmission.problems(badWorkflow)
        assertTrue("ReplayAdmission must reject input_text", problems.any { it.contains("input_text") })
        assertFalse(ReplayAdmission.validate(badWorkflow).isStoreable)

        val repo = LocalSkillRepository()
        assertFalse("Repository must reject unreplayable workflow", repo.saveReplayableWorkflow(badWorkflow))
    }

    @Test
    fun test29_multiSlotWorkflowPreservesContractsWithoutRegressing() {
        val cmd = "Order 2 pizzas from Domino's to 100 Tech Park"
        val understood = CommandInterpreter.understandCommand(cmd)
        assertEquals("order_food", understood.intent.canonicalName)
        assertEquals(4, understood.slots.size)
        assertEquals("2", understood.slots.find { it.name == "quantity" }?.rawValue)
        assertEquals("Pizza", understood.slots.find { it.name == "item" }?.rawValue)
        assertEquals("Domino's", understood.slots.find { it.name == "restaurant" }?.rawValue)
        assertEquals("100 Tech Park", understood.slots.find { it.name == "address" }?.rawValue)

        val bindable = SemanticCommandPolicy.bindableSlots("order_food")
        assertEquals(setOf("item", "restaurant", "quantity", "address"), bindable)
    }

    @Test
    fun test30_searchItemAliasNormalizedToSearchInformation() {
        assertEquals("search_information", SemanticCommandPolicy.canonicalizeIntent("search_item"))
        assertEquals(setOf("item"), SemanticCommandPolicy.bindableSlots("search_item"))
        assertEquals(listOf("item"), SemanticCommandPolicy.EXPECTED_CANONICAL_SLOTS["search_information"])

        // Workflow created with search_item intent matches search_information command
        val wf = Workflow(
            skillId = "skill_alias",
            name = "Search",
            intent = "search_item",
            appContext = "com.example.search",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "test")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.search:id/box", textSlot = "\${item}"),
                    parameters = mapOf("input_parameter" to "\${item}")
                )
            )
        )
        val repo = LocalSkillRepository()
        repo.saveWorkflow(wf)

        val cmdResult = CommandInterpreter.understandCommand("Search for shoes")
        val match = SkillMatcher(repo).match(cmdResult)
        assertEquals(com.chockXlate.teachablevoice.command.matching.SkillMatchStatus.MATCHED, match.status)
    }

    @Test
    fun test31_cleanEditTextInSearchAppWithoutVoiceExtractsItemSlot() {
        // Physical device condition: No voice recording, clean EditText without 'search' in id/text
        val inputAction = ActionEvent(
            actionId = "a_clean_box",
            timestamp = 1000L,
            actionType = "INPUT_TEXT",
            packageName = "com.google.android.googlequicksearchbox",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/text_edit"),
            inputData = "headphones"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_clean_search",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            voiceEvents = emptyList(), // No voice recorded
            traceEvents = listOf(TraceEvent.Action("a_clean_box", 1000L, inputAction)),
            userActions = listOf(inputAction)
        )
        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val filterRes = DemonstrationFilter.filter(norm.normalizedTrace)
        val actions = SemanticActionExtractor.extract(norm.normalizedTrace, filterRes)
        val intentRes = IntentExtractor.extract(norm.normalizedTrace, actions)
        assertEquals("search_information", intentRes.intent.canonicalName)

        val slotRes = SlotExtractor.extract(norm.normalizedTrace, actions, intentRes)
        assertEquals("Item slot must be extracted", 1, slotRes.extractedSlots.size)
        assertEquals("item", slotRes.extractedSlots.single().name)
        assertEquals("headphones", slotRes.extractedSlots.single().rawValue)

        val alignment = DemonstrationAlignment.align(listOf(DemonstrationDataset(trace.traceId, trace.traceId, intentRes, slotRes)))
        val inference = SingleDemonstrationConfirmation.confirm(ConstantVariableInference.infer(alignment), setOf("item"))
        val synthRes = WorkflowSynthesizer.synthesize(intentRes, actions, slotRes, alignment, inference, norm.normalizedTrace)

        assertTrue(synthRes.diagnostics.toString(), synthRes.isExecutable)
        assertEquals(SynthesisStatus.VALID, synthRes.status)
        assertEquals("item", synthRes.workflow!!.slots.single().name)
        assertEquals("\${item}", synthRes.workflow!!.steps.single().parameters["input_parameter"])
    }

    @Test
    fun test32_searchWorkflowVoiceUtteranceWithPunctuationExtractsCleanQuery() {
        val understood = CommandInterpreter.understandCommand("Search for headphones.")
        assertEquals("search_information", understood.intent.canonicalName)
        assertEquals("headphones", understood.slots.single { it.name == "item" }.rawValue)
    }

    @Test
    fun test33_conflictingSpokenSearchCommandFallsBackFailClosed() {
        // Spoken command said "Search tea", but typed "different" in a generic field
        val voiceEv = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Search tea")
        val inputAction = ActionEvent(
            actionId = "a1",
            timestamp = 1100L,
            actionType = "INPUT_TEXT",
            packageName = "com.example.plain",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/plain"),
            inputData = "different"
        )
        val trace = DemonstrationTrace(
            traceId = "tr_conflicting_search",
            timestamp = 1000L,
            appContext = "com.example.plain",
            voiceEvents = listOf(voiceEv),
            traceEvents = listOf(
                TraceEvent.Voice("v1", 1000L, voiceEv),
                TraceEvent.Action("a1", 1100L, inputAction)
            ),
            userActions = listOf(inputAction)
        )
        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val actions = SemanticActionExtractor.extract(norm.normalizedTrace)
        val intentRes = IntentExtractor.extract(norm.normalizedTrace, actions)
        val slotRes = SlotExtractor.extract(norm.normalizedTrace, actions, intentRes)

        // The action input does not match spoken search query and target has no search keywords -> falls back to input_text
        val actionSlot = slotRes.extractedSlots.find { it.rawValue == "different" }
        assertEquals("input_text", actionSlot?.name)
    }

    @Test
    fun test34_safetyGateRemainsUntouched() {
        val gate = com.chockXlate.teachablevoice.safety.SafetyGate()
        assertNull(gate.reason())
        gate.block("Emergency stop triggered")
        assertEquals("Emergency stop triggered", gate.reason())
    }
}
