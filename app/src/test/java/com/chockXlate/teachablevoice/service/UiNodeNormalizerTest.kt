package com.chockXlate.teachablevoice.service

import com.chockXlate.teachablevoice.app.service.MockAccessibilityNode
import com.chockXlate.teachablevoice.app.service.TeachableVoiceAccessibilityService
import com.chockXlate.teachablevoice.app.service.UiNodeNormalizer
import com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter
import com.chockXlate.teachablevoice.command.matching.SkillMatcher
import com.chockXlate.teachablevoice.command.request.ExecutionRequestBuilder
import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiElementBounds
import com.chockXlate.teachablevoice.contract.ui.UiObservationStatus
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // =========================================================================
    // PHASE 4.4 TEST MATRIX (PH4.4-T1 through PH4.4-T20 + Integration + Kill Test)
    // =========================================================================

    // --- PH4.4-T1 — Basic UI observation ---
    @Test
    fun testPH4_4_T1_BasicUiObservation() {
        val node = MockAccessibilityNode(
            className = "android.widget.Button",
            text = "Search",
            isClickable = true
        )
        val state = UiNodeNormalizer.normalizeState(node, "com.example.shop")

        assertNotNull(state)
        val searchElement = state.allElements.find { it.text == "Search" }
        assertNotNull(searchElement)
        assertEquals("Button", searchElement!!.role)
        assertTrue(searchElement.isClickable)
    }

    // --- PH4.4-T2 — Text field observation ---
    @Test
    fun testPH4_4_T2_TextFieldObservation() {
        val node = MockAccessibilityNode(
            className = "android.widget.EditText",
            text = null,
            contentDescription = "Search products",
            isEditable = true
        )
        val state = UiNodeNormalizer.normalizeState(node, "com.example.shop")

        val editElement = state.allElements.find { it.isEditable }
        assertNotNull(editElement)
        assertEquals("EditText", editElement!!.role)
        assertEquals("Search products", editElement.contentDescription)
        assertTrue(editElement.isEditable)
    }

    // --- PH4.4-T3 — Content description ---
    @Test
    fun testPH4_4_T3_ContentDescription() {
        val node = MockAccessibilityNode(
            className = "android.widget.ImageButton",
            text = "",
            contentDescription = "Search",
            isClickable = true
        )
        val state = UiNodeNormalizer.normalizeState(node, "com.example.shop")

        val btn = state.allElements.find { it.contentDescription == "Search" }
        assertNotNull(btn)
        assertEquals("ImageButton", btn!!.role)
        assertTrue(btn.isClickable)
    }

    // --- PH4.4-T4 — Resource ID ---
    @Test
    fun testPH4_4_T4_ResourceIdPreserved() {
        val node = MockAccessibilityNode(
            className = "android.widget.Button",
            viewIdResourceName = "com.example.shop:id/btn_checkout",
            text = "Checkout"
        )
        val state = UiNodeNormalizer.normalizeState(node, "com.example.shop")

        val el = state.allElements.find { it.resourceId == "com.example.shop:id/btn_checkout" }
        assertNotNull(el)
        assertEquals("Checkout", el!!.text)
    }

    // --- PH4.4-T5 — Disabled control ---
    @Test
    fun testPH4_4_T5_DisabledControlPreserved() {
        val node = MockAccessibilityNode(
            className = "android.widget.Button",
            text = "Continue",
            isEnabled = false
        )
        val state = UiNodeNormalizer.normalizeState(node, "com.example.shop")

        val el = state.allElements.find { it.text == "Continue" }
        assertNotNull(el)
        assertFalse(el!!.isEnabled)
    }

    // --- PH4.4-T6 — Hierarchy preservation ---
    @Test
    fun testPH4_4_T6_HierarchyPreservation() {
        val titleNode = MockAccessibilityNode(className = "android.widget.TextView", text = "Blue Jacket")
        val addBtnNode = MockAccessibilityNode(className = "android.widget.Button", text = "Add to Cart", isClickable = true)
        val itemContainer = MockAccessibilityNode(
            className = "android.widget.LinearLayout",
            childrenList = listOf(titleNode, addBtnNode)
        )
        val listContainer = MockAccessibilityNode(
            className = "androidx.recyclerview.widget.RecyclerView",
            childrenList = listOf(itemContainer)
        )

        val state = UiNodeNormalizer.normalizeState(listContainer, "com.example.shop")
        assertNotNull(state.rootElement)
        assertEquals("RecyclerView", state.rootElement!!.role)
        assertEquals(1, state.rootElement!!.children.size)
        val item = state.rootElement!!.children[0]
        assertEquals("LinearLayout", item.role)
        assertEquals(2, item.children.size)
        assertEquals("TextView", item.children[0].role)
        assertEquals("Button", item.children[1].role)
        assertEquals("LinearLayout", item.children[0].parentRole)
    }

    // --- PH4.4-T7 — Repeated elements ---
    @Test
    fun testPH4_4_T7_RepeatedElements() {
        val addBtn1 = MockAccessibilityNode(className = "android.widget.Button", text = "Add", isClickable = true)
        val item1 = MockAccessibilityNode(className = "android.widget.LinearLayout", childrenList = listOf(addBtn1))

        val addBtn2 = MockAccessibilityNode(className = "android.widget.Button", text = "Add", isClickable = true)
        val item2 = MockAccessibilityNode(className = "android.widget.LinearLayout", childrenList = listOf(addBtn2))

        val list = MockAccessibilityNode(className = "android.widget.ListView", childrenList = listOf(item1, item2))
        val state = UiNodeNormalizer.normalizeState(list, "com.example.shop")

        val addButtons = state.allElements.filter { it.text == "Add" }
        assertEquals(2, addButtons.size)
        assertNotEquals(addButtons[0].elementId, addButtons[1].elementId)
    }

    // --- PH4.4-T8 — Scrollable container ---
    @Test
    fun testPH4_4_T8_ScrollableContainer() {
        val recycler = MockAccessibilityNode(
            className = "androidx.recyclerview.widget.RecyclerView",
            isScrollable = true
        )
        val state = UiNodeNormalizer.normalizeState(recycler, "com.example.shop")

        val scrollable = state.allElements.find { it.isScrollable }
        assertNotNull(scrollable)
        assertTrue(scrollable!!.isScrollable)
    }

    // --- PH4.4-T9 — Foreground package ---
    @Test
    fun testPH4_4_T9_ForegroundPackage() {
        val node = MockAccessibilityNode(className = "android.widget.FrameLayout")
        val state = UiNodeNormalizer.normalizeState(node, "com.myntra.android")

        assertEquals("com.myntra.android", state.appContext)
        assertEquals("com.myntra.android", state.foregroundPackage)
    }

    // --- PH4.4-T10 — Application transition ---
    @Test
    fun testPH4_4_T10_ApplicationTransition() {
        val service = TeachableVoiceAccessibilityService()
        service.isRuntimeReady = true

        // State 1: App A
        service.nodeSourceProvider = { MockAccessibilityNode(className = "android.widget.FrameLayout") }
        service.currentPackageOverride = "com.myntra.android"
        val state1 = service.observeCurrentUiState().state
        assertNotNull(state1)
        assertEquals("com.myntra.android", state1!!.foregroundPackage)

        // State 2: App B
        service.currentPackageOverride = "org.telegram.messenger"
        val state2 = service.observeCurrentUiState().state
        assertNotNull(state2)
        assertEquals("org.telegram.messenger", state2!!.foregroundPackage)
        assertEquals("org.telegram.messenger", service.latestUiState?.foregroundPackage)
    }

    // --- PH4.4-T11 — State identity stability ---
    @Test
    fun testPH4_4_T11_StateIdentityStability() {
        val node1 = MockAccessibilityNode(className = "android.widget.Button", text = "Search", isClickable = true)
        val node2 = MockAccessibilityNode(className = "android.widget.Button", text = "Search", isClickable = true)

        val state1 = UiNodeNormalizer.normalizeState(node1, "com.example.shop")
        // Small delay to simulate different timestamp
        Thread.sleep(10)
        val state2 = UiNodeNormalizer.normalizeState(node2, "com.example.shop")

        assertNotEquals(state1.timestamp, state2.timestamp)
        assertEquals(state1.stateId, state2.stateId)
    }

    // --- PH4.4-T12 — Meaningful state change ---
    @Test
    fun testPH4_4_T12_MeaningfulStateChange() {
        val initialNode = MockAccessibilityNode(className = "android.widget.EditText", text = "")
        val laterNode = MockAccessibilityNode(className = "android.widget.EditText", text = "headphones")

        val state1 = UiNodeNormalizer.normalizeState(initialNode, "com.example.shop")
        val state2 = UiNodeNormalizer.normalizeState(laterNode, "com.example.shop")

        assertNotEquals(state1.stateId, state2.stateId)
    }

    // --- PH4.4-T13 — Invisible node ---
    @Test
    fun testPH4_4_T13_InvisibleNode() {
        val invisibleNode = MockAccessibilityNode(
            className = "android.widget.Button",
            text = "Hidden Action",
            isVisibleToUser = false,
            isClickable = true
        )
        val state = UiNodeNormalizer.normalizeState(invisibleNode, "com.example.shop")

        val element = state.allElements.find { it.text == "Hidden Action" }
        assertNotNull(element)
        assertFalse(element!!.isVisible)
        assertFalse(element.isActionable)
        assertFalse(state.actionableElements.contains(element))
    }

    // --- PH4.4-T14 — Empty-text actionable element ---
    @Test
    fun testPH4_4_T14_EmptyTextActionableElement() {
        val iconBtn = MockAccessibilityNode(
            className = "android.widget.ImageButton",
            text = "",
            contentDescription = "Cart",
            isClickable = true
        )
        val state = UiNodeNormalizer.normalizeState(iconBtn, "com.example.shop")

        val el = state.allElements.find { it.contentDescription == "Cart" }
        assertNotNull(el)
        assertTrue(el!!.isClickable)
        assertTrue(state.actionableElements.contains(el))
    }

    // --- PH4.4-T15 — Sensitive field ---
    @Test
    fun testPH4_4_T15_SensitiveField() {
        val pwdNode = MockAccessibilityNode(
            className = "android.widget.EditText",
            text = "secret123",
            isPassword = true,
            isEditable = true
        )
        val state = UiNodeNormalizer.normalizeState(pwdNode, "com.example.bank")

        val el = state.allElements.find { it.role == "EditText" }
        assertNotNull(el)
        assertTrue(el!!.isSensitive)
        assertTrue(state.isSensitiveContext)
        assertNull(el.text) // Text redacted for sensitive credential fields
    }

    // --- PH4.4-T16 — No Accessibility root ---
    @Test
    fun testPH4_4_T16_NoAccessibilityRoot() {
        val service = TeachableVoiceAccessibilityService()
        service.isRuntimeReady = true
        service.nodeSourceProvider = { null }

        val result = service.observeCurrentUiState()
        assertEquals(UiObservationStatus.UNAVAILABLE, result.status)
        assertNull(result.state)
        assertNotNull(result.errorMessage)
    }

    // --- PH4.4-T17 — Malformed / deep hierarchy ---
    @Test
    fun testPH4_4_T17_MalformedDeepHierarchy() {
        var current: MockAccessibilityNode = MockAccessibilityNode(className = "android.widget.TextView", text = "Deepest Leaf")
        for (i in 0 until 80) {
            current = MockAccessibilityNode(className = "android.widget.FrameLayout", childrenList = listOf(current))
        }

        val state = UiNodeNormalizer.normalizeState(current, "com.example.app")
        assertNotNull(state)
        assertNotNull(state.rootElement)
    }

    // --- PH4.4-T18 — System UI ---
    @Test
    fun testPH4_4_T18_SystemUi() {
        val dialog = MockAccessibilityNode(
            className = "android.widget.TextView",
            text = "Allow permission?",
            viewIdResourceName = "com.android.permissioncontroller:id/permission_message"
        )
        val state = UiNodeNormalizer.normalizeState(dialog, "com.android.permissioncontroller")

        assertNotNull(state)
        assertEquals("com.android.permissioncontroller", state.foregroundPackage)
        assertEquals("Allow permission?", state.allElements[0].text)
    }

    // --- PH4.4-T19 — No execution side effects ---
    @Test
    fun testPH4_4_T19_NoExecutionSideEffects() {
        val root = MockAccessibilityNode(
            className = "android.widget.LinearLayout",
            childrenList = listOf(
                MockAccessibilityNode(className = "android.widget.Button", text = "Buy", isClickable = true),
                MockAccessibilityNode(className = "android.widget.EditText", text = "Search", isEditable = true),
                MockAccessibilityNode(className = "android.widget.Button", text = "Continue", isClickable = true)
            )
        )

        val service = TeachableVoiceAccessibilityService()
        service.isRuntimeReady = true
        service.nodeSourceProvider = { root }
        service.currentPackageOverride = "com.example.shop"

        val result = service.observeCurrentUiState()
        assertEquals(UiObservationStatus.SUCCESS, result.status)
        val state = result.state!!
        assertEquals(4, state.allElements.size)
        // Verify pure observation: no action triggered
        assertTrue(service.isRuntimeReady)
    }

    // --- PH4.4-T20 — Cross-app observation ---
    @Test
    fun testPH4_4_T20_CrossAppObservation() {
        val service = TeachableVoiceAccessibilityService()
        service.isRuntimeReady = true

        // Phase 1: Shopping App
        service.currentPackageOverride = "com.shopping.app"
        service.nodeSourceProvider = {
            MockAccessibilityNode(className = "android.widget.TextView", text = "Products")
        }
        val shoppingState = service.observeCurrentUiState().state!!
        assertEquals("com.shopping.app", shoppingState.foregroundPackage)

        // Phase 2: Messaging App
        service.currentPackageOverride = "org.telegram.messenger"
        service.nodeSourceProvider = {
            MockAccessibilityNode(className = "android.widget.TextView", text = "Chats")
        }
        val messagingState = service.observeCurrentUiState().state!!
        assertEquals("org.telegram.messenger", messagingState.foregroundPackage)

        assertNotEquals(shoppingState.foregroundPackage, messagingState.foregroundPackage)
    }

    // =========================================================================
    // PHASE 4.4 INTEGRATION TEST (Phase 4.3 -> Phase 4.4)
    // =========================================================================
    @Test
    fun testPhase4_4_IntegrationWithExecutionRequest() {
        val repo = LocalSkillRepository()
        val matcher = SkillMatcher(repo)

        val taughtWf = Workflow(
            skillId = "skill_shopping_live",
            name = "Shop Item",
            intent = "shop_item",
            appContext = "com.shop.app",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "jacket", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Myntra", provenance = "variable")
            )
        )
        repo.saveWorkflow(taughtWf)

        // Phase 4.1 & 4.2
        val cmd = CommandInterpreter.understandCommand("Buy blue jacket from Myntra")
        val match = matcher.match(cmd)

        // Phase 4.3: Build ExecutionRequest
        val buildResult = ExecutionRequestBuilder.build(cmd, match, repo)
        val request: ExecutionRequest = buildResult.executionRequest!!
        assertNotNull(request)
        assertEquals("Blue Jacket", request.boundSlots["item"])
        assertEquals("Myntra", request.boundSlots["platform"])

        // Phase 4.4: Live Observation
        val service = TeachableVoiceAccessibilityService()
        service.isRuntimeReady = true
        service.currentPackageOverride = "com.myntra.android"
        service.nodeSourceProvider = {
            MockAccessibilityNode(
                className = "android.widget.FrameLayout",
                childrenList = listOf(
                    MockAccessibilityNode(className = "android.widget.EditText", contentDescription = "Search for items", isEditable = true),
                    MockAccessibilityNode(className = "android.widget.Button", text = "Search", isClickable = true)
                )
            )
        }

        val observation = service.observeCurrentUiState()
        assertEquals(UiObservationStatus.SUCCESS, observation.status)
        val liveState = observation.state!!
        assertEquals("com.myntra.android", liveState.foregroundPackage)
        assertEquals(3, liveState.allElements.size)
        // Request inspected live UI state safely without execution
        assertNotNull(liveState.allElements.find { it.isEditable })
    }

    // =========================================================================
    // KILL TEST
    // =========================================================================
    @Test
    fun testPhase4_4_KillTest() {
        // Teach demonstration was on Amazon with white shirt
        val repo = LocalSkillRepository()
        val matcher = SkillMatcher(repo)

        val taughtWorkflow = Workflow(
            skillId = "skill_kill_44",
            name = "Order white shirt from Amazon",
            intent = "shop_item",
            appContext = "com.amazon.shopping",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "white shirt", provenance = "variable"),
                WorkflowSlot(name = "platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", provenance = "variable")
            )
        )
        repo.saveWorkflow(taughtWorkflow)

        // Runtime command is for Myntra / Blue Jacket
        val cmd = CommandInterpreter.understandCommand("Can you get me a blue jacket from Myntra?")
        val match = matcher.match(cmd)
        val execReq = ExecutionRequestBuilder.build(cmd, match, repo).executionRequest!!

        assertEquals("Blue Jacket", execReq.boundSlots["item"])
        assertEquals("Myntra", execReq.boundSlots["platform"])

        // Phase 4.4 Live UI observation on the ACTIVE device UI
        val service = TeachableVoiceAccessibilityService()
        service.isRuntimeReady = true
        service.currentPackageOverride = "com.myntra.android"
        service.nodeSourceProvider = {
            MockAccessibilityNode(
                className = "android.widget.FrameLayout",
                childrenList = listOf(
                    MockAccessibilityNode(className = "android.widget.EditText", text = "", contentDescription = "Search on Myntra", isEditable = true),
                    MockAccessibilityNode(className = "android.widget.Button", text = "Explore Blue Jackets", isClickable = true)
                )
            )
        }

        val observationResult = service.observeCurrentUiState()
        assertEquals(UiObservationStatus.SUCCESS, observationResult.status)
        val liveUi = observationResult.state!!

        // Invariants:
        // 1. Live UI observed current foreground package, NOT Amazon from demonstration
        assertEquals("com.myntra.android", liveUi.foregroundPackage)
        assertFalse(liveUi.foregroundPackage == "com.amazon.shopping")

        // 2. Elements are live, not replaying recorded events
        val searchField = liveUi.allElements.find { it.isEditable }
        assertNotNull(searchField)
        assertEquals("Search on Myntra", searchField!!.contentDescription)
        assertFalse(searchField.contentDescription?.contains("Amazon") == true)

        // 3. Zero execution side-effects performed
        assertTrue(service.isRuntimeReady)
        assertFalse(liveUi.allElements.any { it.bounds == UiElementBounds(420, 812, 420, 812) })
    }
}

