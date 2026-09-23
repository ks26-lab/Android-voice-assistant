package com.chockXlate.teachablevoice.service

import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UiNodeNormalizerTest {

    @Test
    fun testUiElementSemanticFields() {
        val element = UiElement(
            schemaVersion = "1.0",
            elementId = UUID.randomUUID().toString(),
            role = "Button",
            text = "Submit Order",
            contentDescription = "Submit button for food order",
            resourceId = "com.example.app:id/submit_btn",
            parentRole = "LinearLayout",
            ancestorRole = "FrameLayout",
            nearbyText = "Total: $25.00",
            relativePosition = "BELOW",
            isClickable = true,
            isEnabled = true,
            isEditable = false,
            isSelected = false,
            isChecked = false,
            isScrollable = false
        )

        assertEquals("Button", element.role)
        assertEquals("Submit Order", element.text)
        assertEquals("com.example.app:id/submit_btn", element.resourceId)
        assertTrue(element.isClickable)
        assertTrue(element.isEnabled)
    }

    @Test
    fun testUiStateCreation() {
        val element = UiElement(
            schemaVersion = "1.0",
            elementId = "elem_1",
            role = "TextView",
            text = "Welcome to Food App",
            resourceId = "com.example.app:id/title"
        )

        val uiState = UiState(
            schemaVersion = "1.0",
            stateId = "state_1",
            timestamp = System.currentTimeMillis(),
            appContext = "com.example.app",
            windowId = 1,
            rootElement = element,
            allElements = listOf(element)
        )

        assertEquals("1.0", uiState.schemaVersion)
        assertEquals("com.example.app", uiState.appContext)
        assertEquals(1, uiState.allElements.size)
        assertEquals("TextView", uiState.allElements[0].role)
    }
}
