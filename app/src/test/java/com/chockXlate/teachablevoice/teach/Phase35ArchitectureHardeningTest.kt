package com.chockXlate.teachablevoice.teach

import com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy
import com.chockXlate.teachablevoice.command.matching.SkillMatchStatus
import com.chockXlate.teachablevoice.command.matching.SkillMatcher
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
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.slots.SlotBinder
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import com.chockXlate.teachablevoice.safety.SafetyGate
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.teach.filter.DemonstrationFilter
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Phase 3.5 Test Suite: Architecture Hardening & Phase 4 Readiness.
 * Strictly verifies P3.5-T1 through P3.5-T12 and the End-to-End Kill Test.
 */
class Phase35ArchitectureHardeningTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var repository: LocalSkillRepository
    private val matcher = SemanticMatcher()

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("skills_p35_test")
        repository = LocalSkillRepository(storageDir)
    }

    @After
    fun tearDown() {
        repository.clear()
    }

    // --- Helper Fixtures ---

    private fun createUiElement(
        id: String,
        role: String = "View",
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        pkg: String = "com.test.app"
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
            packageName = resId?.substringBefore(":id/") ?: pkg
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

    private fun teachShoppingWorkflow(
        skillName: String = "Order a white shirt from Amazon",
        skillDesc: String = "Order a white shirt from Amazon",
        platformApp: String = "com.amazon.mShop.android.shopping",
        itemName: String = "white shirt"
    ): Workflow {
        val searchBar = createUiElement(
            id = "el_search",
            role = "EditText",
            text = "Search Amazon",
            resId = "$platformApp:id/rs_search_src_text",
            clickable = true,
            editable = true,
            pkg = platformApp
        )
        val productCard = createUiElement(
            id = "el_product",
            role = "Button",
            text = "White Cotton Shirt Slim Fit",
            resId = "$platformApp:id/item_title",
            clickable = true,
            pkg = platformApp
        )

        val state0 = UiState("s0", 1000L, platformApp, listOf(searchBar))
        val state1 = UiState("s1", 2000L, platformApp, listOf(searchBar.copy(text = itemName)))
        val state2 = UiState("s2", 3000L, platformApp, listOf(productCard))

        val traceEvents = listOf(
            TraceEvent(
                eventId = "te1",
                timestamp = 1000L,
                uiEvent = UiEvent("ue1", 1000L, "accessibility", state0),
                actionEvent = ActionEvent("ae1", 1001L, "INPUT_TEXT", searchBar, inputValue = itemName, packageName = platformApp),
                stateEvent = StateEvent("se1", 1002L, "ae1", state0, state1)
            ),
            TraceEvent(
                eventId = "te2",
                timestamp = 2000L,
                uiEvent = UiEvent("ue2", 2000L, "accessibility", state1),
                actionEvent = ActionEvent("ae2", 2001L, "CLICK", searchBar, packageName = platformApp),
                stateEvent = StateEvent("se2", 2002L, "ae2", state1, state2)
            ),
            TraceEvent(
                eventId = "te3",
                timestamp = 3000L,
                uiEvent = UiEvent("ue3", 3000L, "accessibility", state2),
                actionEvent = ActionEvent("ae3", 3001L, "CLICK", productCard, packageName = platformApp),
                stateEvent = StateEvent("se3", 3002L, "ae3", state2, state2)
            )
        )

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_shop_${UUID.randomUUID()}",
            skillId = "skill_shop_${UUID.randomUUID().toString().take(6)}",
            skillName = skillName,
            description = skillDesc,
            startTime = 1000L,
            endTime = 3500L,
            appContext = platformApp,
            events = traceEvents
        )

        val synthResult = WorkflowSynthesizer.synthesizeFromTrace(
            trace = trace,
            targetSkillId = trace.skillId,
            targetSkillName = skillName,
            targetSkillDescription = skillDesc
        )

        assertNotNull("Workflow synthesis must succeed", synthResult.workflow)
        val workflow = synthResult.workflow!!
        repository.saveWorkflow(workflow)
        return workflow
    }

    // =========================================================================
    // P3.5-T1 — Portable Shopping (Amazon -> Myntra)
    // =========================================================================
    @Test
    fun testP3_5_T1_PortableShopping() {
        val workflow = teachShoppingWorkflow(
            skillName = "Order a white shirt from Amazon",
            skillDesc = "Order a white shirt from Amazon"
        )
        // Verify Workflow IR parameterization
        assertTrue(workflow.slots.any { it.isPlatformSlot() })
        assertEquals("Amazon", workflow.slots.first { it.isPlatformSlot() }.exampleValue)

        // Command: Order a white shirt from Myntra
        val cmd = SemanticCommandPolicy.understandCommand("Order a white shirt from Myntra")
        assertEquals("shop_item", cmd.intent.canonicalName)
        assertEquals("Myntra", cmd.slots.find { it.name == "platform" }?.typedValue)
        assertEquals("White Shirt", cmd.slots.find { it.name == "item" }?.typedValue)

        val match = SkillMatcher(repository).match(cmd)
        assertEquals(SkillMatchStatus.MATCHED, match.status)
        assertEquals(workflow.skillId, match.selectedSkillId)

        val reqResult = ExecutionRequestBuilder.build(cmd, match, repository)
        assertEquals(ExecutionRequestStatus.READY_FOR_EXECUTION, reqResult.status)
        assertEquals("Myntra", reqResult.executionRequest!!.boundSlots["platform"])
        assertEquals("White Shirt", reqResult.executionRequest!!.boundSlots["item"])
    }

    // =========================================================================
    // P3.5-T2 — Same Task / Different Platform (Zomato -> Swiggy)
    // =========================================================================
    @Test
    fun testP3_5_T2_SameTaskDifferentPlatform() {
        val searchBar = createUiElement("z_search", "EditText", "Search restaurants", resId = "com.zomato:id/search", clickable = true, editable = true, pkg = "com.zomato")
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
                    ActionEvent("ae1", 1000L, "INPUT_TEXT", searchBar, inputValue = "Pizza", packageName = "com.zomato"),
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
        // Demonstrated food "Pizza" is retained where omitted in command
        assertEquals("Pizza", req.boundSlots["item"])
    }

    // =========================================================================
    // P3.5-T3 — Same Platform / Different Item (Amazon: headphones -> white shirt)
    // =========================================================================
    @Test
    fun testP3_5_T3_SamePlatformDifferentItem() {
        teachShoppingWorkflow(
            skillName = "Search headphones on Amazon",
            skillDesc = "Search headphones on Amazon",
            itemName = "headphones"
        )

        val cmd = SemanticCommandPolicy.understandCommand("Search white shirt on Amazon")
        assertEquals("shop_item", cmd.intent.canonicalName)
        assertEquals("Amazon", cmd.slots.find { it.name == "platform" }?.typedValue)
        assertEquals("White Shirt", cmd.slots.find { it.name == "item" }?.typedValue)

        val match = SkillMatcher(repository).match(cmd)
        assertEquals(SkillMatchStatus.MATCHED, match.status)

        val req = ExecutionRequestBuilder.build(cmd, match, repository).executionRequest!!
        assertEquals("Amazon", req.boundSlots["platform"])
        assertEquals("White Shirt", req.boundSlots["item"])
    }

    // =========================================================================
    // P3.5-T4 — Different Task / Same Platform (Settings vs Shopping)
    // =========================================================================
    @Test
    fun testP3_5_T4_DifferentTaskSamePlatform_MustNotMatch() {
        teachShoppingWorkflow(skillName = "Shop for a shirt on Amazon", skillDesc = "Shop for a shirt on Amazon")

        val cmd = SemanticCommandPolicy.understandCommand("Open Amazon settings")
        // Settings must not resolve to shopping intent
        assertNotEquals("shop_item", cmd.intent.canonicalName)

        val match = SkillMatcher(repository).match(cmd)
        assertNotEquals(SkillMatchStatus.MATCHED, match.status)
    }

    // =========================================================================
    // P3.5-T5 — Paraphrase ("Buy a white shirt from Amazon" -> "Get me a white shirt on Myntra")
    // =========================================================================
    @Test
    fun testP3_5_T5_Paraphrase() {
        val wf = teachShoppingWorkflow(
            skillName = "Buy a white shirt from Amazon",
            skillDesc = "Buy a white shirt from Amazon"
        )

        val cmd = SemanticCommandPolicy.understandCommand("Get me a white shirt on Myntra")
        assertEquals("shop_item", cmd.intent.canonicalName)
        assertEquals("Myntra", cmd.slots.find { it.name == "platform" }?.typedValue)

        val match = SkillMatcher(repository).match(cmd)
        assertEquals(SkillMatchStatus.MATCHED, match.status)
        assertEquals(wf.skillId, match.selectedSkillId)
    }

    // =========================================================================
    // P3.5-T6 — Different UI Layout ("Search Amazon" vs "Search for products")
    // =========================================================================
    @Test
    fun testP3_5_T6_DifferentUiLayout() {
        val learnedSelector = SemanticSelector(
            schemaVersion = "1.0",
            role = "EditText",
            text = "Search Amazon",
            resourceId = "com.amazon.mShop:id/search_query"
        )

        val runtimeTarget = createUiElement(
            id = "myntra_search_bar",
            role = "EditText",
            text = "Search for products",
            resId = "com.myntra.android:id/universal_search",
            clickable = true,
            editable = true,
            pkg = "com.myntra.android"
        )
        val observation = createObservation("com.myntra.android", listOf(runtimeTarget))

        val match = matcher.match(learnedSelector, observation, RuntimeAction.INPUT_TEXT)
        assertEquals(MatchStatus.MATCHED, match.status)
        assertEquals("myntra_search_bar", match.best?.element?.elementId)
        assertTrue("Confidence must be strong for semantic match", match.best!!.confidence >= 0.85)
    }

    // =========================================================================
    // P3.5-T7 — Wrong Application Detour (Settings in raw trace, absent in workflow)
    // =========================================================================
    @Test
    fun testP3_5_T7_WrongApplicationDetour() {
        val googleSearch = createUiElement("g_search", "EditText", "Search or type URL", resId = "com.google.android.googlequicksearchbox:id/search_box", clickable = true, editable = true, pkg = "com.google.android.googlequicksearchbox")
        val settingsItem = createUiElement("set_item", "TextView", "Display", resId = "com.android.settings:id/title", clickable = true, pkg = "com.android.settings")
        val backButton = createUiElement("back_btn", "ImageView", desc = "Navigate up", resId = "com.android.settings:id/back", clickable = true, pkg = "com.android.settings")

        val stateGoogle = UiState("sg", 1000L, "com.google.android.googlequicksearchbox", listOf(googleSearch))
        val stateSettings = UiState("ss", 2000L, "com.android.settings", listOf(settingsItem, backButton))

        // Raw Demonstration Trace: Google -> Settings -> Back -> Google Search
        val rawTrace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_detour",
            skillId = "skill_google_search",
            skillName = "Search headphones on Google",
            description = "Search headphones on Google",
            startTime = 1000L,
            endTime = 5000L,
            appContext = "com.google.android.googlequicksearchbox",
            events = listOf(
                TraceEvent("te1", 1000L, UiEvent("ue1", 1000L, "a11y", stateGoogle),
                    ActionEvent("ae1", 1001L, "CLICK", googleSearch, packageName = "com.google.android.googlequicksearchbox"),
                    StateEvent("se1", 1002L, "ae1", stateGoogle, stateSettings)),
                // Accidental detour into Settings
                TraceEvent("te2", 2000L, UiEvent("ue2", 2000L, "a11y", stateSettings),
                    ActionEvent("ae2", 2001L, "CLICK", settingsItem, packageName = "com.android.settings"),
                    StateEvent("se2", 2002L, "ae2", stateSettings, stateSettings)),
                // Back action out of Settings
                TraceEvent("te3", 3000L, UiEvent("ue3", 3000L, "a11y", stateSettings),
                    ActionEvent("ae3", 3001L, "BACK", backButton, packageName = "com.android.settings"),
                    StateEvent("se3", 3002L, "ae3", stateSettings, stateGoogle)),
                // Resume task on Google
                TraceEvent("te4", 4000L, UiEvent("ue4", 4000L, "a11y", stateGoogle),
                    ActionEvent("ae4", 4001L, "INPUT_TEXT", googleSearch, inputValue = "headphones", packageName = "com.google.android.googlequicksearchbox"),
                    StateEvent("se4", 4002L, "ae4", stateGoogle, stateGoogle))
            )
        )

        // INVARIANT 1: Raw trace retains ALL events
        assertEquals(4, rawTrace.events.size)
        assertTrue(rawTrace.events.any { it.actionEvent?.packageName == "com.android.settings" })

        // Filter and Synthesize
        val filterResult = DemonstrationFilter.filter(rawTrace, "Search headphones on Google", "Search headphones on Google")
        assertFalse("Settings action ae2 must NOT be task-relevant", filterResult.isTaskRelevant("ae2"))
        assertFalse("Back action ae3 must NOT be task-relevant", filterResult.isTaskRelevant("ae3"))
        assertTrue("Google action ae4 must be task-relevant", filterResult.isTaskRelevant("ae4"))

        val synth = WorkflowSynthesizer.synthesizeFromTrace(rawTrace, "skill_google_search", "Search headphones on Google", "Search headphones on Google")
        val workflow = synth.workflow!!

        // INVARIANT 2: Synthesized workflow excludes the detour completely
        assertFalse(workflow.steps.any { it.semanticSelector.resourceId?.contains("settings") == true })
        assertFalse(workflow.steps.any { it.semanticSelector.text == "Display" })
        assertTrue(workflow.steps.any { it.semanticAction == "INPUT_TEXT" })
    }

    // =========================================================================
    // P3.5-T8 — Legitimate Multi-App (Amazon + WhatsApp -> Myntra + Telegram)
    // =========================================================================
    @Test
    fun testP3_5_T8_LegitimateMultiApp() {
        val multiAppWorkflow = Workflow(
            schemaVersion = "1.0",
            skillId = "skill_multi_app",
            name = "Share Product Across Apps",
            intent = "shop_item",
            appContext = "com.amazon.mShop.android.shopping",
            slots = listOf(
                WorkflowSlot(name = "shopping_platform", type = SlotType.PLATFORM, required = true, exampleValue = "Amazon", role = "shopping_platform"),
                WorkflowSlot(name = "messaging_platform", type = SlotType.PLATFORM, required = true, exampleValue = "WhatsApp", role = "messaging_platform"),
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "White Shirt", role = "target_item")
            ),
            steps = emptyList()
        )
        repository.saveWorkflow(multiAppWorkflow)

        // Command substitutes both platforms distinctly
        val cmd = SemanticCommandPolicy.understandCommand("Find a product on Myntra and send it through Telegram")
        assertEquals("shop_item", cmd.intent.canonicalName)
        assertEquals("Myntra", cmd.slots.find { it.name == "shopping_platform" || it.name == "platform" }?.typedValue)
        assertEquals("Telegram", cmd.slots.find { it.name == "messaging_platform" }?.typedValue)

        val match = SkillMatcher(repository).match(cmd)
        assertEquals(SkillMatchStatus.MATCHED, match.status)
        assertEquals("skill_multi_app", match.selectedSkillId)
    }

    // =========================================================================
    // P3.5-T9 — Correction (cars -> headphones, query parameterized as variable)
    // =========================================================================
    @Test
    fun testP3_5_T9_Correction() {
        val searchInput = createUiElement("in1", "EditText", "Search", resId = "com.app:id/search", clickable = true, editable = true, pkg = "com.app")
        val state0 = UiState("s0", 1000L, "com.app", listOf(searchInput))
        val state1 = UiState("s1", 1001L, "com.app", listOf(searchInput.copy(text = "cars")))
        val state2 = UiState("s2", 1002L, "com.app", listOf(searchInput.copy(text = "headphones")))

        val trace = DemonstrationTrace(
            schemaVersion = "1.0",
            traceId = "trace_corr",
            skillId = "skill_corr",
            skillName = "Search headphones on ShopApp",
            description = "Search headphones on ShopApp",
            startTime = 1000L,
            endTime = 3000L,
            appContext = "com.app",
            events = listOf(
                // Mistaken input "cars"
                TraceEvent("te1", 1000L, UiEvent("ue1", 1000L, "a11y", state0),
                    ActionEvent("ae1", 1001L, "INPUT_TEXT", searchInput, inputValue = "cars", packageName = "com.app"),
                    StateEvent("se1", 1002L, "ae1", state0, state1)),
                // Corrected input "headphones"
                TraceEvent("te2", 2000L, UiEvent("ue2", 2000L, "a11y", state1),
                    ActionEvent("ae2", 2001L, "INPUT_TEXT", searchInput, inputValue = "headphones", packageName = "com.app"),
                    StateEvent("se2", 2002L, "ae2", state1, state2))
            )
        )

        val synth = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_corr", "Search headphones on ShopApp", "Search headphones on ShopApp")
        val workflow = synth.workflow!!

        // Query/Item must be parameterized as a variable slot
        assertTrue(workflow.slots.any { it.name == "item" || it.name == "query" })
        val itemSlot = workflow.slots.first { it.name == "item" || it.name == "query" }
        // Must NOT hardcode the mistaken text "cars"
        assertNotEquals("cars", itemSlot.exampleValue)
    }

    // =========================================================================
    // P3.5-T10 — Unsupported Platform
    // =========================================================================
    @Test
    fun testP3_5_T10_UnsupportedPlatform() {
        teachShoppingWorkflow()

        val cmd = SemanticCommandPolicy.understandCommand("Buy this product from an unrelated unsupported service")
        val match = SkillMatcher(repository).match(cmd)
        // Must NOT match blindly
        assertNotEquals(SkillMatchStatus.MATCHED, match.status)
    }

    // =========================================================================
    // P3.5-T11 — Resource ID Change (appA:id/search vs appB:id/searchInput)
    // =========================================================================
    @Test
    fun testP3_5_T11_ResourceIdChange() {
        val learnedSelector = SemanticSelector(
            schemaVersion = "1.0",
            role = "EditText",
            text = "Search",
            resourceId = "appA:id/search"
        )

        val substitutedElement = createUiElement(
            id = "target_input",
            role = "EditText",
            text = "Search",
            resId = "appB:id/searchInput",
            clickable = true,
            editable = true,
            pkg = "appB"
        )
        val observation = createObservation("appB", listOf(substitutedElement))

        val match = matcher.match(learnedSelector, observation, RuntimeAction.INPUT_TEXT)
        assertEquals(MatchStatus.MATCHED, match.status)
        assertEquals("target_input", match.best?.element?.elementId)
    }

    // =========================================================================
    // P3.5-T12 — Safety Boundary (UPI PIN blocked across platforms)
    // =========================================================================
    @Test
    fun testP3_5_T12_SafetyBoundary() {
        val gate = SafetyGate()
        val boundary = SafetyBoundary(
            schemaVersion = "1.0",
            requiresExplicitUserConfirmation = true,
            sensitiveKeywords = listOf("payment", "cvv", "otp", "upi", "pin"),
            restrictedActions = listOf("CLICK_SENSITIVE")
        )

        val upiPinElement = createUiElement(
            id = "upi_field",
            role = "EditText",
            text = "Enter UPI PIN",
            resId = "in.org.npci.upiapp:id/pin_field",
            clickable = true,
            editable = true
        )
        val sensitiveUi = createObservation("in.org.npci.upiapp", listOf(upiPinElement))

        val step = BoundStep(
            source = WorkflowStep(
                schemaVersion = "1.0",
                stepId = "step_pin",
                semanticAction = "INPUT_TEXT",
                semanticSelector = SemanticSelector(role = "EditText", text = "Enter UPI PIN")
            ),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", text = "Enter UPI PIN"),
            preconditions = com.chockXlate.teachablevoice.contract.workflow.Preconditions(fromState = "payment"),
            transition = com.chockXlate.teachablevoice.contract.workflow.ExpectedTransition(fromState = "payment", toState = "authorized")
        )

        val blockedReason = gate.check(boundary, sensitiveUi, step)
        assertNotNull("SafetyGate must block UPI PIN / sensitive credential entry", blockedReason)

        val dispatch = gate.dispatch(boundary, sensitiveUi, step) { true }
        assertFalse(dispatch.attempted)
        assertFalse(dispatch.accepted)
    }

    // =========================================================================
    // KILL TEST — Complete End-to-End Amazon -> Myntra Substitution
    // =========================================================================
    @Test
    fun testKillTest_AmazonToMyntraEndToEnd() {
        // 1. TEACH: Order a white shirt from Amazon
        val workflow = teachShoppingWorkflow(
            skillName = "Order a white shirt from Amazon",
            skillDesc = "Order a white shirt from Amazon",
            platformApp = "com.amazon.mShop.android.shopping",
            itemName = "white shirt"
        )
        assertNotNull(workflow)
        assertEquals("shop_item", workflow.intent)

        // 2. RUNTIME COMMAND: Order a white shirt from Myntra
        val understanding = SemanticCommandPolicy.understandCommand("Order a white shirt from Myntra")
        assertEquals("shop_item", understanding.intent.canonicalName)
        assertEquals("Myntra", understanding.slots.find { it.name == "platform" }?.typedValue)
        assertEquals("White Shirt", understanding.slots.find { it.name == "item" }?.typedValue)

        // 3. SKILL MATCHING: Matches Amazon-taught workflow via semantic task intent and platform slot
        val matchResult = SkillMatcher(repository).match(understanding)
        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)
        assertEquals(workflow.skillId, matchResult.selectedSkillId)

        // 4. EXECUTION REQUEST: Build parameterized request
        val reqResult = ExecutionRequestBuilder.build(understanding, matchResult, repository)
        assertEquals(ExecutionRequestStatus.READY_FOR_EXECUTION, reqResult.status)
        val req = reqResult.executionRequest!!
        assertEquals("Myntra", req.boundSlots["platform"])
        assertEquals("White Shirt", req.boundSlots["item"])

        // 5. SLOT BINDING: Bind parameters into workflow steps
        val bound = SlotBinder.bind(workflow, req.boundSlots)
        assertNull(bound.error)
        assertEquals(3, bound.steps.size)

        // 6. FRESH OBSERVATION: Observe Myntra UI
        val myntraSearchField = createUiElement(
            id = "myntra_query",
            role = "EditText",
            text = "Search for products",
            resId = "com.myntra.android:id/search_query_box",
            clickable = true,
            editable = true,
            pkg = "com.myntra.android"
        )
        val myntraProductResult = createUiElement(
            id = "myntra_product_card",
            role = "Button",
            text = "White Cotton Shirt Slim Fit",
            resId = "com.myntra.android:id/product_card_view",
            clickable = true,
            pkg = "com.myntra.android"
        )
        val myntraUiInitial = createObservation("com.myntra.android", listOf(myntraSearchField))
        val myntraUiResults = createObservation("com.myntra.android", listOf(myntraProductResult))

        // Step 1: Semantic search input matching on Myntra
        val step1Match = matcher.match(bound.steps[0].selector, myntraUiInitial, RuntimeAction.INPUT_TEXT)
        assertEquals(MatchStatus.MATCHED, step1Match.status)
        assertEquals("myntra_query", step1Match.best?.element?.elementId)

        // Step 3: Semantic product card matching on Myntra
        val step3Match = matcher.match(bound.steps[2].selector, myntraUiResults, RuntimeAction.CLICK)
        assertEquals(MatchStatus.MATCHED, step3Match.status)
        assertEquals("myntra_product_card", step3Match.best?.element?.elementId)
    }
}
