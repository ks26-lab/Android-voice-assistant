package com.chockXlate.teachablevoice.learning.synthesis

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.learning.actions.SemanticActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ActionExtractionTest {

    @Test
    fun `test action extraction for Search Google for Tabla`() {
        // Setup: Mocking a demonstration trace for "Search Google for Tabla"
        
        // 1. OPEN Google (This is often implicitly modeled as the context or the first action)
        // 2. TYPE "Tabla" into search field
        // 3. CLICK search

        val searchFieldSelector = SemanticSelector(
            schemaVersion = "1.0",
            role = "EditText",
            contentDescription = "Search field"
        )
        
        val searchButtonSelector = SemanticSelector(
            schemaVersion = "1.0",
            role = "Button",
            text = "Search"
        )

        val action1 = ActionEvent(
            actionId = "evt_1",
            timestamp = 1000L,
            actionType = "INPUT_TEXT",
            semanticSelector = searchFieldSelector,
            inputData = "Tabla",
            packageName = "com.google.android.googlequicksearchbox"
        )
        
        val action2 = ActionEvent(
            actionId = "evt_2",
            timestamp = 2000L,
            actionType = "CLICK",
            semanticSelector = searchButtonSelector,
            packageName = "com.google.android.googlequicksearchbox"
        )

        val trace = DemonstrationTrace(
            traceId = "trace_test_1",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            userActions = listOf(action1, action2),
            traceEvents = listOf(
                TraceEvent.Action(action1),
                TraceEvent.Action(action2)
            ),
            voiceEvents = listOf(
                com.chockXlate.teachablevoice.contract.event.VoiceEvent(
                    eventId = "v_1",
                    timestamp = 500L,
                    transcript = "Search Google for Tabla",
                    isFinal = true
                )
            ),
            stateEvents = emptyList()
        )

        // Execution
        val result = WorkflowSynthesizer.synthesizeFromTrace(
            trace = trace,
            targetSkillName = "search_google",
            targetSkillDescription = "Search Google for Tabla"
        )

        // Verification
        assertNotNull("Workflow should not be null", result.workflow)
        val workflow = result.workflow!!
        
        println("Synthesized workflow intent: ${workflow.intent}")
        assertEquals("search_google", workflow.intent)

        // Verify Slots
        val slot = workflow.slots.find { it.name == "search_query" || it.exampleValue == "Tabla" }
        assertNotNull("Should have identified a slot for 'Tabla'", slot)
        assertEquals("Tabla", slot?.exampleValue)

        // Verify Actions
        val steps = workflow.steps
        
        // We expect at least SET_TEXT and SEARCH
        val setTextStep = steps.find { it.semanticAction == "SET_TEXT" }
        assertNotNull("Should have SET_TEXT action", setTextStep)
        assertEquals("Search field", setTextStep?.semanticSelector?.contentDescription)
        assertEquals("\${${slot?.name}}", setTextStep?.parameters?.get("input_parameter"))

        val tapStep = steps.find { it.semanticAction == "SEARCH" }
        assertNotNull("Should have SEARCH action", tapStep)
        assertEquals("Search", tapStep?.semanticSelector?.text)
    }
}
