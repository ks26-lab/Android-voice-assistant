package com.chockXlate.teachablevoice.teach

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.filter.DemonstrationFilterClassification
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.teach.filter.DemonstrationFilter
import org.junit.Assert.*
import org.junit.Test

class DemonstrationFilterTest {

    @Test
    fun testLauncherNavigationBeforeTargetTaskIsClassifiedAsNavigationContext() {
        val launcherAction = ActionEvent(
            actionId = "act_launcher_1",
            timestamp = 1000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(
                role = "TextView",
                text = "Target App",
                resourceId = "com.google.android.apps.nexuslauncher:id/icon"
            )
        )
        val targetAction = ActionEvent(
            actionId = "act_target_1",
            timestamp = 2000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(
                role = "Button",
                text = "Order Now",
                resourceId = "com.example.foodapp:id/btn_order"
            )
        )

        val stateEv = StateEvent(
            stateEventId = "state_1",
            timestamp = 2050L,
            causeActionId = "act_target_1",
            beforeState = UiState(stateId = "state_before", timestamp = 2000L, appContext = "com.example.foodapp"),
            afterState = UiState(stateId = "state_after", timestamp = 2050L, appContext = "com.example.foodapp")
        )

        val trace = DemonstrationTrace(
            traceId = "demo_launcher_test",
            timestamp = 1000L,
            appContext = "com.example.foodapp",
            traceEvents = listOf(
                TraceEvent.Action("act_launcher_1", 1000L, launcherAction),
                TraceEvent.Action("act_target_1", 2000L, targetAction),
                TraceEvent.State("state_1", 2050L, stateEv)
            )
        )

        val result = DemonstrationFilter.filter(trace)

        assertTrue(result.navigationContextEventIds.contains("act_launcher_1"))
        assertTrue(result.taskRelevantEventIds.contains("act_target_1"))

        // When extracted, only task-relevant actions are extracted
        val extracted = SemanticActionExtractor.extract(trace, result)
        assertEquals(1, extracted.size)
        assertEquals("act_target_1", extracted[0].actionId)
    }

    @Test
    fun testSystemUiEventBetweenTwoTaskActionsIsClassifiedAsSystemNoise() {
        val targetAction1 = ActionEvent(
            actionId = "act_1",
            timestamp = 1000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Start", resourceId = "com.example.app:id/start")
        )
        val systemUiAction = ActionEvent(
            actionId = "act_system_notification",
            timestamp = 2000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "TextView", text = "Notification", resourceId = "com.android.systemui:id/notif")
        )
        val targetAction2 = ActionEvent(
            actionId = "act_2",
            timestamp = 3000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Next", resourceId = "com.example.app:id/next")
        )

        val trace = DemonstrationTrace(
            traceId = "demo_system_ui",
            timestamp = 1000L,
            appContext = "com.example.app",
            traceEvents = listOf(
                TraceEvent.Action("act_1", 1000L, targetAction1),
                TraceEvent.Action("act_system_notification", 2000L, systemUiAction),
                TraceEvent.Action("act_2", 3000L, targetAction2)
            )
        )

        val result = DemonstrationFilter.filter(trace)
        assertTrue(result.systemNoiseEventIds.contains("act_system_notification"))
        assertTrue(result.taskRelevantEventIds.contains("act_1"))
        assertTrue(result.taskRelevantEventIds.contains("act_2"))

        val extracted = SemanticActionExtractor.extract(trace, result)
        assertEquals(2, extracted.size)
        assertEquals("act_1", extracted[0].actionId)
        assertEquals("act_2", extracted[1].actionId)
    }

    @Test
    fun testKeyboardEventWithoutInputDataIsClassifiedAsNavigationContext() {
        val keyboardAction = ActionEvent(
            actionId = "act_ime_open",
            timestamp = 1500L,
            actionType = "FOCUS",
            semanticSelector = SemanticSelector(role = "InputView", resourceId = "com.google.android.inputmethod.latin:id/keyboard")
        )
        val inputAction = ActionEvent(
            actionId = "act_real_input",
            timestamp = 2000L,
            actionType = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.app:id/search_box"),
            inputData = "Pizza"
        )

        val trace = DemonstrationTrace(
            traceId = "demo_keyboard",
            timestamp = 1500L,
            appContext = "com.example.app",
            traceEvents = listOf(
                TraceEvent.Action("act_ime_open", 1500L, keyboardAction),
                TraceEvent.Action("act_real_input", 2000L, inputAction)
            )
        )

        val result = DemonstrationFilter.filter(trace)
        assertTrue(result.navigationContextEventIds.contains("act_ime_open"))
        assertTrue(result.taskRelevantEventIds.contains("act_real_input"))
    }

    @Test
    fun testUnrelatedContentChangeIsClassifiedAsSystemNoise() {
        val uiNoise = UiEvent(
            schemaVersion = "1.0",
            eventId = "ui_noise_1",
            timestamp = 1200L,
            accessibilityEventType = "TYPE_WINDOW_CONTENT_CHANGED",
            packageName = "com.example.app",
            targetElement = null
        )

        val trace = DemonstrationTrace(
            traceId = "demo_content_noise",
            timestamp = 1000L,
            appContext = "com.example.app",
            traceEvents = listOf(
                TraceEvent.Ui("ui_noise_1", 1200L, uiNoise)
            )
        )

        val result = DemonstrationFilter.filter(trace)
        assertTrue(result.systemNoiseEventIds.contains("ui_noise_1"))
    }

    @Test
    fun testLegitimateCrossPackageTransitionRetainsContinuity() {
        val actionInAppA = ActionEvent(
            actionId = "act_app_a",
            timestamp = 1000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Pick Location", resourceId = "com.example.mainapp:id/loc")
        )
        val stateEv1 = StateEvent(
            stateEventId = "st_1",
            timestamp = 1100L,
            causeActionId = "act_app_a",
            beforeState = UiState(stateId = "st_a", timestamp = 1000L, appContext = "com.example.mainapp"),
            afterState = UiState(stateId = "st_b", timestamp = 1100L, appContext = "com.example.mapchooser")
        )
        val actionInAppB = ActionEvent(
            actionId = "act_app_b",
            timestamp = 2000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "TextView", text = "Downtown", resourceId = "com.example.mapchooser:id/pin")
        )
        val stateEv2 = StateEvent(
            stateEventId = "st_2",
            timestamp = 2100L,
            causeActionId = "act_app_b",
            beforeState = UiState(stateId = "st_b", timestamp = 2000L, appContext = "com.example.mapchooser"),
            afterState = UiState(stateId = "st_c", timestamp = 2100L, appContext = "com.example.mainapp")
        )

        val trace = DemonstrationTrace(
            traceId = "demo_cross_pkg",
            timestamp = 1000L,
            appContext = "com.example.mainapp",
            traceEvents = listOf(
                TraceEvent.Action("act_app_a", 1000L, actionInAppA),
                TraceEvent.State("st_1", 1100L, stateEv1),
                TraceEvent.Action("act_app_b", 2000L, actionInAppB),
                TraceEvent.State("st_2", 2100L, stateEv2)
            ),
            stateEvents = listOf(stateEv1, stateEv2)
        )

        val result = DemonstrationFilter.filter(trace)
        assertTrue(result.taskRelevantEventIds.contains("act_app_a"))
        assertTrue(result.taskRelevantEventIds.contains("act_app_b"))
        val extracted = SemanticActionExtractor.extract(trace, result)
        assertEquals(2, extracted.size)
        assertEquals("act_app_a", extracted[0].actionId)
        assertEquals("act_app_b", extracted[1].actionId)
    }

    @Test
    fun testUncertainEventPreservation() {
        val emptyAction = ActionEvent(
            actionId = "act_uncertain_blank",
            timestamp = 1000L,
            actionType = "",
            semanticSelector = SemanticSelector() // completely blank
        )

        val trace = DemonstrationTrace(
            traceId = "demo_uncertain",
            timestamp = 1000L,
            appContext = "com.example.app",
            traceEvents = listOf(
                TraceEvent.Action("act_uncertain_blank", 1000L, emptyAction)
            )
        )

        val result = DemonstrationFilter.filter(trace)
        assertTrue(result.uncertainEventIds.contains("act_uncertain_blank"))

        // Uncertain events must NOT become executable actions
        val extracted = SemanticActionExtractor.extract(trace, result)
        assertTrue(extracted.isEmpty())

        // But uncertain event is preserved in result for provenance
        val ev = result.allEvents.find { it.eventId == "act_uncertain_blank" }
        assertNotNull(ev)
        assertEquals(DemonstrationFilterClassification.UNCERTAIN, ev!!.classification)
        assertFalse(ev.reason.isBlank())
    }
}
