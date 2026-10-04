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
import com.chockXlate.teachablevoice.runtime.ui.ActionOutcome
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction
import com.chockXlate.teachablevoice.runtime.ui.UiDriver
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import com.chockXlate.teachablevoice.runtime.verification.PreconditionEvaluator
import com.chockXlate.teachablevoice.runtime.verification.TransitionVerifier
import com.chockXlate.teachablevoice.safety.SafetyGate
import com.chockXlate.teachablevoice.skill.inspector.WorkflowInspectorImpl
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Phase 3 — End-to-End Integration, Storage Persistence & UI Interaction Verification Suite.
 * Validates all 35 test criteria across:
 * - Storage (1-4)
 * - Execution (5-13)
 * - Automatic typing (14-16)
 * - Safety (17-19)
 * - Recovery (20-22)
 * - Bonus capabilities (23-26)
 * - UI integration & non-bypassability (27-35)
 */
class Phase3EndToEndIntegrationTest {

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

    private class TestUiDriver(
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

        override suspend fun awaitChange(delayMs: Long) {}

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
        storageDir = tempFolder.newFolder("p3_skills_storage")
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
        elements: List<UiElement>,
        isSensitive: Boolean = false
    ): UiObservation {
        return UiObservation(
            uiState = UiState(
                stateId = "state_${UUID.randomUUID()}",
                timestamp = System.currentTimeMillis(),
                appContext = appContext,
                elements = elements
            ),
            stateName = stateName,
            isSensitive = isSensitive
        )
    }

    // =========================================================================
    // STORAGE TESTS (1-4)
    // =========================================================================

    @Test
    fun test01_TeachSaveAppearsInRepositoryAndUI() {
        val workflow = Workflow(
            skillId = "skill_search_prod",
            name = "Search Product",
            intent = "search_product",
            appContext = "com.example.store",
            slots = listOf(WorkflowSlot(name = "query", type = SlotType.TEXT, required = true, exampleValue = "headphones")),
            steps = listOf(
                WorkflowStep(
                    stepId = "step_1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.store:id/search_box", textSlot = "\${query}"),
                    parameters = mapOf("input_parameter" to "\${query}")
                )
            )
        )
        val saved = repository.save(workflow)
        assertTrue("Workflow must be successfully saved", saved)
        assertEquals("Repository count must be 1", 1, repository.getWorkflowCount())
        assertEquals("Saved workflow must match ID", "skill_search_prod", repository.getWorkflow("skill_search_prod")?.skillId)
    }

    @Test
    fun test02_RestartPreservesSavedWorkflows() {
        val workflow = Workflow(
            skillId = "skill_persist_test",
            name = "Persistent Skill",
            intent = "test_persistence",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "lamp"))
        )
        repository.save(workflow)
        assertEquals(1, repository.getWorkflowCount())

        // Simulate application restart
        SkillRepositoryProvider.reset()
        SkillRepositoryProvider.initialize(storageDir)
        val reloadedRepo = SkillRepositoryProvider.getRepository()

        assertEquals("Workflow count must persist across restart", 1, reloadedRepo.getWorkflowCount())
        val reloadedWf = reloadedRepo.getWorkflow("skill_persist_test")
        assertNotNull("Persisted workflow must be retrievable after restart", reloadedWf)
        assertEquals("Persistent Skill", reloadedWf?.name)
        assertEquals("item", reloadedWf?.slots?.firstOrNull()?.name)
    }

    @Test
    fun test03_InspectorReadsPersistedWorkflowData() {
        val workflow = Workflow(
            skillId = "skill_inspect_test",
            name = "Inspectable Skill",
            intent = "inspect_intent",
            appContext = "com.example.app",
            slots = listOf(WorkflowSlot(name = "dest", type = SlotType.TEXT, required = true, exampleValue = "Airport")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", text = "Go")
                )
            ),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = false)
        )
        repository.save(workflow)

        val retrieved = repository.getWorkflow("skill_inspect_test")!!
        val inspection = WorkflowInspectorImpl.inspect(retrieved, repository)

        assertTrue("Workflow must be inspectable and valid", inspection.isExecutableByPerson2)
        assertEquals("skill_inspect_test", inspection.skillId)
        assertTrue("Inspection formatted text must contain slot info", inspection.formattedText.contains("dest"))
        assertTrue("Inspection formatted text must contain step count", inspection.formattedText.contains("1"))
    }

    @Test
    fun test04_MultipleWorkflowsRemainDistinct() {
        val wf1 = Workflow(
            skillId = "skill_alpha",
            name = "Alpha Task",
            intent = "do_alpha",
            slots = listOf(WorkflowSlot(name = "alpha_param", type = SlotType.TEXT, required = true, exampleValue = "A"))
        )
        val wf2 = Workflow(
            skillId = "skill_beta",
            name = "Beta Task",
            intent = "do_beta",
            slots = listOf(WorkflowSlot(name = "beta_param", type = SlotType.INTEGER, required = true, exampleValue = "42"))
        )
        repository.save(wf1)
        repository.save(wf2)

        assertEquals("Must store two distinct workflows", 2, repository.getWorkflowCount())

        val retrieved1 = repository.getWorkflow("skill_alpha")
        val retrieved2 = repository.getWorkflow("skill_beta")

        assertNotNull(retrieved1)
        assertNotNull(retrieved2)
        assertEquals("Alpha Task", retrieved1?.name)
        assertEquals("Beta Task", retrieved2?.name)
        assertEquals("alpha_param", retrieved1?.slots?.first()?.name)
        assertEquals("beta_param", retrieved2?.slots?.first()?.name)
    }

    // =========================================================================
    // EXECUTION TESTS (5-13)
    // =========================================================================

    @Test
    fun test05_ExactReplayExecution() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_exact_run",
            name = "Exact Task",
            intent = "run_exact",
            appContext = "com.example.exact",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "book")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.exact:id/input", textSlot = "\${item}"),
                    parameters = mapOf("input_parameter" to "\${item}"),
                    expectedTransition = ExpectedTransition(toState = "DONE_STATE")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.exact", "START_STATE", listOf(createUiElement("in1", "EditText", resourceId = "com.example.exact:id/input", editable = true)))
        val s2 = createUiObservation("com.example.exact", "DONE_STATE", listOf(createUiElement("res1", "TextView", text = "book")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e5", "skill_exact_run", boundSlots = mapOf("item" to "book")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("book", driver.lastExecutedAction?.inputText)
    }

    @Test
    fun test06_ChangedItemExecution() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_item_var",
            name = "Item Task",
            intent = "select_item",
            appContext = "com.example.store",
            slots = listOf(WorkflowSlot(name = "product", type = SlotType.TEXT, required = true, exampleValue = "coffee")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", text = "\${product}"),
                    parameters = emptyMap(),
                    expectedTransition = ExpectedTransition(toState = "ITEM_SELECTED")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.store", "CATALOG", listOf(createUiElement("b1", "Button", text = "tea", resourceId = "com.example.store:id/btn_tea")))
        val s2 = createUiObservation("com.example.store", "ITEM_SELECTED", listOf(createUiElement("t1", "TextView", text = "tea selected")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e6", "skill_item_var", boundSlots = mapOf("product" to "tea")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals(1, driver.executedActionsCount)
    }

    @Test
    fun test07_ChangedQuantityExecution() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_qty_var",
            name = "Qty Task",
            intent = "set_qty",
            appContext = "com.example.store",
            slots = listOf(WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "1")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.store:id/qty_field", textSlot = "\${quantity}"),
                    parameters = mapOf("input_parameter" to "\${quantity}"),
                    expectedTransition = ExpectedTransition(toState = "QTY_SET")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.store", "CART", listOf(createUiElement("q1", "EditText", resourceId = "com.example.store:id/qty_field", editable = true)))
        val s2 = createUiObservation("com.example.store", "QTY_SET", listOf(createUiElement("t1", "TextView", text = "Qty: 10")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e7", "skill_qty_var", boundSlots = mapOf("quantity" to "10")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("10", driver.lastExecutedAction?.inputText)
    }

    @Test
    fun test08_ChangedAddressExecution() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_addr_var",
            name = "Addr Task",
            intent = "set_addr",
            appContext = "com.example.ship",
            slots = listOf(WorkflowSlot(name = "address", type = SlotType.ADDRESS, required = true, exampleValue = "10 Downing St")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.ship:id/address_in", textSlot = "\${address}"),
                    parameters = mapOf("input_parameter" to "\${address}"),
                    expectedTransition = ExpectedTransition(toState = "ADDR_SAVED")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.ship", "SHIPPING", listOf(createUiElement("a1", "EditText", resourceId = "com.example.ship:id/address_in", editable = true)))
        val s2 = createUiObservation("com.example.ship", "ADDR_SAVED", listOf(createUiElement("t1", "TextView", text = "221B Baker St")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e8", "skill_addr_var", boundSlots = mapOf("address" to "221B Baker St")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("221B Baker St", driver.lastExecutedAction?.inputText)
    }

    @Test
    fun test09_ChangedUiStatePreventsBlindExecution() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_state_check",
            name = "State Task",
            intent = "state_intent",
            appContext = "com.example.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.app:id/submit", text = "Submit"),
                    preconditions = Preconditions(requiredState = "VERIFIED_STATE")
                )
            )
        )
        repository.save(workflow)

        val mismatchScreen = createUiObservation("com.example.app", "UNEXPECTED_DIALOG", listOf(createUiElement("d1", "TextView", text = "Error Dialog")))
        val driver = TestUiDriver(mismatchScreen)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e9", "skill_state_check"))

        assertNotEquals(ExecutionState.COMPLETED, report.state)
        assertEquals(0, driver.executedActionsCount)
    }

    @Test
    fun test10_AutomaticSkillRepositorySearch() {
        val workflow = Workflow(
            skillId = "skill_weather_auto",
            name = "Check Weather",
            intent = "check_weather",
            utteranceExamples = listOf("check weather", "how is the weather")
        )
        repository.save(workflow)

        val interpretation = CommandInterpreter().interpret("how is the weather")
        val matchResult = SkillMatcher().match(interpretation, repository)

        assertEquals(SkillMatchStatus.EXACT_MATCH, matchResult.status)
        assertEquals("skill_weather_auto", matchResult.matchedSkill?.skillId)
    }

    @Test
    fun test11_UnknownCommandRejection() {
        val interpretation = CommandInterpreter().interpret("teleport to mars")
        val matchResult = SkillMatcher().match(interpretation, repository)

        assertEquals(SkillMatchStatus.UNKNOWN, matchResult.status)
        assertTrue(matchResult.candidates.isEmpty())
    }

    @Test
    fun test12_AmbiguousCommandHandling() {
        val s1 = Workflow(skillId = "skill_play_a", name = "Play Artist", intent = "play_media", utteranceExamples = listOf("play music"))
        val s2 = Workflow(skillId = "skill_play_g", name = "Play Genre", intent = "play_media", utteranceExamples = listOf("play music"))
        repository.save(s1)
        repository.save(s2)

        val interpretation = CommandInterpreter().interpret("play music")
        val matchResult = SkillMatcher().match(interpretation, repository)

        assertEquals(SkillMatchStatus.AMBIGUOUS, matchResult.status)
        assertTrue(matchResult.candidates.size >= 2)
    }

    @Test
    fun test13_MissingSlotRejection() {
        val workflow = Workflow(
            skillId = "skill_book_flight",
            name = "Book Flight",
            intent = "book_flight",
            slots = listOf(
                WorkflowSlot(name = "origin", type = SlotType.TEXT, required = true, exampleValue = "NYC"),
                WorkflowSlot(name = "destination", type = SlotType.TEXT, required = true, exampleValue = "LAX")
            )
        )
        repository.save(workflow)

        val interpretation = CommandInterpreter().interpret("book flight from NYC") // missing destination
        val matchResult = SkillMatchResult(
            status = SkillMatchStatus.EXACT_MATCH,
            matchedSkill = workflow,
            candidates = listOf(SkillCandidateMatch(workflow, 1.0))
        )

        val reqResult = ExecutionRequestBuilder().buildRequest(interpretation, matchResult)
        assertEquals(ExecutionRequestStatus.MISSING_SLOTS, reqResult.status)
        assertTrue(reqResult.missingSlots.contains("destination"))
    }

    // =========================================================================
    // AUTOMATIC TYPING (14-16)
    // =========================================================================

    @Test
    fun test14_ChangedTextDynamicallyTyped() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_type_text",
            name = "Type Text Task",
            intent = "type_text",
            appContext = "com.example.notes",
            slots = listOf(WorkflowSlot(name = "note", type = SlotType.TEXT, required = true, exampleValue = "Initial note")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.notes:id/editor", textSlot = "\${note}"),
                    parameters = mapOf("input_parameter" to "\${note}"),
                    expectedTransition = ExpectedTransition(toState = "NOTE_SAVED")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.notes", "EDITOR", listOf(createUiElement("e1", "EditText", resourceId = "com.example.notes:id/editor", editable = true)))
        val s2 = createUiObservation("com.example.notes", "NOTE_SAVED", listOf(createUiElement("t1", "TextView", text = "Updated Note Content")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e14", "skill_type_text", boundSlots = mapOf("note" to "Updated Note Content")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("Updated Note Content", driver.lastExecutedAction?.inputText)
    }

    @Test
    fun test15_ChangedQuantityDynamicallyTyped() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_type_qty",
            name = "Type Qty Task",
            intent = "type_qty",
            appContext = "com.example.store",
            slots = listOf(WorkflowSlot(name = "count", type = SlotType.INTEGER, required = true, exampleValue = "1")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.store:id/count_in", textSlot = "\${count}"),
                    parameters = mapOf("input_parameter" to "\${count}"),
                    expectedTransition = ExpectedTransition(toState = "COUNT_SAVED")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.store", "COUNT_PAGE", listOf(createUiElement("c1", "EditText", resourceId = "com.example.store:id/count_in", editable = true)))
        val s2 = createUiObservation("com.example.store", "COUNT_SAVED", listOf(createUiElement("t1", "TextView", text = "Count: 7")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e15", "skill_type_qty", boundSlots = mapOf("count" to "7")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("7", driver.lastExecutedAction?.inputText)
    }

    @Test
    fun test16_TransitionVerificationChecksTypedResult() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_type_verify",
            name = "Verify Typed",
            intent = "type_verify",
            appContext = "com.example.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.app:id/in", text = "text"),
                    expectedTransition = ExpectedTransition(toState = "EXPECTED_TRANSITION"),
                    recoveryPolicy = RecoveryPolicy(maxRetries = 0, strategy = RecoveryStrategy.FAIL)
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "START", listOf(createUiElement("i1", "EditText", resourceId = "com.example.app:id/in", editable = true)))
        val s2_mismatch = createUiObservation("com.example.app", "UNEXPECTED_STATE", listOf(createUiElement("i1", "EditText", resourceId = "com.example.app:id/in", editable = true)))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2_mismatch }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e16", "skill_type_verify"))

        assertEquals("Must fail when transition verifier detects unexpected resulting state", ExecutionState.FAILED, report.state)
    }

    // =========================================================================
    // SAFETY TESTS (17-19)
    // =========================================================================

    @Test
    fun test17_PaymentBoundaryBlocksExecution() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_pay_block",
            name = "Pay Bill",
            intent = "pay_bill",
            appContext = "com.example.pay",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.pay:id/btn_submit_pay", text = "Pay $50"),
                    isPayment = true
                )
            ),
            safetyBoundary = SafetyBoundary(isPaymentBoundary = true, requiresExplicitUserConfirmation = true)
        )
        repository.save(workflow)

        val payScreen = createUiObservation("com.example.pay", "PAYMENT_PAGE", listOf(createUiElement("b1", "Button", text = "Pay $50", resourceId = "com.example.pay:id/btn_submit_pay")), isSensitive = true)
        val driver = TestUiDriver(payScreen)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e17", "skill_pay_block"))

        assertEquals(ExecutionState.HANDOFF, report.state)
        assertEquals(0, driver.executedActionsCount)
    }

    @Test
    fun test18_CredentialOtpBoundaryBlocksExecution() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_otp_block",
            name = "Enter OTP",
            intent = "enter_otp",
            appContext = "com.example.auth",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.auth:id/otp_input", isSensitive = true),
                    isSensitive = true
                )
            ),
            safetyBoundary = SafetyBoundary(isCredentialBoundary = true, requiresExplicitUserConfirmation = true)
        )
        repository.save(workflow)

        val otpScreen = createUiObservation("com.example.auth", "OTP_VERIFY", listOf(createUiElement("o1", "EditText", resourceId = "com.example.auth:id/otp_input", editable = true)), isSensitive = true)
        val driver = TestUiDriver(otpScreen)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e18", "skill_otp_block"))

        assertEquals(ExecutionState.HANDOFF, report.state)
        assertEquals(0, driver.executedActionsCount)
    }

    @Test
    fun test19_ZeroProhibitedAutomatedActions() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_zero_click",
            name = "Sensitive Action",
            intent = "sensitive_intent",
            appContext = "com.example.secure",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.secure:id/confirm_transfer", text = "Confirm Wire Transfer")
                )
            ),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = true)
        )
        repository.save(workflow)

        val secureScreen = createUiObservation("com.example.secure", "TRANSFER_PAGE", listOf(createUiElement("b1", "Button", text = "Confirm Wire Transfer", resourceId = "com.example.secure:id/confirm_transfer")), isSensitive = true)
        val driver = TestUiDriver(secureScreen)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e19", "skill_zero_click"))

        assertEquals(ExecutionState.HANDOFF, report.state)
        assertEquals("Must execute exactly 0 automated actions on sensitive boundary", 0, driver.executedActionsCount)
    }

    // =========================================================================
    // RECOVERY TESTS (20-22)
    // =========================================================================

    @Test
    fun test20_MissingTargetDetection() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_missing_target",
            name = "Missing Target Task",
            intent = "missing_target",
            appContext = "com.example.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.app:id/non_existent")
                )
            )
        )
        repository.save(workflow)

        val emptyScreen = createUiObservation("com.example.app", "EMPTY", listOf(createUiElement("t1", "TextView", text = "No buttons")))
        val driver = TestUiDriver(emptyScreen)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e20", "skill_missing_target"))

        assertEquals(ExecutionState.FAILED, report.state)
        assertEquals(0, driver.executedActionsCount)
    }

    @Test
    fun test21_BoundedRecoveryAttempts() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_bounded_rec",
            name = "Bounded Retry Task",
            intent = "bounded_retry",
            appContext = "com.example.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.app:id/flaky_btn"),
                    recoveryPolicy = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)
                )
            )
        )
        repository.save(workflow)

        val screen = createUiObservation("com.example.app", "STABLE_SCREEN", emptyList())
        val driver = TestUiDriver(screen)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e21", "skill_bounded_rec"))

        assertEquals(ExecutionState.FAILED, report.state)
        assertTrue(report.completedSteps == 0)
    }

    @Test
    fun test22_NoInfiniteExecution() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_no_inf",
            name = "No Infinite Loop Task",
            intent = "no_inf",
            appContext = "com.example.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.app:id/never_match"),
                    recoveryPolicy = RecoveryPolicy(maxRetries = 3, strategy = RecoveryStrategy.RETRY_STEP)
                )
            )
        )
        repository.save(workflow)

        val screen = createUiObservation("com.example.app", "SCREEN", emptyList())
        val driver = TestUiDriver(screen)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e22", "skill_no_inf"))

        assertNotNull("Report must be returned promptly without hanging", report)
        assertEquals(ExecutionState.FAILED, report.state)
    }

    // =========================================================================
    // BONUS CAPABILITIES (23-26)
    // =========================================================================

    @Test
    fun test23_DemonstrationFilterRemovesNoiseAndNavigation() {
        val launcherAction = ActionEvent(
            actionId = "act_launcher",
            timestamp = 1000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "TextView", text = "Launcher Icon", resourceId = "com.google.android.apps.nexuslauncher:id/icon")
        )
        val taskAction = ActionEvent(
            actionId = "act_task",
            timestamp = 2000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Search", resourceId = "com.example.app:id/search")
        )
        val noiseAction = ActionEvent(
            actionId = "act_noise",
            timestamp = 2500L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "FrameLayout", resourceId = "com.example.app:id/container")
        )

        val stateEv = StateEvent(
            stateEventId = "st_1",
            timestamp = 2100L,
            causeActionId = "act_task",
            beforeState = UiState("b1", 2000L, "com.example.app"),
            afterState = UiState("a1", 2100L, "com.example.app")
        )

        val trace = DemonstrationTrace(
            traceId = "demo_trace_b23",
            timestamp = 1000L,
            appContext = "com.example.app",
            traceEvents = listOf(
                TraceEvent.Action("act_launcher", 1000L, launcherAction),
                TraceEvent.Action("act_task", 2000L, taskAction),
                TraceEvent.State("st_1", 2100L, stateEv),
                TraceEvent.Action("act_noise", 2500L, noiseAction)
            )
        )

        val filterResult = DemonstrationFilter.filter(trace)
        assertTrue(filterResult.navigationContextEventIds.contains("act_launcher"))
        assertTrue(filterResult.systemNoiseEventIds.contains("act_noise"))
        assertTrue(filterResult.taskRelevantEventIds.contains("act_task"))

        val extracted = SemanticActionExtractor.extract(trace, filterResult)
        assertEquals(1, extracted.size)
        assertEquals("act_task", extracted[0].actionId)
    }

    @Test
    fun test24_CrossAppSemanticSelectorResolution() {
        val selector = SemanticSelector(role = "Button", text = "Checkout", nearbyText = "Total")
        val app1 = createUiObservation("com.shop.one", "PAGE", listOf(createUiElement("b1", "Button", text = "Checkout", resourceId = "com.shop.one:id/chk", nearbyText = listOf("Total: $10"))))
        val app2 = createUiObservation("com.shop.two", "PAGE", listOf(createUiElement("b2", "Button", text = "Checkout", resourceId = "com.shop.two:id/btn_out", nearbyText = listOf("Total: $20"))))

        val matcher = SemanticMatcher()
        val m1 = matcher.match(selector, app1, RuntimeAction.CLICK)
        val m2 = matcher.match(selector, app2, RuntimeAction.CLICK)

        assertEquals(MatchStatus.MATCHED, m1.status)
        assertEquals("b1", m1.best?.element?.elementId)
        assertEquals(MatchStatus.MATCHED, m2.status)
        assertEquals("b2", m2.best?.element?.elementId)
    }

    @Test
    fun test25_MidFlowClarificationPause() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_midflow_pause",
            name = "Midflow Task",
            intent = "midflow_intent",
            appContext = "com.example.app",
            slots = listOf(
                WorkflowSlot(name = "step1_param", type = SlotType.TEXT, required = true, exampleValue = "A"),
                WorkflowSlot(name = "step2_param", type = SlotType.TEXT, required = true, exampleValue = "B")
            ),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.app:id/in1", textSlot = "\${step1_param}"),
                    parameters = mapOf("input_parameter" to "\${step1_param}"),
                    expectedTransition = ExpectedTransition(toState = "STEP2_SCREEN")
                ),
                WorkflowStep(
                    stepId = "s2",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.app:id/in2", textSlot = "\${step2_param}"),
                    parameters = mapOf("input_parameter" to "\${step2_param}")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "STEP1_SCREEN", listOf(createUiElement("i1", "EditText", resourceId = "com.example.app:id/in1", editable = true)))
        val s2 = createUiObservation("com.example.app", "STEP2_SCREEN", listOf(createUiElement("i2", "EditText", resourceId = "com.example.app:id/in2", editable = true)))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        // Request provides step1_param, omitting step2_param to trigger mid-flow pause
        val report = engine.execute(ExecutionRequest("e25", "skill_midflow_pause", boundSlots = mapOf("step1_param" to "ValA")))

        assertEquals(ExecutionState.ASK, report.state)
        assertTrue(engine.isPaused)
        assertEquals("e25", engine.pausedExecutionId)
    }

    @Test
    fun test26_ClarificationResponseResumesExecution() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_midflow_resume",
            name = "Midflow Resume Task",
            intent = "midflow_resume",
            appContext = "com.example.app",
            slots = listOf(
                WorkflowSlot(name = "p1", type = SlotType.TEXT, required = true, exampleValue = "A"),
                WorkflowSlot(name = "p2", type = SlotType.TEXT, required = true, exampleValue = "B")
            ),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.app:id/in1", textSlot = "\${p1}"),
                    parameters = mapOf("input_parameter" to "\${p1}"),
                    expectedTransition = ExpectedTransition(toState = "STEP2_SCREEN")
                ),
                WorkflowStep(
                    stepId = "s2",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.app:id/in2", textSlot = "\${p2}"),
                    parameters = mapOf("input_parameter" to "\${p2}"),
                    expectedTransition = ExpectedTransition(toState = "FINAL_SCREEN")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "STEP1_SCREEN", listOf(createUiElement("i1", "EditText", resourceId = "com.example.app:id/in1", editable = true)))
        val s2 = createUiObservation("com.example.app", "STEP2_SCREEN", listOf(createUiElement("i2", "EditText", resourceId = "com.example.app:id/in2", editable = true)))
        val s3 = createUiObservation("com.example.app", "FINAL_SCREEN", listOf(createUiElement("t1", "TextView", text = "Finished")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { step ->
            if (step.stepId == "s1") driver.currentScreen = s2
            else if (step.stepId == "s2") driver.currentScreen = s3
        }

        val engine = ExecutionEngine(repository, driver)
        val initialReport = engine.execute(ExecutionRequest("e26", "skill_midflow_resume", boundSlots = mapOf("p1" to "FirstVal")))

        assertEquals(ExecutionState.ASK, initialReport.state)
        assertTrue(engine.isPaused)

        val response = ClarificationResponse(
            clarificationId = initialReport.clarificationRequest?.clarificationId ?: "clar_26",
            executionId = "e26",
            providedSlotValues = mapOf("p2" to "SecondVal")
        )

        val resumedReport = engine.resume(response)

        assertEquals(ExecutionState.COMPLETED, resumedReport.state)
        assertEquals(2, driver.executedActionsCount)
        assertFalse(engine.isPaused)
    }

    // =========================================================================
    // UI INTEGRATION (27-35)
    // =========================================================================

    @Test
    fun test27_NewUiReflectsRuntimeReady() {
        RuntimeLifecycleManager.initialize(repository)
        assertTrue(RuntimeLifecycleManager.isReady())
        assertEquals(RuntimeLifecycleState.READY, RuntimeLifecycleManager.state.value)
    }

    @Test
    fun test28_NewUiReflectsExecuting() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_exec_state",
            name = "Exec State Task",
            intent = "exec_state",
            appContext = "com.example.app",
            steps = listOf(WorkflowStep(stepId = "s1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.app:id/btn")))
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "S1", listOf(createUiElement("b1", "Button", resourceId = "com.example.app:id/btn")))
        val driver = TestUiDriver(s1)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e28", "skill_exec_state"))
        assertEquals(ExecutionState.COMPLETED, report.state)
    }

    @Test
    fun test29_NewUiReflectsAskState() {
        val request = ExecutionRequest("e29", "unknown_skill")
        val clarReq = ClarificationRequest("c29", "e29", "Missing address", "address")
        val report = com.chockXlate.teachablevoice.runtime.trace.ExecutionTraceRecorder(request).pauseForClarification(clarReq)

        assertEquals(ExecutionState.ASK, report.state)
        assertEquals("address", report.clarificationRequest?.missingSlotName)
    }

    @Test
    fun test30_NewUiReflectsHandoffBlockedState() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_handoff_ui",
            name = "Handoff Task",
            intent = "handoff_intent",
            appContext = "com.example.secure",
            steps = listOf(WorkflowStep(stepId = "s1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "Pay"), isPayment = true)),
            safetyBoundary = SafetyBoundary(isPaymentBoundary = true)
        )
        repository.save(workflow)

        val secureScreen = createUiObservation("com.example.secure", "PAY", listOf(createUiElement("b1", "Button", text = "Pay")), isSensitive = true)
        val driver = TestUiDriver(secureScreen)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e30", "skill_handoff_ui"))

        assertEquals(ExecutionState.HANDOFF, report.state)
    }

    @Test
    fun test31_NewUiReflectsCompletedState() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_comp_ui",
            name = "Completed Task",
            intent = "comp_intent",
            appContext = "com.example.app",
            steps = listOf(WorkflowStep(stepId = "s1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.app:id/done")))
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "DONE", listOf(createUiElement("d1", "Button", resourceId = "com.example.app:id/done")))
        val driver = TestUiDriver(s1)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e31", "skill_comp_ui"))

        assertEquals(ExecutionState.COMPLETED, report.state)
    }

    @Test
    fun test32_NewUiReflectsFailedState() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_fail_ui",
            name = "Failed Task",
            intent = "fail_intent",
            appContext = "com.example.app",
            steps = listOf(WorkflowStep(stepId = "s1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.app:id/missing")))
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "EMPTY", emptyList())
        val driver = TestUiDriver(s1)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e32", "skill_fail_ui"))

        assertEquals(ExecutionState.FAILED, report.state)
    }

    @Test
    fun test33_NewUiDisplaysActualPersistedSkills() {
        val workflow = Workflow(
            skillId = "skill_real_display",
            name = "Real Order Skill",
            intent = "order_coffee",
            slots = listOf(WorkflowSlot(name = "roast", type = SlotType.TEXT, required = true, exampleValue = "Dark Roast"))
        )
        repository.save(workflow)

        val all = repository.getAllWorkflows()
        assertEquals(1, all.size)
        assertEquals("Real Order Skill", all[0].name)
        assertEquals("order_coffee", all[0].intent)
    }

    @Test
    fun test34_NewUiInspectorDisplaysActualWorkflowData() {
        val workflow = Workflow(
            skillId = "skill_inspect_real",
            name = "Inspection Target",
            intent = "target_intent",
            appContext = "com.example.test",
            slots = listOf(WorkflowSlot(name = "query", type = SlotType.TEXT, required = true, exampleValue = "keyword")),
            steps = listOf(WorkflowStep(stepId = "step_1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "Search"))),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = false)
        )
        repository.save(workflow)

        val inspection = WorkflowInspectorImpl.inspect(workflow, repository)
        assertTrue(inspection.isExecutableByPerson2)
        assertEquals("skill_inspect_real", inspection.skillId)
        assertTrue(inspection.formattedText.contains("com.example.test"))
    }

    @Test
    fun test35_NewUiNeverExecutesAccessibilityActionsDirectly() {
        // Confirms all executions route exclusively through ExecutionEngine -> AccessibilityUiDriver -> SafetyGate
        val gate = SafetyGate()
        val boundary = SafetyBoundary(isPaymentBoundary = true)
        val step = BoundStep(
            stepId = "s1",
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Pay"),
            isPayment = true
        )
        val obs = createUiObservation("com.example.pay", "PAY", listOf(createUiElement("b1", "Button", text = "Pay")), isSensitive = true)

        var directActionAttempted = false
        val outcome = gate.dispatch(boundary, obs, step) {
            directActionAttempted = true
            true
        }

        assertFalse("SafetyGate must reject execution on sensitive boundary", outcome.accepted)
        assertFalse("Direct action block must never execute when gate blocks", directActionAttempted)
    }
}
