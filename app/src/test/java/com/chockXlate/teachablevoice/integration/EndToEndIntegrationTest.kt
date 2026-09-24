package com.chockXlate.teachablevoice.integration

import com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter
import com.chockXlate.teachablevoice.command.matching.SkillCandidateMatch
import com.chockXlate.teachablevoice.command.matching.SkillMatchResult
import com.chockXlate.teachablevoice.command.matching.SkillMatchStatus
import com.chockXlate.teachablevoice.command.matching.SkillMatcher
import com.chockXlate.teachablevoice.command.request.ExecutionRequestBuilder
import com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus
import com.chockXlate.teachablevoice.contract.runtime.DecisionType
import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import com.chockXlate.teachablevoice.contract.runtime.ExecutionState
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.ExpectedTransition
import com.chockXlate.teachablevoice.contract.workflow.Preconditions
import com.chockXlate.teachablevoice.contract.workflow.RecoveryPolicy
import com.chockXlate.teachablevoice.contract.workflow.RecoveryStrategy
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import com.chockXlate.teachablevoice.runtime.ExecutionEngine
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.ui.ActionOutcome
import com.chockXlate.teachablevoice.runtime.ui.UiDriver
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import com.chockXlate.teachablevoice.runtime.verification.PreconditionEvaluator
import com.chockXlate.teachablevoice.runtime.verification.TransitionVerifier
import com.chockXlate.teachablevoice.runtime.workflow.WorkflowResolver
import com.chockXlate.teachablevoice.safety.SafetyGate
import com.chockXlate.teachablevoice.skill.repository.SkillRepositoryProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * End-to-end integration test suite validating:
 * TEACH -> LEARN -> STORE -> COMMAND -> INTERPRET -> MATCH -> ExecutionRequest -> RESOLVE SAME WORKFLOW -> ExecutionEngine -> LIVE UI -> SAFETY GATE -> RESULT/TRACE
 */
class EndToEndIntegrationTest {

    private class TestUiDriver(var screen: UiObservation) : UiDriver {
        var ready = true
        var actions = 0
        var waits = 0
        var observations = 0
        var accepted = true
        var effect: (BoundStep) -> Unit = {}

        override fun isReady(): Boolean = ready

        override suspend fun observe(): UiObservation? {
            observations++
            return if (ready) screen else null
        }

        override suspend fun awaitChange(delayMs: Long) {
            waits++
        }

        override suspend fun execute(
            step: BoundStep,
            expectedPackage: String,
            boundary: SafetyBoundary,
            matcher: SemanticMatcher,
            gate: SafetyGate
        ): ActionOutcome {
            val before = screen
            gate.check(boundary, before, step)?.let {
                return ActionOutcome(false, reason = it, before = before)
            }
            PreconditionEvaluator(matcher).evaluate(step.preconditions, before, expectedPackage, step.stateEvidence)?.let {
                return ActionOutcome(false, reason = it, before = before)
            }
            TransitionVerifier(matcher).startingStateError(step, before)?.let {
                return ActionOutcome(false, reason = it, before = before)
            }
            val match = matcher.match(step.selector, before, step.action)
            if (match.status != MatchStatus.MATCHED) {
                return ActionOutcome(false, reason = match.reason, before = before)
            }
            val result = gate.dispatch(boundary, before, step) {
                actions++
                effect(step)
                accepted
            }
            return ActionOutcome(result.attempted, result.accepted, result.reason, before)
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var completion: Result<T>? = null
        val done = CountDownLatch(1)
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                completion = result
                done.countDown()
            }
        })
        assertTrue("Coroutine timed out after 5 seconds", done.await(5, TimeUnit.SECONDS))
        return completion!!.getOrThrow()
    }

    private fun createUiObservation(
        packageName: String = "com.example.app",
        vararg elements: UiElement
    ): UiObservation {
        val state = UiState(
            stateId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            appContext = packageName,
            allElements = elements.toList()
        )
        return UiObservation(state)
    }

    private fun createWorkflow(
        skillId: String = "skill_search_1",
        name: String = "Search Skill",
        intent: String = "search_item",
        appContext: String = "com.example.app",
        semanticAction: String = "INPUT_TEXT",
        targetRole: String = "EditText",
        targetResId: String = "com.example.app:id/search_box",
        textSlot: String? = null,
        slots: List<WorkflowSlot> = listOf(
            WorkflowSlot(
                name = "query",
                type = SlotType.TEXT,
                required = true,
                exampleValue = "\${query}",
                provenance = "variable"
            )
        ),
        safetyBoundary: SafetyBoundary = SafetyBoundary(),
        recoveryPolicy: RecoveryPolicy = RecoveryPolicy(),
        timeoutMs: Long = 500
    ): Workflow {
        val resolvedSlot = textSlot ?: if (semanticAction == "INPUT_TEXT") slots.firstOrNull()?.name else null
        return Workflow(
            skillId = skillId,
            name = name,
            intent = intent,
            appContext = appContext,
            slots = slots,
            steps = listOf(
                WorkflowStep(
                    stepId = "step_1",
                    semanticAction = semanticAction,
                    semanticSelector = SemanticSelector(
                        role = targetRole,
                        resourceId = targetResId,
                        textSlot = resolvedSlot
                    ),
                    expectedTransition = ExpectedTransition(timeoutMs = timeoutMs),
                    preconditions = Preconditions(),
                    recoveryPolicy = recoveryPolicy
                )
            ),
            safetyBoundary = safetyBoundary
        )
    }

    @Before
    fun setUp() {
        SkillRepositoryProvider.reset()
    }

    @After
    fun tearDown() {
        SkillRepositoryProvider.reset()
    }

    @Test
    fun testA_workflowSavedInSharedProvider_resolvesInExecutionEngine() {
        val repo = SkillRepositoryProvider.getRepository()
        val workflow = createWorkflow(skillId = "skill_query_flow")
        repo.saveWorkflow(workflow)

        val initialScreen = createUiObservation(
            packageName = "com.example.app",
            UiElement(
                elementId = "e1",
                role = "EditText",
                resourceId = "com.example.app:id/search_box",
                text = "",
                isEditable = true
            )
        )
        val driver = TestUiDriver(initialScreen)
        driver.effect = { step ->
            driver.screen = createUiObservation(
                packageName = "com.example.app",
                UiElement(
                    elementId = "e1",
                    role = "EditText",
                    resourceId = "com.example.app:id/search_box",
                    text = step.inputText ?: "",
                    isEditable = true
                )
            )
        }

        val engine = ExecutionEngine(
            repository = SkillRepositoryProvider.getRepository(),
            driver = driver
        )

        val request = ExecutionRequest(
            executionId = "req_101",
            skillId = "skill_query_flow",
            boundSlots = mapOf("query" to "Margherita")
        )

        val report = runSuspend { engine.execute(request) }

        assertEquals("req_101", report.result.executionId)
        assertEquals("skill_query_flow", report.trace.skillId)
        assertEquals(ExecutionState.COMPLETED, report.result.finalState)
        assertTrue(report.result.success)
        assertEquals(1, report.result.stepsCompleted)
        assertEquals(1, report.result.totalSteps)
    }

    @Test
    fun testB_validExecutionRequest_executesSemanticStepOnDriver() {
        val repo = SkillRepositoryProvider.getRepository()
        val workflow = createWorkflow(
            skillId = "skill_click_button",
            semanticAction = "CLICK",
            targetRole = "Button",
            targetResId = "com.example.app:id/submit_btn",
            slots = emptyList()
        )
        repo.saveWorkflow(workflow)

        val initialScreen = createUiObservation(
            packageName = "com.example.app",
            UiElement(
                elementId = "btn_1",
                role = "Button",
                resourceId = "com.example.app:id/submit_btn",
                text = "Submit",
                isClickable = true
            )
        )
        val driver = TestUiDriver(initialScreen)
        driver.effect = {
            driver.screen = createUiObservation(
                packageName = "com.example.app",
                UiElement(
                    elementId = "status_1",
                    role = "TextView",
                    resourceId = "com.example.app:id/status",
                    text = "Submitted Successfully"
                )
            )
        }

        val engine = ExecutionEngine(
            repository = SkillRepositoryProvider.getRepository(),
            driver = driver
        )

        val request = ExecutionRequest(
            executionId = "req_click_1",
            skillId = "skill_click_button",
            boundSlots = emptyMap()
        )

        val report = runSuspend { engine.execute(request) }

        assertEquals(ExecutionState.COMPLETED, report.result.finalState)
        assertTrue(report.result.success)
        assertEquals(1, driver.actions)
    }

    @Test
    fun testC_safetyBoundaryBlocked_zeroDriverActions_pausedForHandoff() {
        val repo = SkillRepositoryProvider.getRepository()
        val safety = SafetyBoundary(
            requiresExplicitUserConfirmation = true
        )
        val workflow = createWorkflow(
            skillId = "skill_sensitive",
            safetyBoundary = safety
        )
        repo.saveWorkflow(workflow)

        val driver = TestUiDriver(createUiObservation(
            packageName = "com.example.app",
            UiElement(
                elementId = "e1",
                role = "EditText",
                resourceId = "com.example.app:id/search_box",
                isEditable = true
            )
        ))

        val engine = ExecutionEngine(
            repository = SkillRepositoryProvider.getRepository(),
            driver = driver
        )

        val request = ExecutionRequest(
            executionId = "req_sensitive",
            skillId = "skill_sensitive",
            boundSlots = mapOf("query" to "sensitive_data")
        )

        val report = runSuspend { engine.execute(request) }

        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertFalse(report.result.success)
        assertEquals(0, driver.actions) // ZERO automated actions permitted!
        assertNotNull(report.result.errorMessage)
    }

    @Test
    fun testD_voiceOnlyWorkflow_executesZeroActions_pausedForHandoffClarification() {
        val repo = SkillRepositoryProvider.getRepository()
        // Voice-only workflows have an EXECUTE_INTENT synthetic step
        val voiceOnlyWorkflow = createWorkflow(
            skillId = "skill_voice_only",
            semanticAction = "EXECUTE_INTENT"
        )
        repo.saveWorkflow(voiceOnlyWorkflow)

        val driver = TestUiDriver(createUiObservation("com.example.app"))
        val engine = ExecutionEngine(
            repository = SkillRepositoryProvider.getRepository(),
            driver = driver
        )

        val request = ExecutionRequest(
            executionId = "req_voice_only",
            skillId = "skill_voice_only",
            boundSlots = mapOf("query" to "Margherita")
        )

        val report = runSuspend { engine.execute(request) }

        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertFalse(report.result.success)
        assertEquals(0, driver.actions) // ZERO automated actions!
        assertTrue(
            report.result.errorMessage?.contains("Voice-only demonstration has no recorded UI actions") == true
        )
    }

    @Test
    fun testE_unknownSkill_failsWithoutExecution() {
        val driver = TestUiDriver(createUiObservation("com.example.app"))
        val engine = ExecutionEngine(
            repository = SkillRepositoryProvider.getRepository(),
            driver = driver
        )

        val request = ExecutionRequest(
            executionId = "req_unknown",
            skillId = "non_existent_skill_999",
            boundSlots = emptyMap()
        )

        val report = runSuspend { engine.execute(request) }

        assertEquals(ExecutionState.FAILED, report.result.finalState)
        assertFalse(report.result.success)
        assertEquals(0, driver.actions)
        assertTrue(report.result.errorMessage?.contains("No learned workflow exists") == true)
    }

    @Test
    fun testF_missingRequiredSlot_rejectedAtRequestBuilder_noExecution() {
        val repo = SkillRepositoryProvider.getRepository()
        val workflow = createWorkflow(
            skillId = "skill_req_slots",
            intent = "order_food",
            textSlot = "item",
            slots = listOf(
                WorkflowSlot(
                    name = "item",
                    type = SlotType.TEXT,
                    required = true,
                    exampleValue = "\${item}",
                    provenance = "variable"
                ),
                WorkflowSlot(
                    name = "quantity",
                    type = SlotType.INTEGER,
                    required = true,
                    exampleValue = "\${quantity}",
                    provenance = "variable"
                )
            )
        )
        repo.saveWorkflow(workflow)

        val understanding = CommandInterpreter.understandCommand("Order food")

        val matcher = SkillMatcher(repo)
        val matchResult = matcher.match(understanding)
        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)

        val buildResult = ExecutionRequestBuilder.build(understanding, matchResult, repo)

        assertEquals(ExecutionRequestStatus.REJECTED_MISSING_REQUIRED_SLOTS, buildResult.status)
        assertNull(buildResult.executionRequest)
        assertTrue(buildResult.missingSlots.contains("item"))
        assertTrue(buildResult.missingSlots.contains("quantity"))
    }

    @Test
    fun testG_workflowSavedInTeachingRepo_visibleDirectlyToPerson2WorkflowResolver() {
        val repo = SkillRepositoryProvider.getRepository()
        val workflow = createWorkflow(skillId = "skill_resolver_test")
        repo.saveWorkflow(workflow)

        val resolver = WorkflowResolver(SkillRepositoryProvider.getRepository())
        val request = ExecutionRequest(
            executionId = "req_resolve",
            skillId = "skill_resolver_test"
        )

        val resolution = resolver.resolve(request)

        assertNotNull(resolution.workflow)
        assertEquals("skill_resolver_test", resolution.workflow?.skillId)
        assertNull(resolution.error)
    }

    @Test
    fun testH_ambiguousCommand_rejectedAtRequestBuilder_noExecution() {
        val repo = SkillRepositoryProvider.getRepository()
        // Save two identical-intent workflows without distinguishing constants
        val wf1 = createWorkflow(
            skillId = "skill_order_food_fast",
            intent = "order_food",
            slots = emptyList()
        )
        val wf2 = createWorkflow(
            skillId = "skill_order_food_standard",
            intent = "order_food",
            slots = emptyList()
        )
        repo.saveWorkflow(wf1)
        repo.saveWorkflow(wf2)

        val understanding = CommandInterpreter.understandCommand("Order food")
        val matcher = SkillMatcher(repo)
        val matchResult = matcher.match(understanding)

        assertEquals(SkillMatchStatus.AMBIGUOUS, matchResult.status)
        assertTrue(matchResult.candidates.size >= 2)

        val buildResult = ExecutionRequestBuilder.build(understanding, matchResult, repo)

        assertEquals(ExecutionRequestStatus.REJECTED_AMBIGUOUS_MATCH, buildResult.status)
        assertNull(buildResult.executionRequest)
    }

    @Test
    fun testI_transitionTimeout_failsGracefullyWithoutFabrication() {
        val repo = SkillRepositoryProvider.getRepository()
        val workflow = createWorkflow(
            skillId = "skill_timeout_test",
            semanticAction = "CLICK",
            targetRole = "Button",
            targetResId = "com.example.app:id/submit_btn",
            slots = emptyList(),
            timeoutMs = 100
        )
        repo.saveWorkflow(workflow)

        // Screen NEVER updates upon click: simulates a hung or non-responsive app
        val driver = TestUiDriver(createUiObservation(
            packageName = "com.example.app",
            UiElement(
                elementId = "btn_1",
                role = "Button",
                resourceId = "com.example.app:id/submit_btn",
                text = "Submit",
                isClickable = true
            )
        ))
        // Notice driver.effect is intentionally empty: screen does not change

        val engine = ExecutionEngine(
            repository = SkillRepositoryProvider.getRepository(),
            driver = driver
        )

        val request = ExecutionRequest(
            executionId = "req_timeout",
            skillId = "skill_timeout_test",
            boundSlots = emptyMap()
        )

        val report = runSuspend { engine.execute(request) }

        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertFalse(report.result.success)
        assertTrue(report.result.errorMessage?.contains("timed out") == true ||
                report.result.errorMessage?.contains("exhausted") == true)
    }

    @Test
    fun testJ_boundedRecovery_doesNotLoopInfinitely() {
        val repo = SkillRepositoryProvider.getRepository()
        val workflow = createWorkflow(
            skillId = "skill_recovery_test",
            semanticAction = "CLICK",
            targetRole = "Button",
            targetResId = "com.example.app:id/missing_btn",
            slots = emptyList(),
            recoveryPolicy = RecoveryPolicy(
                maxRetries = 2,
                retryDelayMs = 10,
                strategy = RecoveryStrategy.RETRY_STEP
            )
        )
        repo.saveWorkflow(workflow)

        // Screen does NOT contain the target button
        val driver = TestUiDriver(createUiObservation(
            packageName = "com.example.app",
            UiElement(
                elementId = "txt_1",
                role = "TextView",
                text = "Loading..."
            )
        ))

        val engine = ExecutionEngine(
            repository = SkillRepositoryProvider.getRepository(),
            driver = driver
        )

        val request = ExecutionRequest(
            executionId = "req_recovery",
            skillId = "skill_recovery_test",
            boundSlots = emptyMap()
        )

        val report = runSuspend { engine.execute(request) }

        // Must terminate safely without infinite loop
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertFalse(report.result.success)
        assertEquals(0, driver.actions) // Never attempted action on non-existent control
    }

    @Test
    fun testK_executionTraceAndResultCorrectness() {
        val repo = SkillRepositoryProvider.getRepository()
        val workflow = createWorkflow(
            skillId = "skill_trace_test",
            semanticAction = "CLICK",
            targetRole = "Button",
            targetResId = "com.example.app:id/submit_btn",
            slots = emptyList()
        )
        repo.saveWorkflow(workflow)

        val driver = TestUiDriver(createUiObservation(
            packageName = "com.example.app",
            UiElement(
                elementId = "btn_1",
                role = "Button",
                resourceId = "com.example.app:id/submit_btn",
                text = "Submit",
                isClickable = true
            )
        ))
        driver.effect = {
            driver.screen = createUiObservation(
                packageName = "com.example.app",
                UiElement(
                    elementId = "txt_done",
                    role = "TextView",
                    text = "Done"
                )
            )
        }

        val engine = ExecutionEngine(
            repository = SkillRepositoryProvider.getRepository(),
            driver = driver
        )

        val request = ExecutionRequest(
            executionId = "req_trace_123",
            skillId = "skill_trace_test",
            boundSlots = emptyMap()
        )

        val report = runSuspend { engine.execute(request) }

        assertEquals("req_trace_123", report.result.executionId)
        assertEquals("skill_trace_test", report.trace.skillId)
        assertTrue(report.result.success)
        assertEquals(1, report.result.stepsCompleted)
        assertEquals(1, report.result.totalSteps)
        assertTrue(report.diagnostics.isNotEmpty())
        assertTrue(report.trace.events.isNotEmpty())
    }

    @Test
    fun testL_twoIndependentWorkflows_isolatedResolution() {
        val repo = SkillRepositoryProvider.getRepository()

        // Workflow 1: Food ordering
        val foodWorkflow = createWorkflow(
            skillId = "skill_food",
            name = "Food Ordering",
            intent = "order_food",
            slots = listOf(
                WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "pizza", provenance = "variable")
            ),
            textSlot = "item"
        )

        // Workflow 2: Messaging
        val messageWorkflow = createWorkflow(
            skillId = "skill_message",
            name = "Send Message",
            intent = "send_message",
            targetResId = "com.example.app:id/msg_box",
            slots = listOf(
                WorkflowSlot(name = "recipient", type = SlotType.TEXT, required = true, exampleValue = "Alice", provenance = "variable")
            ),
            textSlot = "recipient"
        )

        repo.saveWorkflow(foodWorkflow)
        repo.saveWorkflow(messageWorkflow)

        // Test Command 1 -> resolves Food
        val foodCmd = CommandInterpreter.understandCommand("Order pizza")
        val foodMatch = SkillMatcher(repo).match(foodCmd)
        assertEquals(SkillMatchStatus.MATCHED, foodMatch.status)
        assertEquals("skill_food", foodMatch.selectedSkillId)

        // Test Command 2 -> resolves Message
        val msgCmd = CommandInterpreter.understandCommand("Send message to Alice")
        val msgMatch = SkillMatcher(repo).match(msgCmd)
        assertEquals(SkillMatchStatus.MATCHED, msgMatch.status)
        assertEquals("skill_message", msgMatch.selectedSkillId)
    }

    @Test
    fun testM_changedBoundSlot_propagatesToDriverAction() {
        val repo = SkillRepositoryProvider.getRepository()
        val workflow = createWorkflow(
            skillId = "skill_param_test",
            semanticAction = "INPUT_TEXT",
            targetResId = "com.example.app:id/search_box",
            textSlot = "dish",
            slots = listOf(
                WorkflowSlot(
                    name = "dish",
                    type = SlotType.TEXT,
                    required = true,
                    exampleValue = "\${dish}",
                    provenance = "variable"
                )
            )
        )
        repo.saveWorkflow(workflow)

        var capturedInputText: String? = null
        val driver = TestUiDriver(createUiObservation(
            packageName = "com.example.app",
            UiElement(
                elementId = "input_1",
                role = "EditText",
                resourceId = "com.example.app:id/search_box",
                text = "",
                isEditable = true
            )
        ))
        driver.effect = { step ->
            capturedInputText = step.inputText
            driver.screen = createUiObservation(
                packageName = "com.example.app",
                UiElement(
                    elementId = "input_1",
                    role = "EditText",
                    resourceId = "com.example.app:id/search_box",
                    text = step.inputText ?: "",
                    isEditable = true
                )
            )
        }

        val engine = ExecutionEngine(
            repository = SkillRepositoryProvider.getRepository(),
            driver = driver
        )

        // User changed slot value from "Margherita" to "Farmhouse Deluxe"
        val request = ExecutionRequest(
            executionId = "req_param_changed",
            skillId = "skill_param_test",
            boundSlots = mapOf("dish" to "Farmhouse Deluxe")
        )

        val report = runSuspend { engine.execute(request) }

        assertTrue(report.result.success)
        assertEquals("Farmhouse Deluxe", capturedInputText)
    }
}
