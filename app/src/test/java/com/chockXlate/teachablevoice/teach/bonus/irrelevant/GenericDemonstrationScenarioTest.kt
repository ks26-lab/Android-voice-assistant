package com.chockXlate.teachablevoice.teach.bonus.irrelevant

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import org.junit.Assert.*
import org.junit.Test

class GenericDemonstrationScenarioTest {

    private fun createDummyState(id: String, appPkg: String = "com.example.genericapp"): UiState {
        return UiState(
            schemaVersion = "1.0",
            stateId = id,
            timestamp = System.currentTimeMillis(),
            appContext = appPkg,
            windowId = 1,
            rootElement = com.chockXlate.teachablevoice.contract.ui.UiElement(
                elementId = "root",
                role = "FrameLayout"
            ),
            allElements = emptyList()
        )
    }

    private fun createActionEvent(
        actionId: String,
        timestamp: Long,
        actionType: String,
        role: String?,
        text: String?,
        resourceId: String?,
        inputData: String? = null
    ): ActionEvent {
        return ActionEvent(
            schemaVersion = "1.0",
            actionId = actionId,
            timestamp = timestamp,
            actionType = actionType,
            semanticSelector = SemanticSelector(
                schemaVersion = "1.0",
                role = role,
                text = text,
                resourceId = resourceId
            ),
            inputData = inputData,
            packageName = "com.example.genericapp"
        )
    }

    @Test
    fun testGenericDemonstrationScenario_searchItemWithAccidentalInteractions() {
        val t0 = 1000L

        // Voice utterance
        val voice = VoiceEvent("v1", t0, "Search for an item")

        // 1. Opens search
        val openSearchAction = createActionEvent("a1_open", t0 + 100L, "CLICK", "Button", "Search", "com.example.genericapp:id/btn_open_search")
        val stateEv1 = StateEvent("s1", t0 + 110L, createDummyState("s_home"), createDummyState("s_search_screen"), "a1_open")

        // 2. Accidentally scrolls
        val accidentalScroll = createActionEvent("a2_scroll", t0 + 200L, "SCROLL", "ScrollView", null, "com.example.genericapp:id/scroll_view")

        // 3. Taps intended search field
        val tapSearchField = createActionEvent("a3_tap_field", t0 + 300L, "CLICK", "EditText", "Search field", "com.example.genericapp:id/search_input")

        // 4. Types the item
        val typeItem = createActionEvent("a4_type", t0 + 400L, "INPUT_TEXT", "EditText", "Wireless Headphones", "com.example.genericapp:id/search_input", inputData = "Wireless Headphones")

        // 5. Accidentally taps/focuses elsewhere (transient focus on blank container)
        val accidentalFocus = createActionEvent("a5_noise_focus", t0 + 500L, "FOCUS", "View", null, null)

        // 6. Returns to workflow & 7. Performs selection
        val selectItem = createActionEvent("a6_select", t0 + 600L, "CLICK", "TextView", "Wireless Headphones Pro", "com.example.genericapp:id/item_title")
        val stateEv2 = StateEvent("s2", t0 + 610L, createDummyState("s_search_screen"), createDummyState("s_detail_screen"), "a6_select")

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "scenario_search_item",
            sessionId = "session_generic_1",
            appContext = "com.example.genericapp",
            startTime = t0,
            endTime = t0 + 700L,
            traceEvents = listOf(
                TraceEvent.Voice("v1", t0, voice),
                TraceEvent.Action("a1_open", t0 + 100L, openSearchAction),
                TraceEvent.State("s1", t0 + 110L, stateEv1),
                TraceEvent.Action("a2_scroll", t0 + 200L, accidentalScroll),
                TraceEvent.Action("a3_tap_field", t0 + 300L, tapSearchField),
                TraceEvent.Action("a4_type", t0 + 400L, typeItem),
                TraceEvent.Action("a5_noise_focus", t0 + 500L, accidentalFocus),
                TraceEvent.Action("a6_select", t0 + 600L, selectItem),
                TraceEvent.State("s2", t0 + 610L, stateEv2)
            ),
            userActions = listOf(openSearchAction, accidentalScroll, tapSearchField, typeItem, accidentalFocus, selectItem),
            voiceEvents = listOf(voice),
            stateEvents = listOf(stateEv1, stateEv2)
        )

        val result = BonusTeachingFilter.filter(trace)

        // Verify filtering results:
        // - Voice, openSearch, stateEv1, accidentalScroll (retained under conservative policy), tapSearchField, typeItem, selectItem, stateEv2 retained.
        // - Transient focus noise 'a5_noise_focus' filtered out!
        assertTrue(result.removedEvents.any { it.eventId == "a5_noise_focus" })
        assertFalse(result.filteredTrace.userActions.any { it.actionId == "a5_noise_focus" })

        // Verify meaningful task sequence remains intact in exact order
        val retainedActionIds = result.filteredTrace.userActions.map { it.actionId }
        assertTrue(retainedActionIds.containsAll(listOf("a1_open", "a3_tap_field", "a4_type", "a6_select")))
    }
}
