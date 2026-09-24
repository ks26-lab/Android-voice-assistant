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
        safetyBoundary: SafetyBoundary = SafetyBoundary()
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
                    expectedTransition = ExpectedTransition(timeoutMs = 500),
                    preconditions = Preconditions(),
                    recoveryPolicy = RecoveryPolicy()
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
}
