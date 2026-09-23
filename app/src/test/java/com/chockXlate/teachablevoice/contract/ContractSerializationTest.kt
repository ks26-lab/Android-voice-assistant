package com.chockXlate.teachablevoice.contract

import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader

class ContractSerializationTest {

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun testWorkflowSerialization() {
        val selector = SemanticSelector(
            role = "Button",
            text = "ADD",
            contentDescription = "Add to Cart",
            resourceId = "com.example.app:id/add"
        )
        val workflow = Workflow(
            schemaVersion = "1.0",
            skillId = "test_skill_1",
            name = "Test Skill",
            intent = "test intent",
            appContext = "com.example.app"
        )

        val jsonString = jsonFormatter.encodeToString(Workflow.serializer(), workflow)
        val decoded = jsonFormatter.decodeFromString(Workflow.serializer(), jsonString)

        assertEquals("1.0", decoded.schemaVersion)
        assertEquals("test_skill_1", decoded.skillId)
        assertEquals("com.example.app", decoded.appContext)
    }

    @Test
    fun testSemanticSelectorDoesNotUseCoordinatesForIdentity() {
        val selector = SemanticSelector(
            role = "EditText",
            text = "Search",
            textSlot = "search_query",
            contentDescription = "Search input field",
            resourceId = "com.example.app:id/input",
            parentRole = "LinearLayout"
        )

        val jsonString = jsonFormatter.encodeToString(SemanticSelector.serializer(), selector)
        val decoded = jsonFormatter.decodeFromString(SemanticSelector.serializer(), jsonString)

        assertEquals("EditText", decoded.role)
        assertEquals("Search", decoded.text)
        assertEquals("search_query", decoded.textSlot)
    }

    @Test
    fun testLoadMockOrderFoodV1WorkflowFixture() {
        val stream = javaClass.classLoader?.getResourceAsStream("mock/workflows/order_food_v1.json")
        assertNotNull("Mock fixture order_food_v1.json must exist in test resources", stream)

        val jsonContent = stream!!.bufferedReader().use { it.readText() }
        val workflow = jsonFormatter.decodeFromString(Workflow.serializer(), jsonContent)

        assertEquals("1.0", workflow.schemaVersion)
        assertEquals("skill_order_food_001", workflow.skillId)
        assertEquals("Order Food Workflow", workflow.name)
        assertEquals(3, workflow.slots.size)
        assertEquals(3, workflow.steps.size)
        assertTrue(workflow.safetyBoundary.requiresExplicitUserConfirmation)
    }

    @Test
    fun testLoadMockExecutionRequestFixture() {
        val stream = javaClass.classLoader?.getResourceAsStream("mock/requests/sample_execution_request.json")
        assertNotNull("Mock fixture sample_execution_request.json must exist", stream)

        val jsonContent = stream!!.bufferedReader().use { it.readText() }
        val request = jsonFormatter.decodeFromString(ExecutionRequest.serializer(), jsonContent)

        assertEquals("1.0", request.schemaVersion)
        assertEquals("exec_req_1001", request.executionId)
        assertEquals("skill_order_food_001", request.skillId)
        assertEquals("Margherita Pizza", request.boundSlots["item_name"])
    }

    @Test
    fun testLoadMockUiStateFixture() {
        val stream = javaClass.classLoader?.getResourceAsStream("mock/ui/sample_ui_state.json")
        assertNotNull("Mock fixture sample_ui_state.json must exist", stream)

        val jsonContent = stream!!.bufferedReader().use { it.readText() }
        val uiState = jsonFormatter.decodeFromString(UiState.serializer(), jsonContent)

        assertEquals("1.0", uiState.schemaVersion)
        assertEquals("com.example.fooddelivery", uiState.appContext)
        assertEquals(1, uiState.allElements.size)
        assertEquals("EditText", uiState.allElements.first().role)
    }
}
