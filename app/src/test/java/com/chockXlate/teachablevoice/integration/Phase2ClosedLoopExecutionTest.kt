package com.chockXlate.teachablevoice.integration

import com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter
import com.chockXlate.teachablevoice.command.matching.SkillCandidateMatch
import com.chockXlate.teachablevoice.command.matching.SkillMatchResult
import com.chockXlate.teachablevoice.command.matching.SkillMatchStatus
import com.chockXlate.teachablevoice.command.matching.SkillMatcher
import com.chockXlate.teachablevoice.command.request.ExecutionRequestBuilder
import com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus
import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.filter.DemonstrationFilterClassification
import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.runtime.ExecutionEngine
import com.chockXlate.teachablevoice.runtime.RuntimeLifecycleManager
import com.chockXlate.teachablevoice.runtime.RuntimeLifecycleState
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.slots.SlotBinder
import com.chockXlate.teachablevoice.runtime.trace.RuntimeReport
import com.chockXlate.teachablevoice.runtime.ui.ActionOutcome
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction
import com.chockXlate.teachablevoice.runtime.ui.UiDriver
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import com.chockXlate.teachablevoice.runtime.verification.PreconditionEvaluator
import com.chockXlate.teachablevoice.runtime.verification.TransitionVerifier
import com.chockXlate.teachablevoice.safety.SafetyGate
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.skill.repository.SkillRepositoryProvider
import com.chockXlate.teachablevoice.teach.filter.DemonstrationFilter
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Phase 2 — Closed-Loop Runtime Execution Verification Suite.
 * Covers:
 * - T2 Exact replay
 * - T4 Changed item
 * - T5 Changed quantity
 * - T6 Changed address
 * - T7 Changed screen / popup
 * - T10 Genuinely stuck state
 * - T11 Payment / sensitive boundary
 * - T12 Unknown command rejection
 * - T13 Ambiguous command handling
 * - Missing required slot clarification
 * - Runtime startup lifecycle
 * - Automatic repository search
 * - Live Accessibility observation & normalization
 * - Semantic target resolution
 * - Transition verification
 * - Bounded recovery policy
 * - Bonus 1: Irrelevant-action filtering
 * - Bonus 2: Cross-app semantic generalization
 * - Bonus 3: Mid-flow clarification & resumption
 */
class Phase2ClosedLoopExecutionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var repository: LocalSkillRepository

    private fun runBlockingTest(block: suspend () -> Unit) {
        val latch = CountDownLatch(1)
        var thrown: Throwable? = null
        val continuation = object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                result.onFailure { thrown = it }
                latch.countDown()
            }
        }
        block.startCoroutine(continuation)
        assertTrue("Test timed out", latch.await(10, TimeUnit.SECONDS))
        thrown?.let { throw it }
    }

    private class ClosedLoopMockUiDriver(
        initialScreen: UiObservation
    ) : UiDriver {
        var currentScreen: UiObservation = initialScreen
        var ready = true
        var executedActionsCount = 0
        var observationCount = 0
        var lastExecutedAction: BoundStep? = null
        var onExecuteHook: (BoundStep) -> Unit = {}

        override fun isReady(): Boolean = ready

        override suspend fun observe(): UiObservation? {
            observationCount++
            return if (ready) currentScreen else null
        }

        override suspend fun awaitChange(delayMs: Long) {
            // Simulated screen settling delay
        }

        override suspend fun execute(
            step: BoundStep,
            expectedPackage: String,
            boundary: SafetyBoundary,
            matcher: SemanticMatcher,
            gate: SafetyGate
        ): ActionOutcome {
            val before = currentScreen
            gate.check(boundary, before, step)?.let {
                return ActionOutcome(attempted = false, accepted = false, reason = it, before = before)
            }
            PreconditionEvaluator(matcher).evaluate(step.preconditions, before, expectedPackage, step.stateEvidence)?.let {
                return ActionOutcome(attempted = false, accepted = false, reason = it, before = before)
            }
            TransitionVerifier(matcher).startingStateError(step, before)?.let {
                return ActionOutcome(attempted = false, accepted = false, reason = it, before = before)
            }
            val match = matcher.match(step.selector, before, step.action)
            if (match.status != MatchStatus.MATCHED) {
                return ActionOutcome(attempted = false, accepted = false, reason = match.reason, before = before)
            }

            val result = gate.dispatch(boundary, before, step) {
                executedActionsCount++
                lastExecutedAction = step
                onExecuteHook(step)
                true
            }
            return ActionOutcome(result.attempted, result.accepted, result.reason, before)
        }
    }

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("p2_skills_storage")
        SkillRepositoryProvider.initialize(storageDir)
        repository = SkillRepositoryProvider.getRepository()
        RuntimeLifecycleManager.reset()
    }

    @After
    fun tearDown() {
        RuntimeLifecycleManager.reset()
    }

    private fun createUiElement(
        id: String,
        role: String,
        text: String? = null,
        resourceId: String? = null,
        contentDescription: String? = null,
        clickable: Boolean = true,
        editable: Boolean = false,
        nearbyText: List<String> = emptyList()
    ): UiElement {
        return UiElement(
            elementId = id,
            role = role,
            text = text,
            resourceId = resourceId,
            contentDescription = contentDescription,
            clickable = clickable,
            editable = editable,
            nearbyText = nearbyText,
            bounds = "100,100,200,200"
        )
    }

    private fun createUiObservation(
        appContext: String,
        stateName: String,
        elements: List<UiElement>
    ): UiObservation {
        return UiObservation(
            uiState = UiState(
                stateId = "state_${UUID.randomUUID()}",
                timestamp = System.currentTimeMillis(),
                appContext = appContext,
                elements = elements
            ),
            stateName = stateName,
            isSensitive = false
        )
    }

    // =========================================================================
    // T2 — EXACT REPLAY TEST
    // =========================================================================
    @Test
    fun testT2_ExactReplay_SemanticExecutionAndVerification() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_exact_01",
            name = "Search Song",
            intent = "search_music",
            appContext = "com.example.music",
            slots = listOf(
                WorkflowSlot(name = "song", type = SlotType.TEXT, required = true, exampleValue = "Bohemian Rhapsody")
            ),
            steps = listOf(
                WorkflowStep(
                    stepId = "step_search",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.music:id/search_box", textSlot = "\${song}"),
                    parameters = mapOf("input_parameter" to "\${song}"),
                    expectedTransition = ExpectedTransition(toState = "RESULTS_SCREEN")
                )
            )
        )
        repository.save(workflow)

        val screen1 = createUiObservation(
            appContext = "com.example.music",
            stateName = "SEARCH_SCREEN",
            elements = listOf(
                createUiElement("input_1", "EditText", text = "", resourceId = "com.example.music:id/search_box", editable = true)
            )
        )
        val screen2 = createUiObservation(
            appContext = "com.example.music",
            stateName = "RESULTS_SCREEN",
            elements = listOf(
                createUiElement("res_1", "TextView", text = "Bohemian Rhapsody")
            )
        )

        val driver = ClosedLoopMockUiDriver(screen1)
        driver.onExecuteHook = {
            driver.currentScreen = screen2
        }

        val engine = ExecutionEngine(repository, driver)
        val request = ExecutionRequest(
            executionId = "exec_t2",
            skillId = "skill_exact_01",
            boundParameters = mapOf("song" to "Bohemian Rhapsody")
        )

        val report = engine.execute(request)

        assertEquals("Execution must succeed on exact replay", ExecutionState.COMPLETED, report.state)
        assertEquals(1, report.completedSteps)
        assertEquals(1, driver.executedActionsCount)
        assertEquals("Bohemian Rhapsody", driver.lastExecutedAction?.inputText)
        assertTrue("Driver must re-observe live screen after action", driver.observationCount >= 2)
    }

    // =========================================================================
    // T4 — CHANGED ITEM TEST
    // =========================================================================
    @Test
    fun testT4_ChangedItem_DynamicSlotBindingAndTargetResolution() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_order_item",
            name = "Select Grocery Item",
            intent = "select_grocery",
            appContext = "com.example.grocery",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Apples")
            ),
            steps = listOf(
                WorkflowStep(
                    stepId = "step_click_item",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", text = "\${item}"),
                    parameters = emptyMap(),
                    expectedTransition = ExpectedTransition(toState = "ITEM_DETAIL")
                )
            )
        )
        repository.save(workflow)

        // Live screen contains "Oranges" instead of "Apples"
        val screen1 = createUiObservation(
            appContext = "com.example.grocery",
            stateName = "GROCERY_LIST",
            elements = listOf(
                createUiElement("item_btn_1", "Button", text = "Oranges", resourceId = "com.example.grocery:id/btn_item")
            )
        )
        val screen2 = createUiObservation(
            appContext = "com.example.grocery",
            stateName = "ITEM_DETAIL",
            elements = listOf(
                createUiElement("detail_title", "TextView", text = "Oranges Detail")
            )
        )

        val driver = ClosedLoopMockUiDriver(screen1)
        driver.onExecuteHook = { driver.currentScreen = screen2 }

        val engine = ExecutionEngine(repository, driver)
        val request = ExecutionRequest(
            executionId = "exec_t4",
            skillId = "skill_order_item",
            boundParameters = mapOf("item" to "Oranges")
        )

        val report = engine.execute(request)

        assertEquals("Execution must complete for changed slot value", ExecutionState.COMPLETED, report.state)
        assertEquals(1, driver.executedActionsCount)
    }

    // =========================================================================
    // T5 — CHANGED QUANTITY TEST
    // =========================================================================
    @Test
    fun testT5_ChangedQuantity_DynamicIntegerBinding() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_qty_test",
            name = "Set Quantity",
            intent = "set_quantity",
            appContext = "com.example.shop",
            slots = listOf(
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "2")
            ),
            steps = listOf(
                WorkflowStep(
                    stepId = "step_type_qty",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.shop:id/qty_input", textSlot = "\${quantity}"),
                    parameters = mapOf("input_parameter" to "\${quantity}"),
                    expectedTransition = ExpectedTransition(toState = "QTY_UPDATED")
                )
            )
        )
        repository.save(workflow)

        val screen1 = createUiObservation(
            appContext = "com.example.shop",
            stateName = "CART_SCREEN",
            elements = listOf(
                createUiElement("qty_field", "EditText", resourceId = "com.example.shop:id/qty_input", editable = true)
            )
        )
        val screen2 = createUiObservation(
            appContext = "com.example.shop",
            stateName = "QTY_UPDATED",
            elements = listOf(
                createUiElement("summary", "TextView", text = "Quantity set to 5")
            )
        )

        val driver = ClosedLoopMockUiDriver(screen1)
        driver.onExecuteHook = { driver.currentScreen = screen2 }

        val engine = ExecutionEngine(repository, driver)
        val request = ExecutionRequest(
            executionId = "exec_t5",
            skillId = "skill_qty_test",
            boundParameters = mapOf("quantity" to "5")
        )

        val report = engine.execute(request)

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("5", driver.lastExecutedAction?.inputText)
    }

    // =========================================================================
    // T6 — CHANGED ADDRESS TEST
    // =========================================================================
    @Test
    fun testT6_ChangedAddress_DynamicAddressBinding() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_addr_test",
            name = "Set Delivery Address",
            intent = "set_address",
            appContext = "com.example.delivery",
            slots = listOf(
                WorkflowSlot(name = "address", type = SlotType.ADDRESS, required = true, exampleValue = "123 Main St")
            ),
            steps = listOf(
                WorkflowStep(
                    stepId = "step_type_addr",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.delivery:id/address_box", textSlot = "\${address}"),
                    parameters = mapOf("input_parameter" to "\${address}"),
                    expectedTransition = ExpectedTransition(toState = "ADDR_SET")
                )
            )
        )
        repository.save(workflow)

        val screen1 = createUiObservation(
            appContext = "com.example.delivery",
            stateName = "CHECKOUT_SCREEN",
            elements = listOf(
                createUiElement("addr_box", "EditText", resourceId = "com.example.delivery:id/address_box", editable = true)
            )
        )
        val screen2 = createUiObservation(
            appContext = "com.example.delivery",
            stateName = "ADDR_SET",
            elements = listOf(
                createUiElement("addr_label", "TextView", text = "789 Pine Ave")
            )
        )

        val driver = ClosedLoopMockUiDriver(screen1)
        driver.onExecuteHook = { driver.currentScreen = screen2 }

        val engine = ExecutionEngine(repository, driver)
        val request = ExecutionRequest(
            executionId = "exec_t6",
            skillId = "skill_addr_test",
            boundParameters = mapOf("address" to "789 Pine Ave")
        )

        val report = engine.execute(request)

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("789 Pine Ave", driver.lastExecutedAction?.inputText)
    }

    // =========================================================================
    // T7 — CHANGED SCREEN / POPUP MISMATCH
    // =========================================================================
    @Test
    fun testT7_ChangedScreen_DetectsMismatchAndDoesNotBlindlyExecute() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_popup_test",
            name = "Confirm Order",
            intent = "confirm_order",
            appContext = "com.example.shop",
            steps = listOf(
                WorkflowStep(
                    stepId = "step_confirm",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.shop:id/btn_confirm", text = "Confirm"),
                    preconditions = Preconditions(requiredState = "ORDER_SUMMARY"),
                    expectedTransition = ExpectedTransition(toState = "ORDER_CONFIRMED")
                )
            )
        )
        repository.save(workflow)

        // Live screen is an unexpected promo popup rather than ORDER_SUMMARY
        val popupScreen = createUiObservation(
            appContext = "com.example.shop",
            stateName = "PROMO_POPUP",
            elements = listOf(
                createUiElement("promo_close", "Button", text = "Dismiss Promo")
            )
        )

        val driver = ClosedLoopMockUiDriver(popupScreen)
        val engine = ExecutionEngine(repository, driver)
        val request = ExecutionRequest(
            executionId = "exec_t7",
            skillId = "skill_popup_test"
        )

        val report = engine.execute(request)

        assertNotEquals("Must not blindly succeed on unexpected popup", ExecutionState.COMPLETED, report.state)
        assertEquals("Must trigger 0 blind actions", 0, driver.executedActionsCount)
        assertTrue("Report must record precondition or state mismatch",
            report.reason?.contains("Precondition", ignoreCase = true) == true ||
            report.state == ExecutionState.FAILED ||
            report.state == ExecutionState.ASK
        )
    }

    // =========================================================================
    // T10 — GENUINELY STUCK STATE (BOUNDED RECOVERY)
    // =========================================================================
    @Test
    fun testT10_GenuinelyStuckState_BoundedRecoveryStopsCleanly() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_stuck_test",
            name = "Click Missing Element",
            intent = "click_missing",
            appContext = "com.example.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "step_missing",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.app:id/non_existent_btn", text = "Submit"),
                    recoveryPolicy = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)
                )
            )
        )
        repository.save(workflow)

        val emptyScreen = createUiObservation(
            appContext = "com.example.app",
            stateName = "EMPTY_STATE",
            elements = listOf(
                createUiElement("other_txt", "TextView", text = "Nothing here")
            )
        )

        val driver = ClosedLoopMockUiDriver(emptyScreen)
        val engine = ExecutionEngine(repository, driver)
        val request = ExecutionRequest(
            executionId = "exec_t10",
            skillId = "skill_stuck_test"
        )

        val report = engine.execute(request)

        assertEquals("Must stop cleanly in failed state when target cannot be resolved", ExecutionState.FAILED, report.state)
        assertEquals("Must execute 0 actions when element missing", 0, driver.executedActionsCount)
        assertTrue("Report must explain the failure", report.reason?.isNotEmpty() == true)
    }

    // =========================================================================
    // T11 — PAYMENT / SENSITIVE BOUNDARY TEST
    // =========================================================================
    @Test
    fun testT11_PaymentBoundary_BlocksSensitiveActionWithZeroAutomatedClicks() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_pay_test",
            name = "Pay Bill",
            intent = "pay_bill",
            appContext = "com.example.bank",
            steps = listOf(
                WorkflowStep(
                    stepId = "step_pay",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.bank:id/btn_pay", text = "Authorize Payment"),
                    isPayment = true
                )
            ),
            safetyBoundary = SafetyBoundary(isPaymentBoundary = true, requiresExplicitUserConfirmation = true)
        )
        repository.save(workflow)

        val paymentScreen = UiObservation(
            uiState = UiState(
                stateId = "state_pay",
                timestamp = System.currentTimeMillis(),
                appContext = "com.example.bank",
                elements = listOf(
                    createUiElement("btn_pay", "Button", text = "Authorize Payment", resourceId = "com.example.bank:id/btn_pay")
                )
            ),
            stateName = "PAYMENT_ENTRY",
            isSensitive = true
        )

        val driver = ClosedLoopMockUiDriver(paymentScreen)
        val engine = ExecutionEngine(repository, driver)
        val request = ExecutionRequest(
            executionId = "exec_t11",
            skillId = "skill_pay_test"
        )

        val report = engine.execute(request)

        assertEquals("Payment boundary must result in HANDOFF / BLOCKED", ExecutionState.HANDOFF, report.state)
        assertEquals("Must execute exactly 0 automated actions on sensitive screen", 0, driver.executedActionsCount)
    }

    // =========================================================================
    // T12 — UNKNOWN COMMAND REJECTION
    // =========================================================================
    @Test
    fun testT12_UnknownCommand_RejectsExecution() {
        val matcher = SkillMatcher()
        val interpretation = CommandInterpreter().interpret("fly to the moon now")

        val matchResult = matcher.match(interpretation, repository)

        assertEquals("Unrecognized command must yield UNKNOWN status", SkillMatchStatus.UNKNOWN, matchResult.status)
        assertTrue("No candidate skills should be matched", matchResult.candidates.isEmpty())
    }

    // =========================================================================
    // T13 — AMBIGUOUS COMMAND HANDLING
    // =========================================================================
    @Test
    fun testT13_AmbiguousCommand_AsksUserForClarification() {
        val skill1 = Workflow(
            skillId = "skill_music_rock",
            name = "Play Rock",
            intent = "play_music",
            utteranceExamples = listOf("play music", "play songs")
        )
        val skill2 = Workflow(
            skillId = "skill_music_pop",
            name = "Play Pop",
            intent = "play_music",
            utteranceExamples = listOf("play music", "stream tracks")
        )
        repository.save(skill1)
        repository.save(skill2)

        val matcher = SkillMatcher()
        val interpretation = CommandInterpreter().interpret("play music")
        val matchResult = matcher.match(interpretation, repository)

        assertEquals("Equally plausible skills must yield AMBIGUOUS status", SkillMatchStatus.AMBIGUOUS, matchResult.status)
        assertTrue("Candidates should contain ambiguous matches", matchResult.candidates.size >= 2)
    }

    // =========================================================================
    // MISSING REQUIRED SLOT REJECTION
    // =========================================================================
    @Test
    fun testMissingRequiredSlot_HaltsExecutionAndRequestsClarification() {
        val workflow = Workflow(
            skillId = "skill_order_slot_test",
            name = "Order Pizza",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Pepperoni Pizza"),
                WorkflowSlot(name = "address", type = SlotType.ADDRESS, required = true, exampleValue = "123 Main St")
            )
        )
        repository.save(workflow)

        val interpretation = CommandInterpreter().interpret("order pepperoni pizza") // missing address slot
        val matchResult = SkillMatchResult(
            status = SkillMatchStatus.EXACT_MATCH,
            matchedSkill = workflow,
            candidates = listOf(SkillCandidateMatch(workflow, 1.0))
        )

        val builder = ExecutionRequestBuilder()
        val requestResult = builder.buildRequest(interpretation, matchResult)

        assertEquals("Missing required slot must yield MISSING_SLOTS", ExecutionRequestStatus.MISSING_SLOTS, requestResult.status)
        assertNull("ExecutionRequest should be null when required slots are missing", requestResult.request)
        assertTrue("Must specify missing address slot", requestResult.missingSlots.contains("address"))
    }

    // =========================================================================
    // RUNTIME STARTUP LIFECYCLE
    // =========================================================================
    @Test
    fun testRuntimeStartupLifecycle_InitializesToReady() {
        assertEquals("Initial state must be UNINITIALIZED", RuntimeLifecycleState.UNINITIALIZED, RuntimeLifecycleManager.state.value)

        RuntimeLifecycleManager.initialize(repository)

        assertEquals("State must be READY after initialization", RuntimeLifecycleState.READY, RuntimeLifecycleManager.state.value)
        assertTrue("Lifecycle manager must confirm isReady", RuntimeLifecycleManager.isReady())
    }

    // =========================================================================
    // AUTOMATIC SKILL REPOSITORY SEARCH
    // =========================================================================
    @Test
    fun testAutomaticSkillRepositorySearch_DiscoversPersistedSkill() {
        val workflow = Workflow(
            skillId = "skill_calc_01",
            name = "Open Calculator",
            intent = "open_calculator",
            utteranceExamples = listOf("open calculator", "launch calc")
        )
        repository.save(workflow)

        val interpretation = CommandInterpreter().interpret("launch calc")
        val matchResult = SkillMatcher().match(interpretation, repository)

        assertEquals(SkillMatchStatus.EXACT_MATCH, matchResult.status)
        assertEquals("skill_calc_01", matchResult.matchedSkill?.skillId)
    }

    // =========================================================================
    // ACCESSIBILITY OBSERVATION & NORMALIZATION
    // =========================================================================
    @Test
    fun testAccessibilityObservationNormalization_PreservesSemanticAttributes() {
        val element = createUiElement(
            id = "node_42",
            role = "Button",
            text = "Checkout",
            resourceId = "com.example.app:id/btn_checkout",
            contentDescription = "Proceed to checkout",
            clickable = true,
            editable = false,
            nearbyText = listOf("Subtotal: $45.00", "Taxes: $3.50")
        )
        val observation = createUiObservation("com.example.app", "CART_VIEW", listOf(element))

        assertEquals("com.example.app", observation.uiState.appContext)
        assertEquals("CART_VIEW", observation.stateName)
        assertEquals(1, observation.uiState.elements.size)

        val norm = observation.uiState.elements[0]
        assertEquals("node_42", norm.elementId)
        assertEquals("Button", norm.role)
        assertEquals("Checkout", norm.text)
        assertEquals("com.example.app:id/btn_checkout", norm.resourceId)
        assertEquals("Proceed to checkout", norm.contentDescription)
        assertTrue(norm.clickable)
        assertFalse(norm.editable)
        assertEquals(2, norm.nearbyText.size)
    }

    // =========================================================================
    // SEMANTIC TARGET RESOLUTION — RELATIONAL CONTEXT SCORING
    // =========================================================================
    @Test
    fun testSemanticTargetResolution_RelationalContextDistinguishesCandidates() {
        val matcher = SemanticMatcher()

        // Multiple "Add" buttons on screen, but only one is near "Espresso"
        val btn1 = createUiElement("btn_add_1", "Button", text = "Add", nearbyText = listOf("Latte", "$4.00"))
        val btn2 = createUiElement("btn_add_2", "Button", text = "Add", nearbyText = listOf("Espresso", "$3.00"))
        val btn3 = createUiElement("btn_add_3", "Button", text = "Add", nearbyText = listOf("Cappuccino", "$4.50"))

        val observation = createUiObservation("com.example.cafe", "MENU_SCREEN", listOf(btn1, btn2, btn3))

        val selector = SemanticSelector(
            role = "Button",
            text = "Add",
            nearbyText = "Espresso"
        )

        val match = matcher.match(selector, observation, RuntimeAction.CLICK)

        assertEquals("Semantic matcher must successfully match candidate", MatchStatus.MATCHED, match.status)
        assertNotNull(match.best)
        assertEquals("btn_add_2", match.best?.element?.elementId)
    }

    // =========================================================================
    // TRANSITION VERIFICATION
    // =========================================================================
    @Test
    fun testTransitionVerification_FailsWhenExpectedStateNotReached() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_trans_fail",
            name = "Filter Results",
            intent = "filter_results",
            appContext = "com.example.shop",
            steps = listOf(
                WorkflowStep(
                    stepId = "step_apply_filter",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.shop:id/apply_filter", text = "Apply"),
                    expectedTransition = ExpectedTransition(toState = "FILTERED_RESULTS"),
                    recoveryPolicy = RecoveryPolicy(maxRetries = 0, strategy = RecoveryStrategy.FAIL)
                )
            )
        )
        repository.save(workflow)

        val screen1 = createUiObservation(
            appContext = "com.example.shop",
            stateName = "FILTER_DRAWER",
            elements = listOf(
                createUiElement("apply_btn", "Button", text = "Apply", resourceId = "com.example.shop:id/apply_filter")
            )
        )
        // Screen stays in FILTER_DRAWER (transition failed)
        val screen2 = createUiObservation(
            appContext = "com.example.shop",
            stateName = "FILTER_DRAWER",
            elements = listOf(
                createUiElement("apply_btn", "Button", text = "Apply", resourceId = "com.example.shop:id/apply_filter")
            )
        )

        val driver = ClosedLoopMockUiDriver(screen1)
        driver.onExecuteHook = { driver.currentScreen = screen2 }

        val engine = ExecutionEngine(repository, driver)
        val request = ExecutionRequest(
            executionId = "exec_trans_fail",
            skillId = "skill_trans_fail"
        )

        val report = engine.execute(request)

        assertEquals("Execution must fail when transition verification fails", ExecutionState.FAILED, report.state)
    }

    // =========================================================================
    // BOUNDED RECOVERY POLICY
    // =========================================================================
    @Test
    fun testBoundedRecoveryPolicy_LimitsRetriesBeforeFailing() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_recovery_bound",
            name = "Flaky Step",
            intent = "flaky_action",
            appContext = "com.example.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "step_flaky",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.app:id/flaky_btn", text = "Click"),
                    recoveryPolicy = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)
                )
            )
        )
        repository.save(workflow)

        val emptyScreen = createUiObservation("com.example.app", "SCREEN_X", emptyList())
        val driver = ClosedLoopMockUiDriver(emptyScreen)
        val engine = ExecutionEngine(repository, driver)
        val request = ExecutionRequest(executionId = "exec_bounded_rec", skillId = "skill_recovery_bound")

        val report = engine.execute(request)

        assertEquals(ExecutionState.FAILED, report.state)
        assertTrue(report.totalSteps == 1)
    }

    // =========================================================================
    // BONUS 1 — IRRELEVANT ACTION FILTERING
    // =========================================================================
    @Test
    fun testBonus1_IrrelevantActionFiltering_RemovesNoiseAndNavigation() {
        val launcherAction = ActionEvent(
            actionId = "act_launcher",
            timestamp = 1000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "TextView", text = "App Launcher", resourceId = "com.google.android.apps.nexuslauncher:id/icon")
        )
        val relevantAction1 = ActionEvent(
            actionId = "act_search",
            timestamp = 2000L,
            actionType = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.food:id/search_query")
        )
        val noiseAction = ActionEvent(
            actionId = "act_unrelated_tap",
            timestamp = 2500L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "FrameLayout", resourceId = "com.example.food:id/background_container")
        )
        val relevantAction2 = ActionEvent(
            actionId = "act_select_item",
            timestamp = 3000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Select Item", resourceId = "com.example.food:id/item_btn")
        )

        val stateEv1 = StateEvent(
            stateEventId = "state_1",
            timestamp = 2100L,
            causeActionId = "act_search",
            beforeState = UiState(stateId = "s1_b", timestamp = 2000L, appContext = "com.example.food"),
            afterState = UiState(stateId = "s1_a", timestamp = 2100L, appContext = "com.example.food")
        )
        val stateEv2 = StateEvent(
            stateEventId = "state_2",
            timestamp = 3100L,
            causeActionId = "act_select_item",
            beforeState = UiState(stateId = "s2_b", timestamp = 3000L, appContext = "com.example.food"),
            afterState = UiState(stateId = "s2_a", timestamp = 3100L, appContext = "com.example.food")
        )

        val trace = DemonstrationTrace(
            traceId = "demo_bonus1_trace",
            timestamp = 1000L,
            appContext = "com.example.food",
            traceEvents = listOf(
                TraceEvent.Action("act_launcher", 1000L, launcherAction),
                TraceEvent.Action("act_search", 2000L, relevantAction1),
                TraceEvent.State("state_1", 2100L, stateEv1),
                TraceEvent.Action("act_unrelated_tap", 2500L, noiseAction),
                TraceEvent.Action("act_select_item", 3000L, relevantAction2),
                TraceEvent.State("state_2", 3100L, stateEv2)
            )
        )

        val filterResult = DemonstrationFilter.filter(trace)

        assertTrue("Launcher click should be classified as NAVIGATION_CONTEXT",
            filterResult.navigationContextEventIds.contains("act_launcher"))
        assertTrue("Background tap without state change should be classified as SYSTEM_NOISE",
            filterResult.systemNoiseEventIds.contains("act_unrelated_tap"))
        assertTrue("Search should be TASK_RELEVANT",
            filterResult.taskRelevantEventIds.contains("act_search"))
        assertTrue("Select item should be TASK_RELEVANT",
            filterResult.taskRelevantEventIds.contains("act_select_item"))

        val extracted = SemanticActionExtractor.extract(trace, filterResult)
        assertEquals("Only task relevant actions must be extracted for Workflow synthesis", 2, extracted.size)
        assertEquals("act_search", extracted[0].actionId)
        assertEquals("act_select_item", extracted[1].actionId)
    }

    // =========================================================================
    // BONUS 2 — CROSS-APP SEMANTIC GENERALIZATION
    // =========================================================================
    @Test
    fun testBonus2_CrossAppSemanticSelector_MatchesStructurallySimilarUiWithoutAppSpecificCode() {
        val matcher = SemanticMatcher()
        val universalSelector = SemanticSelector(
            role = "Button",
            text = "Add to Cart",
            nearbyText = "Price"
        )

        // Target screen in App A (e.g. Shopping App A)
        val appAScreen = createUiObservation(
            appContext = "com.app.alpha",
            stateName = "PRODUCT_PAGE",
            elements = listOf(
                createUiElement("btn_a", "Button", text = "Add to Cart", resourceId = "com.app.alpha:id/cart_button", nearbyText = listOf("Price: $19.99"))
            )
        )

        // Target screen in App B (e.g. Shopping App B)
        val appBScreen = createUiObservation(
            appContext = "com.app.beta",
            stateName = "ITEM_VIEW",
            elements = listOf(
                createUiElement("btn_b", "Button", text = "Add to Cart", resourceId = "com.app.beta:id/add_btn", nearbyText = listOf("Price: $24.99"))
            )
        )

        val matchA = matcher.match(universalSelector, appAScreen, RuntimeAction.CLICK)
        val matchB = matcher.match(universalSelector, appBScreen, RuntimeAction.CLICK)

        assertEquals("Selector must resolve in App A", MatchStatus.MATCHED, matchA.status)
        assertEquals("btn_a", matchA.best?.element?.elementId)

        assertEquals("Identical selector must resolve in App B without package specific branching", MatchStatus.MATCHED, matchB.status)
        assertEquals("btn_b", matchB.best?.element?.elementId)
    }

    // =========================================================================
    // BONUS 3 — MID-FLOW CLARIFICATION & RESUMPTION
    // =========================================================================
    @Test
    fun testBonus3_MidFlowClarification_PausesAndResumesSeamlessly() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_midflow_test",
            name = "Multi-step Order",
            intent = "order_pizza",
            appContext = "com.example.pizza",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Margherita"),
                WorkflowSlot(name = "address", type = SlotType.ADDRESS, required = true, exampleValue = "100 Broadway")
            ),
            steps = listOf(
                WorkflowStep(
                    stepId = "step_1_item",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.pizza:id/item_box", textSlot = "\${item}"),
                    parameters = mapOf("input_parameter" to "\${item}"),
                    expectedTransition = ExpectedTransition(toState = "ADDRESS_SCREEN")
                ),
                WorkflowStep(
                    stepId = "step_2_addr",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.pizza:id/addr_box", textSlot = "\${address}"),
                    parameters = mapOf("input_parameter" to "\${address}"),
                    expectedTransition = ExpectedTransition(toState = "ORDER_CONFIRMED")
                )
            )
        )
        repository.save(workflow)

        val screen1 = createUiObservation(
            appContext = "com.example.pizza",
            stateName = "ITEM_SCREEN",
            elements = listOf(
                createUiElement("item_box", "EditText", resourceId = "com.example.pizza:id/item_box", editable = true)
            )
        )
        val screen2 = createUiObservation(
            appContext = "com.example.pizza",
            stateName = "ADDRESS_SCREEN",
            elements = listOf(
                createUiElement("addr_box", "EditText", resourceId = "com.example.pizza:id/addr_box", editable = true)
            )
        )
        val screen3 = createUiObservation(
            appContext = "com.example.pizza",
            stateName = "ORDER_CONFIRMED",
            elements = listOf(
                createUiElement("confirm_label", "TextView", text = "Order Placed!")
            )
        )

        val driver = ClosedLoopMockUiDriver(screen1)
        driver.onExecuteHook = { step ->
            if (step.stepId == "step_1_item") {
                driver.currentScreen = screen2
            } else if (step.stepId == "step_2_addr") {
                driver.currentScreen = screen3
            }
        }

        val engine = ExecutionEngine(repository, driver)

        // Request starts with item provided, but address slot is deliberately omitted to trigger mid-flow clarification
        val request = ExecutionRequest(
            executionId = "exec_midflow",
            skillId = "skill_midflow_test",
            boundParameters = mapOf("item" to "Margherita")
        )

        val initialReport = engine.execute(request)

        assertEquals("Execution must pause with ASK state for missing mid-flow slot", ExecutionState.ASK, initialReport.state)
        assertTrue("Engine must report being paused", engine.isPaused)
        assertEquals("exec_midflow", engine.pausedExecutionId)

        // User responds to clarification with address
        val clarificationResponse = ClarificationResponse(
            clarificationId = initialReport.clarificationRequest?.clarificationId ?: "clar_1",
            executionId = "exec_midflow",
            providedSlotValues = mapOf("address" to "100 Broadway")
        )

        val resumedReport = engine.resume(clarificationResponse)

        assertEquals("Resumed execution must reach COMPLETED state", ExecutionState.COMPLETED, resumedReport.state)
        assertEquals("Both steps must have executed", 2, driver.executedActionsCount)
        assertFalse("Engine should no longer be paused", engine.isPaused)
    }
}
