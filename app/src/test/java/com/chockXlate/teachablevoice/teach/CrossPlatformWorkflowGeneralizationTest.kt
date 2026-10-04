package com.chockXlate.teachablevoice.teach

import com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy
import com.chockXlate.teachablevoice.command.matching.SkillMatcher
import com.chockXlate.teachablevoice.command.matching.SkillMatchStatus
import com.chockXlate.teachablevoice.command.request.ExecutionRequestBuilder
import com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus
import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot
import com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.slots.SlotBinder
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import com.chockXlate.teachablevoice.safety.SafetyGate
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Phase 3B Test Suite: Cross-Platform Workflow Generalization & Semantic Platform Substitution.
 * Validates CP-T1 through CP-T12 and the End-to-End Kill Test.
 */
class CrossPlatformWorkflowGeneralizationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var repository: LocalSkillRepository
    private val matcher = SemanticMatcher()

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("skills_phase3b_test")
        repository = LocalSkillRepository(storageDir)
    }

    @After
    fun tearDown() {
        repository.clear()
    }

    // --- Helper fixtures ---

    private fun createUiElement(
        id: String,
        role: String = "View",
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false
    ): UiElement {
        return UiElement(
            elementId = id,
            role = role,
            text = text,
            contentDescription = desc,
            resourceId = resId,
            isClickable = clickable,
            isEditable = editable,
            isEnabled = true,
            packageName = resId?.substringBefore(":id/") ?: "com.app"
        )
    }

    private fun createObservation(appContext: String, elements: List<UiElement>): UiObservation {
        return UiObservation(
            state = UiState(
                stateId = "state_${UUID.randomUUID()}",
                timestamp = System.currentTimeMillis(),
                appContext = appContext,
                allElements = elements
            )
        )
    }

    /**
     * Helper to simulate teaching an e-commerce workflow on a given platform.
     */
    private fun teachShoppingWorkflow(
        skillName: String = "Order a white shirt from Amazon",
        skillDesc: String = "Order a white shirt from Amazon",
        platformApp: String = "com.amazon.mShop.android.shopping",
        platformName: String = "Amazon",
        itemName: String = "white shirt"
    ): Workflow {
        val searchBar = createUiElement(
            id = "el_search",
            role = "EditText",
            text = "Search Amazon",
            resId = "$platformApp:id/rs_search_src_text",
            clickable = true,
            editable = true
        )
        val productCard = createUiElement(
            id = "el_product",
            role = "Button",
            text = "White Cotton Shirt Slim Fit",
            resId = "$platformApp:id/item_title",
            clickable = true
        )

        val state0 = UiState("s0", System.currentTimeMillis(), platformApp, listOf(searchBar))
        val state1 = UiState("s1", System.currentTimeMillis(), platformApp, listOf(searchBar.copy(text = itemName)))
        val state2 = UiState("s2", System.currentTimeMillis(), platformApp, listOf(productCard))

        val traceEvents = listOf(
            TraceEvent(
                eventId = "te1",
                timestamp = 1000L,
                uiEvent = UiEvent("ue1", 1000L, "accessibility", state0),
                actionEvent = ActionEvent("ae1", 1001L, "INPUT_TEXT", searchBar, inputValue = itemName),
                stateEvent = StateEvent("se1", 1002L, "ae1", state0, state1)
            ),
            TraceEvent(
                eventId = "te2",
                timestamp = 2000L,
                uiEvent = UiEvent("ue2", 2000L, "accessibility", state1),
                actionEvent = ActionEvent("ae2", 2001L, "CLICK", searchBar),
                stateEvent = StateEvent("se2", 2002L, "ae2", state1, state2)
            ),
            TraceEvent(
                eventId = "te3",
                timestamp = 3000L,
                uiEvent = UiEvent("ue3", 3000L, "accessibility", state2),
                actionEvent = ActionEvent("ae3", 3001L, "CLICK", productCard),
                stateEvent = StateEvent("se3", 3002L, "ae3", state2, state2)
            )
        )

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_shop_${UUID.randomUUID()}",
            skillId = "skill_shop",
            skillName = skillName,
            description = skillDesc,
            startTime = 1000L,
            endTime = 3500L,
            appContext = platformApp,
            events = traceEvents
        )

        val synthResult = WorkflowSynthesizer.synthesizeFromTrace(
            trace = trace,
            targetSkillId = "skill_shop",
            targetSkillName = skillName,
            targetSkillDescription = skillDesc
        )

        assertNotNull(synthResult.workflow)
        val workflow = synthResult.workflow!!
        repository.saveWorkflow(workflow)
        return workflow
    }

    // =========================================================================
    // CP-T1 — Amazon → Myntra
    // =========================================================================
    @Test
    fun testCP_T1_AmazonToMyntra() {
        val workflow = teachShoppingWorkflow()
        assertTrue(workflow.slots.any { it.isPlatformSlot() })
        assertEquals("Amazon", workflow.slots.first { it.isPlatformSlot() }.exampleValue)

        // Runtime command on Myntra
        val understanding = SemanticCommandPolicy.understandCommand("Order a white shirt from Myntra")
        assertEquals("shop_item", understanding.intent.canonicalName)
        assertEquals("Myntra", understanding.slots.find { it.name == "platform" }?.typedValue)

        val skillMatcher = SkillMatcher(repository)
        val matchResult = skillMatcher.match(understanding)
        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)
        assertEquals(workflow.skillId, matchResult.selectedSkillId)

        // Build execution request
        val reqResult = ExecutionRequestBuilder.build(understanding, matchResult, repository)
        assertEquals(ExecutionRequestStatus.READY_FOR_EXECUTION, reqResult.status)
        val req = reqResult.executionRequest!!
        assertEquals("Myntra", req.boundSlots["platform"])
        assertEquals("White Shirt", req.boundSlots["item"])

        // Slot binding
        val bindingResult = SlotBinder.bind(workflow, req.boundSlots)
        assertNull(bindingResult.error)
        assertTrue(bindingResult.steps.isNotEmpty())

        // Semantic UI matching against Myntra UI
        val myntraSearchBar = createUiElement(
            id = "myntra_search",
            role = "EditText",
            text = "Search for products",
            resId = "com.myntra.android:id/search_query_box",
            clickable = true,
            editable = true
        )
        val myntraUi = createObservation("com.myntra.android", listOf(myntraSearchBar))

        val step1 = bindingResult.steps[0]
        val match = matcher.match(step1.selector, myntraUi, RuntimeAction.INPUT_TEXT)
        assertEquals(MatchStatus.MATCHED, match.status)
        assertEquals("myntra_search", match.best?.element?.elementId)
    }

    // =========================================================================
    // CP-T2 — Zomato → Swiggy
    // =========================================================================
    @Test
    fun testCP_T2_ZomatoToSwiggy() {
        val searchBar = createUiElement("z_search", "EditText", "Search restaurants", resId = "com.zomato:id/search", clickable = true, editable = true)
        val state0 = UiState("s0", 1000L, "com.zomato", listOf(searchBar))
        val state1 = UiState("s1", 1001L, "com.zomato", listOf(searchBar.copy(text = "Pizza")))

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_food",
            skillId = "skill_food",
            skillName = "Order Food",
            description = "Order food from Zomato",
            startTime = 1000L,
            endTime = 2000L,
            appContext = "com.zomato",
            events = listOf(
                TraceEvent("te1", 1000L, UiEvent("ue1", 1000L, "a11y", state0),
                    ActionEvent("ae1", 1000L, "INPUT_TEXT", searchBar, inputValue = "Pizza"),
                    StateEvent("se1", 1001L, "ae1", state0, state1))
            )
        )
        val synth = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_food", "Order Food", "Order food from Zomato")
        val wf = synth.workflow!!
        repository.saveWorkflow(wf)

        // Command: "Order the same food from Swiggy"
        val cmd = SemanticCommandPolicy.understandCommand("Order the same food from Swiggy")
        assertEquals("order_food", cmd.intent.canonicalName)
        assertEquals("Swiggy", cmd.slots.find { it.name == "platform" }?.typedValue)

        val match = SkillMatcher(repository).match(cmd)
        assertEquals(SkillMatchStatus.MATCHED, match.status)

        val req = ExecutionRequestBuilder.build(cmd, match, repository).executionRequest!!
        assertEquals("Swiggy", req.boundSlots["platform"])
        // Demonstrated food "Pizza" is retained
        assertEquals("Pizza", req.boundSlots["item"])
    }

    // =========================================================================
    // CP-T3 — Amazon → Flipkart
    // =========================================================================
    @Test
    fun testCP_T3_AmazonToFlipkart() {
        val workflow = teachShoppingWorkflow(platformName = "Amazon")

        val cmd = SemanticCommandPolicy.understandCommand("Order a white shirt from Flipkart")
        val matchResult = SkillMatcher(repository).match(cmd)
        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)

        val req = ExecutionRequestBuilder.build(cmd, matchResult, repository).executionRequest!!
        assertEquals("Flipkart", req.boundSlots["platform"])

        val bound = SlotBinder.bind(workflow, req.boundSlots)
        assertNull(bound.error)

        // Flipkart UI with semantic search field
        val flipkartSearch = createUiElement("fk_search", "EditText", "Search products and brands", resId = "com.flipkart.android:id/search_widget", clickable = true, editable = true)
        val fkUi = createObservation("com.flipkart.android", listOf(flipkartSearch))

        val stepMatch = matcher.match(bound.steps[0].selector, fkUi, RuntimeAction.INPUT_TEXT)
        assertEquals(MatchStatus.MATCHED, stepMatch.status)
        assertEquals("fk_search", stepMatch.best?.element?.elementId)
    }

    // =========================================================================
    // CP-T4 — Platform + Item Change
    // =========================================================================
    @Test
    fun testCP_T4_PlatformAndItemChange() {
        val workflow = teachShoppingWorkflow(
            skillName = "Buy black headphones from Amazon",
            skillDesc = "Buy black headphones from Amazon",
            itemName = "black headphones"
        )

        val cmd = SemanticCommandPolicy.understandCommand("Buy a white shirt from Myntra")
        assertEquals("shop_item", cmd.intent.canonicalName)
        assertEquals("Myntra", cmd.slots.find { it.name == "platform" }?.typedValue)
        assertEquals("White Shirt", cmd.slots.find { it.name == "item" }?.typedValue)

        val matchResult = SkillMatcher(repository).match(cmd)
        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)

        val req = ExecutionRequestBuilder.build(cmd, matchResult, repository).executionRequest!!
        assertEquals("Myntra", req.boundSlots["platform"])
        assertEquals("White Shirt", req.boundSlots["item"])
    }

    // =========================================================================
    // CP-T5 — Paraphrase
    // =========================================================================
    @Test
    fun testCP_T5_Paraphrasing() {
        teachShoppingWorkflow(skillName = "Shopping on Amazon", skillDesc = "Shopping on Amazon")

        val cmd1 = SemanticCommandPolicy.understandCommand("Buy me a white shirt on Myntra")
        assertEquals("shop_item", cmd1.intent.canonicalName)
        assertEquals("Myntra", cmd1.slots.find { it.name == "platform" }?.typedValue)

        val match1 = SkillMatcher(repository).match(cmd1)
        assertEquals(SkillMatchStatus.MATCHED, match1.status)

        val cmd2 = SemanticCommandPolicy.understandCommand("I want to get a white shirt through Myntra")
        assertEquals("shop_item", cmd2.intent.canonicalName)
        val match2 = SkillMatcher(repository).match(cmd2)
        assertEquals(SkillMatchStatus.MATCHED, match2.status)
    }

    // =========================================================================
    // CP-T6 — Same Platform, Different Item
    // =========================================================================
    @Test
    fun testCP_T6_SamePlatformDifferentItem() {
        teachShoppingWorkflow(
            skillName = "Buy black headphones from Amazon",
            skillDesc = "Buy black headphones from Amazon",
            itemName = "black headphones"
        )

        val cmd = SemanticCommandPolicy.understandCommand("Buy white headphones from Amazon")
        assertEquals("shop_item", cmd.intent.canonicalName)
        assertEquals("Amazon", cmd.slots.find { it.name == "platform" }?.typedValue)
        assertEquals("White Headphones", cmd.slots.find { it.name == "item" }?.typedValue)

        val match = SkillMatcher(repository).match(cmd)
        assertEquals(SkillMatchStatus.MATCHED, match.status)

        val req = ExecutionRequestBuilder.build(cmd, match, repository).executionRequest!!
        assertEquals("Amazon", req.boundSlots["platform"])
        assertEquals("White Headphones", req.boundSlots["item"])
    }

    // =========================================================================
    // CP-T7 — Different Platform, Same Task
    // =========================================================================
    @Test
    fun testCP_T7_DifferentPlatformSameTask() {
        val searchBar = createUiElement("z_s", "EditText", "Search food", resId = "com.zomato:id/search", clickable = true, editable = true)
        val state0 = UiState("s0", 1000L, "com.zomato", listOf(searchBar))
        val trace = DemonstrationTrace(
            schemaVersion = "1.0", traceId = "t_food", skillId = "s_food", skillName = "Order Food",
            description = "Order food from Zomato", startTime = 1000L, endTime = 2000L, appContext = "com.zomato",
            events = listOf(
                TraceEvent("te1", 1000L, UiEvent("ue1", 1000L, "a11y", state0),
                    ActionEvent("ae1", 1000L, "INPUT_TEXT", searchBar, inputValue = "Burger"),
                    StateEvent("se1", 1001L, "ae1", state0, state0))
            )
        )
        val wf = WorkflowSynthesizer.synthesizeFromTrace(trace, "s_food", "Order Food", "Order food from Zomato").workflow!!
        repository.saveWorkflow(wf)

        val cmd = SemanticCommandPolicy.understandCommand("Get food from Swiggy")
        assertEquals("order_food", cmd.intent.canonicalName)
        assertEquals("Swiggy", cmd.slots.find { it.name == "platform" }?.typedValue)

        val match = SkillMatcher(repository).match(cmd)
        assertEquals(SkillMatchStatus.MATCHED, match.status)
        val req = ExecutionRequestBuilder.build(cmd, match, repository).executionRequest!!
        assertEquals("Swiggy", req.boundSlots["platform"])
        assertEquals("Burger", req.boundSlots["item"])
    }

    // =========================================================================
    // CP-T8 — Different Task, Same Platform (Negative Test)
    // =========================================================================
    @Test
    fun testCP_T8_DifferentTaskSamePlatform_MustNotMatch() {
        teachShoppingWorkflow(skillName = "Shopping on Amazon", skillDesc = "Shopping on Amazon")

        // "Open Amazon settings" is a settings task, NOT a shopping workflow
        val cmd = SemanticCommandPolicy.understandCommand("Open Amazon settings")
        assertNotEquals("shop_item", cmd.intent.canonicalName)

        val match = SkillMatcher(repository).match(cmd)
        assertNotEquals(SkillMatchStatus.MATCHED, match.status)
    }

    // =========================================================================
    // CP-T9 — Legitimate Multi-App
    // =========================================================================
    @Test
    fun testCP_T9_LegitimateMultiApp() {
        val wf = Workflow(
            schemaVersion = "1.0",
            skillId = "skill_share_product",
            name = "Share Product",
            intent = "shop_item",
            appContext = "com.amazon.mShop.android.shopping",
            slots = listOf(
                WorkflowSlot(name = "shopping_platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", role = "shopping_platform"),
                WorkflowSlot(name = "messaging_platform", type = SlotType.PLATFORM, required = true, exampleValue = "WhatsApp", role = "messaging_platform"),
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Product", role = "target_item")
            ),
            steps = emptyList()
        )
        repository.saveWorkflow(wf)

        val cmd = SemanticCommandPolicy.understandCommand("Find a product on Myntra and send it to me on Telegram")
        assertEquals("shop_item", cmd.intent.canonicalName)
        assertEquals("Myntra", cmd.slots.find { it.name == "shopping_platform" || it.name == "platform" }?.typedValue)
        assertEquals("Telegram", cmd.slots.find { it.name == "messaging_platform" }?.typedValue)

        val match = SkillMatcher(repository).match(cmd)
        assertEquals(SkillMatchStatus.MATCHED, match.status)
    }

    // =========================================================================
    // CP-T10 — Different UI Layout Semantic Matching
    // =========================================================================
    @Test
    fun testCP_T10_DifferentUiLayoutSemanticMatching() {
        val learnedSelector = SemanticSelector(
            schemaVersion = "1.0",
            role = "EditText",
            text = "Search Amazon",
            resourceId = "com.amazon.mShop:id/search_src_text"
        )

        // Target application has different layout, different resource ID, and different wording
        val targetSearchField = createUiElement(
            id = "target_input",
            role = "EditText",
            text = "Search for products",
            resId = "com.otherapp:id/main_query_input",
            clickable = true,
            editable = true
        )
        val targetUi = createObservation("com.otherapp", listOf(targetSearchField))

        val match = matcher.match(learnedSelector, targetUi, RuntimeAction.INPUT_TEXT)
        assertEquals(MatchStatus.MATCHED, match.status)
        assertEquals("target_input", match.best?.element?.elementId)
        assertTrue(match.best!!.confidence >= 0.85)
    }

    // =========================================================================
    // CP-T11 — Unsupported Platform
    // =========================================================================
    @Test
    fun testCP_T11_UnsupportedPlatform_MustRejectOrClarify() {
        teachShoppingWorkflow()

        val cmd = SemanticCommandPolicy.understandCommand("Buy product from an unrelated service")
        val match = SkillMatcher(repository).match(cmd)
        // Must NOT blindly match with high confidence
        assertNotEquals(SkillMatchStatus.MATCHED, match.status)
    }

    // =========================================================================
    // CP-T12 — Safety Boundary Preservation
    // =========================================================================
    @Test
    fun testCP_T12_SafetyBoundaryPreservedAcrossPlatforms() {
        val gate = SafetyGate()
        val boundary = SafetyBoundary(
            schemaVersion = "1.0",
            requiresExplicitUserConfirmation = true,
            sensitiveKeywords = listOf("payment", "cvv", "otp", "upi"),
            restrictedActions = listOf("CLICK_SENSITIVE")
        )

        // Target platform (Swiggy / Myntra) reaches a payment confirmation screen
        val sensitiveElement = createUiElement(
            id = "pay_btn",
            role = "Button",
            text = "Confirm Payment and Enter UPI PIN",
            clickable = true
        )
        val paymentUi = createObservation("in.swiggy.android", listOf(sensitiveElement))

        val step = com.chockXlate.teachablevoice.runtime.slots.BoundStep(
            source = com.chockXlate.teachablevoice.contract.workflow.WorkflowStep(
                schemaVersion = "1.0",
                stepId = "step_pay",
                semanticAction = "CLICK",
                semanticSelector = SemanticSelector(role = "Button", text = "Pay")
            ),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Pay"),
            preconditions = com.chockXlate.teachablevoice.contract.workflow.Preconditions(fromState = "checkout"),
            transition = com.chockXlate.teachablevoice.contract.workflow.ExpectedTransition(fromState = "checkout", toState = "paid")
        )

        val blockedReason = gate.check(boundary, paymentUi, step)
        assertNotNull(blockedReason)
        assertTrue(blockedReason!!.contains("Sensitive") || blockedReason.contains("payment") || blockedReason.contains("PIN", ignoreCase = true))

        val dispatch = gate.dispatch(boundary, paymentUi, step) { true }
        assertFalse(dispatch.attempted)
        assertFalse(dispatch.accepted)
    }

    // =========================================================================
    // KILL TEST — Comprehensive End-to-End Generalization
    // =========================================================================
    @Test
    fun testKillTest_AmazonToMyntraEndToEnd() {
        // 1. TEACH: Order a white shirt from Amazon
        val workflow = teachShoppingWorkflow(
            skillName = "Order a white shirt from Amazon",
            skillDesc = "Order a white shirt from Amazon",
            platformApp = "com.amazon.mShop.android.shopping",
            platformName = "Amazon",
            itemName = "white shirt"
        )
        assertNotNull(workflow)
        assertEquals("shop_item", workflow.intent)

        // 2. COMMAND: Order a white shirt from Myntra
        val understanding = SemanticCommandPolicy.understandCommand("Order a white shirt from Myntra")
        assertEquals("shop_item", understanding.intent.canonicalName)
        assertEquals("Myntra", understanding.slots.find { it.name == "platform" }?.typedValue)
        assertEquals("White Shirt", understanding.slots.find { it.name == "item" }?.typedValue)

        // 3. MATCH: Match Amazon-taught workflow with platform substitution
        val matchResult = SkillMatcher(repository).match(understanding)
        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)
        assertEquals(workflow.skillId, matchResult.selectedSkillId)

        // 4. REQUEST: Build runtime execution request
        val reqResult = ExecutionRequestBuilder.build(understanding, matchResult, repository)
        assertEquals(ExecutionRequestStatus.READY_FOR_EXECUTION, reqResult.status)
        val req = reqResult.executionRequest!!
        assertEquals("Myntra", req.boundSlots["platform"])
        assertEquals("White Shirt", req.boundSlots["item"])

        // 5. BIND: SlotBinder binds platform and item parameters
        val bound = SlotBinder.bind(workflow, req.boundSlots)
        assertNull(bound.error)
        assertEquals(3, bound.steps.size)

        // 6. OBSERVE & MATCH: Myntra UI Observation
        val myntraSearchField = createUiElement(
            id = "myntra_query",
            role = "EditText",
            text = "Search for products",
            resId = "com.myntra.android:id/search_query_box",
            clickable = true,
            editable = true
        )
        val myntraProductResult = createUiElement(
            id = "myntra_product_card",
            role = "Button",
            text = "White Cotton Shirt Slim Fit",
            resId = "com.myntra.android:id/product_card_view",
            clickable = true
        )
        val myntraUiInitial = createObservation("com.myntra.android", listOf(myntraSearchField))
        val myntraUiResults = createObservation("com.myntra.android", listOf(myntraProductResult))

        // Step 1: Search input matching on Myntra
        val step1Match = matcher.match(bound.steps[0].selector, myntraUiInitial, RuntimeAction.INPUT_TEXT)
        assertEquals(MatchStatus.MATCHED, step1Match.status)
        assertEquals("myntra_query", step1Match.best?.element?.elementId)

        // Step 3: Product card matching on Myntra
        val step3Match = matcher.match(bound.steps[2].selector, myntraUiResults, RuntimeAction.CLICK)
        assertEquals(MatchStatus.MATCHED, step3Match.status)
        assertEquals("myntra_product_card", step3Match.best?.element?.elementId)
    }
}
