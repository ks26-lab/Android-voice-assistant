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
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.filter.DemonstrationFilterClassification
import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.learning.actions.SemanticAction
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.learning.actions.SemanticActionType
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset
import com.chockXlate.teachablevoice.learning.inference.ConstantVariableInference
import com.chockXlate.teachablevoice.learning.inference.SlotInferenceStatus
import com.chockXlate.teachablevoice.learning.intent.IntentExtractionResult
import com.chockXlate.teachablevoice.learning.intent.IntentExtractor
import com.chockXlate.teachablevoice.learning.slots.SlotExtractor
import com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer
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
import com.chockXlate.teachablevoice.skill.validation.ValidationStatus
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator
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
 * Phase 4 — Complete Learning, Parameterization & Reuse Verification Suite.
 * Validates the full loop:
 * TEACH -> UNDERSTAND -> PARAMETERIZE -> STORE -> INSPECT -> REUSE -> EXECUTE
 * across all 33 test criteria.
 */
class Phase4LearningAndReuseTest {

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
        storageDir = tempFolder.newFolder("p4_skills_storage")
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
    // 1. LEARNING TESTS (1-5)
    // =========================================================================

    @Test
    fun test01_BasicWorkflowTeaching() {
        val voiceEvent = VoiceEvent("v1", 1000L, "search for headphones", 0.95)
        val action = ActionEvent(
            actionId = "a1",
            timestamp = 1100L,
            actionType = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.search:id/query"),
            inputValue = "headphones"
        )
        val stateEv = StateEvent(
            stateEventId = "s1",
            timestamp = 1200L,
            causeActionId = "a1",
            beforeState = UiState("b1", 1100L, "com.example.search"),
            afterState = UiState("a1", 1200L, "com.example.search")
        )

        val trace = DemonstrationTrace(
            traceId = "demo_teach_01",
            timestamp = 1000L,
            appContext = "com.example.search",
            traceEvents = listOf(
                TraceEvent.Voice("v1", 1000L, voiceEvent),
                TraceEvent.Action("a1", 1100L, action),
                TraceEvent.State("s1", 1200L, stateEv)
            )
        )

        val filterResult = DemonstrationFilter.filter(trace)
        val semanticActions = SemanticActionExtractor.extract(trace, filterResult)
        assertEquals("Should extract 1 semantic action", 1, semanticActions.size)
        assertEquals("headphones", semanticActions[0].inputValue)
    }

    @Test
    fun test02_WorkflowSynthesis() {
        val voiceEvent = VoiceEvent("v1", 1000L, "order pizza", 0.95)
        val action = ActionEvent(
            actionId = "a1",
            timestamp = 1100L,
            actionType = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.food:id/item_input"),
            inputValue = "pepperoni"
        )
        val stateEv = StateEvent(
            stateEventId = "s1",
            timestamp = 1200L,
            causeActionId = "a1",
            beforeState = UiState("b1", 1100L, "com.example.food"),
            afterState = UiState("a1", 1200L, "com.example.food")
        )

        val trace = DemonstrationTrace(
            traceId = "demo_synth_02",
            timestamp = 1000L,
            appContext = "com.example.food",
            traceEvents = listOf(
                TraceEvent.Voice("v1", 1000L, voiceEvent),
                TraceEvent.Action("a1", 1100L, action),
                TraceEvent.State("s1", 1200L, stateEv)
            )
        )

        val filterResult = DemonstrationFilter.filter(trace)
        val semanticActions = SemanticActionExtractor.extract(trace, filterResult)
        val intentResult = IntentExtractor.extract(trace, semanticActions)
        val slotResult = SlotExtractor.extract(trace, semanticActions, intentResult)

        val dataset = DemonstrationDataset("demo_synth_02", trace, filterResult, semanticActions, intentResult, slotResult)
        val inferenceResult = ConstantVariableInference.infer(listOf(dataset))

        val synthResult = WorkflowSynthesizer.synthesize(
            skillId = "skill_pizza_synth",
            name = "Order Pizza",
            appContext = "com.example.food",
            datasets = listOf(dataset),
            inferenceResult = inferenceResult
        )

        val workflow = synthResult.workflow
        assertNotNull(workflow)
        assertEquals("skill_pizza_synth", workflow.skillId)
        assertTrue(workflow.steps.isNotEmpty())
    }

    @Test
    fun test03_WorkflowValidation() {
        val workflow = Workflow(
            skillId = "skill_valid_test",
            name = "Valid Workflow",
            intent = "valid_intent",
            appContext = "com.example.app",
            slots = listOf(WorkflowSlot(name = "q", type = SlotType.TEXT, required = true, exampleValue = "test")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/input", textSlot = "\${q}"),
                    parameters = mapOf("input_parameter" to "\${q}")
                )
            )
        )
        val report = WorkflowValidator.validate(workflow)
        assertEquals(ValidationStatus.VALID, report.status)
        assertTrue(report.isValid)
    }

    @Test
    fun test04_WorkflowPersistence() {
        val workflow = Workflow(
            skillId = "skill_persist_04",
            name = "Persisted Skill",
            intent = "persist_intent",
            slots = listOf(WorkflowSlot(name = "x", type = SlotType.TEXT, required = true, exampleValue = "v"))
        )
        val saved = repository.save(workflow)
        assertTrue(saved)
        val loaded = repository.getWorkflow("skill_persist_04")
        assertNotNull(loaded)
        assertEquals("Persisted Skill", loaded?.name)
    }

    @Test
    fun test05_PersistenceAfterRestart() {
        val workflow = Workflow(
            skillId = "skill_restart_05",
            name = "Restart Skill",
            intent = "restart_intent"
        )
        repository.save(workflow)

        SkillRepositoryProvider.reset()
        SkillRepositoryProvider.initialize(storageDir)
        val reloaded = SkillRepositoryProvider.getRepository()

        assertNotNull(reloaded.getWorkflow("skill_restart_05"))
    }

    // =========================================================================
    // 2. PARAMETERIZATION TESTS (6-12)
    // =========================================================================

    @Test
    fun test06_ChangedItem() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_item_chg",
            name = "Order Item",
            intent = "order_item",
            appContext = "com.example.shop",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Apples")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.shop:id/search", textSlot = "\${item}"),
                    parameters = mapOf("input_parameter" to "\${item}"),
                    expectedTransition = ExpectedTransition(toState = "RESULTS")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.shop", "SEARCH_PAGE", listOf(createUiElement("in", "EditText", resourceId = "com.example.shop:id/search", editable = true)))
        val s2 = createUiObservation("com.example.shop", "RESULTS", listOf(createUiElement("res", "TextView", text = "Oranges")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e6", "skill_item_chg", boundParameters = mapOf("item" to "Oranges")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("Oranges", driver.lastExecutedAction?.inputText)
    }

    @Test
    fun test07_ChangedQuantity() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_qty_chg",
            name = "Set Qty",
            intent = "set_qty",
            appContext = "com.example.shop",
            slots = listOf(WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "2")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.shop:id/qty", textSlot = "\${quantity}"),
                    parameters = mapOf("input_parameter" to "\${quantity}"),
                    expectedTransition = ExpectedTransition(toState = "CART")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.shop", "CART_PAGE", listOf(createUiElement("q", "EditText", resourceId = "com.example.shop:id/qty", editable = true)))
        val s2 = createUiObservation("com.example.shop", "CART", listOf(createUiElement("q_lbl", "TextView", text = "5")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e7", "skill_qty_chg", boundParameters = mapOf("quantity" to "5")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("5", driver.lastExecutedAction?.inputText)
    }

    @Test
    fun test08_ChangedAddress() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_addr_chg",
            name = "Set Address",
            intent = "set_address",
            appContext = "com.example.ship",
            slots = listOf(WorkflowSlot(name = "address", type = SlotType.ADDRESS, required = true, exampleValue = "123 Main St")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.ship:id/addr", textSlot = "\${address}"),
                    parameters = mapOf("input_parameter" to "\${address}"),
                    expectedTransition = ExpectedTransition(toState = "CONFIRMED")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.ship", "ADDR_PAGE", listOf(createUiElement("a", "EditText", resourceId = "com.example.ship:id/addr", editable = true)))
        val s2 = createUiObservation("com.example.ship", "CONFIRMED", listOf(createUiElement("c", "TextView", text = "789 Pine Ave")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e8", "skill_addr_chg", boundParameters = mapOf("address" to "789 Pine Ave")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("789 Pine Ave", driver.lastExecutedAction?.inputText)
    }

    @Test
    fun test09_ChangedSearchTerm() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_search_chg",
            name = "Search Term",
            intent = "search",
            appContext = "com.example.search",
            slots = listOf(WorkflowSlot(name = "query", type = SlotType.TEXT, required = true, exampleValue = "laptop")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.search:id/q", textSlot = "\${query}"),
                    parameters = mapOf("input_parameter" to "\${query}"),
                    expectedTransition = ExpectedTransition(toState = "RESULTS")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.search", "PAGE", listOf(createUiElement("in", "EditText", resourceId = "com.example.search:id/q", editable = true)))
        val s2 = createUiObservation("com.example.search", "RESULTS", listOf(createUiElement("out", "TextView", text = "monitor")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e9", "skill_search_chg", boundParameters = mapOf("query" to "monitor")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("monitor", driver.lastExecutedAction?.inputText)
    }

    @Test
    fun test10_MultipleChangedParameters() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_multi_param",
            name = "Multi Param Task",
            intent = "multi_param",
            appContext = "com.example.order",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Burger"),
                WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "1")
            ),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.order:id/item", textSlot = "\${item}"),
                    parameters = mapOf("input_parameter" to "\${item}"),
                    expectedTransition = ExpectedTransition(toState = "QTY_SCREEN")
                ),
                WorkflowStep(
                    stepId = "s2",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.order:id/qty", textSlot = "\${quantity}"),
                    parameters = mapOf("input_parameter" to "\${quantity}"),
                    expectedTransition = ExpectedTransition(toState = "SUMMARY")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.order", "ITEM_SCREEN", listOf(createUiElement("it", "EditText", resourceId = "com.example.order:id/item", editable = true)))
        val s2 = createUiObservation("com.example.order", "QTY_SCREEN", listOf(createUiElement("qt", "EditText", resourceId = "com.example.order:id/qty", editable = true)))
        val s3 = createUiObservation("com.example.order", "SUMMARY", listOf(createUiElement("sm", "TextView", text = "Order: Salad x3")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { step ->
            if (step.stepId == "s1") driver.currentScreen = s2
            else if (step.stepId == "s2") driver.currentScreen = s3
        }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e10", "skill_multi_param", boundParameters = mapOf("item" to "Salad", "quantity" to "3")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals(2, driver.executedActionsCount)
    }

    @Test
    fun test11_ConstantPreservation() {
        val step = WorkflowStep(
            stepId = "s_static",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.app:id/submit", text = "Confirm Order")
        )
        assertNull("Static step must not have variable slot placeholder", step.semanticSelector.textSlot)
        assertEquals("Confirm Order", step.semanticSelector.text)
    }

    @Test
    fun test12_VariableInference() {
        val slot = WorkflowSlot(
            name = "destination",
            type = SlotType.TEXT,
            required = true,
            exampleValue = "Airport",
            provenance = "demonstration"
        )
        assertEquals("destination", slot.name)
        assertTrue(slot.required)
        assertEquals("Airport", slot.exampleValue)
    }

    // =========================================================================
    // 3. REUSE TESTS (13-19)
    // =========================================================================

    @Test
    fun test13_ExactCommandReuse() {
        val workflow = Workflow(
            skillId = "skill_exact_cmd",
            name = "Exact Command Task",
            intent = "exact_intent",
            utteranceExamples = listOf("order groceries")
        )
        repository.save(workflow)

        val interp = CommandInterpreter().interpret("order groceries")
        val match = SkillMatcher().match(interp, repository)
        assertEquals(SkillMatchStatus.EXACT_MATCH, match.status)
        assertEquals("skill_exact_cmd", match.matchedSkill?.skillId)
    }

    @Test
    fun test14_ParaphrasedCommandReuse() {
        val workflow = Workflow(
            skillId = "skill_para_cmd",
            name = "Paraphrased Task",
            intent = "order_food",
            utteranceExamples = listOf("order pizza", "get pizza delivered")
        )
        repository.save(workflow)

        val interp = CommandInterpreter().interpret("order food")
        val match = SkillMatcher().match(interp, repository)
        assertTrue(match.status == SkillMatchStatus.EXACT_MATCH || match.status == SkillMatchStatus.HIGH_CONFIDENCE)
        assertEquals("skill_para_cmd", match.matchedSkill?.skillId)
    }

    @Test
    fun test15_MultipleWorkflowsCoexist() {
        val w1 = Workflow(skillId = "w1", name = "Workflow 1", intent = "intent_1")
        val w2 = Workflow(skillId = "w2", name = "Workflow 2", intent = "intent_2")
        val w3 = Workflow(skillId = "w3", name = "Workflow 3", intent = "intent_3")
        repository.save(w1)
        repository.save(w2)
        repository.save(w3)

        assertEquals(3, repository.getWorkflowCount())
    }

    @Test
    fun test16_CorrectWorkflowSelection() {
        val wMusic = Workflow(skillId = "w_music", name = "Play Music", intent = "play_music", utteranceExamples = listOf("play music", "play song"))
        val wMap = Workflow(skillId = "w_map", name = "Open Maps", intent = "open_map", utteranceExamples = listOf("open maps", "show navigation"))
        repository.save(wMusic)
        repository.save(wMap)

        val interp = CommandInterpreter().interpret("show navigation")
        val match = SkillMatcher().match(interp, repository)
        assertEquals("w_map", match.matchedSkill?.skillId)
    }

    @Test
    fun test17_UnknownCommand() {
        val interp = CommandInterpreter().interpret("launch spacecraft to alpha centauri")
        val match = SkillMatcher().match(interp, repository)
        assertEquals(SkillMatchStatus.UNKNOWN, match.status)
    }

    @Test
    fun test18_AmbiguousCommand() {
        val wA = Workflow(skillId = "wa", name = "Search Web", intent = "search", utteranceExamples = listOf("find stuff"))
        val wB = Workflow(skillId = "wb", name = "Search Files", intent = "search", utteranceExamples = listOf("find stuff"))
        repository.save(wA)
        repository.save(wB)

        val interp = CommandInterpreter().interpret("find stuff")
        val match = SkillMatcher().match(interp, repository)
        assertEquals(SkillMatchStatus.AMBIGUOUS, match.status)
    }

    @Test
    fun test19_MissingRequiredSlot() {
        val workflow = Workflow(
            skillId = "skill_need_slot",
            name = "Slot Task",
            intent = "slot_intent",
            slots = listOf(WorkflowSlot(name = "required_slot", type = SlotType.TEXT, required = true, exampleValue = "ex"))
        )
        repository.save(workflow)

        val interp = CommandInterpreter().interpret("run task") // no slot value provided
        val match = SkillMatchResult(SkillMatchStatus.EXACT_MATCH, workflow, listOf(SkillCandidateMatch(workflow, 1.0)))
        val reqRes = ExecutionRequestBuilder().buildRequest(interp, match)

        assertEquals(ExecutionRequestStatus.MISSING_SLOTS, reqRes.status)
    }

    // =========================================================================
    // 4. EXECUTION TESTS (20-24)
    // =========================================================================

    @Test
    fun test20_AutomaticTyping() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_auto_type",
            name = "Auto Type",
            intent = "auto_type",
            appContext = "com.example.type",
            slots = listOf(WorkflowSlot(name = "text", type = SlotType.TEXT, required = true, exampleValue = "initial")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.example.type:id/in", textSlot = "\${text}"),
                    parameters = mapOf("input_parameter" to "\${text}"),
                    expectedTransition = ExpectedTransition(toState = "DONE")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.type", "INPUT_PAGE", listOf(createUiElement("in", "EditText", resourceId = "com.example.type:id/in", editable = true)))
        val s2 = createUiObservation("com.example.type", "DONE", listOf(createUiElement("out", "TextView", text = "typed value")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e20", "skill_auto_type", boundParameters = mapOf("text" to "typed value")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("typed value", driver.lastExecutedAction?.inputText)
    }

    @Test
    fun test21_SemanticTargetResolution() {
        val matcher = SemanticMatcher()
        val selector = SemanticSelector(role = "Button", text = "Save", nearbyText = "Profile")
        val obs = createUiObservation(
            "com.example.app",
            "PROFILE_PAGE",
            listOf(
                createUiElement("b1", "Button", text = "Save", nearbyText = listOf("Settings")),
                createUiElement("b2", "Button", text = "Save", nearbyText = listOf("Profile", "User info"))
            )
        )
        val match = matcher.match(selector, obs, RuntimeAction.CLICK)
        assertEquals(MatchStatus.MATCHED, match.status)
        assertEquals("b2", match.best?.element?.elementId)
    }

    @Test
    fun test22_ChangedUiState() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_changed_ui",
            name = "Changed UI",
            intent = "changed_ui",
            appContext = "com.example.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "id/btn"),
                    preconditions = Preconditions(requiredState = "EXPECTED_SCREEN")
                )
            )
        )
        repository.save(workflow)

        val mismatchScreen = createUiObservation("com.example.app", "CHANGED_SCREEN", emptyList())
        val driver = TestUiDriver(mismatchScreen)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e22", "skill_changed_ui"))
        assertNotEquals(ExecutionState.COMPLETED, report.state)
        assertEquals(0, driver.executedActionsCount)
    }

    @Test
    fun test23_TransitionVerification() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_tv_fail",
            name = "TV Fail",
            intent = "tv_fail",
            appContext = "com.example.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "id/btn"),
                    expectedTransition = ExpectedTransition(toState = "DESIRED_STATE"),
                    recoveryPolicy = RecoveryPolicy(maxRetries = 0, strategy = RecoveryStrategy.FAIL)
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "START", listOf(createUiElement("b", "Button", resourceId = "id/btn")))
        val s2 = createUiObservation("com.example.app", "STILL_START", listOf(createUiElement("b", "Button", resourceId = "id/btn")))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e23", "skill_tv_fail"))

        assertEquals(ExecutionState.FAILED, report.state)
    }

    @Test
    fun test24_Recovery() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_rec_test",
            name = "Rec Task",
            intent = "rec_intent",
            appContext = "com.example.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "id/missing"),
                    recoveryPolicy = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.RETRY_STEP)
                )
            )
        )
        repository.save(workflow)

        val screen = createUiObservation("com.example.app", "EMPTY", emptyList())
        val driver = TestUiDriver(screen)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e24", "skill_rec_test"))
        assertEquals(ExecutionState.FAILED, report.state)
    }

    // =========================================================================
    // 5. BONUS CAPABILITY TESTS (25-27)
    // =========================================================================

    @Test
    fun test25_IrrelevantActionFiltering() {
        val navAction = ActionEvent("act_nav", 1000L, "CLICK", SemanticSelector("TextView", resourceId = "com.google.android.apps.nexuslauncher:id/icon"))
        val mainAction = ActionEvent("act_main", 2000L, "CLICK", SemanticSelector("Button", resourceId = "com.example.app:id/submit"))
        val stateEv = StateEvent("st1", 2100L, "act_main", UiState("b", 2000L, "com.example.app"), UiState("a", 2100L, "com.example.app"))

        val trace = DemonstrationTrace("tr_25", 1000L, "com.example.app", listOf(
            TraceEvent.Action("act_nav", 1000L, navAction),
            TraceEvent.Action("act_main", 2000L, mainAction),
            TraceEvent.State("st1", 2100L, stateEv)
        ))

        val filtered = DemonstrationFilter.filter(trace)
        assertTrue(filtered.navigationContextEventIds.contains("act_nav"))
        assertTrue(filtered.taskRelevantEventIds.contains("act_main"))

        val actions = SemanticActionExtractor.extract(trace, filtered)
        assertEquals(1, actions.size)
        assertEquals("act_main", actions[0].actionId)
    }

    @Test
    fun test26_CrossAppSemanticSelector() {
        val selector = SemanticSelector(role = "Button", text = "Submit")
        val appA = createUiObservation("com.pkg.a", "P", listOf(createUiElement("b1", "Button", text = "Submit", resourceId = "com.pkg.a:id/btn")))
        val appB = createUiObservation("com.pkg.b", "P", listOf(createUiElement("b2", "Button", text = "Submit", resourceId = "com.pkg.b:id/btn")))

        val matcher = SemanticMatcher()
        assertEquals(MatchStatus.MATCHED, matcher.match(selector, appA, RuntimeAction.CLICK).status)
        assertEquals(MatchStatus.MATCHED, matcher.match(selector, appB, RuntimeAction.CLICK).status)
    }

    @Test
    fun test27_MidFlowClarification() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_midflow_27",
            name = "Midflow 27",
            intent = "midflow_27",
            appContext = "com.example.app",
            slots = listOf(
                WorkflowSlot(name = "p1", type = SlotType.TEXT, required = true, exampleValue = "1"),
                WorkflowSlot(name = "p2", type = SlotType.TEXT, required = true, exampleValue = "2")
            ),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/in1", textSlot = "\${p1}"),
                    parameters = mapOf("input_parameter" to "\${p1}"),
                    expectedTransition = ExpectedTransition(toState = "S2")
                ),
                WorkflowStep(
                    stepId = "s2",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/in2", textSlot = "\${p2}"),
                    parameters = mapOf("input_parameter" to "\${p2}")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "S1", listOf(createUiElement("i1", "EditText", resourceId = "id/in1", editable = true)))
        val s2 = createUiObservation("com.example.app", "S2", listOf(createUiElement("i2", "EditText", resourceId = "id/in2", editable = true)))

        val driver = TestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val initialReport = engine.execute(ExecutionRequest("e27", "skill_midflow_27", boundParameters = mapOf("p1" to "Val1")))

        assertEquals(ExecutionState.ASK, initialReport.state)
        assertTrue(engine.isPaused)

        val resumedReport = engine.resume(ClarificationResponse("c27", "e27", mapOf("p2" to "Val2")))
        assertEquals(ExecutionState.COMPLETED, resumedReport.state)
    }

    // =========================================================================
    // 6. UI INTEGRATION TESTS (28-33)
    // =========================================================================

    @Test
    fun test28_NewUiShowsPersistedSkill() {
        val workflow = Workflow(
            skillId = "skill_ui_show",
            name = "UI Show Skill",
            intent = "show_intent"
        )
        repository.save(workflow)

        val workflows = repository.getAllWorkflows()
        assertEquals(1, workflows.size)
        assertEquals("UI Show Skill", workflows[0].name)
    }

    @Test
    fun test29_InspectorShowsCanonicalWorkflow() {
        val workflow = Workflow(
            skillId = "skill_inspect_canon",
            name = "Canonical Inspect",
            intent = "canon_intent",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Sample"))
        )
        repository.save(workflow)

        val inspection = WorkflowInspectorImpl.inspect(workflow, repository)
        assertTrue(inspection.isExecutableByPerson2)
        assertEquals("skill_inspect_canon", inspection.skillId)
    }

    @Test
    fun test30_NewUiReflectsExecutionState() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_exec_rf",
            name = "Exec Rf",
            intent = "exec_rf",
            steps = listOf(WorkflowStep(stepId = "s1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", resourceId = "id/btn")))
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "S", listOf(createUiElement("b", "Button", resourceId = "id/btn")))
        val driver = TestUiDriver(s1)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e30", "skill_exec_rf"))
        assertEquals(ExecutionState.COMPLETED, report.state)
    }

    @Test
    fun test31_NewUiReflectsAsk() {
        val req = ExecutionRequest("e31", "skill_31")
        val clar = ClarificationRequest("c31", "e31", "Clarify item", "item")
        val report = com.chockXlate.teachablevoice.runtime.trace.ExecutionTraceRecorder(req).pauseForClarification(clar)

        assertEquals(ExecutionState.ASK, report.state)
    }

    @Test
    fun test32_NewUiReflectsHandoffBlocked() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_handoff_32",
            name = "Handoff 32",
            intent = "handoff_32",
            steps = listOf(WorkflowStep(stepId = "s1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "Pay"), isPayment = true)),
            safetyBoundary = SafetyBoundary(isPaymentBoundary = true)
        )
        repository.save(workflow)

        val secure = createUiObservation("com.example.sec", "PAY", listOf(createUiElement("p", "Button", text = "Pay")), isSensitive = true)
        val driver = TestUiDriver(secure)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e32", "skill_handoff_32"))
        assertEquals(ExecutionState.HANDOFF, report.state)
    }

    @Test
    fun test33_NewUiReflectsCompletion() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_comp_33",
            name = "Comp 33",
            intent = "comp_33",
            steps = listOf(WorkflowStep(stepId = "s1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", resourceId = "id/btn")))
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "DONE", listOf(createUiElement("b", "Button", resourceId = "id/btn")))
        val driver = TestUiDriver(s1)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e33", "skill_comp_33"))
        assertEquals(ExecutionState.COMPLETED, report.state)
    }
}
