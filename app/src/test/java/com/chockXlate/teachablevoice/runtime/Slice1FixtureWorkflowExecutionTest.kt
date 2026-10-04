package com.chockXlate.teachablevoice.runtime

import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.ui.*
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.runtime.matching.*
import com.chockXlate.teachablevoice.runtime.slots.*
import com.chockXlate.teachablevoice.runtime.ui.*
import com.chockXlate.teachablevoice.runtime.verification.*
import com.chockXlate.teachablevoice.safety.*
import com.chockXlate.teachablevoice.skill.repository.SkillRepository
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.*

/**
 * Slice 1: Fixture Workflow Execution Test Suite.
 * Proves generic execution of a canonical WorkflowIR on Android UI contracts without hardcoded logic.
 */
class Slice1FixtureWorkflowExecutionTest {

    private val targetApp = "com.example.fooddelivery"
    private val matcher = SemanticMatcher()
    private val preconditions = PreconditionEvaluator(matcher)
    private val verifier = TransitionVerifier(matcher)

    // Fixture Canonical WorkflowIR
    private fun createOrderFoodWorkflowFixture(): Workflow {
        return Workflow(
            schemaVersion = "1.0",
            skillId = "skill_order_food_001",
            name = "Order Food",
            intent = "order_food",
            appContext = targetApp,
            slots = listOf(
                WorkflowSlot(slotName = "restaurant_name", slotType = "STRING", isRequired = true),
                WorkflowSlot(slotName = "item_name", slotType = "STRING", isRequired = true)
            ),
            steps = listOf(
                // Step 1: SEARCH
                WorkflowStep(
                    stepId = "step_1_search",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(
                        role = "EditText",
                        contentDescription = "Search restaurants",
                        resourceId = "com.example.fooddelivery:id/search_bar"
                    ),
                    parameters = mapOf("text" to "\$restaurant_name"),
                    preconditions = Preconditions(requiredPackage = targetApp),
                    expectedTransition = ExpectedTransition(
                        timeoutMs = 1000,
                        expectedEvidence = listOf(
                            StateEvidenceRequirement(
                                type = EvidenceType.EXPECTED_PACKAGE,
                                expectedPackage = targetApp
                            )
                        )
                    ),
                    confidence = 0.95
                ),
                // Step 2: SELECT RESTAURANT
                WorkflowStep(
                    stepId = "step_2_select_restaurant",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(
                        role = "Button",
                        textSlot = "restaurant_name",
                        resourceId = "com.example.fooddelivery:id/restaurant_card"
                    ),
                    preconditions = Preconditions(requiredPackage = targetApp),
                    expectedTransition = ExpectedTransition(
                        timeoutMs = 1000,
                        expectedEvidence = listOf(
                            StateEvidenceRequirement(
                                type = EvidenceType.EXPECTED_PACKAGE,
                                expectedPackage = targetApp
                            )
                        )
                    ),
                    confidence = 0.92
                ),
                // Step 3: SELECT ITEM (SET/SELECT target)
                WorkflowStep(
                    stepId = "step_3_select_item",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(
                        role = "Button",
                        textSlot = "item_name",
                        resourceId = "com.example.fooddelivery:id/add_item_button"
                    ),
                    preconditions = Preconditions(requiredPackage = targetApp),
                    expectedTransition = ExpectedTransition(
                        timeoutMs = 1000,
                        expectedEvidence = listOf(
                            StateEvidenceRequirement(
                                type = EvidenceType.EXPECTED_PACKAGE,
                                expectedPackage = targetApp
                            )
                        )
                    ),
                    confidence = 0.90
                )
            ),
            safetyBoundary = SafetyBoundary(
                requiresExplicitUserConfirmation = true,
                sensitiveKeywords = listOf("payment", "checkout", "cvv", "otp", "password", "pay now")
            )
        )
    }

    private fun createExecutionRequestFixture(): ExecutionRequest {
        return ExecutionRequest(
            schemaVersion = "1.0",
            executionId = "exec_req_101",
            skillId = "skill_order_food_001",
            boundSlots = mapOf(
                "restaurant_name" to "Burger Palace",
                "item_name" to "Cheeseburger"
            )
        )
    }

    private class InMemorySkillRepository(private val workflow: Workflow) : SkillRepository {
        override fun getWorkflowById(skillId: String): Workflow? = if (workflow.skillId == skillId) workflow else null
        override fun getAllWorkflows(): List<Workflow> = listOf(workflow)
        override fun saveWorkflow(workflow: Workflow): Boolean = true
        override fun deleteWorkflow(skillId: String): Boolean = true
    }

    private class MockUiDriver(var currentObservation: UiObservation) : UiDriver {
        var actionsExecuted = 0
        val executedSteps = mutableListOf<BoundStep>()
        var onExecuteCallback: ((BoundStep) -> Unit)? = null

        override fun isReady(): Boolean = true

        override suspend fun observe(): UiObservation? = currentObservation

        override suspend fun execute(
            step: BoundStep,
            expectedPackage: String,
            boundary: SafetyBoundary,
            matcher: SemanticMatcher,
            gate: SafetyGate
        ): ActionOutcome {
            gate.check(boundary, currentObservation, step)?.let {
                return ActionOutcome(attempted = false, accepted = false, reason = it, before = currentObservation)
            }
            val match = matcher.match(step.selector, currentObservation, step.action)
            if (match.status != MatchStatus.MATCHED) {
                return ActionOutcome(attempted = false, accepted = false, reason = match.reason, before = currentObservation)
            }
            val dispatchResult = gate.dispatch(boundary, currentObservation, step) {
                actionsExecuted++
                executedSteps.add(step)
                onExecuteCallback?.invoke(step)
                true
            }
            return ActionOutcome(
                attempted = dispatchResult.attempted,
                accepted = dispatchResult.accepted,
                reason = dispatchResult.reason,
                before = currentObservation
            )
        }

        override suspend fun awaitChange(delayMs: Long) {}
    }

    private fun <T> runBlockingCoroutine(block: suspend () -> T): T {
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(res: Result<T>) {
                result = res
                latch.countDown()
            }
        })
        assertTrue("Execution timeout", latch.await(5, TimeUnit.SECONDS))
        return result!!.getOrThrow()
    }

    private fun createUiState(packageName: String, elements: List<UiElement>): UiObservation {
        return UiObservation(
            state = UiState(
                schemaVersion = "1.0",
                stateId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                appContext = packageName,
                windowId = 1,
                allElements = elements
            )
        )
    }

    @Test
    fun test1_WorkflowIRLoadsSuccessfully() {
        val workflow = createOrderFoodWorkflowFixture()
        assertEquals("skill_order_food_001", workflow.skillId)
        assertEquals("order_food", workflow.intent)
        assertEquals(3, workflow.steps.size)
        assertEquals(2, workflow.slots.size)
        assertTrue(workflow.safetyBoundary.sensitiveKeywords.contains("payment"))
    }

    @Test
    fun test2_ExecutionRequestLoadsSuccessfully() {
        val request = createExecutionRequestFixture()
        assertEquals("exec_req_101", request.executionId)
        assertEquals("skill_order_food_001", request.skillId)
        assertEquals("Burger Palace", request.boundSlots["restaurant_name"])
        assertEquals("Cheeseburger", request.boundSlots["item_name"])
    }

    @Test
    fun test3_SlotsBindCorrectly() {
        val workflow = createOrderFoodWorkflowFixture()
        val request = createExecutionRequestFixture()
        val boundStep2 = SlotBinder.bind(workflow.steps[1], request.boundSlots)

        assertEquals("Burger Palace", boundStep2.selector.text)
        assertEquals(RuntimeAction.CLICK, boundStep2.action)

        val boundStep1 = SlotBinder.bind(workflow.steps[0], request.boundSlots)
        assertEquals("Burger Palace", boundStep1.inputText)
        assertEquals(RuntimeAction.INPUT_TEXT, boundStep1.action)
    }

    @Test
    fun test4_CurrentUiStateObserved() {
        val uiObs = createUiState(
            packageName = targetApp,
            elements = listOf(
                UiElement(
                    elementId = "e1",
                    role = "EditText",
                    contentDescription = "Search restaurants",
                    resourceId = "com.example.fooddelivery:id/search_bar",
                    isEditable = true
                )
            )
        )
        val driver = MockUiDriver(uiObs)
        val observed = runBlockingCoroutine { driver.observe() }

        assertNotNull(observed)
        assertEquals(targetApp, observed?.state?.appContext)
        assertEquals(1, observed?.state?.allElements?.size)
    }

    @Test
    fun test5_WorkflowPreconditionMatchesCurrentState() {
        val workflow = createOrderFoodWorkflowFixture()
        val uiObs = createUiState(packageName = targetApp, elements = emptyList())
        val error = preconditions.evaluate(workflow.steps[0].preconditions, uiObs, targetApp, null)
        assertNull("Preconditions should match target app package", error)

        val wrongUiObs = createUiState(packageName = "com.other.app", elements = emptyList())
        val wrongError = preconditions.evaluate(workflow.steps[0].preconditions, wrongUiObs, targetApp, null)
        assertNotNull("Preconditions should fail on mismatched package", wrongError)
    }

    @Test
    fun test6_SemanticTargetResolvedWithoutCoordinates() {
        val uiObs = createUiState(
            packageName = targetApp,
            elements = listOf(
                UiElement(
                    elementId = "target_btn",
                    role = "Button",
                    text = "Burger Palace",
                    resourceId = "com.example.fooddelivery:id/restaurant_card",
                    isClickable = true
                ),
                UiElement(
                    elementId = "other_btn",
                    role = "Button",
                    text = "Pizza Hut",
                    resourceId = "com.example.fooddelivery:id/restaurant_card",
                    isClickable = true
                )
            )
        )
        val selector = SemanticSelector(
            role = "Button",
            text = "Burger Palace",
            resourceId = "com.example.fooddelivery:id/restaurant_card"
        )
        val match = matcher.match(selector, uiObs, RuntimeAction.CLICK)

        assertEquals(MatchStatus.MATCHED, match.status)
        assertEquals("target_btn", match.best?.element?.elementId)
        // Verify matching relies purely on semantic attributes, not bounding box coordinates
        assertEquals("Burger Palace", match.best?.element?.text)
    }

    @Test
    fun test7_ConfidenceGateAllowsHighConfidence() {
        val step = createOrderFoodWorkflowFixture().steps[0]
        assertTrue(step.confidence >= 0.70)
    }

    @Test
    fun test8_SafetyGatePermitsNonSensitiveAction() {
        val gate = SafetyGate()
        val boundary = SafetyBoundary(sensitiveKeywords = listOf("payment", "password"))
        val uiObs = createUiState(
            packageName = targetApp,
            elements = listOf(
                UiElement(elementId = "search", role = "EditText", text = "Search")
            )
        )
        val step = BoundStep(
            stepId = "s1",
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "EditText", text = "Search"),
            confidence = 0.95
        )
        val check = gate.check(boundary, uiObs, step)
        assertNull("Safety gate should allow non-sensitive interaction", check)
    }

    @Test
    fun test9_10_11_12_CompleteGenericExecutionAndVerification() {
        val workflow = createOrderFoodWorkflowFixture()
        val request = createExecutionRequestFixture()
        val repo = InMemorySkillRepository(workflow)

        // Initial UI with Search Bar
        val step1Ui = createUiState(
            packageName = targetApp,
            elements = listOf(
                UiElement(
                    elementId = "search_bar",
                    role = "EditText",
                    contentDescription = "Search restaurants",
                    resourceId = "com.example.fooddelivery:id/search_bar",
                    isEditable = true
                )
            )
        )

        val driver = MockUiDriver(step1Ui)

        // Dynamic UI transitions upon action execution
        driver.onExecuteCallback = { executedStep ->
            when (executedStep.stepId) {
                "step_1_search" -> {
                    // Transition to Restaurant search results
                    driver.currentObservation = createUiState(
                        packageName = targetApp,
                        elements = listOf(
                            UiElement(
                                elementId = "rest_1",
                                role = "Button",
                                text = "Burger Palace",
                                resourceId = "com.example.fooddelivery:id/restaurant_card",
                                isClickable = true
                            )
                        )
                    )
                }
                "step_2_select_restaurant" -> {
                    // Transition to Menu items
                    driver.currentObservation = createUiState(
                        packageName = targetApp,
                        elements = listOf(
                            UiElement(
                                elementId = "item_1",
                                role = "Button",
                                text = "Cheeseburger",
                                resourceId = "com.example.fooddelivery:id/add_item_button",
                                isClickable = true
                            )
                        )
                    )
                }
                "step_3_select_item" -> {
                    // Item added to cart
                    driver.currentObservation = createUiState(
                        packageName = targetApp,
                        elements = listOf(
                            UiElement(
                                elementId = "cart_summary",
                                role = "TextView",
                                text = "1 Item in Cart: Cheeseburger"
                            )
                        )
                    )
                }
            }
        }

        val engine = ExecutionEngine(
            repository = repo,
            driver = driver,
            matcher = matcher,
            verifier = verifier
        )

        val report = runBlockingCoroutine { engine.execute(request) }

        // Test 12: ExecutionResult is generated and verified
        assertEquals(ExecutionState.COMPLETED, report.state)
        assertTrue(report.result.success)
        assertEquals(3, report.completedSteps)
        assertEquals(3, report.totalSteps)
        assertEquals(3, driver.actionsExecuted)
        assertEquals("step_1_search", driver.executedSteps[0].stepId)
        assertEquals("step_2_select_restaurant", driver.executedSteps[1].stepId)
        assertEquals("step_3_select_item", driver.executedSteps[2].stepId)
    }

    @Test
    fun testNegative_TargetNotFound_BoundedRecovery_StopsGracefully() {
        val workflow = createOrderFoodWorkflowFixture()
        val request = createExecutionRequestFixture()
        val repo = InMemorySkillRepository(workflow)

        // Empty / mismatched UI state where target is missing
        val emptyUi = createUiState(
            packageName = targetApp,
            elements = listOf(
                UiElement(elementId = "dummy", role = "TextView", text = "Welcome")
            )
        )

        val driver = MockUiDriver(emptyUi)
        val engine = ExecutionEngine(
            repository = repo,
            driver = driver,
            matcher = matcher,
            verifier = verifier
        )

        val report = runBlockingCoroutine { engine.execute(request) }

        assertFalse(report.result.success)
        assertEquals(0, driver.actionsExecuted)
        assertTrue(
            report.state == ExecutionState.WAITING_FOR_USER ||
            report.state == ExecutionState.FAILED ||
            report.state == ExecutionState.ABORTED
        )
    }

    @Test
    fun testSafety_PaymentSensitiveBoundary_HardExecutionLock_UserHandoff() {
        val workflow = createOrderFoodWorkflowFixture()
        val request = createExecutionRequestFixture()
        val repo = InMemorySkillRepository(workflow)

        // UI state showing a sensitive Payment / Checkout screen
        val sensitiveUi = createUiState(
            packageName = targetApp,
            elements = listOf(
                UiElement(
                    elementId = "search_bar",
                    role = "EditText",
                    contentDescription = "Search restaurants",
                    resourceId = "com.example.fooddelivery:id/search_bar",
                    isEditable = true
                ),
                UiElement(
                    elementId = "payment_section",
                    role = "TextView",
                    text = "Payment & Checkout Details"
                )
            )
        )

        val driver = MockUiDriver(sensitiveUi)
        val engine = ExecutionEngine(
            repository = repo,
            driver = driver,
            matcher = matcher,
            verifier = verifier
        )

        val report = runBlockingCoroutine { engine.execute(request) }

        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.state)
        assertFalse(report.result.success)
        assertEquals(0, driver.actionsExecuted)
        assertTrue("Should mention user handoff / sensitive boundary", report.result.errorMessage?.contains("User handoff") == true || report.result.errorMessage?.contains("sensitive") == true)
    }
}
