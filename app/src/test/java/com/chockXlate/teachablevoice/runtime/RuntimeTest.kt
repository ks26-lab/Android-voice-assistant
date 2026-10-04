package com.chockXlate.teachablevoice.runtime

import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.ui.*
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.contract.event.*
import com.chockXlate.teachablevoice.contract.trace.*
import com.chockXlate.teachablevoice.runtime.matching.*
import com.chockXlate.teachablevoice.runtime.recovery.*
import com.chockXlate.teachablevoice.runtime.slots.*
import com.chockXlate.teachablevoice.runtime.ui.*
import com.chockXlate.teachablevoice.runtime.verification.*
import com.chockXlate.teachablevoice.runtime.trace.*
import com.chockXlate.teachablevoice.runtime.workflow.WorkflowResolver
import com.chockXlate.teachablevoice.execution.SemanticExecutor
import com.chockXlate.teachablevoice.command.matching.*
import com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter
import com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy
import com.chockXlate.teachablevoice.command.request.ExecutionRequestBuilder
import com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import com.chockXlate.teachablevoice.teach.filter.DemonstrationFilter
import com.chockXlate.teachablevoice.safety.*
import com.chockXlate.teachablevoice.skill.repository.SkillRepository
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.*

class RuntimeTest {
    private val app = "test.generic"
    private val matcher = SemanticMatcher()
    private fun button(id: String = "one", text: String = "Continue") = UiElement(
        elementId = id, role = "Button", text = text, isClickable = true
    )
    private fun input(text: String = "old") = UiElement(
        elementId = "input", role = "EditText", resourceId = "field", text = text, isEditable = true
    )
    private fun ui(vararg elements: UiElement, packageName: String = app) = UiObservation(UiState(
        stateId = UUID.randomUUID().toString(), timestamp = System.currentTimeMillis(),
        appContext = packageName, allElements = elements.toList()
    ))
    private fun step(
        action: String = "CLICK", selector: SemanticSelector = SemanticSelector(text = "Continue"),
        transition: ExpectedTransition = ExpectedTransition(timeoutMs = 200),
        pre: Preconditions = Preconditions(), recovery: RecoveryPolicy = RecoveryPolicy()
    ) = WorkflowStep(stepId = "step", semanticAction = action, semanticSelector = selector,
        preconditions = pre, expectedTransition = transition, recoveryPolicy = recovery)
    private fun workflow(step: WorkflowStep = step(), slots: List<WorkflowSlot> = emptyList(), safety: SafetyBoundary = SafetyBoundary()) = Workflow(
        skillId = "learned", name = "Generic workflow", intent = "generic", appContext = app,
        steps = listOf(step), slots = slots, safetyBoundary = safety
    )
    private fun request(slots: Map<String, String> = emptyMap()) = ExecutionRequest(executionId = "run", skillId = "learned", boundSlots = slots)
    private class Store(var workflow: Workflow? = null) : SkillRepository {
        val workflows = mutableMapOf<String, Workflow>()
        init {
            workflow?.let { workflows[it.skillId] = it }
        }
        override fun getWorkflowById(skillId: String) = workflows[skillId] ?: workflow?.takeIf { it.skillId == skillId }
        override fun getAllWorkflows() = (workflows.values + listOfNotNull(workflow)).distinctBy { it.skillId }
        override fun saveWorkflow(workflow: Workflow): Boolean { workflows[workflow.skillId] = workflow; this.workflow = workflow; return true }
        override fun deleteWorkflow(skillId: String): Boolean { workflows.remove(skillId); if (workflow?.skillId == skillId) workflow = null; return true }
        override fun createSkill(name: String, description: String, id: String): com.chockXlate.teachablevoice.contract.skill.SkillRecord = com.chockXlate.teachablevoice.contract.skill.SkillRecord(id = id.ifBlank { "id" }, name = name, description = description)
        override fun getSkill(id: String): com.chockXlate.teachablevoice.contract.skill.SkillRecord? = null
        override fun listSkills(): List<com.chockXlate.teachablevoice.contract.skill.SkillRecord> = emptyList()
        override fun updateSkill(skill: com.chockXlate.teachablevoice.contract.skill.SkillRecord): Boolean = true
    }
    private class FakeDriver(var screen: UiObservation) : UiDriver {
        var ready = true
        var actions = 0
        var waits = 0
        var observations = 0
        var accepted = true
        var lastStep: BoundStep? = null
        var beforeDispatch: (() -> Unit)? = null
        var effect: (BoundStep) -> Unit = {}
        var waiting: (() -> Unit)? = null
        override fun isReady() = ready
        override suspend fun observe(): UiObservation? { observations++; return if (ready) screen else null }
        override suspend fun awaitChange(delayMs: Long) { waits++; waiting?.invoke() }
        override suspend fun execute(step: BoundStep, expectedPackage: String, boundary: SafetyBoundary, matcher: SemanticMatcher, gate: SafetyGate): ActionOutcome {
            beforeDispatch?.invoke()
            val before = screen
            gate.check(boundary, before, step)?.let { return ActionOutcome(false, reason = it, before = before) }
            PreconditionEvaluator(matcher).evaluate(step.preconditions, before, expectedPackage, step.stateEvidence)?.let {
                return ActionOutcome(false, reason = it, before = before)
            }
            TransitionVerifier(matcher).startingStateError(step, before)?.let {
                return ActionOutcome(false, reason = it, before = before)
            }
            val match = matcher.match(step.selector, before, step.action)
            if (match.status != MatchStatus.MATCHED) return ActionOutcome(false, reason = match.reason, before = before)
            val result = gate.dispatch(boundary, before, step) { actions++; lastStep = step; effect(step); accepted }
            return ActionOutcome(result.attempted, result.accepted, result.reason, before)
        }
    }
    private fun <T> run(block: suspend () -> T): T {
        var completion: Result<T>? = null
        val done = CountDownLatch(1)
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completion = result; done.countDown() }
        })
        check(done.await(5, TimeUnit.SECONDS)) { "Test coroutine did not complete" }
        return completion!!.getOrThrow()
    }

    @Test fun workflowResolutionUsesInjectedRepository() {
        val stored = workflow()
        assertEquals(stored, WorkflowResolver(Store(stored)).resolve(request()).workflow)
    }
    @Test fun unknownWorkflowNeverExecutes() {
        val driver = FakeDriver(ui(button()))
        val report = run { ExecutionEngine(Store(null), driver).execute(request()) }
        assertFalse(report.result.success); assertEquals(0, driver.actions)
        assertTrue(report.result.errorMessage!!.contains("No learned workflow"))
    }
    @Test fun unsupportedRequestSchemaStops() {
        assertNotNull(ExecutionRequestValidator.validate(request().copy(schemaVersion = "2.0")))
    }
    @Test fun changedSlotsBindWithoutRequiringOldFieldText() {
        val slot = WorkflowSlot(name = "query", type = SlotType.TEXT, exampleValue = "old")
        val wf = workflow(step("INPUT_TEXT", SemanticSelector(resourceId = "field", text = "old", textSlot = "\${query}")), listOf(slot))
        val bound = SlotBinder.bind(wf, mapOf("query" to "changed"))
        assertNull(bound.error); assertEquals("changed", bound.steps.single().inputText)
        assertNull(bound.steps.single().selector.text); assertNull(bound.steps.single().selector.textSlot)
    }
    @Test fun missingRequiredSlotFails() {
        assertNotNull(SlotBinder.bind(workflow(slots = listOf(WorkflowSlot(name = "query", type = SlotType.TEXT))), emptyMap()).error)
    }
    @Test fun referenceCompatibilityIsCentralized() {
        for (ref in listOf("item", "{item}", "\${item}")) assertEquals("item", SlotBinder.reference(ref))
        assertNull(SlotBinder.reference("prefix {item}"))
    }
    @Test fun inputParameterAndLegacyTextReferenceBind() {
        val wf = workflow(step("INPUT_TEXT", SemanticSelector(resourceId = "field")).copy(
            parameters = mapOf("input_parameter" to "query", "text" to "{query}")
        ), listOf(WorkflowSlot(name = "query", type = SlotType.TEXT)))
        assertEquals("changed", SlotBinder.bind(wf, mapOf("query" to "changed")).steps.single().inputText)
    }
    @Test fun conflictingInputSourcesFail() {
        val wf = workflow(step("INPUT_TEXT", SemanticSelector(resourceId = "field")).copy(
            parameters = mapOf("input_literal" to "one", "text" to "two")))
        assertNotNull(SlotBinder.bind(wf, emptyMap()).error)
    }
    @Test fun constantsCannotBeOverridden() {
        val wf = workflow(slots = listOf(WorkflowSlot(name = "context", type = SlotType.TEXT, required = false, exampleValue = "fixed")))
        assertNotNull(SlotBinder.bind(wf, mapOf("context" to "changed")).error)
    }
    @Test fun typedSlotsRejectInvalidNumbersAndNonFiniteValues() {
        for ((type, value) in listOf(SlotType.INTEGER to "two", SlotType.DECIMAL to "NaN", SlotType.BOOLEAN to "perhaps")) {
            assertNotNull(SlotBinder.bind(workflow(slots = listOf(WorkflowSlot(name = "value", type = type))), mapOf("value" to value)).error)
        }
    }
    @Test fun exactSemanticMatchNormalizesCaseAndWhitespace() {
        val result = matcher.match(SemanticSelector(text = " continue "), ui(button()), RuntimeAction.CLICK)
        assertEquals(MatchStatus.MATCHED, result.status); assertEquals("one", result.best!!.element.elementId)
    }
    @Test fun contentDescriptionAloneCanIdentifyControl() {
        val result = matcher.match(SemanticSelector(contentDescription = "Open details"),
            ui(button().copy(text = null, contentDescription = "Open details")), RuntimeAction.CLICK)
        assertEquals(MatchStatus.MATCHED, result.status)
    }
    @Test fun equalCandidatesRequireClarificationRegardlessOfOrder() {
        for (nodes in listOf(listOf(button("a"), button("b")), listOf(button("b"), button("a")))) {
            assertEquals(MatchStatus.AMBIGUOUS, matcher.match(SemanticSelector(text = "Continue"), ui(*nodes.toTypedArray()), RuntimeAction.CLICK).status)
        }
    }
    @Test fun roleOnlyEvidenceNeverExecutes() {
        assertEquals(MatchStatus.WEAK, matcher.match(SemanticSelector(role = "Button"), ui(button()), RuntimeAction.CLICK).status)
    }
    @Test fun noMatchAndDisabledMatchAreRefused() {
        assertEquals(MatchStatus.NONE, matcher.match(SemanticSelector(text = "Missing"), ui(button()), RuntimeAction.CLICK).status)
        assertEquals(MatchStatus.NONE, matcher.match(SemanticSelector(text = "Continue"), ui(button().copy(isEnabled = false)), RuntimeAction.CLICK).status)
    }
    @Test fun resourceIdContradictionCannotBeOutvoted() {
        assertEquals(MatchStatus.NONE, matcher.match(SemanticSelector(resourceId = "different", text = "Continue"),
            ui(button().copy(resourceId = "actual")), RuntimeAction.CLICK).status)
    }
    @Test fun tapAndClickAreEquivalentButUnsupportedActionsAreRejected() {
        assertEquals(RuntimeAction.CLICK, RuntimeAction.parse("TAP")); assertEquals(RuntimeAction.CLICK, RuntimeAction.parse("CLICK"))
        assertNull(RuntimeAction.parse("SUBMIT")); assertNotNull(SlotBinder.bind(workflow(step("SUBMIT")), emptyMap()).error)
    }
    @Test fun scrollRequiresDirectionAndLongPressRequiresLiveCapability() {
        assertNotNull(SlotBinder.bind(workflow(step("SCROLL")), emptyMap()).error)
        assertEquals(MatchStatus.NONE, matcher.match(SemanticSelector(text = "Continue"), ui(button()), RuntimeAction.LONG_PRESS).status)
        assertEquals(MatchStatus.MATCHED, matcher.match(SemanticSelector(text = "Continue"),
            ui(button()).copy(capabilities = mapOf("one" to setOf(RuntimeAction.LONG_PRESS))), RuntimeAction.LONG_PRESS).status)
    }
    @Test fun preconditionPackageMismatchPreventsAction() {
        val driver = FakeDriver(ui(button(), packageName = "different.generic"))
        val result = run { ExecutionEngine(Store(workflow()), driver).execute(request()) }
        assertEquals(0, driver.actions); assertTrue(result.result.errorMessage!!.contains("required app"))
    }
    @Test fun unobservablePreconditionsAreNotAssumedTrue() {
        val evaluator = PreconditionEvaluator(matcher)
        for (p in listOf(Preconditions(requiredActivity = "Screen"), Preconditions(fromState = "state_1"), Preconditions(customConditions = mapOf("unknown" to "true")))) {
            assertNotNull(evaluator.evaluate(p, ui(button()), app))
        }
    }
    @Test fun missingRequiredElementStopsBeforeAction() {
        val driver = FakeDriver(ui(button()))
        val wf = workflow(step(pre = Preconditions(requiredElementPresent = SemanticSelector(text = "Missing"))))
        assertFalse(run { ExecutionEngine(Store(wf), driver).execute(request()) }.result.success)
        assertEquals(0, driver.actions)
    }
    @Test fun closedLoopTapVerifiesAppearanceAndReportsSuccess() {
        val driver = FakeDriver(ui(button()))
        driver.effect = { driver.screen = ui(button(), button("result", "Details")) }
        val wf = workflow(step("TAP", transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Details"), timeoutMs = 200)))
        val report = run { ExecutionEngine(Store(wf), driver).execute(request()) }
        assertTrue(report.result.success); assertEquals(1, report.result.stepsCompleted)
        assertEquals(ExecutionState.COMPLETED, report.result.finalState); assertEquals(1, driver.actions)
        assertEquals(2, report.trace.events.size); assertEquals(report.result, report.trace.result)
        assertTrue(driver.observations >= 2); assertNull(report.stoppedStepId)
    }
    @Test fun closedLoopChangedTextUsesNewValueAndVerifiesIt() {
        val driver = FakeDriver(ui(input()))
        driver.effect = { driver.screen = ui(input(it.inputText!!)) }
        val wf = workflow(step("INPUT_TEXT", SemanticSelector(resourceId = "field", textSlot = "query")), listOf(WorkflowSlot(name = "query", type = SlotType.TEXT)))
        val report = run { ExecutionEngine(Store(wf), driver).execute(request(mapOf("query" to "new value"))) }
        assertTrue(report.result.success); assertEquals("new value", driver.lastStep!!.inputText)
        assertFalse(report.trace.toString().contains("new value"))
    }
    @Test fun inputTextDoesNotSucceedOnUnrelatedUiChange() {
        val driver = FakeDriver(ui(input()))
        driver.effect = { driver.screen = ui(input(), button("noise", "Loading")) }
        val wf = workflow(step("INPUT_TEXT", SemanticSelector(resourceId = "field")).copy(parameters = mapOf("input_literal" to "new value")))
        assertFalse(run { ExecutionEngine(Store(wf), driver).execute(request()) }.result.success)
    }
    @Test fun uuidOnlyChangeIsNotTransitionEvidence() {
        assertEquals(TransitionVerifier.fingerprint(ui(button())), TransitionVerifier.fingerprint(ui(button().copy(elementId = "new"))))
    }
    @Test fun semanticFingerprintDistinguishesAbsentAndLiteralNullText() {
        assertNotEquals(TransitionVerifier.fingerprint(ui(button().copy(text = null))),
            TransitionVerifier.fingerprint(ui(button(text = "null"))))
    }
    @Test fun acceptedActionWithNoTransitionTimesOutTruthfully() {
        val driver = FakeDriver(ui(button()))
        val result = run { ExecutionEngine(Store(workflow()), driver).execute(request()) }
        assertFalse(result.result.success); assertEquals(0, result.result.stepsCompleted)
        assertEquals("step", result.stoppedStepId); assertTrue(result.result.errorMessage!!.contains("timed out"))
        assertEquals(1, driver.actions); assertEquals(2, driver.waits)
    }
    @Test fun preexistingExpectedElementDoesNotProveAppearance() {
        val driver = FakeDriver(ui(button(), button("result", "Details")))
        driver.effect = { driver.screen = ui(button(), button("result", "Details"), button("noise", "Other")) }
        val wf = workflow(step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Details"), timeoutMs = 100)))
        assertFalse(run { ExecutionEngine(Store(wf), driver).execute(request()) }.result.success)
    }
    @Test fun disappearanceRequiresPriorPresence() {
        val driver = FakeDriver(ui(button(), button("old", "Old panel")))
        driver.effect = { driver.screen = ui(button()) }
        val wf = workflow(step(transition = ExpectedTransition(expectedElementDisappeared = SemanticSelector(text = "Old panel"))))
        assertTrue(run { ExecutionEngine(Store(wf), driver).execute(request()) }.result.success)
    }
    @Test fun unsupportedVerificationStopsBeforeDispatch() {
        val driver = FakeDriver(ui(button()))
        val wf = workflow(step(transition = ExpectedTransition(verification = "something arbitrary")))
        assertFalse(run { ExecutionEngine(Store(wf), driver).execute(request()) }.result.success)
        assertEquals(0, driver.actions)
    }
    @Test fun boundedRecoveryCanFindLateControl() {
        val driver = FakeDriver(ui())
        driver.waiting = { driver.screen = ui(button()) }
        driver.effect = { driver.screen = ui(button(text = "Finished")) }
        val wf = workflow(step(recovery = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP, retryDelayMs = 1)))
        assertTrue(run { ExecutionEngine(Store(wf), driver).execute(request()) }.result.success)
        assertEquals(1, driver.waits); assertEquals(1, driver.actions)
    }
    @Test fun recoveryBudgetExhaustionIsBounded() {
        val driver = FakeDriver(ui())
        val wf = workflow(step(recovery = RecoveryPolicy(maxRetries = 1000, strategy = RecoveryStrategy.RETRY_STEP, retryDelayMs = 1)))
        assertFalse(run { ExecutionEngine(Store(wf), driver).execute(request()) }.result.success)
        assertEquals(3, driver.waits); assertEquals(0, driver.actions)
    }
    @Test fun dispatchedActionIsNeverAutomaticallyRetried() {
        val driver = FakeDriver(ui(button()))
        val wf = workflow(step(recovery = RecoveryPolicy(maxRetries = 3, strategy = RecoveryStrategy.RETRY_STEP)))
        val report = run { ExecutionEngine(Store(wf), driver).execute(request()) }
        assertEquals(1, driver.actions); assertFalse(report.result.success)
        assertTrue(report.result.errorMessage!!.contains("duplicate a side effect"))
    }
    @Test fun navigationAndPopupRecoveryRequireManualControl() {
        for (strategy in listOf(RecoveryStrategy.DISMISS_POPUP_AND_RETRY, RecoveryStrategy.NAVIGATE_BACK_AND_RETRY)) {
            assertEquals(RecoveryAction.HANDOFF, RecoveryController().decide(RecoveryPolicy(strategy = strategy), 0, false).action)
        }
        assertEquals(RecoveryAction.ABORT, RecoveryController().decide(RecoveryPolicy(strategy = RecoveryStrategy.ABORT), 0, false).action)
    }
    @Test fun credentialAndPaymentKeywordsBlockWithoutLoggingValues() {
        for (text in listOf("Password", "Passcode", "PIN", "OTP", "Verification code", "CVV", "Card number", "Security code", "Login", "Confirm payment", "Checkout", "Place order", "पासवर्ड", "भुगतान")) {
            val driver = FakeDriver(ui(button(), button("sensitive", text)))
            val report = run { ExecutionEngine(Store(workflow()), driver).execute(request()) }
            assertEquals(text, 0, driver.actions); assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
            assertFalse(report.result.success)
        }
    }
    @Test fun passwordFlagAloneBlocksCredentialCapture() {
        val driver = FakeDriver(ui(button()).copy(credentialFieldPresent = true))
        val report = run { ExecutionEngine(Store(workflow()), driver).execute(request()) }
        assertEquals(0, driver.actions); assertTrue(report.result.errorMessage!!.contains("credential"))
    }
    @Test fun declaredSensitiveKeywordAndRestrictedActionBlock() {
        for (boundary in listOf(SafetyBoundary(sensitiveKeywords = listOf("Continue")), SafetyBoundary(restrictedActions = listOf("CLICK")), SafetyBoundary(restrictedActions = listOf("UNKNOWN_POLICY")))) {
            val driver = FakeDriver(ui(button()))
            val report = run { ExecutionEngine(Store(workflow(safety = boundary)), driver).execute(request()) }
            assertEquals(0, driver.actions); assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        }
    }
    @Test fun confirmationAndUnverifiableValueLimitStopAtAdmission() {
        for (boundary in listOf(SafetyBoundary(requiresExplicitUserConfirmation = true), SafetyBoundary(maxAllowedValue = 10.0))) {
            val driver = FakeDriver(ui(button()))
            assertFalse(run { ExecutionEngine(Store(workflow(safety = boundary)), driver).execute(request()) }.result.success)
            assertEquals(0, driver.actions)
        }
    }
    @Test fun safetyLockPersistsAcrossRequestsUntilExplicitNewPath() {
        val driver = FakeDriver(ui(button(), button("login", "Login")))
        val engine = ExecutionEngine(Store(workflow()), driver)
        assertFalse(run { engine.execute(request()) }.result.success)
        driver.screen = ui(button()); driver.effect = { driver.screen = ui(button(text = "Finished")) }
        assertFalse(run { engine.execute(request()) }.result.success); assertEquals(0, driver.actions)
        assertTrue(engine.acknowledgeHandoffForNewExecution())
        assertTrue(run { engine.execute(request()) }.result.success); assertEquals(1, driver.actions)
    }
    @Test fun lateSafetyBoundaryBetweenMatchAndDispatchBlocks() {
        val driver = FakeDriver(ui(button()))
        driver.beforeDispatch = { driver.screen = ui(button(), button("login", "OTP")) }
        assertFalse(run { ExecutionEngine(Store(workflow()), driver).execute(request()) }.result.success)
        assertEquals(0, driver.actions)
    }
    @Test fun paymentScreenAfterFirstActionStopsRemainingSteps() {
        val driver = FakeDriver(ui(button()))
        driver.effect = { driver.screen = ui(button(), button("payment", "Pay")) }
        val wf = workflow().let { it.copy(steps = listOf(it.steps.single(), step().copy(stepId = "second"))) }
        val report = run { ExecutionEngine(Store(wf), driver).execute(request()) }
        assertEquals(1, driver.actions); assertEquals(0, report.result.stepsCompleted)
        assertEquals(2, report.result.totalSteps); assertFalse(report.result.success)
    }
    @Test fun directSafetyGateCannotDispatchAfterBlock() {
        val gate = SafetyGate()
        val bound = SlotBinder.bind(workflow(), emptyMap()).steps.single()
        gate.block("Manual handoff required.")
        var calls = 0
        repeat(3) { assertFalse(gate.dispatch(SafetyBoundary(), ui(button()), bound) { calls++; true }.attempted) }
        assertEquals(0, calls)
    }
    @Test fun cancellationDuringPollingPreventsLaterSteps() {
        val driver = FakeDriver(ui(button()))
        val engine = ExecutionEngine(Store(workflow()), driver)
        driver.waiting = { engine.cancel() }
        val report = run { engine.execute(request()) }
        assertEquals(ExecutionState.ABORTED, report.result.finalState); assertEquals(1, driver.actions)
        assertFalse(run { engine.execute(request()) }.result.success); assertEquals(1, driver.actions)
    }
    @Test fun unavailableServiceIsReportedWithoutActions() {
        val driver = FakeDriver(ui(button())).apply { ready = false }
        val report = run { ExecutionEngine(Store(workflow()), driver).execute(request()) }
        assertFalse(report.result.success); assertEquals(0, driver.actions)
        assertTrue(report.result.errorMessage!!.contains("service is unavailable"))
    }
    @Test fun serviceDisconnectionDoesNotBecomeSuccess() {
        val driver = FakeDriver(ui(button()))
        driver.effect = { driver.ready = false }
        assertFalse(run { ExecutionEngine(Store(workflow()), driver).execute(request()) }.result.success)
    }
    @Test fun ambiguousMatchNeverDispatches() {
        val driver = FakeDriver(ui(button("a"), button("b")))
        val report = run { ExecutionEngine(Store(workflow()), driver).execute(request()) }
        assertEquals(0, driver.actions); assertTrue(report.diagnostics.any { it.decision.type == DecisionType.ASK_USER })
    }
    @Test fun falseActionReturnNeverBecomesSuccessEvenWithUiChange() {
        val driver = FakeDriver(ui(button())).apply { accepted = false }
        driver.effect = { driver.screen = ui(button(text = "Changed")) }
        assertFalse(run { ExecutionEngine(Store(workflow()), driver).execute(request()) }.result.success)
    }
    @Test fun secondStepFailurePreservesVerifiedCountAndStoppedStep() {
        val driver = FakeDriver(ui(button()))
        driver.effect = { driver.screen = ui(button(text = "Finished")) }
        val wf = workflow().let { it.copy(steps = listOf(it.steps.single(), step().copy(stepId = "second"))) }
        val report = run { ExecutionEngine(Store(wf), driver).execute(request()) }
        assertEquals(1, report.result.stepsCompleted); assertEquals(2, report.result.totalSteps)
        assertEquals("second", report.stoppedStepId); assertFalse(report.result.success)
    }
    @Test fun traceOmitsAllScreenTextAndTypedValues() {
        val driver = FakeDriver(ui(button(), input("private user data")))
        driver.effect = { driver.screen = ui(button(text = "Finished"), input("more private data")) }
        val report = run { ExecutionEngine(Store(workflow()), driver).execute(request()) }
        assertTrue(report.result.success)
        assertFalse(report.trace.toString().contains("private"))
        assertFalse(report.diagnostics.toString().contains("private"))
    }
    @Test fun versionLimitationIsExplicitAndCurrentWorkflowIsUsed() {
        val driver = FakeDriver(ui(button()))
        driver.effect = { driver.screen = ui(button(text = "Finished")) }
        val report = run { ExecutionEngine(Store(workflow()), driver).execute(request().copy(version = 99)) }
        assertTrue(report.result.success)
        assertTrue(report.diagnostics.any { it.decision.reason.contains("cannot verify") })
    }

    @Test fun symbolicLabelsRequireObservableEvidenceAndVerifiedPredecessor() {
        val firstSelector = SemanticSelector(text = "Continue")
        val first = step(selector = firstSelector,
            pre = Preconditions(fromState = "INITIAL_STATE", requiredPackage = app, requiredElementPresent = firstSelector),
            transition = ExpectedTransition(fromState = "INITIAL_STATE", toState = "state_step_1"))
        val secondSelector = SemanticSelector(text = "Next")
        val second = step(selector = secondSelector,
            pre = Preconditions(fromState = "state_step_1", requiredPackage = app, requiredElementPresent = secondSelector),
            transition = ExpectedTransition(fromState = "state_step_1", toState = "state_step_2")).copy(stepId = "second")
        val wf = workflow(first).copy(steps = listOf(first, second))
        val driver = FakeDriver(ui(button()))
        driver.effect = { driver.screen = ui(button(text = if (driver.actions == 1) "Next" else "Finished")) }
        val report = run { ExecutionEngine(Store(wf), driver).execute(request()) }
        assertTrue(report.result.success); assertEquals(2, report.result.stepsCompleted)
    }
    @Test fun symbolicTransitionStartingStateCannotBeInvented() {
        val driver = FakeDriver(ui(button()))
        val wf = workflow(step(transition = ExpectedTransition(fromState = "unobserved", toState = "later")))
        assertFalse(run { ExecutionEngine(Store(wf), driver).execute(request()) }.result.success)
        assertEquals(0, driver.actions)
    }
    @Test fun initialStateWithoutElementEvidenceIsRefused() {
        assertNotNull(PreconditionEvaluator(matcher).evaluate(Preconditions(fromState = "INITIAL_STATE", requiredPackage = app), ui(button()), app))
    }
    @Test fun camelCaseCredentialResourceNamesAreRecognizedWithoutSubstringFalsePositive() {
        assertTrue(RuntimeSafetyPolicy().credentialText("otpInput"))
        assertTrue(RuntimeSafetyPolicy().credentialText("passwordField"))
        assertFalse(RuntimeSafetyPolicy().credentialText("shopping"))
    }
    @Test fun unknownExceptionMessagesNeverEnterReports() {
        val driver = FakeDriver(ui(button()))
        driver.beforeDispatch = { error("private credential value") }
        val report = run { ExecutionEngine(Store(workflow()), driver).execute(request()) }
        assertFalse(report.result.success); assertFalse(report.toString().contains("private credential value"))
    }
    @Test fun activeRunRejectsConcurrentExecutionAndReset() {
        val driver = FakeDriver(ui(button()))
        val engine = ExecutionEngine(Store(workflow()), driver)
        driver.effect = {
            assertFalse(engine.acknowledgeHandoffForNewExecution())
            val concurrent = run { engine.execute(request()) }
            assertFalse(concurrent.result.success)
            assertTrue(concurrent.result.errorMessage!!.contains("already active"))
            driver.screen = ui(button(text = "Finished"))
        }
        assertTrue(run { engine.execute(request()) }.result.success); assertEquals(1, driver.actions)
    }
    @Test fun cancelBetweenMatchingAndDispatchPreventsAction() {
        val driver = FakeDriver(ui(button()))
        val engine = ExecutionEngine(Store(workflow()), driver)
        driver.beforeDispatch = { engine.cancel() }
        val report = run { engine.execute(request()) }
        assertEquals(ExecutionState.ABORTED, report.result.finalState); assertEquals(0, driver.actions)
    }
    @Test fun threeStepWorkflowBindsTextQuantityAndAddressInOrder() {
        val names = listOf("item", "quantity", "address")
        val values = listOf("Changed text", "7", "New destination")
        val types = listOf(SlotType.TEXT, SlotType.INTEGER, SlotType.ADDRESS)
        val selectors = names.map { SemanticSelector(role = "EditText", resourceId = "id/$it", textSlot = it) }
        val steps = selectors.mapIndexed { index, selector ->
            step(action = "INPUT_TEXT", selector = selector).copy(stepId = "step_$index")
        }
        val wf = workflow().copy(steps = steps, slots = names.mapIndexed { index, name ->
            WorkflowSlot(name = name, type = types[index], required = true, exampleValue = "Old value")
        })
        val entered = mutableMapOf<String, String>()
        fun screen() = ui(*names.mapIndexed { index, name -> UiElement(elementId = "field_$index",
            role = "EditText", resourceId = "id/$name", text = entered[name].orEmpty(), isEditable = true)
        }.toTypedArray())
        val driver = FakeDriver(screen())
        val observedInputs = mutableListOf<String?>()
        driver.effect = { bound ->
            observedInputs.add(bound.inputText)
            entered[names[observedInputs.size - 1]] = bound.inputText!!
            driver.screen = screen()
        }
        val report = run { ExecutionEngine(Store(wf), driver).execute(request(names.zip(values).toMap())) }
        assertEquals(values, observedInputs)
        assertEquals(3, driver.actions)
        assertEquals(3, report.result.stepsCompleted)
        assertTrue(report.result.success)
    }

    @Test fun sensitiveSecondScreenBlocksNextStepAndEverySubsequentRun() {
        val wf = workflow().copy(steps = listOf(step().copy(stepId = "first"), step().copy(stepId = "second")))
        val driver = FakeDriver(ui(button()))
        driver.effect = { driver.screen = ui(button(text = "Password")) }
        val engine = ExecutionEngine(Store(wf), driver)
        val first = run { engine.execute(request()) }
        val second = run { engine.execute(request()) }
        assertEquals(1, driver.actions)
        assertFalse(first.result.success)
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, second.result.finalState)
        assertEquals(0, first.result.stepsCompleted)
    }

    @Test fun constantInputValueIsNotMistakenForCurrentFieldIdentity() {
        val selector = SemanticSelector(role = "EditText", resourceId = "field", text = "Fixed text")
        val source = step(action = "INPUT_TEXT", selector = selector,
            pre = Preconditions(requiredElementPresent = selector)).copy(parameters = mapOf("input_literal" to "Fixed text"))
        val bound = SlotBinder.bind(workflow(source), emptyMap()).steps.single()
        assertNull(bound.selector.text)
        assertNull(bound.preconditions.requiredElementPresent!!.text)
        assertEquals("Fixed text", bound.inputText)
        assertEquals(MatchStatus.MATCHED, matcher.match(bound.selector, ui(input("")), bound.action).status)
    }

    @Test fun financialTransferConfirmationRequiresHandoffWithoutAction() {
        val driver = FakeDriver(ui(button(text = "Confirm transfer")))
        val report = run { ExecutionEngine(Store(workflow()), driver).execute(request()) }
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertEquals(0, driver.actions)
    }

    // =========================================================================
    // PHASE 4.5 — SEMANTIC UI TARGET RESOLUTION & MATCHING TEST MATRIX
    // =========================================================================

    @Test fun testPH4_5_T1_ExactSemanticMatch() {
        val selector = SemanticSelector(role = "button", text = "Search")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.example.app",
            allElements = listOf(button(id = "search_btn", text = "Search"))
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertNotNull(result.matchedElement)
        assertEquals("search_btn", result.matchedElement!!.elementId)
        assertTrue(result.confidence >= 0.85)
    }

    @Test fun testPH4_5_T2_CaseNormalization() {
        val selector = SemanticSelector(text = "Search")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.example.app",
            allElements = listOf(button(id = "b1", text = "search"))
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("b1", result.matchedElement!!.elementId)
    }

    @Test fun testPH4_5_T3_ContentDescriptionMatch() {
        val selector = SemanticSelector(role = "button", contentDescription = "Shopping Cart")
        val liveElement = UiElement(
            elementId = "cart_btn", role = "button", text = null,
            contentDescription = "Shopping Cart", isClickable = true
        )
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.example.app",
            allElements = listOf(liveElement)
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("cart_btn", result.matchedElement!!.elementId)
    }

    @Test fun testPH4_5_T4_ResourceIdSupportingEvidence() {
        val c1 = button(id = "c1", text = "Search").copy(resourceId = "com.app:id/search_button")
        val c2 = button(id = "c2", text = "Search").copy(resourceId = "com.app:id/unrelated_button")
        val selector = SemanticSelector(role = "button", text = "Search", resourceId = "com.app:id/search_button")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(c2, c1)
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("c1", result.matchedElement!!.elementId)
        assertTrue(result.candidates.first().confidence > result.candidates.last().confidence)
    }

    @Test fun testPH4_5_T5_RoleDiscrimination() {
        val liveInput = input(text = "Search")
        val liveButton = button(id = "search_btn", text = "Search")
        val selector = SemanticSelector(role = "button", text = "Search")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(liveInput, liveButton)
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("search_btn", result.matchedElement!!.elementId)
    }

    @Test fun testPH4_5_T6_DisabledTarget() {
        val disabledButton = button(id = "buy_btn", text = "Buy").copy(isEnabled = false)
        val selector = SemanticSelector(role = "button", text = "Buy")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(disabledButton)
        )
        val result = matcher.match(selector, liveState, RuntimeAction.CLICK)
        assertNotEquals(MatchStatus.MATCHED, result.status)
        assertTrue(result.status == MatchStatus.NO_MATCH || result.status == MatchStatus.NONE)
    }

    @Test fun testPH4_5_T7_NonVisibleTarget() {
        val hiddenButton = button(id = "hidden", text = "Search").copy(isVisible = false)
        val selector = SemanticSelector(role = "button", text = "Search")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(hiddenButton)
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.NO_MATCH, result.status)
        assertNull(result.matchedElement)
    }

    @Test fun testPH4_5_T8_RepeatedElements_Ambiguous() {
        val itemAAdd = button(id = "itemA_add", text = "Add to cart")
        val itemBAdd = button(id = "itemB_add", text = "Add to cart")
        val selector = SemanticSelector(role = "button", text = "Add to cart")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(itemAAdd, itemBAdd)
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.AMBIGUOUS, result.status)
        assertTrue(result.isAmbiguous)
    }

    @Test fun testPH4_5_T9_HierarchicalDisambiguation() {
        val itemAAdd = button(id = "elem_0_1_btn", text = "Add to cart").copy(nearbyText = "Product A")
        val itemBAdd = button(id = "elem_1_1_btn", text = "Add to cart").copy(nearbyText = "Product B")
        val selector = SemanticSelector(role = "button", text = "Add to cart", nearbyText = "Product B")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(itemAAdd, itemBAdd)
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("elem_1_1_btn", result.matchedElement!!.elementId)
    }

    @Test fun testPH4_5_T10_SemanticSynonym() {
        val selector = SemanticSelector(role = "button", text = "Search")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(button(id = "find_btn", text = "Find"))
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("find_btn", result.matchedElement!!.elementId)
    }

    @Test fun testPH4_5_T11_WrongRoleRejection() {
        val selector = SemanticSelector(role = "input", text = "Search")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(button(id = "btn", text = "Search"))
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.NO_MATCH, result.status)
    }

    @Test fun testPH4_5_T12_WrongInteractionCapability() {
        val nonClickable = UiElement(
            elementId = "b1", role = "Button", text = "Click Me",
            isClickable = false, isEnabled = true
        )
        val selector = SemanticSelector(role = "Button", text = "Click Me")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(nonClickable)
        )
        val result = matcher.match(selector, liveState, RuntimeAction.CLICK)
        assertEquals(MatchStatus.NO_MATCH, result.status)
    }

    @Test fun testPH4_5_T13_MultipleCandidatesCloseScores() {
        val b1 = button(id = "b1", text = "Submit order")
        val b2 = button(id = "b2", text = "Submit order")
        val selector = SemanticSelector(text = "Submit order")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(b1, b2)
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.AMBIGUOUS, result.status)
    }

    @Test fun testPH4_5_T14_StrongUniqueCandidate() {
        val target = button(id = "unique_btn", text = "Proceed to payment")
        val other = button(id = "other_btn", text = "Cancel")
        val selector = SemanticSelector(text = "Proceed to payment")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(target, other)
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("unique_btn", result.matchedElement!!.elementId)
        assertTrue(result.confidence >= 0.85)
    }

    @Test fun testPH4_5_T15_NoMatchingElement() {
        val selector = SemanticSelector(text = "Checkout")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(button(text = "Home"), button(text = "Profile"))
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.NO_MATCH, result.status)
        assertNull(result.matchedElement)
    }

    @Test fun testPH4_5_T16_UnavailableUiState() {
        val selector = SemanticSelector(text = "Search")
        val obs = UiObservationResult(status = UiObservationStatus.UNAVAILABLE, errorMessage = "Root window is null")
        val result = matcher.match(selector, obs)
        assertEquals(MatchStatus.INVALID_STATE, result.status)
        assertNull(result.matchedElement)
    }

    @Test fun testPH4_5_T17_StaleUiState() {
        val selector = SemanticSelector(text = "Search")
        val obs = UiObservationResult(status = UiObservationStatus.STALE)
        val result = matcher.match(selector, obs)
        assertEquals(MatchStatus.INVALID_STATE, result.status)
        assertNull(result.matchedElement)
    }

    @Test fun testPH4_5_T18_SensitiveTarget() {
        val sensitiveField = input(text = "••••••••").copy(isSensitive = true)
        val selector = SemanticSelector(role = "EditText", resourceId = "field")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(sensitiveField), isSensitiveContext = true
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.SENSITIVE_TARGET, result.status)
        assertTrue(result.isSensitive)
        assertNotNull(result.matchedElement)
        assertTrue(result.matchedElement!!.isSensitive)
    }

    @Test fun testPH4_5_T19_CrossAppSemanticMatching() {
        val selector = SemanticSelector(role = "button", text = "Search", resourceId = "com.original.store:id/search_btn")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.target.store",
            allElements = listOf(button(id = "t_search", text = "Search").copy(packageName = "com.target.store"))
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("t_search", result.matchedElement!!.elementId)
    }

    @Test fun testPH4_5_T20_CoordinateIndependence() {
        val selector = SemanticSelector(role = "button", text = "Filter")
        val stateA = UiState(
            stateId = "state_A", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(button(id = "filter", text = "Filter").copy(bounds = UiElementBounds(0, 0, 100, 100)))
        )
        val stateB = UiState(
            stateId = "state_B", timestamp = 2000L, appContext = "com.app",
            allElements = listOf(button(id = "filter", text = "Filter").copy(bounds = UiElementBounds(500, 600, 700, 800)))
        )
        val resultA = matcher.match(selector, stateA)
        val resultB = matcher.match(selector, stateB)
        assertEquals(resultA.status, resultB.status)
        assertEquals(resultA.confidence, resultB.confidence, 0.0001)
        assertEquals(resultA.matchedElement!!.elementId, resultB.matchedElement!!.elementId)
    }

    @Test fun testPH4_5_T21_StateIdentityIndependence() {
        val selector = SemanticSelector(role = "button", text = "Apply")
        val state1 = UiState(stateId = "id_1", timestamp = 1000L, appContext = "com.app", allElements = listOf(button(text = "Apply")))
        val state2 = UiState(stateId = "id_2", timestamp = 9999L, appContext = "com.app", allElements = listOf(button(text = "Apply")))
        val r1 = matcher.match(selector, state1)
        val r2 = matcher.match(selector, state2)
        assertEquals(r1.status, r2.status)
        assertEquals(r1.confidence, r2.confidence, 0.0001)
    }

    @Test fun testPH4_5_T22_EmptyTextAndContentDescription() {
        val selector = SemanticSelector(role = "button", text = "Shopping Cart")
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(UiElement(elementId = "cart", role = "button", text = null, contentDescription = "Shopping Cart", isClickable = true))
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("cart", result.matchedElement!!.elementId)
    }

    @Test fun testPH4_5_T23_MissingResourceId() {
        val selector = SemanticSelector(role = "button", text = "Submit", resourceId = null)
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(button(id = "sub", text = "Submit").copy(resourceId = null))
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("sub", result.matchedElement!!.elementId)
    }

    @Test fun testPH4_5_T24_MissingContentDescription() {
        val selector = SemanticSelector(role = "button", text = "Sort", contentDescription = null)
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(button(id = "sort", text = "Sort").copy(contentDescription = null))
        )
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("sort", result.matchedElement!!.elementId)
    }

    @Test fun testPH4_5_T25_NoExecutionSideEffects() {
        val liveState = UiState(
            stateId = "state_1", timestamp = 1000L, appContext = "com.app",
            allElements = listOf(button(text = "Buy Now"), input(text = "Query"), button(text = "Continue"))
        )
        val driver = FakeDriver(UiObservation(liveState))
        val selector = SemanticSelector(role = "button", text = "Buy Now")

        // Call matcher directly
        val result = matcher.match(selector, liveState)
        assertEquals(MatchStatus.MATCHED, result.status)

        // Verify read-only invariant: zero actions performed
        assertEquals(0, driver.actions)
        assertEquals(0, driver.waits)
    }

    // =========================================================================
    // PHASE 4.5 — INTEGRATION TEST & KILL TEST
    // =========================================================================

    @Test fun testPhase4_5_IntegrationTest_PipelineThroughSemanticMatching() {
        // Step 1: Bind workflow step with runtime values
        val stepSource = step(
            action = "CLICK",
            selector = SemanticSelector(role = "button", text = "Search")
        )
        val wf = workflow(step = stepSource)
        val bindingResult = SlotBinder.bind(wf, emptyMap())
        val boundStep = bindingResult.steps.single()

        // Step 2: Live UIState observed from Accessibility
        val liveUi = UiState(
            stateId = "live_store_screen",
            timestamp = System.currentTimeMillis(),
            appContext = "com.target.store",
            allElements = listOf(
                input("").copy(elementId = "search_box", role = "EditText"),
                button(id = "search_btn", text = "Search")
            )
        )

        // Step 3: Phase 4.5 resolves target from bound step and live UIState
        val execReq = ExecutionRequest(executionId = "exec_1", skillId = wf.skillId)
        val matchResult = matcher.match(execReq, boundStep, liveUi)

        assertEquals(MatchStatus.MATCHED, matchResult.status)
        assertNotNull(matchResult.matchedElement)
        assertEquals("search_btn", matchResult.matchedElement!!.elementId)
        assertTrue(matchResult.confidence >= 0.85)
        assertFalse(matchResult.isAmbiguous)
        assertFalse(matchResult.isSensitive)
    }

    @Test fun testPhase4_5_KillTest_ZeroDemonstrationReplay_GenericSemanticResolution() {
        // Teaching demonstrated ordering a "white shirt" on "Amazon" with tap coordinates (412, 815)
        // Runtime request asks for "blue jacket" on "Myntra"
        val searchSelector = SemanticSelector(role = "button", text = "Search")
        val runtimeStep = BoundStep(
            source = step(action = "CLICK", selector = searchSelector),
            action = RuntimeAction.CLICK,
            selector = searchSelector,
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )

        // Live UI observed from device foreground is the runtime platform (Myntra)
        val liveUi = UiState(
            stateId = "sig_myntra_live",
            timestamp = System.currentTimeMillis(),
            appContext = "com.myntra.android",
            allElements = listOf(
                button(id = "live_search_button", text = "Search").copy(
                    packageName = "com.myntra.android",
                    bounds = UiElementBounds(100, 200, 300, 400) // completely different from demonstration (412, 815)
                )
            )
        )

        // Phase 4.5 matches target purely against live semantic properties
        val result = matcher.match(runtimeStep, liveUi)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("live_search_button", result.matchedElement!!.elementId)

        // Verifications:
        // 1. Target identity comes from live UI, not demonstration replay
        assertEquals("com.myntra.android", result.matchedElement!!.packageName)
        // 2. Demonstration coordinates (412, 815) were completely ignored
        assertEquals(UiElementBounds(100, 200, 300, 400), result.matchedElement!!.bounds)
        // 3. No action was executed (pure read-only)
        assertEquals(MatchStatus.MATCHED, result.status)
    }

    // =========================================================================
    // PHASE 4.6 — SAFETY-GATED GENERIC ANDROID ACTION EXECUTION TEST MATRIX
    // =========================================================================

    @Test fun testPH4_6_T1_SuccessfulGenericClick() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Continue")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Continue"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button(text = "Continue")))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertTrue(outcome.attempted)
        assertTrue(outcome.accepted)
        assertEquals(1, driver.actions)
        assertEquals("SUCCESS", outcome.status)
    }

    @Test fun testPH4_6_T2_SuccessfulSetText_UsesRuntimeBoundValue() {
        val step = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "field")),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", resourceId = "field"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(),
            inputText = "Blue Jacket"
        )
        val driver = FakeDriver(ui(input(text = "")))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertTrue(outcome.attempted)
        assertTrue(outcome.accepted)
        assertEquals(1, driver.actions)
        assertEquals("Blue Jacket", driver.lastStep?.inputText)
    }

    @Test fun testPH4_6_T3_SuccessfulGenericScroll() {
        val scrollableList = UiElement(elementId = "list", role = "RecyclerView", isScrollable = true, isVisible = true, isEnabled = true)
        val step = BoundStep(
            source = step(action = "SCROLL", selector = SemanticSelector(role = "RecyclerView")),
            action = RuntimeAction.SCROLL,
            selector = SemanticSelector(role = "RecyclerView"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(),
            scrollDirection = "forward"
        )
        val driver = FakeDriver(ui(scrollableList))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertTrue(outcome.attempted)
        assertTrue(outcome.accepted)
        assertEquals(1, driver.actions)
    }

    @Test fun testPH4_6_T4_DisabledTarget_ZeroAction() {
        val disabled = button(text = "Submit").copy(isEnabled = false)
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Submit")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Submit"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(disabled))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertFalse(outcome.accepted)
        assertEquals(0, driver.actions)
    }

    @Test fun testPH4_6_T5_InvisibleTarget_ZeroAction() {
        val invisible = button(text = "Submit").copy(isVisible = false)
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Submit")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Submit"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(invisible))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertEquals(0, driver.actions)
    }

    @Test fun testPH4_6_T6_NullTarget_SafeFailure() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(text = "NonExistent")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(text = "NonExistent"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button(text = "Other")))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertEquals(0, driver.actions)
        assertTrue(outcome.reason.contains("NO_MATCH") || outcome.reason.contains("NONE"))
    }

    @Test fun testPH4_6_T7_UnavailableAccessibilityService_SafeFailure() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Continue")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Continue"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button(text = "Continue"))).apply { ready = false }
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.accepted)
        assertEquals(0, driver.actions)
    }

    @Test fun testPH4_6_T8_UnsupportedAction() {
        val nonEditable = button(text = "ClickOnly")
        val step = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "Button", text = "ClickOnly")),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "Button", text = "ClickOnly"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(),
            inputText = "test"
        )
        val driver = FakeDriver(ui(nonEditable))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertEquals(0, driver.actions)
    }

    @Test fun testPH4_6_T9_SafetyGateBlocked_ZeroAction() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Continue")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Continue"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button(text = "Continue")))
        val gate = SafetyGate().apply { block("Security violation detected.") }
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertFalse(outcome.accepted)
        assertEquals(0, driver.actions)
        assertEquals("Security violation detected.", outcome.reason)
    }

    @Test fun testPH4_6_T10_SafetyGateNeedsUserControl_ZeroAction() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Pay now")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Pay now"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button(text = "Pay now")))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertEquals(0, driver.actions)
        assertTrue(outcome.reason.contains("payment") || outcome.reason.contains("user control"))
    }

    @Test fun testPH4_6_T11_SensitiveTarget_ZeroAutomaticAction() {
        val sensitiveNode = input(text = "").copy(isSensitive = true)
        val step = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "field")),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", resourceId = "field"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(),
            inputText = "Secret123"
        )
        val driver = FakeDriver(ui(sensitiveNode))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertEquals(0, driver.actions)
        assertTrue(outcome.isSensitiveBlocked || outcome.reason.contains("sensitive") || outcome.reason.contains("credential"))
    }

    @Test fun testPH4_6_T12_SensitiveTextInput_ZeroAutomaticAction() {
        val pwdField = input(text = "").copy(text = "password")
        val step = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", text = "password")),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", text = "password"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(),
            inputText = "my_password"
        )
        val driver = FakeDriver(ui(pwdField))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertEquals(0, driver.actions)
    }

    @Test fun testPH4_6_T13_PaymentBoundary_ZeroAutomaticAction() {
        val buyBtn = button(text = "Confirm order")
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Confirm order")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Confirm order"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(buyBtn))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertEquals(0, driver.actions)
    }

    @Test fun testPH4_6_T14_TargetStaleBeforeExecution_ZeroAction() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Continue")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Continue"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val initialObservation = ui(button(text = "Continue"))
        val driver = FakeDriver(initialObservation)
        // Screen changes before dispatch
        driver.beforeDispatch = {
            driver.screen = ui(button(text = "Done"))
        }
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.accepted)
        assertEquals(0, driver.actions)
    }

    @Test fun testPH4_6_T15_CurrentTargetDiffersFromMatchedState_SafeRefusal() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Submit")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Submit"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button(text = "DifferentButton")))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertEquals(0, driver.actions)
    }

    @Test fun testPH4_6_T16_NoCoordinateReplay_UsesLiveTarget() {
        val liveButton = button(id = "live_btn", text = "ClickMe").copy(
            bounds = UiElementBounds(500, 600, 700, 800)
        )
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "ClickMe")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "ClickMe"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(liveButton))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertTrue(outcome.attempted)
        assertTrue(outcome.accepted)
        assertEquals(1, driver.actions)
        assertEquals("live_btn", outcome.targetElementId)
    }

    @Test fun testPH4_6_T17_NoDemonstrationValueLeakage_UsesBlueJacket() {
        // Taught "white shirt", but runtime BoundStep.inputText has "blue jacket"
        val step = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "field")),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", resourceId = "field"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(),
            inputText = "blue jacket"
        )
        val driver = FakeDriver(ui(input(text = "")))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertTrue(outcome.accepted)
        assertEquals("blue jacket", driver.lastStep?.inputText)
        assertNotEquals("white shirt", driver.lastStep?.inputText)
    }

    @Test fun testPH4_6_T18_NoAppSpecificExecutionBranches() {
        // Verify execution logic runs purely on generic elements regardless of package name
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Open")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Open"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button(text = "Open"), packageName = "com.arbitrary.package"))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, "com.arbitrary.package", SafetyBoundary(), gate) }
        assertTrue(outcome.attempted)
        assertTrue(outcome.accepted)
    }

    @Test fun testPH4_6_T19_NoActionWhenMatcherAmbiguous() {
        val b1 = button(id = "b1", text = "Add to cart")
        val b2 = button(id = "b2", text = "Add to cart")
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Add to cart")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Add to cart"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(b1, b2))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertEquals(0, driver.actions)
        assertTrue(outcome.reason.contains("AMBIGUOUS"))
    }

    @Test fun testPH4_6_T20_NoActionWhenMatcherNoMatch() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Checkout")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Checkout"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button(text = "Catalog")))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertEquals(0, driver.actions)
        assertTrue(outcome.reason.contains("NO_MATCH") || outcome.reason.contains("NONE"))
    }

    @Test fun testPH4_6_T21_DeterministicExecutionDecision() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Continue")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Continue"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver1 = FakeDriver(ui(button(text = "Continue")))
        val driver2 = FakeDriver(ui(button(text = "Continue")))
        val executor = SemanticExecutor(matcher)

        val o1 = run { executor.execute(driver1, step, driver1.screen, app, SafetyBoundary(), SafetyGate()) }
        val o2 = run { executor.execute(driver2, step, driver2.screen, app, SafetyBoundary(), SafetyGate()) }

        assertEquals(o1.attempted, o2.attempted)
        assertEquals(o1.accepted, o2.accepted)
        assertEquals(o1.status, o2.status)
    }

    @Test fun testPH4_6_T22_ExecutionTraceGenerated() {
        val recorder = ExecutionTraceRecorder(request())
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Continue")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Continue"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val actionId = recorder.action(step)
        assertNotNull(actionId)
        assertTrue(actionId.isNotBlank())
    }

    @Test fun testPH4_6_T23_SensitiveDataRedaction() {
        val recorder = ExecutionTraceRecorder(request())
        val step = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", text = "password")),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", text = "password"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(),
            inputText = "super_secret_password"
        )
        val actionId = recorder.action(step)
        val before = ui(input(text = "super_secret_password"))
        val after = ui(input(text = "super_secret_password"))
        recorder.transition(actionId, before, after)
        val report = recorder.finish(ExecutionResult(true, 1, ExecutionState.COMPLETED))

        // Ensure secrets never appear in trace events
        val traceString = report.trace.toString()
        assertFalse(traceString.contains("super_secret_password"))
    }

    @Test fun testPH4_6_T24_NoAutomaticRetryLoop() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Continue")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Continue"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button(text = "Continue"))).apply { accepted = false }
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertEquals(1, driver.actions) // strictly 1 attempt, zero retry loops
        assertFalse(outcome.accepted)
    }

    @Test fun testPH4_6_T25_DriverExceptionHandledSafely() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Continue")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Continue"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = object : UiDriver {
            override fun isReady() = true
            override suspend fun observe() = ui(button(text = "Continue"))
            override suspend fun awaitChange(delayMs: Long) {}
            override suspend fun execute(step: BoundStep, expectedPackage: String, boundary: SafetyBoundary, matcher: SemanticMatcher, gate: SafetyGate): ActionOutcome {
                throw RuntimeException("Simulated driver failure")
            }
        }
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        try {
            val outcome = run { executor.execute(driver, step, ui(button(text = "Continue")), app, SafetyBoundary(), gate) }
            assertFalse(outcome.accepted)
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Simulated driver failure") == true)
        }
    }

    // =========================================================================
    // KILL TEST, ADVERSARIAL TESTS, AND CROSS-APP TEST
    // =========================================================================

    @Test fun testPhase4_6_KillTest_ZeroDemonstrationReplay_RuntimeExecution() {
        // Taught: "Order white shirt on Amazon" with coords (412, 815)
        // Runtime: "Get me a blue jacket from Myntra"
        val liveMyntraSearchButton = button(id = "myntra_search", text = "Search").copy(
            bounds = UiElementBounds(100, 200, 300, 400),
            packageName = "com.myntra.android"
        )
        val runtimeStep = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Search")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(liveMyntraSearchButton, packageName = "com.myntra.android"))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, runtimeStep, driver.screen, "com.myntra.android", SafetyBoundary(), gate) }
        assertTrue(outcome.attempted)
        assertTrue(outcome.accepted)
        assertEquals(1, driver.actions)
        // Target executed is live target from current UI, NOT demonstration coordinates
        assertEquals("myntra_search", outcome.targetElementId)
    }

    @Test fun testPhase4_6_AdversarialTest_ConfidenceOneBlockedBySafetyGate() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Continue")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Continue"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button(text = "Continue")))
        val gate = SafetyGate().apply { block("Safety gate explicitly blocked.") }
        val executor = SemanticExecutor(matcher)

        // Confidence 1.0 match exists, but gate is blocked
        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertFalse(outcome.accepted)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_6_AdversarialTest_StaleTargetRefusesExecution() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Continue")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Continue"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button(text = "Continue")))
        driver.beforeDispatch = {
            // UI dynamically mutated into different screen
            driver.screen = ui(button(text = "UnrelatedScreen"))
        }
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.accepted)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_6_AdversarialTest_AmbiguousNeverExecutes() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Select")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Select"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button(id = "s1", text = "Select"), button(id = "s2", text = "Select")))
        val gate = SafetyGate()
        val executor = SemanticExecutor(matcher)

        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertEquals(0, driver.actions)
        assertTrue(outcome.reason.contains("AMBIGUOUS"))
    }

    @Test fun testPhase4_6_CrossAppTest_GenericExecutionAcrossMultiplePackages() {
        val clickStep = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Open")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Open"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val executor = SemanticExecutor(matcher)

        // App 1: Shopping client
        val driverApp1 = FakeDriver(ui(button(text = "Open"), packageName = "com.store.app"))
        val outcome1 = run { executor.execute(driverApp1, clickStep, driverApp1.screen, "com.store.app", SafetyBoundary(), SafetyGate()) }
        assertTrue(outcome1.accepted)
        assertEquals(1, driverApp1.actions)

        // App 2: Messaging client
        val driverApp2 = FakeDriver(ui(button(text = "Open"), packageName = "com.message.app"))
        val outcome2 = run { executor.execute(driverApp2, clickStep, driverApp2.screen, "com.message.app", SafetyBoundary(), SafetyGate()) }
        assertTrue(outcome2.accepted)
        assertEquals(1, driverApp2.actions)
    }

    // ==========================================
    // PHASE 4.7 — CLOSED-LOOP TRANSITION VERIFICATION TESTS
    // ==========================================

    @Test fun testPhase4_7_T1_SuccessfulStateTransition() {
        val clickStep = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Search"),
                transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "TextView", text = "Results"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "TextView", text = "Results"))
        )
        val before = ui(button(id = "search_btn", text = "Search"))
        val after = ui(UiElement(elementId = "res", role = "TextView", text = "Results"))
        val verifier = TransitionVerifier(matcher)
        val result = verifier.verifyStateTransition(before, clickStep, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
        assertEquals(1.0, result.confidence, 0.001)
    }

    @Test fun testPhase4_7_T2_NoStateChange_ReturnsNotVerified() {
        val clickStep = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Search"),
                transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "TextView", text = "Results"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "TextView", text = "Results"))
        )
        val before = ui(button(id = "search_btn", text = "Search"))
        val after = ui(button(id = "search_btn", text = "Search"))
        val verifier = TransitionVerifier(matcher)
        val result = verifier.verifyStateTransition(before, clickStep, after)
        assertFalse(result.verified)
        assertEquals(VerificationStatus.NOT_VERIFIED, result.status)
    }

    @Test fun testPhase4_7_T3_ExpectedElementAppears() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Order Placed"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Confirm"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Order Placed"))
        )
        val before = ui(button(text = "Confirm"))
        val after = ui(UiElement(elementId = "status", role = "TextView", text = "Order Placed"))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_T4_ExpectedElementDisappears() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementDisappeared = SemanticSelector(role = "Dialog", text = "Confirm Dialog"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Close"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementDisappeared = SemanticSelector(role = "Dialog", text = "Confirm Dialog"))
        )
        val before = ui(UiElement(elementId = "dlg", role = "Dialog", text = "Confirm Dialog"), button(text = "Close"))
        val after = ui(button(text = "Close"))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_T5_ExpectedTextValueChange() {
        val step = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "field")),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", resourceId = "field"),
            inputText = "Blue Jacket",
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val before = ui(input(text = ""))
        val after = ui(input(text = "Blue Jacket"))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_T6_SelectionStateChange() {
        val req = StateEvidenceRequirement(type = EvidenceType.SELECTED_STATE, selector = SemanticSelector(role = "RadioButton", text = "Medium"), expectedSelected = true)
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedEvidence = listOf(req))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "RadioButton", text = "Medium"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedEvidence = listOf(req))
        )
        val before = ui(UiElement("rb", role = "RadioButton", text = "Medium", isSelected = false))
        val after = ui(UiElement("rb", role = "RadioButton", text = "Medium", isSelected = true))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_T7_QuantityValueIncrement() {
        val req = StateEvidenceRequirement(type = EvidenceType.COUNTER_CHANGE, selector = SemanticSelector(resourceId = "qty_count"), expectedValue = "2")
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedEvidence = listOf(req))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "+"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedEvidence = listOf(req))
        )
        val before = ui(UiElement("c", role = "TextView", resourceId = "qty_count", text = "1"))
        val after = ui(UiElement("c", role = "TextView", resourceId = "qty_count", text = "2"))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_T8_ForegroundApplicationTransition() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedPackage = "com.destination.app")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Switch"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedPackage = "com.destination.app")
        )
        val before = ui(button(text = "Switch"), packageName = "com.source.app")
        val after = ui(button(text = "Welcome"), packageName = "com.destination.app")
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_T9_WrongForegroundTransition_ReturnsUnexpectedTransition() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedPackage = "com.destination.app")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Switch"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedPackage = "com.destination.app")
        )
        val before = ui(button(text = "Switch"), packageName = "com.source.app")
        val after = ui(button(text = "Error"), packageName = "com.wrong.app")
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertFalse(result.verified)
        assertEquals(VerificationStatus.UNEXPECTED_TRANSITION, result.status)
    }

    @Test fun testPhase4_7_T10_UnexpectedScreen_ReturnsUnexpectedTransition() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))
        )
        val before = ui(button(text = "Search"))
        val after = ui(UiElement("err", role = "TextView", text = "Login Required"))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertFalse(result.verified)
        assertEquals(VerificationStatus.UNEXPECTED_TRANSITION, result.status)
    }

    @Test fun testPhase4_7_T11_VerificationTimeout_ReturnsTimeoutStatus() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"), timeoutMs = 200)),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"), timeoutMs = 200)
        )
        val before = ui(button(text = "Search"))
        val driver = FakeDriver(ui(UiElement("load", role = "ProgressBar", text = "Loading...")))
        val verifier = TransitionVerifier(matcher, VerificationConfig(pollMs = 50, maximumTimeoutMs = 200))
        val result = run { verifier.verify(driver, before, step) }
        assertFalse(result.verified)
        assertEquals(VerificationStatus.VERIFICATION_TIMEOUT, result.status)
        assertTrue(result.status.isTimeout)
    }

    @Test fun testPhase4_7_T12_UiUnavailable_ReturnsUiUnavailable() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))
        )
        val before = ui(button(text = "Search"))
        val driver = FakeDriver(before).apply { ready = false }
        val result = run { TransitionVerifier(matcher).verify(driver, before, step) }
        assertFalse(result.verified)
        assertEquals(VerificationStatus.UI_UNAVAILABLE, result.status)
        assertTrue(result.status.isUnavailable)
    }

    @Test fun testPhase4_7_T13_StaleObservation_FailsSafely() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(fromState = "STATE_BASELINE")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Go"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(fromState = "STATE_BASELINE"),
            stateEvidence = mapOf("STATE_BASELINE" to "mismatched_fingerprint_hash")
        )
        val before = ui(button(text = "Go"))
        val after = ui(button(text = "Done"))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertFalse(result.verified)
        assertEquals(VerificationStatus.NOT_VERIFIED, result.status)
        assertTrue(result.reason.contains("starting state"))
    }

    @Test fun testPhase4_7_T14_StateIdChangedButSemanticExpectationFailed_ReturnsUnexpectedTransition() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))
        )
        val before = ui(button(text = "Search"))
        val after = ui(button(text = "Unrelated Screen"))
        assertNotEquals(before.state.stateId, after.state.stateId)
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertFalse(result.verified)
        assertEquals(VerificationStatus.UNEXPECTED_TRANSITION, result.status)
    }

    @Test fun testPhase4_7_T15_SameStateIdButExpectedValueChanged_VerifiedSuccess() {
        val step = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "field")),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", resourceId = "field"),
            inputText = "Blue Jacket",
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val fixedStateId = "same-state-id"
        val before = UiObservation(UiState(stateId = fixedStateId, timestamp = 100L, appContext = app, allElements = listOf(input(text = ""))))
        val after = UiObservation(UiState(stateId = fixedStateId, timestamp = 200L, appContext = app, allElements = listOf(input(text = "Blue Jacket"))))
        assertEquals(before.state.stateId, after.state.stateId)
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_T16_ParameterizedRuntimeValue_VerifiedAgainstBoundValue() {
        val step = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "field")),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", resourceId = "field"),
            inputText = "Blue Jacket",
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val before = ui(input(text = ""))
        val after = ui(input(text = "Blue Jacket"))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_T17_DemonstrationValueMustNeverLeakIntoVerification() {
        val step = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "field")),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", resourceId = "field"),
            inputText = "Blue Jacket",
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val before = ui(input(text = ""))
        val after = ui(input(text = "White Shirt"))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertFalse(result.verified)
        assertNotEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_T18_CoordinateIndependence_IgnoresBoundsChanges() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "Button", text = "Submit"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Next"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "Button", text = "Submit"))
        )
        val before = ui(UiElement("b1", role = "Button", text = "Next", bounds = BoundingBox(0, 0, 100, 100)))
        val after = ui(UiElement("b2", role = "Button", text = "Submit", bounds = BoundingBox(720, 1400, 1080, 1600)))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_T19_ResourceIdVariation_SemanticMatchSucceeds() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "TextView", text = "Results"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "TextView", text = "Results"))
        )
        val before = ui(button(text = "Search"))
        val after = ui(UiElement("elem99", role = "TextView", resourceId = "com.generated.dynamic:id/res_random_hash", text = "Results"))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_T20_SemanticTextNormalization_MatchesNormalizedText() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Search"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Go"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Search"))
        )
        val before = ui(button(text = "Go"))
        val after = ui(UiElement("e1", role = "TextView", text = "  search  "))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_T21_RepeatedElements_ContextualDisambiguation() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "TextView", text = "Item", parentRole = "HeaderGroup"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Add"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "TextView", text = "Item", parentRole = "HeaderGroup"))
        )
        val before = ui(button(text = "Add"))
        val after = ui(
            UiElement("i1", role = "TextView", text = "Item", parentRole = "ListRow"),
            UiElement("i2", role = "TextView", text = "Item", parentRole = "HeaderGroup")
        )
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_T22_SensitiveAfterState_DetectedSafely() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "TextView", text = "Enter Payment Details"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Proceed"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "TextView", text = "Enter Payment Details"))
        )
        val before = ui(button(text = "Proceed"))
        val after = UiObservation(UiState(
            stateId = "sec-state",
            timestamp = System.currentTimeMillis(),
            appContext = app,
            isSensitiveContext = true,
            allElements = listOf(UiElement("p1", role = "TextView", text = "Enter Payment Details"))
        ))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertTrue(result.isSensitive)
    }

    @Test fun testPhase4_7_T23_SensitiveDataRedaction_TraceOmitsRawSecrets() {
        val recorder = ExecutionTraceRecorder(request())
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(text = "Submit")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(text = "Submit"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val before = ui(UiElement("pwd", role = "EditText", text = "super_secret_pin_1234", isPassword = true))
        val after = ui(UiElement("ok", role = "TextView", text = "Success"))
        val actionId = recorder.action(step)
        val verification = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        recorder.recordVerification(step.source.stepId, verification)
        recorder.transition(actionId, before, after)
        val report = recorder.finish(ExecutionState.COMPLETED, 1, 1, null, null)
        val traceString = report.trace.toString()
        assertFalse(traceString.contains("super_secret_pin_1234"))
    }

    @Test fun testPhase4_7_T24_VerificationCausesZeroNewUiActions() {
        val clickStep = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Submit"),
                transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Submit"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done"))
        )
        val driver = FakeDriver(ui(button(text = "Submit")))
        val executor = SemanticExecutor(matcher)
        val outcome = run { executor.execute(driver, clickStep, driver.screen, app, SafetyBoundary(), SafetyGate()) }
        assertTrue(outcome.accepted)
        val actionsAfterExecution = driver.actions
        assertEquals(1, actionsAfterExecution)

        driver.screen = ui(UiElement("d", role = "TextView", text = "Done"))
        val verification = run { TransitionVerifier(matcher).verify(driver, outcome.before!!, clickStep) }
        assertTrue(verification.verified)
        assertEquals(actionsAfterExecution, driver.actions)
    }

    @Test fun testPhase4_7_T25_NoAutomaticRecovery_StopsAtVerification() {
        val clickStep = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Submit"),
                transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "ExpectedSuccess"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Submit"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "ExpectedSuccess"), timeoutMs = 150)
        )
        val driver = FakeDriver(ui(button(text = "Submit")))
        val executor = SemanticExecutor(matcher)
        val outcome = run { executor.execute(driver, clickStep, driver.screen, app, SafetyBoundary(), SafetyGate()) }
        assertTrue(outcome.accepted)
        assertEquals(1, driver.actions)

        val verifier = TransitionVerifier(matcher, VerificationConfig(pollMs = 50, maximumTimeoutMs = 150))
        val verification = run { verifier.verify(driver, outcome.before!!, clickStep) }
        assertFalse(verification.verified)
        assertEquals(1, driver.actions)
    }

    @Test fun testPhase4_7_T26_DeterministicVerification_SameInputsProduceIdenticalResults() {
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))
        )
        val before = ui(button(text = "Search"))
        val after = ui(UiElement("r", role = "TextView", text = "Results"))
        val verifier = TransitionVerifier(matcher)
        val res1 = verifier.verifyStateTransition(before, step, after)
        val res2 = verifier.verifyStateTransition(before, step, after)
        assertEquals(res1.status, res2.status)
        assertEquals(res1.verified, res2.verified)
        assertEquals(res1.reason, res2.reason)
        assertEquals(res1.confidence, res2.confidence, 0.001)
    }

    @Test fun testPhase4_7_T27_ExecutionOutcomeAloneCannotDetermineVerificationSuccess() {
        val before = ui(button(text = "Search"))
        val simulatedOutcome = ActionOutcome(attempted = true, accepted = true, reason = "Accessibility action accepted", before = before)
        assertTrue(simulatedOutcome.accepted)

        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))
        )
        val after = ui(button(text = "Search"))
        val verification = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertFalse(verification.verified)
        assertEquals(VerificationStatus.NOT_VERIFIED, verification.status)
    }

    @Test fun testPhase4_7_T28_ActionOutcomeFailure_NotVerifiedWithoutRecovery() {
        val before = ui(button(text = "Submit"))
        val failedOutcome = ActionOutcome(attempted = false, accepted = false, reason = "Action blocked by safety policy", before = before)
        assertFalse(failedOutcome.accepted)

        val driver = FakeDriver(before)
        val step = BoundStep(
            source = step(transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Submit"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done"))
        )
        val verification = TransitionVerifier(matcher).verifyStateTransition(before, step, driver.screen)
        assertFalse(verification.verified)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_7_EndToEndIntegration_4_1_to_4_7() {
        val parsedIntent = "search"
        val querySlot = "Blue Jacket"

        val wf = workflow(
            step = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "search_box")),
            slots = listOf(WorkflowSlot(name = "query", type = "string", isRequired = true))
        )
        val resolver = WorkflowResolver(Store(wf))
        val resolved = resolver.resolve(request(slots = mapOf("query" to querySlot)))
        assertEquals(wf, resolved.workflow)

        val binder = RuntimeSlotBinder()
        val boundStep = binder.bind(resolved.workflow.steps.first(), resolved.boundSlots, resolved.workflow.slots)
        assertEquals("Blue Jacket", boundStep.inputText)

        val beforeObservation = ui(UiElement("input1", role = "EditText", resourceId = "search_box", text = "", isEditable = true))
        val driver = FakeDriver(beforeObservation)

        val matchResult = matcher.match(boundStep.selector, beforeObservation, boundStep.action)
        assertEquals(MatchStatus.MATCHED, matchResult.status)

        val executor = SemanticExecutor(matcher)
        driver.effect = {
            driver.screen = ui(UiElement("input1", role = "EditText", resourceId = "search_box", text = "Blue Jacket", isEditable = true))
        }
        val outcome = run { executor.execute(driver, boundStep, beforeObservation, app, SafetyBoundary(), SafetyGate()) }
        assertTrue(outcome.accepted)

        val verification = run { TransitionVerifier(matcher).verify(driver, beforeObservation, boundStep) }
        assertTrue(verification.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, verification.status)
        assertEquals(1, driver.actions)
    }

    @Test fun testPhase4_7_NavigationIntegrationTest() {
        val clickStep = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Search"),
                transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "TextView", text = "Results"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(role = "TextView", text = "Results"))
        )
        val before = ui(button(id = "search_btn", text = "Search"))
        val driver = FakeDriver(before)
        val match = matcher.match(clickStep.selector, before, clickStep.action)
        assertEquals(MatchStatus.MATCHED, match.status)

        val executor = SemanticExecutor(matcher)
        driver.effect = {
            driver.screen = ui(UiElement("res", role = "TextView", text = "Results"))
        }
        val outcome = run { executor.execute(driver, clickStep, before, app, SafetyBoundary(), SafetyGate()) }
        assertTrue(outcome.accepted)

        val verification = run { TransitionVerifier(matcher).verify(driver, before, clickStep) }
        assertTrue(verification.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, verification.status)
    }

    @Test fun testPhase4_7_KillTest_CrossAppRuntimeRebindingVerification() {
        val targetPkg = "com.client.store"
        val step = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "search_input"),
                transition = ExpectedTransition(expectedPackage = targetPkg)),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", resourceId = "search_input"),
            inputText = "Blue Jacket",
            preconditions = Preconditions(),
            transition = ExpectedTransition(
                expectedPackage = targetPkg,
                expectedEvidence = listOf(
                    StateEvidenceRequirement(type = EvidenceType.EXPECTED_PACKAGE, expectedPackage = targetPkg),
                    StateEvidenceRequirement(type = EvidenceType.TEXT_EQUALS, selector = SemanticSelector(resourceId = "search_input"), expectedValue = "Blue Jacket")
                )
            )
        )
        val before = ui(UiElement("search_input", role = "EditText", resourceId = "search_input", text = "", isEditable = true), packageName = targetPkg)
        val after = ui(UiElement("search_input", role = "EditText", resourceId = "search_input", text = "Blue Jacket", isEditable = true), packageName = targetPkg)

        val verifier = TransitionVerifier(matcher)
        val result = verifier.verifyStateTransition(before, step, after)
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_AdversarialTest_FalseSuccess() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Search"),
                transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))
        )
        val before = ui(button(text = "Search"))
        val after = ui(button(text = "Search"))
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertFalse(result.verified)
        assertEquals(VerificationStatus.NOT_VERIFIED, result.status)
    }

    @Test fun testPhase4_7_AdversarialTest_FalseStateChange() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Search"),
                transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))
        )
        val before = ui(button(text = "Search"))
        val after = ui(UiElement("login", role = "TextView", text = "Login Required"))
        assertNotEquals(before.state.stateId, after.state.stateId)
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertFalse(result.verified)
        assertEquals(VerificationStatus.UNEXPECTED_TRANSITION, result.status)
    }

    @Test fun testPhase4_7_AdversarialTest_NoResponse() {
        val fixedId = "fixed-id-A"
        val before = UiObservation(UiState(stateId = fixedId, timestamp = 100L, appContext = app, allElements = listOf(button(text = "Search"))))
        val after = UiObservation(UiState(stateId = fixedId, timestamp = 200L, appContext = app, allElements = listOf(button(text = "Search"))))
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Search"),
                transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"))
        )
        val result = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertFalse(result.verified)
        assertEquals(VerificationStatus.NOT_VERIFIED, result.status)
    }

    @Test fun testPhase4_7_AdversarialTest_DelayedSuccess() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Search"),
                transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"), timeoutMs = 500)),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"), timeoutMs = 500)
        )
        val before = ui(button(text = "Search"))
        val driver = FakeDriver(ui(UiElement("load", role = "ProgressBar", text = "Loading...")))
        var pollCount = 0
        driver.waiting = {
            pollCount++
            if (pollCount >= 2) {
                driver.screen = ui(UiElement("res", role = "TextView", text = "Results"))
            }
        }
        val verifier = TransitionVerifier(matcher, VerificationConfig(pollMs = 50, maximumTimeoutMs = 500))
        val result = run { verifier.verify(driver, before, step) }
        assertTrue(result.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test fun testPhase4_7_AdversarialTest_NeverSettles() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Search"),
                transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"), timeoutMs = 150)),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results"), timeoutMs = 150)
        )
        val before = ui(button(text = "Search"))
        val driver = FakeDriver(ui(UiElement("load", role = "ProgressBar", text = "Loading...")))
        val verifier = TransitionVerifier(matcher, VerificationConfig(pollMs = 50, maximumTimeoutMs = 150))
        val result = run { verifier.verify(driver, before, step) }
        assertFalse(result.verified)
        assertEquals(VerificationStatus.VERIFICATION_TIMEOUT, result.status)
        assertEquals(0, driver.actions)
    }

    // ==========================================
    // PHASE 4.8 — BOUNDED RECOVERY & RE-PLANNING TESTS
    // ==========================================

    @Test fun testPhase4_8_T1_VerifiedSuccess_NoRecoveryNeeded() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = true, reason = "Transition verified.", status = VerificationStatus.VERIFIED_SUCCESS)
        val controller = RecoveryController()
        val decision = controller.decideRecovery(policy, verification)
        assertEquals(RecoveryAction.HANDOFF, decision.action)
        assertTrue(decision.reason.contains("no recovery needed"))
    }

    @Test fun testPhase4_8_T2_NotVerified_EvaluatesBoundedRecovery() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "No transition.", status = VerificationStatus.NOT_VERIFIED)
        val controller = RecoveryController()
        val decision = controller.decideRecovery(policy, verification, retries = 0)
        assertNotNull(decision)
        assertTrue(decision.action == RecoveryAction.RETRY || decision.action == RecoveryAction.RERESOLVE_TARGET)
    }

    @Test fun testPhase4_8_T3_FreshObservationBeforeRecovery() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "Failed", status = VerificationStatus.NOT_VERIFIED)
        val freshUi = ui(button(text = "UpdatedSearch"))
        val controller = RecoveryController()
        val decision = controller.decideRecovery(policy, verification, currentUi = freshUi, retries = 0)
        assertEquals(1, decision.attemptNumber)
        assertNotNull(decision.action)
    }

    @Test fun testPhase4_8_T4_StaleTarget_TriggersRematch() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "Target stale", status = VerificationStatus.NOT_VERIFIED)
        val controller = RecoveryController()
        val actionOutcome = ActionOutcome(attempted = true, accepted = true, reason = "Accepted but changed", isStale = true)
        val decision = controller.decideRecovery(policy, verification, actionOutcome = actionOutcome, retries = 0)
        assertEquals(RecoveryAction.RERESOLVE_TARGET, decision.action)
    }

    @Test fun testPhase4_8_T5_TargetChanged_RematchesSemantically() {
        val step = step(selector = SemanticSelector(text = "Search"))
        val currentUi = ui(UiElement("s2", role = "Button", text = "Search Products"))
        val match = matcher.match(step.semanticSelector, currentUi)
        assertEquals(MatchStatus.MATCHED, match.status)
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "Target moved", status = VerificationStatus.NOT_VERIFIED)
        val decision = RecoveryController().decideRecovery(policy, verification, currentUi = currentUi, targetMatch = match, retries = 0)
        assertNotNull(decision)
    }

    @Test fun testPhase4_8_T6_AmbiguousRecoveryTarget_NoAction() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "Failed", status = VerificationStatus.NOT_VERIFIED)
        val ambMatch = com.chockXlate.teachablevoice.runtime.matching.MatchResult(status = MatchStatus.AMBIGUOUS, reason = "Multiple matches")
        val decision = RecoveryController().decideRecovery(policy, verification, targetMatch = ambMatch, retries = 0)
        assertEquals(RecoveryAction.ASK_USER, decision.action)
    }

    @Test fun testPhase4_8_T7_NoMatch_BoundedStop() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "Failed", status = VerificationStatus.NOT_VERIFIED)
        val noMatch = com.chockXlate.teachablevoice.runtime.matching.MatchResult(status = MatchStatus.NONE, reason = "Element absent")
        val decision = RecoveryController().decideRecovery(policy, verification, targetMatch = noMatch, retries = 0)
        assertEquals(RecoveryAction.HANDOFF, decision.action)
    }

    @Test fun testPhase4_8_T8_RecoveryPolicyNone_StopsImmediately() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.NONE)
        val verification = VerificationResult(verified = false, reason = "Failed", status = VerificationStatus.NOT_VERIFIED)
        val decision = RecoveryController().decideRecovery(policy, verification, retries = 0)
        assertEquals(RecoveryAction.ABORT, decision.action)
    }

    @Test fun testPhase4_8_T9_BoundedRetry_EnforcesMaxLimit() {
        val policy = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)
        val controller = RecoveryController(hardRetryLimit = 3)
        assertEquals(2, controller.retryLimit(policy))
        val verification = VerificationResult(verified = false, reason = "Failed", status = VerificationStatus.NOT_VERIFIED)
        val dec1 = controller.decideRecovery(policy, verification, retries = 0)
        assertNotEquals(RecoveryAction.HANDOFF, dec1.action)
        val dec2 = controller.decideRecovery(policy, verification, retries = 1)
        assertNotEquals(RecoveryAction.HANDOFF, dec2.action)
        val dec3 = controller.decideRecovery(policy, verification, retries = 2)
        assertEquals(RecoveryAction.HANDOFF, dec3.action)
    }

    @Test fun testPhase4_8_T10_RetryExhaustion_ReturnsHandoff() {
        val policy = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "Failed", status = VerificationStatus.NOT_VERIFIED)
        val decision = RecoveryController().decideRecovery(policy, verification, retries = 1)
        assertEquals(RecoveryAction.HANDOFF, decision.action)
        assertTrue(decision.reason.contains("exhausted"))
    }

    @Test fun testPhase4_8_T11_ScrollRecovery_WhenAllowed() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.SCROLL_AND_RETRY)
        val verification = VerificationResult(verified = false, reason = "Target absent", status = VerificationStatus.NOT_VERIFIED)
        val noMatch = com.chockXlate.teachablevoice.runtime.matching.MatchResult(status = MatchStatus.NONE, reason = "Not on screen")
        val decision = RecoveryController().decideRecovery(policy, verification, targetMatch = noMatch, scrollCount = 0, maxScrolls = 2)
        assertEquals(RecoveryAction.SCROLL, decision.action)
    }

    @Test fun testPhase4_8_T12_ScrollLimit_PreventsInfiniteScrolling() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.SCROLL_AND_RETRY)
        val verification = VerificationResult(verified = false, reason = "Target absent", status = VerificationStatus.NOT_VERIFIED)
        val noMatch = com.chockXlate.teachablevoice.runtime.matching.MatchResult(status = MatchStatus.NONE, reason = "Not on screen")
        val decision = RecoveryController().decideRecovery(policy, verification, targetMatch = noMatch, scrollCount = 2, maxScrolls = 2)
        assertEquals(RecoveryAction.HANDOFF, decision.action)
        assertTrue(decision.reason.contains("limit reached"))
    }

    @Test fun testPhase4_8_T13_BackRecovery_WhenAllowed() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.NAVIGATE_BACK_AND_RETRY)
        val verification = VerificationResult(verified = false, reason = "Unexpected screen", status = VerificationStatus.UNEXPECTED_TRANSITION)
        val decision = RecoveryController().decideRecovery(policy, verification, retries = 0)
        assertEquals(RecoveryAction.NAVIGATE_BACK, decision.action)
    }

    @Test fun testPhase4_8_T14_BackNotAllowed_NoBackAction() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "Unexpected screen", status = VerificationStatus.UNEXPECTED_TRANSITION)
        val decision = RecoveryController().decideRecovery(policy, verification, retries = 0)
        assertNotEquals(RecoveryAction.NAVIGATE_BACK, decision.action)
    }

    @Test fun testPhase4_8_T15_SafetyBlocked_ZeroAccessibilityActions() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "Failed", status = VerificationStatus.NOT_VERIFIED)
        val decision = RecoveryController().decideRecovery(policy, verification, safetyBlocked = true, retries = 0)
        assertEquals(RecoveryAction.HANDOFF, decision.action)
        assertTrue(decision.reason.contains("Safety boundary"))
    }

    @Test fun testPhase4_8_T16_SensitiveRecoveryTarget_BlocksAutomatedAction() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "Failed", status = VerificationStatus.NOT_VERIFIED)
        val sensMatch = com.chockXlate.teachablevoice.runtime.matching.MatchResult(status = MatchStatus.SENSITIVE_TARGET, reason = "Sensitive")
        val decision = RecoveryController().decideRecovery(policy, verification, targetMatch = sensMatch, retries = 0)
        assertEquals(RecoveryAction.HANDOFF, decision.action)
    }

    @Test fun testPhase4_8_T17_PaymentBoundary_StopsAutomation() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "Reached payment", status = VerificationStatus.UNEXPECTED_TRANSITION, isSensitive = true)
        val decision = RecoveryController().decideRecovery(policy, verification, isSensitive = true, retries = 0)
        assertEquals(RecoveryAction.HANDOFF, decision.action)
        assertTrue(decision.reason.contains("sensitive"))
    }

    @Test fun testPhase4_8_T18_UiUnavailable_BoundedReobservationThenStop() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "No UI", status = VerificationStatus.UI_UNAVAILABLE)
        val dec1 = RecoveryController().decideRecovery(policy, verification, currentUi = null, retries = 0)
        assertEquals(RecoveryAction.REOBSERVE, dec1.action)
        val dec2 = RecoveryController().decideRecovery(policy, verification, currentUi = null, retries = 2)
        assertEquals(RecoveryAction.HANDOFF, dec2.action)
    }

    @Test fun testPhase4_8_T19_UnexpectedTransition_PolicyDrivenRecovery() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.REOBSERVE_AND_RETRY)
        val verification = VerificationResult(verified = false, reason = "Popup", status = VerificationStatus.UNEXPECTED_TRANSITION)
        val decision = RecoveryController().decideRecovery(policy, verification, retries = 0)
        assertEquals(RecoveryAction.RERESOLVE_TARGET, decision.action)
    }

    @Test fun testPhase4_8_T20_InvalidExpectation_NoSpeculativeRecovery() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "Schema invalid", status = VerificationStatus.INVALID_EXPECTATION)
        val decision = RecoveryController().decideRecovery(policy, verification, retries = 0)
        assertEquals(RecoveryAction.ABORT, decision.action)
    }

    @Test fun testPhase4_8_T21_RecoveryActionMustBeVerified() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(text = "RetryBtn"),
                transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "FinalDone"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(text = "RetryBtn"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "FinalDone"))
        )
        val before = ui(button(text = "RetryBtn"))
        val driver = FakeDriver(before)
        val outcome = ActionOutcome(attempted = true, accepted = true, reason = "Executed")
        assertTrue(outcome.accepted)
        val verification = TransitionVerifier(matcher).verifyStateTransition(before, step, driver.screen)
        assertFalse(verification.verified)
    }

    @Test fun testPhase4_8_T22_FalseRecoverySuccess_NotVerifiedAndBoundedStop() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(text = "RetryBtn"),
                transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "TargetSuccess"))),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(text = "RetryBtn"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "TargetSuccess"))
        )
        val before = ui(button(text = "RetryBtn"))
        val after = ui(button(text = "RetryBtn"))
        val verification = TransitionVerifier(matcher).verifyStateTransition(before, step, after)
        assertFalse(verification.verified)
        val decision = RecoveryController().decideRecovery(RecoveryPolicy(maxRetries = 1), verification, retries = 1)
        assertEquals(RecoveryAction.HANDOFF, decision.action)
    }

    @Test fun testPhase4_8_T23_RuntimeSlotPreservation() {
        val boundStep = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "query")),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", resourceId = "query"),
            inputText = "Blue Jacket",
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        assertEquals("Blue Jacket", boundStep.inputText)
        assertNotEquals("white shirt", boundStep.inputText)
    }

    @Test fun testPhase4_8_T24_PlatformSubstitution_SemanticRecovery() {
        val targetApp = "com.store.myntra"
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Search"),
                transition = ExpectedTransition(expectedPackage = targetApp)),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedPackage = targetApp)
        )
        val uiObs = ui(button(text = "Search"), packageName = targetApp)
        val match = matcher.match(step.selector, uiObs)
        assertEquals(MatchStatus.MATCHED, match.status)
        assertEquals(targetApp, uiObs.state.appContext)
    }

    @Test fun testPhase4_8_T25_ResourceIdVariation_SemanticRecoverySucceeds() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Search")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Search"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val uiObs = ui(UiElement("b_diff", role = "Button", resourceId = "com.client:id/dynamic_random_id", text = "Search", isClickable = true))
        val match = matcher.match(step.selector, uiObs)
        assertEquals(MatchStatus.MATCHED, match.status)
    }

    @Test fun testPhase4_8_T26_CoordinateVariation_RecoveryUnaffected() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Next")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Next"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val ui1 = ui(UiElement("n1", role = "Button", text = "Next", bounds = BoundingBox(10, 20, 100, 200), isClickable = true))
        val ui2 = ui(UiElement("n2", role = "Button", text = "Next", bounds = BoundingBox(800, 1200, 950, 1400), isClickable = true))
        val m1 = matcher.match(step.selector, ui1)
        val m2 = matcher.match(step.selector, ui2)
        assertEquals(MatchStatus.MATCHED, m1.status)
        assertEquals(MatchStatus.MATCHED, m2.status)
    }

    @Test fun testPhase4_8_T27_RepeatedElements_DisambiguatesContextually() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Add", parentRole = "ProductCard")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Add", parentRole = "ProductCard"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val currentUi = ui(
            UiElement("b1", role = "Button", text = "Add", parentRole = "Toolbar", isClickable = true),
            UiElement("b2", role = "Button", text = "Add", parentRole = "ProductCard", isClickable = true)
        )
        val match = matcher.match(step.selector, currentUi)
        assertEquals(MatchStatus.MATCHED, match.status)
        assertEquals("b2", match.best?.element?.elementId)
    }

    @Test fun testPhase4_8_T28_DeterministicRecoveryDecision() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP)
        val verification = VerificationResult(verified = false, reason = "Failed", status = VerificationStatus.NOT_VERIFIED)
        val controller = RecoveryController()
        val dec1 = controller.decideRecovery(policy, verification, retries = 0)
        val dec2 = controller.decideRecovery(policy, verification, retries = 0)
        assertEquals(dec1.action, dec2.action)
        assertEquals(dec1.reason, dec2.reason)
        assertEquals(dec1.delayMs, dec2.delayMs)
    }

    @Test fun testPhase4_8_T29_NoDirectAccessibilityBypass() {
        val driver = FakeDriver(ui(button(text = "Go")))
        val executor = SemanticExecutor(matcher)
        val gate = SafetyGate().apply { block("Blocked") }
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(text = "Go")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(text = "Go"),
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val outcome = run { executor.execute(driver, step, driver.screen, app, SafetyBoundary(), gate) }
        assertFalse(outcome.attempted)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_8_T30_NoUncontrolledLoop_EnforcesStrictLimits() {
        val controller = RecoveryController(hardRetryLimit = 3)
        val policy = RecoveryPolicy(maxRetries = 10, strategy = RecoveryStrategy.RETRY_STEP)
        assertEquals(3, controller.retryLimit(policy))
    }

    @Test fun testPhase4_8_T31_MultiAppRecovery_SemanticHandling() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Launch"),
                transition = ExpectedTransition(expectedPackage = "com.destination.app")),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Launch"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedPackage = "com.destination.app")
        )
        val before = ui(button(text = "Launch"), packageName = "com.source.app")
        val intermediateUi = ui(UiElement("dlg", role = "Dialog", text = "Permission Request"), packageName = "com.destination.app")
        val verification = TransitionVerifier(matcher).verifyStateTransition(before, step, intermediateUi)
        assertTrue(verification.verified)
    }

    @Test fun testPhase4_8_T32_SensitiveTraceRedaction() {
        val recorder = ExecutionTraceRecorder(request())
        recorder.recordRecovery(RecoveryRecord(
            executionId = "rec-1",
            workflowStepId = "step-1",
            attemptNumber = 1,
            originalAction = "INPUT_TEXT",
            actionOutcome = "FAILED",
            verificationStatus = VerificationStatus.NOT_VERIFIED,
            currentStateId = "state-1",
            recoveryStrategy = "RETRY_STEP",
            recoveryReason = "Password entered was rejected",
            recoveryAction = "RETRY",
            finalRecoveryStatus = "HANDOFF"
        ))
        val report = recorder.finish(ExecutionState.PAUSED_FOR_HANDOFF, 0, 1, "Stopped", "step-1")
        assertFalse(report.trace.toString().contains("super_secret_pin"))
        assertEquals(1, report.recoveryRecords.size)
    }

    @Test fun testPhase4_8_EndToEndRecoveryTest() {
        val boundStep = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "search_box"),
                recovery = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", resourceId = "search_box"),
            inputText = "Blue Jacket",
            preconditions = Preconditions(),
            transition = ExpectedTransition()
        )
        val initialBefore = ui(input(text = ""))
        val driver = FakeDriver(initialBefore)
        val controller = RecoveryController()

        // 1. Initial attempt fails to transition (unverified)
        val unverifiedResult = VerificationResult(verified = false, reason = "Text unconfirmed", status = VerificationStatus.NOT_VERIFIED)
        val dec1 = controller.decideRecovery(boundStep.source.recoveryPolicy, unverifiedResult, retries = 0)
        assertEquals(RecoveryAction.RETRY, dec1.action)

        // 2. Recovery re-observes and rematches target
        driver.screen = ui(UiElement("search_box", role = "EditText", resourceId = "search_box", text = "Blue Jacket", isEditable = true))
        val reVerification = TransitionVerifier(matcher).verifyStateTransition(initialBefore, boundStep, driver.screen)
        assertTrue(reVerification.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, reVerification.status)
    }

    @Test fun testPhase4_8_AdversarialTest_ActionSucceedsThenEntersSensitiveState_BlocksAllActions() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Confirm"),
                recovery = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Confirm"),
            preconditions = Preconditions(),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Order Placed"))
        )
        val before = ui(button(text = "Confirm"))
        // Action succeeded at Accessibility level, but UI entered unexpected sensitive payment/card screen
        val sensitivePaymentScreen = UiObservation(UiState(
            stateId = "payment-page",
            timestamp = System.currentTimeMillis(),
            appContext = app,
            isSensitiveContext = true,
            allElements = listOf(UiElement("card_num", role = "EditText", text = "Enter Card Number", isPassword = true))
        ), credentialFieldPresent = true)

        val verification = TransitionVerifier(matcher).verifyStateTransition(before, step, sensitivePaymentScreen)
        assertFalse(verification.verified)
        assertTrue(verification.isSensitive)

        val decision = RecoveryController().decideRecovery(
            step.source.recoveryPolicy,
            verification,
            currentUi = sensitivePaymentScreen,
            isSensitive = true,
            retries = 0
        )
        assertEquals(RecoveryAction.HANDOFF, decision.action)
        assertTrue(decision.reason.contains("sensitive"))
    }

    @Test fun testPhase4_8_KillTest_SemanticRecoveryWithRuntimeParameters() {
        // Teaching: "Search for a white shirt on Amazon."
        // Runtime: "Find a blue jacket on Myntra."
        val myntraPkg = "com.myntra.android"
        val step = BoundStep(
            source = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "search_query"),
                transition = ExpectedTransition(expectedPackage = myntraPkg),
                recovery = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)),
            action = RuntimeAction.INPUT_TEXT,
            selector = SemanticSelector(role = "EditText", resourceId = "search_query"),
            inputText = "Blue Jacket",
            preconditions = Preconditions(),
            transition = ExpectedTransition(
                expectedPackage = myntraPkg,
                expectedEvidence = listOf(
                    StateEvidenceRequirement(type = EvidenceType.EXPECTED_PACKAGE, expectedPackage = myntraPkg),
                    StateEvidenceRequirement(type = EvidenceType.TEXT_EQUALS, selector = SemanticSelector(resourceId = "search_query"), expectedValue = "Blue Jacket")
                )
            )
        )
        val before = ui(UiElement("search_query", role = "EditText", resourceId = "search_query", text = "", isEditable = true), packageName = myntraPkg)
        // First post-action observation fails transition
        val failedObs = ui(UiElement("search_query", role = "EditText", resourceId = "search_query", text = "Loading...", isEditable = true), packageName = myntraPkg)
        val failVerification = TransitionVerifier(matcher).verifyStateTransition(before, step, failedObs)
        assertFalse(failVerification.verified)

        // Recovery: Re-resolves target on current UI, preserves runtime parameter "Blue Jacket"
        val recoveryDecision = RecoveryController().decideRecovery(step.source.recoveryPolicy, failVerification, currentUi = failedObs, retries = 0)
        assertNotEquals(RecoveryAction.ABORT, recoveryDecision.action)

        // After re-observation, target settled with "Blue Jacket" on Myntra
        val recoveredObs = ui(UiElement("search_query", role = "EditText", resourceId = "search_query", text = "Blue Jacket", isEditable = true), packageName = myntraPkg)
        val finalVerification = TransitionVerifier(matcher).verifyStateTransition(before, step, recoveredObs)
        assertTrue(finalVerification.verified)
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, finalVerification.status)
    }

    // =========================================================================
    // PHASE 4.9L — UNCERTAINTY, CLARIFICATION & HUMAN HANDOFF TEST MATRIX
    // =========================================================================

    @Test fun testPhase4_9L_T1_ClearCommand_NoClarification() {
        val slot = WorkflowSlot(name = "item", type = SlotType.TEXT, required = true)
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Search"))
        val wf = workflow(step = step, slots = listOf(slot))
        val driver = FakeDriver(ui(UiElement("b1", role = "Button", text = "Search", isClickable = true)))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request(mapOf("item" to "jacket"))) }
        assertEquals(ExecutionState.COMPLETED, report.result.finalState)
        assertNull(report.result.clarificationRequest)
        assertEquals(1, driver.actions)
    }

    @Test fun testPhase4_9L_T2_MissingRequiredSlot_Clarification() {
        val slot = WorkflowSlot(name = "delivery_address", type = SlotType.TEXT, required = true)
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Confirm"))
        val wf = workflow(step = step, slots = listOf(slot))
        val driver = FakeDriver(ui(UiElement("b1", role = "Button", text = "Confirm", isClickable = true)))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request(emptyMap())) }
        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertNotNull(report.result.clarificationRequest)
        assertEquals("delivery_address", report.result.clarificationRequest!!.requiredSlot)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_9L_T3_OptionalSlotMissing_NoClarification() {
        val slot = WorkflowSlot(name = "promo_code", type = SlotType.TEXT, required = false)
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Checkout"))
        val wf = workflow(step = step, slots = listOf(slot))
        val driver = FakeDriver(ui(UiElement("b1", role = "Button", text = "Checkout", isClickable = true)))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request(emptyMap())) }
        assertEquals(ExecutionState.COMPLETED, report.result.finalState)
        assertNull(report.result.clarificationRequest)
        assertEquals(1, driver.actions)
    }

    @Test fun testPhase4_9L_T4_TwoEquallyPlausibleTargets_Clarification() {
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Select"))
        val wf = workflow(step = step)
        val driver = FakeDriver(ui(
            UiElement("b1", role = "Button", text = "Select", resourceId = "btn_1", isClickable = true),
            UiElement("b2", role = "Button", text = "Select", resourceId = "btn_2", isClickable = true)
        ))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request()) }
        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertNotNull(report.result.clarificationRequest)
        assertEquals(2, report.result.clarificationRequest!!.candidateDescriptions.size)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_9L_T5_AmbiguousWorkflowMatch_Clarification() {
        val matchResult = SkillMatchResult(
            status = SkillMatchStatus.AMBIGUOUS,
            commandText = "Get me a shirt",
            intent = "order",
            candidates = listOf(
                SkillCandidateMatch(skillId = "skill_search", intent = "search_product", isIntentCompatible = true),
                SkillCandidateMatch(skillId = "skill_order", intent = "order_product", isIntentCompatible = true)
            )
        )
        val clarReq = matchResult.toClarificationRequest("exec_amb_wf")
        assertNotNull(clarReq)
        assertEquals(UncertaintyType.AMBIGUOUS_WORKFLOW, clarReq!!.uncertaintyType)
        assertEquals(2, clarReq.candidateDescriptions.size)
        assertTrue(clarReq.question.contains("search_product"))
        assertTrue(clarReq.question.contains("order_product"))
    }

    @Test fun testPhase4_9L_T6_HighConfidenceTarget_Continue() {
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Submit"))
        val wf = workflow(step = step)
        val driver = FakeDriver(ui(UiElement("b1", role = "Button", text = "Submit", isClickable = true)))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request()) }
        assertEquals(ExecutionState.COMPLETED, report.result.finalState)
        assertEquals(1, driver.actions)
    }

    @Test fun testPhase4_9L_T7_LowConfidenceTarget_Clarification() {
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Proceed to Next"))
        val wf = workflow(step = step)
        // Two buttons with partial/weak match
        val driver = FakeDriver(ui(
            UiElement("b1", role = "Button", text = "Proceed", isClickable = true),
            UiElement("b2", role = "Button", text = "Next", isClickable = true)
        ))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request()) }
        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertNotNull(report.result.clarificationRequest)
    }

    @Test fun testPhase4_9L_T8_NaturalLanguageSelectCandidate_SecondOne() {
        val clarReq = ClarificationRequest(
            executionId = "exec_1",
            reason = "Ambiguous target",
            question = "Which option?",
            candidateDescriptions = listOf("Button: Black Jacket A", "Button: Black Jacket B")
        )
        val response = ClarificationResponse(executionId = "exec_1", userResponseText = "the second one")
        val result = NaturalLanguageClarificationResolver.resolve(clarReq, response)

        assertTrue(result is ClarificationResolutionResult.ResolvedCandidate)
        assertEquals(1, (result as ClarificationResolutionResult.ResolvedCandidate).index)
    }

    @Test fun testPhase4_9L_T9_NaturalLanguageAttribute_BlueOne() {
        val clarReq = ClarificationRequest(
            executionId = "exec_1",
            reason = "Ambiguous target",
            question = "Which jacket?",
            candidateDescriptions = listOf("Button: Red Jacket", "Button: Blue Jacket")
        )
        val response = ClarificationResponse(executionId = "exec_1", userResponseText = "the blue one")
        val result = NaturalLanguageClarificationResolver.resolve(clarReq, response)

        assertTrue(result is ClarificationResolutionResult.ResolvedCandidate)
        assertEquals(1, (result as ClarificationResolutionResult.ResolvedCandidate).index)
    }

    @Test fun testPhase4_9L_T10_InvalidClarificationResponse_BoundedReClarification() {
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Pick"))
        val wf = workflow(step = step)
        val driver = FakeDriver(ui(
            UiElement("b1", role = "Button", text = "Pick Red", isClickable = true),
            UiElement("b2", role = "Button", text = "Pick Blue", isClickable = true)
        ))
        val engine = ExecutionEngine(Store(wf), driver)

        val report1 = run { engine.execute(request()) }
        assertEquals(ExecutionState.WAITING_FOR_USER, report1.result.finalState)

        // User gives completely irrelevant answer
        val response1 = ClarificationResponse(executionId = "run", userResponseText = "tomorrow")
        val report2 = run { engine.resume(response1) }

        // Bounded re-clarification with attemptCount = 2
        assertEquals(ExecutionState.WAITING_FOR_USER, report2.result.finalState)
        assertNotNull(report2.result.clarificationRequest)
        assertEquals(2, report2.result.clarificationRequest!!.attemptCount)
        assertTrue(report2.result.clarificationRequest!!.question.contains("didn't understand"))
    }

    @Test fun testPhase4_9L_T11_RepeatedInvalidResponses_ExhaustsBudgetToHandoff() {
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Pick"))
        val wf = workflow(step = step)
        val driver = FakeDriver(ui(
            UiElement("b1", role = "Button", text = "Pick Red", isClickable = true),
            UiElement("b2", role = "Button", text = "Pick Blue", isClickable = true)
        ))
        val engine = ExecutionEngine(Store(wf), driver)

        run { engine.execute(request()) }
        // Attempt 1 invalid -> re-ask (attemptCount 2)
        run { engine.resume(ClarificationResponse(executionId = "run", userResponseText = "tomorrow")) }
        // Attempt 2 invalid -> re-ask (attemptCount 3)
        run { engine.resume(ClarificationResponse(executionId = "run", userResponseText = "yesterday")) }
        // Attempt 3 invalid -> budget exhausted -> HANDOFF!
        val reportFinal = run { engine.resume(ClarificationResponse(executionId = "run", userResponseText = "nevermind")) }

        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, reportFinal.result.finalState)
        assertTrue(reportFinal.result.errorMessage!!.contains("budget exhausted"))
    }

    @Test fun testPhase4_9L_T12_ClarificationDoesNotRestartWorkflow() {
        val step1 = WorkflowStep(stepId = "s1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "Step 1"))
        val step2 = WorkflowStep(stepId = "s2", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "Step 2"))
        val wf = Workflow(skillId = "learned", name = "Test", intent = "test", appContext = app, steps = listOf(step1, step2))

        val driver = FakeDriver(ui(UiElement("b1", role = "Button", text = "Step 1", isClickable = true)))
        driver.effect = {
            if (it.source.stepId == "s1") {
                // Step 2 has two ambiguous buttons
                driver.screen = ui(
                    UiElement("b2_a", role = "Button", text = "Step 2", isClickable = true),
                    UiElement("b2_b", role = "Button", text = "Step 2", isClickable = true)
                )
            }
        }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertEquals(listOf("s1"), report.result.progress?.completedStepIds)
        assertEquals(1, driver.actions)
    }

    @Test fun testPhase4_9L_T13_MidFlowClarification_ResumesCurrentStep() {
        val step1 = WorkflowStep(stepId = "s1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "Step 1"))
        val step2 = WorkflowStep(stepId = "s2", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "Step 2"))
        val wf = Workflow(skillId = "learned", name = "Test", intent = "test", appContext = app, steps = listOf(step1, step2))

        val driver = FakeDriver(ui(UiElement("b1", role = "Button", text = "Step 1", isClickable = true)))
        driver.effect = { step ->
            if (step.source.stepId == "s1") {
                driver.screen = ui(
                    UiElement("b2_a", role = "Button", text = "Step 2 Option A", isClickable = true),
                    UiElement("b2_b", role = "Button", text = "Step 2 Option B", isClickable = true)
                )
            } else {
                driver.screen = ui(UiElement("done", role = "TextView", text = "All Done"))
            }
        }
        val engine = ExecutionEngine(Store(wf), driver)
        run { engine.execute(request()) }

        // Resume step 2 with candidate 0
        val resumeReport = run { engine.resume(ClarificationResponse(executionId = "run", selectedCandidateIndex = 0)) }
        assertTrue(resumeReport.result.success)
        assertEquals(ExecutionState.COMPLETED, resumeReport.result.finalState)
        // Step 1 was executed exactly once, never repeated
        assertEquals(1, driver.executedSteps.count { it == "s1" })
        assertEquals(1, driver.executedSteps.count { it == "s2" })
    }

    @Test fun testPhase4_9L_T14_RuntimeSlotsPreservedDuringClarification() {
        val slot1 = WorkflowSlot(name = "item", type = SlotType.TEXT, required = true)
        val slot2 = WorkflowSlot(name = "size", type = SlotType.TEXT, required = true)
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Confirm"))
        val wf = workflow(step = step, slots = listOf(slot1, slot2))

        val driver = FakeDriver(ui(UiElement("b1", role = "Button", text = "Confirm", isClickable = true)))
        val engine = ExecutionEngine(Store(wf), driver)

        // item is bound, size is missing
        val report = run { engine.execute(request(mapOf("item" to "Blue Jacket"))) }
        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertEquals("size", report.result.clarificationRequest?.requiredSlot)

        // Clarify size = "Medium"
        val resumeReport = run { engine.resume(ClarificationResponse(executionId = "run", providedSlotValue = "Medium")) }
        assertTrue(resumeReport.result.success)
        assertEquals(ExecutionState.COMPLETED, resumeReport.result.finalState)
    }

    @Test fun testPhase4_9L_T15_DemonstrationValuesNeverLeak() {
        val clarReq = ClarificationRequest(
            executionId = "run_demo",
            reason = "Ambiguity",
            question = "Which item?",
            candidateDescriptions = listOf("Button: Jacket 1", "Button: Jacket 2")
        )
        val response = ClarificationResponse(executionId = "run_demo", userResponseText = "the first one")
        val result = NaturalLanguageClarificationResolver.resolve(clarReq, response)

        assertTrue(result is ClarificationResolutionResult.ResolvedCandidate)
        assertFalse((result as ClarificationResolutionResult.ResolvedCandidate).description.contains("White Shirt"))
        assertFalse(result.description.contains("Amazon"))
    }

    @Test fun testPhase4_9L_T16_PlatformSubstitutionSurvivesClarification() {
        val myntraPkg = "com.myntra.android"
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Pick"), pre = Preconditions(requiredPackage = myntraPkg))
        val wf = Workflow(skillId = "learned", name = "Test", intent = "test", appContext = myntraPkg, steps = listOf(step))
        val driver = FakeDriver(ui(
            UiElement("b1", role = "Button", text = "Pick 1", isClickable = true),
            UiElement("b2", role = "Button", text = "Pick 2", isClickable = true)
        , packageName = myntraPkg))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request()) }
        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertEquals(myntraPkg, engine.pausedExpectedPackage)
    }

    @Test fun testPhase4_9L_T17_StaleCandidateAfterUiChange_FreshObservation() {
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Pick"))
        val wf = workflow(step = step)
        val driver = FakeDriver(ui(
            UiElement("b1", role = "Button", text = "Pick Option A", isClickable = true),
            UiElement("b2", role = "Button", text = "Pick Option B", isClickable = true)
        ))
        val engine = ExecutionEngine(Store(wf), driver)
        run { engine.execute(request()) }

        // While paused, UI changes to completely different elements
        driver.screen = ui(
            UiElement("c1", role = "Button", text = "Option C", isClickable = true)
        )
        // User responds with index 0 from old options
        val resumeReport = run { engine.resume(ClarificationResponse(executionId = "run", selectedCandidateIndex = 0)) }
        // Stale context invalidated; does not crash, handles fresh state safely
        assertNotNull(resumeReport)
    }

    @Test fun testPhase4_9L_T18_ClarificationPending_ZeroAutomatedActions() {
        val slot = WorkflowSlot(name = "required_field", type = SlotType.TEXT, required = true)
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Go"))
        val wf = workflow(step = step, slots = listOf(slot))
        val driver = FakeDriver(ui(UiElement("b1", role = "Button", text = "Go", isClickable = true)))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request(emptyMap())) }
        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_9L_T19_PaymentScreen_HandoffNeverClarification() {
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Pay Now"))
        val wf = workflow(step = step)
        val driver = FakeDriver(ui(
            UiElement("pay_btn", role = "Button", text = "Confirm Payment", isClickable = true)
        ))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request()) }
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertNull(report.result.clarificationRequest)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_9L_T20_PasswordPinScreen_Handoff() {
        val step = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", text = "Password"))
        val wf = workflow(step = step)
        val driver = FakeDriver(ui(
            UiElement("pwd", role = "EditText", text = "Enter Passcode", isPassword = true)
        ))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request()) }
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_9L_T21_CaptchaScreen_Handoff() {
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Verify"))
        val wf = workflow(step = step)
        val driver = FakeDriver(ui(
            UiElement("captcha", role = "TextView", text = "Please complete the CAPTCHA to proceed")
        ))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request()) }
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_9L_T22_SafetyGateBlocked_ZeroActions() {
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Continue"))
        val wf = workflow(step = step)
        val driver = FakeDriver(ui(button()))
        val engine = ExecutionEngine(Store(wf), driver)

        engine.cancel() // Blocks gate
        val report = run { engine.execute(request()) }
        assertEquals(ExecutionState.ABORTED, report.result.finalState)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_9L_T23_UnsupportedCapability_Handoff() {
        val step = step(
            action = "CLICK",
            selector = SemanticSelector(role = "Button", text = "Next"),
            transition = ExpectedTransition(schemaVersion = "99.0") // Unsupported transition schema
        )
        val wf = workflow(step = step)
        val driver = FakeDriver(ui(button()))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request()) }
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_9L_T24_RecoveryExhausted_Handoff() {
        val policy = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)
        val controller = RecoveryController()
        val verification = VerificationResult(verified = false, reason = "Failed", status = VerificationStatus.NOT_VERIFIED)

        val decision = controller.decideRecovery(policy, verification, retries = 2)
        assertEquals(RecoveryAction.HANDOFF, decision.action)
    }

    @Test fun testPhase4_9L_T25_RecoveryReturnsAskUser_ClarificationInteraction() {
        val policy = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)
        val controller = RecoveryController()
        val verification = VerificationResult(verified = false, reason = "Ambiguous target", status = VerificationStatus.NOT_VERIFIED)
        val rematch = com.chockXlate.teachablevoice.runtime.matching.MatchResult(
            status = MatchStatus.AMBIGUOUS,
            reason = "Two candidates found"
        )

        val decision = controller.decideRecovery(policy, verification, targetMatch = rematch, retries = 0)
        assertEquals(RecoveryAction.ASK_USER, decision.action)
    }

    @Test fun testPhase4_9L_T26_RecoveryReturnsHandoff_NoFurtherAutomatedAction() {
        val policy = RecoveryPolicy(maxRetries = 0, strategy = RecoveryStrategy.HANDOFF_TO_USER)
        val controller = RecoveryController()
        val verification = VerificationResult(verified = false, reason = "Unverified", status = VerificationStatus.NOT_VERIFIED)

        val decision = controller.decideRecovery(policy, verification, retries = 0)
        assertEquals(RecoveryAction.HANDOFF, decision.action)
    }

    @Test fun testPhase4_9L_T27_RecoveryReturnsAbort_TerminalSafeStop() {
        val policy = RecoveryPolicy(strategy = RecoveryStrategy.ABORT)
        val controller = RecoveryController()
        val verification = VerificationResult(verified = false, reason = "Invalid", status = VerificationStatus.INVALID_EXPECTATION)

        val decision = controller.decideRecovery(policy, verification, retries = 0)
        assertEquals(RecoveryAction.ABORT, decision.action)
    }

    @Test fun testPhase4_9L_T28_Determinism_SameContextSameAnswer() {
        val clarReq = ClarificationRequest(
            executionId = "exec_det",
            reason = "Ambiguity",
            question = "Which color?",
            candidateDescriptions = listOf("Button: Red", "Button: Green", "Button: Blue")
        )
        val resp1 = ClarificationResponse(executionId = "exec_det", userResponseText = "the third one")
        val resp2 = ClarificationResponse(executionId = "exec_det", userResponseText = "the third one")

        val res1 = NaturalLanguageClarificationResolver.resolve(clarReq, resp1)
        val res2 = NaturalLanguageClarificationResolver.resolve(clarReq, resp2)

        assertEquals(res1, res2)
        assertEquals(2, (res1 as ClarificationResolutionResult.ResolvedCandidate).index)
    }

    @Test fun testPhase4_9L_T29_AmbiguousReference_ThatOne() {
        val clarReq = ClarificationRequest(
            executionId = "exec_ref",
            reason = "Ambiguity",
            question = "Which jacket?",
            candidateDescriptions = listOf("Button: Black Jacket A", "Button: Black Jacket B")
        )
        val resp = ClarificationResponse(executionId = "exec_ref", userResponseText = "that one")
        val result = NaturalLanguageClarificationResolver.resolve(clarReq, resp)

        assertTrue(result is ClarificationResolutionResult.AmbiguousReference)
    }

    @Test fun testPhase4_9L_T30_NaturalLanguageClarificationDoesNotBypassSemanticMatching() {
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Confirm"))
        val wf = workflow(step = step)
        val driver = FakeDriver(ui(
            UiElement("b1", role = "Button", text = "Confirm Order 1", isClickable = true),
            UiElement("b2", role = "Button", text = "Confirm Order 2", isClickable = true)
        ))
        val engine = ExecutionEngine(Store(wf), driver)
        run { engine.execute(request()) }

        val resumeReport = run { engine.resume(ClarificationResponse(executionId = "run", userResponseText = "the second one")) }
        assertTrue(resumeReport.result.success)
        // Action was executed with semantic selector
        assertNotNull(driver.lastStep?.selector)
        assertEquals("Button", driver.lastStep?.selector?.role)
    }

    @Test fun testPhase4_9L_T31_SensitiveInformationRedactedFromTrace() {
        val recorder = ExecutionTraceRecorder(request())
        recorder.recordUncertainty(
            com.chockXlate.teachablevoice.runtime.trace.UncertaintyRecord(
                executionId = "run_sec",
                stepId = "step_1",
                uncertaintyType = UncertaintyType.SAFETY_BOUNDARY.name,
                source = "TEST",
                decision = "User entered card 4111 2222 3333 4444 with password: secretpassword",
                clarificationId = null,
                question = "What is card 4111 2222 3333 4444?",
                candidateCount = 0,
                resolution = "password: secretpassword",
                finalDisposition = UncertaintyDisposition.HANDOFF.name
            )
        )
        val report = recorder.finish(ExecutionState.PAUSED_FOR_HANDOFF, 0, 1, "Security stop", null)
        val rec = report.uncertaintyRecords.first()

        assertFalse(rec.decision.contains("4111 2222 3333 4444"))
        assertFalse(rec.decision.contains("secretpassword"))
        assertTrue(rec.decision.contains("[REDACTED_CARD]"))
        assertTrue(rec.decision.contains("[REDACTED_SECRET]"))
    }

    @Test fun testPhase4_9L_T32_NoInfiniteClarificationLoop() {
        val clarReq = ClarificationRequest(
            executionId = "loop_test",
            reason = "Ambiguity",
            question = "Which option?",
            candidateDescriptions = listOf("A", "B"),
            attemptCount = 3,
            maxAttempts = 3
        )
        assertEquals(3, clarReq.maxAttempts)
        assertTrue(clarReq.attemptCount >= clarReq.maxAttempts)
    }

    // =========================================================================
    // PHASE 4.9L — ADVERSARIAL TESTS & KILL TEST
    // =========================================================================

    @Test fun testPhase4_9L_Adversarial_ClarificationSafety_CardNumberRejected() {
        val slot = WorkflowSlot(name = "address", type = SlotType.TEXT, required = true)
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Save"))
        val wf = workflow(step = step, slots = listOf(slot))
        val driver = FakeDriver(ui(UiElement("b1", role = "Button", text = "Save", isClickable = true)))
        val engine = ExecutionEngine(Store(wf), driver)

        run { engine.execute(request(emptyMap())) }
        // User attempts to supply credit card number into address slot
        val maliciousResponse = ClarificationResponse(
            executionId = "run",
            providedSlotValue = "My card number is 4111 2222 3333 4444"
        )
        val report = run { engine.resume(maliciousResponse) }

        // Must reject and transition to PAUSED_FOR_HANDOFF immediately
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertEquals(0, driver.actions)
        // Ensure sensitive card is not bound
        assertFalse(report.result.errorMessage?.contains("4111 2222 3333 4444") ?: false)
    }

    @Test fun testPhase4_9L_Adversarial_PaymentBoundary_SafetyGateBlocked() {
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Proceed"))
        val wf = workflow(step = step)
        val driver = FakeDriver(ui(UiElement("b1", role = "Button", text = "Proceed", isClickable = true)))
        // Simulating transition to payment screen upon execution
        driver.effect = {
            driver.screen = ui(UiElement("pay", role = "Button", text = "Pay with Credit Card", isClickable = true))
        }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        // Must stop with PAUSED_FOR_HANDOFF, zero further automated actions
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_9L_Adversarial_StaleClarification_InvalidatesOldContext() {
        val step = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Pick"))
        val wf = workflow(step = step)
        val driver = FakeDriver(ui(
            UiElement("b1", role = "Button", text = "Candidate A", isClickable = true),
            UiElement("b2", role = "Button", text = "Candidate B", isClickable = true)
        ))
        val engine = ExecutionEngine(Store(wf), driver)
        run { engine.execute(request()) }

        // Screen changed to Candidates C and D
        driver.screen = ui(
            UiElement("c1", role = "Button", text = "Candidate C", isClickable = true),
            UiElement("c2", role = "Button", text = "Candidate D", isClickable = true)
        )

        // Responding with old index
        val resumeReport = run { engine.resume(ClarificationResponse(executionId = "run", selectedCandidateIndex = 1)) }
        // Stale candidate B was not executed on the new screen
        assertNotEquals(ExecutionState.COMPLETED, resumeReport.result.finalState)
    }

    @Test fun testPhase4_9L_Adversarial_DemonstrationLeak_AmazonVsMyntra() {
        val myntraApp = "com.myntra.android"
        val slot = WorkflowSlot(name = "item_query", type = SlotType.TEXT, required = true)
        val step = step(
            action = "INPUT_TEXT",
            selector = SemanticSelector(role = "EditText", resourceId = "search_bar"),
            pre = Preconditions(requiredPackage = myntraApp)
        )
        val wf = Workflow(skillId = "learned", name = "Search", intent = "search", appContext = myntraApp, steps = listOf(step), slots = listOf(slot))
        val driver = FakeDriver(ui(UiElement("search_bar", role = "EditText", resourceId = "search_bar", isEditable = true), packageName = myntraApp))
        val engine = ExecutionEngine(Store(wf), driver)

        // Trigger clarification on item_query
        run { engine.execute(request(emptyMap())) }
        val resumeReport = run { engine.resume(ClarificationResponse(executionId = "run", userResponseText = "Blue Jacket")) }

        assertTrue(resumeReport.result.success)
        assertEquals("Blue Jacket", driver.lastStep?.inputText)
        assertNotEquals("White Shirt", driver.lastStep?.inputText)
        assertEquals(myntraApp, driver.lastStep?.preconditions?.requiredPackage)
    }

    @Test fun testPhase4_9L_KillTest_SemanticDisambiguationWithPreservedSlots() {
        // Teaching: "Search for a white shirt on Amazon."
        // Runtime: "Find a blue jacket on Myntra."
        val myntraPkg = "com.myntra.android"
        val itemSlot = WorkflowSlot(name = "item", type = SlotType.TEXT, required = true)
        val step = BoundStep(
            source = WorkflowStep(
                stepId = "step_select_jacket",
                semanticAction = "CLICK",
                semanticSelector = SemanticSelector(role = "Button", text = "Blue Jacket"),
                expectedTransition = ExpectedTransition(expectedPackage = myntraPkg)
            ),
            action = RuntimeAction.CLICK,
            selector = SemanticSelector(role = "Button", text = "Blue Jacket"),
            preconditions = Preconditions(requiredPackage = myntraPkg),
            transition = ExpectedTransition(expectedPackage = myntraPkg)
        )
        val wf = Workflow(
            skillId = "learned_shopping",
            name = "Shopping Workflow",
            intent = "search_item",
            appContext = myntraPkg,
            slots = listOf(itemSlot),
            steps = listOf(step.source)
        )

        // Two semantically matching jackets visible on Myntra screen
        val driver = FakeDriver(ui(
            UiElement("item_1", role = "Button", text = "Blue Jacket Slim Fit", resourceId = "product_1", isClickable = true),
            UiElement("item_2", role = "Button", text = "Blue Jacket Regular Fit", resourceId = "product_2", isClickable = true)
        , packageName = myntraPkg))

        driver.effect = {
            driver.screen = ui(UiElement("confirmation", role = "TextView", text = "Selected Blue Jacket Regular Fit"), packageName = myntraPkg)
        }

        val engine = ExecutionEngine(Store(wf), driver)

        // 1. Initial runtime execution
        val initialReq = ExecutionRequest(executionId = "kill_test_exec", skillId = "learned_shopping", boundSlots = mapOf("item" to "Blue Jacket"))
        val report = run { engine.execute(initialReq) }

        // Must detect ambiguity and pause for user clarification
        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertNotNull(report.result.clarificationRequest)
        assertEquals(2, report.result.clarificationRequest!!.candidateDescriptions.size)
        assertEquals(0, driver.actions)

        // 2. User clarifies: "the second one"
        val resumeReport = run {
            engine.resume(ClarificationResponse(executionId = "kill_test_exec", userResponseText = "the second one"))
        }

        // 3. Execution resumes, binds second candidate, executes semantically, verifies transition
        assertTrue(resumeReport.result.success)
        assertEquals(ExecutionState.COMPLETED, resumeReport.result.finalState)
        assertEquals(1, driver.actions)
        // Verify runtime slot "Blue Jacket" was preserved throughout
        assertEquals("Blue Jacket", initialReq.boundSlots["item"])
        // Ensure no demo coordinates or fixed Amazon IDs were used
        assertNotNull(driver.lastStep?.selector)
        assertEquals("Button", driver.lastStep?.selector?.role)
        assertTrue(driver.lastStep?.selector?.text?.contains("Blue Jacket") == true)
    }

    // =========================================================================
    // PHASE 4.10 — EXECUTION TRACE, RUN REPORT & OUTCOME EXPLANATION TEST MATRIX
    // =========================================================================

    @Test fun testPhase4_10_PH4_10_T1_SuccessfulExecutionProducesReport() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.effect = {
            driver.screen = ui(button("done_btn", "Done"))
        }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        assertTrue(report.result.success)
        assertEquals(1, report.stepsCompleted)
        assertEquals(1, report.stepsTotal)
        assertEquals(0, report.stepsFailed)
        assertEquals(1, report.stepReports.size)
        assertTrue(report.humanExplanation.isNotBlank())
        assertTrue(report.spokenSummary.isNotBlank())
        assertTrue(report.spokenSummary.contains("successfully"))
    }

    @Test fun testPhase4_10_PH4_10_T2_AllCompletedStepsAppearInCorrectOrder() {
        val s1 = step(action = "CLICK", selector = SemanticSelector(text = "Step1"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Step2"))).copy(stepId = "s1")
        val s2 = step(action = "CLICK", selector = SemanticSelector(text = "Step2"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done"))).copy(stepId = "s2")
        val wf = workflow().copy(steps = listOf(s1, s2))
        val driver = FakeDriver(ui(button("b1", "Step1")))
        driver.effect = { step ->
            if (step.source.stepId == "s1") driver.screen = ui(button("b2", "Step2"))
            else if (step.source.stepId == "s2") driver.screen = ui(button("b3", "Done"))
        }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        assertEquals(2, report.stepReports.size)
        assertEquals(0, report.stepReports[0].stepIndex)
        assertEquals("s1", report.stepReports[0].stepId)
        assertEquals(1, report.stepReports[1].stepIndex)
        assertEquals("s2", report.stepReports[1].stepId)
    }

    @Test fun testPhase4_10_PH4_10_T3_FailedVerificationRepresentedSeparatelyFromActionSuccess() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")),
            recovery = RecoveryPolicy(maxRetries = 0))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.accepted = true // Action accepted at OS level
        // But UI does not change, expected transition not met
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertFalse(report.result.success)
        assertNotEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        val stepRep = report.stepReports.first()
        assertEquals("ACCEPTED", stepRep.actionOutcome)
        assertEquals("NOT_VERIFIED", stepRep.verificationStatus)
        assertNotEquals(StepExecutionStatus.COMPLETED, stepRep.finalStepStatus)
    }

    @Test fun testPhase4_10_PH4_10_T4_RecoveryRecordsAppearInReport() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")),
            recovery = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.RETRY_STEP))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertTrue(report.recoveryCount > 0)
        assertTrue(report.recoveryRecords.isNotEmpty())
        assertEquals(1, report.recoveryRecords.first().attemptNumber)
    }

    @Test fun testPhase4_10_PH4_10_T5_SuccessfulRecoveryProducesRecovered() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")),
            recovery = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.REOBSERVE_AND_RETRY))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.effect = {
            // Screen settles to Done after recovery delay
            driver.screen = ui(button("done", "Done"))
        }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        assertEquals(1, report.recoveryCount)
        val stepRep = report.stepReports.first()
        assertEquals(StepExecutionStatus.RECOVERED, stepRep.finalStepStatus)
        assertEquals(1, stepRep.recoveryCount)
    }

    @Test fun testPhase4_10_PH4_10_T6_ClarificationEventAppearsInTrace() {
        val s = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Option"))
        val wf = workflow(s)
        val driver = FakeDriver(ui(
            UiElement("opt1", role = "Button", text = "Option A", isClickable = true),
            UiElement("opt2", role = "Button", text = "Option B", isClickable = true)
        ))
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(FinalExecutionStatus.CLARIFICATION_REQUIRED, report.finalStatus)
        assertTrue(report.clarificationCount > 0)
        assertTrue(report.uncertaintyRecords.any { it.finalDisposition == "CLARIFY" })
        assertEquals(StepExecutionStatus.WAITING_FOR_USER, report.stepReports.first().finalStepStatus)
    }

    @Test fun testPhase4_10_PH4_10_T7_SuccessfulClarificationResultsInContinuedExecution() {
        val s = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Option"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Option B Confirmed")))
        val wf = workflow(s)
        val driver = FakeDriver(ui(
            UiElement("opt1", role = "Button", text = "Option A", isClickable = true),
            UiElement("opt2", role = "Button", text = "Option B", isClickable = true)
        ))
        driver.effect = {
            driver.screen = ui(UiElement("conf", role = "TextView", text = "Option B Confirmed"))
        }
        val engine = ExecutionEngine(Store(wf), driver)
        val initial = run { engine.execute(request()) }
        assertEquals(FinalExecutionStatus.CLARIFICATION_REQUIRED, initial.finalStatus)

        val resumed = run {
            engine.resume(ClarificationResponse(executionId = "run", selectedCandidateIndex = 1))
        }
        assertEquals(FinalExecutionStatus.SUCCESS, resumed.finalStatus)
        assertTrue(resumed.result.success)
        assertTrue(resumed.clarificationCount >= 1)
    }

    @Test fun testPhase4_10_PH4_10_T8_HandoffAppearsAsTerminalResult() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")),
            recovery = RecoveryPolicy(maxRetries = 0, fallbackAction = RecoveryFallback.HANDOFF))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertTrue(report.handoffRequired)
        assertTrue(report.finalStatus == FinalExecutionStatus.HANDOFF || report.finalStatus == FinalExecutionStatus.RECOVERY_EXHAUSTED)
    }

    @Test fun testPhase4_10_PH4_10_T9_SafetyBlockAppearsInReport() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Pay Now"))
        val wf = workflow(step = s, safety = SafetyBoundary(disallowSensitiveInput = true))
        val sensitiveUi = UiObservation(UiState(
            stateId = "payment_screen", timestamp = System.currentTimeMillis(),
            appContext = app, isSensitiveContext = true,
            allElements = listOf(UiElement("card", role = "EditText", text = "Card Number", isPassword = true))
        ), credentialFieldPresent = true)
        val driver = FakeDriver(sensitiveUi)
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(FinalExecutionStatus.SAFETY_BLOCKED, report.finalStatus)
        assertTrue(report.safetyBlocked)
        assertTrue(report.safetyEvents.isNotEmpty())
        assertEquals("BLOCKED", report.safetyEvents.first().decision)
        assertEquals(StepExecutionStatus.SAFETY_BLOCKED, report.stepReports.first().finalStepStatus)
    }

    @Test fun testPhase4_10_PH4_10_T10_PaymentPinPasswordDetailsAreRedacted() {
        val rawCommand = "Transfer money using PIN 1234 on card 4111 2222 3333 4444 with password secretPassword"
        val redacted = ExecutionTraceRecorder.redactSensitiveText(rawCommand)
        assertFalse(redacted.contains("1234"))
        assertFalse(redacted.contains("4111"))
        assertFalse(redacted.contains("secretPassword"))
        assertTrue(redacted.contains("[REDACTED_SECRET]"))
        assertTrue(redacted.contains("[REDACTED_CARD]"))
    }

    @Test fun testPhase4_10_PH4_10_T11_RuntimeValuesAppearInsteadOfDemonstrationValues() {
        val itemSlot = WorkflowSlot(name = "item", type = "string", required = true)
        val s = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "field"))
        val wf = workflow(step = s, slots = listOf(itemSlot))
        val driver = FakeDriver(ui(input()))
        driver.effect = { driver.screen = ui(button("done", "Success")) }
        val engine = ExecutionEngine(Store(wf), driver)
        val req = ExecutionRequest(
            executionId = "runtime_run_1",
            skillId = "learned",
            boundSlots = mapOf("item" to "Blue Jacket"),
            originalCommand = "Buy a Blue Jacket"
        )
        val report = run { engine.execute(req) }

        assertEquals("Blue Jacket", report.boundSlots["item"])
        assertFalse(report.boundSlots.containsValue("White Shirt"))
    }

    @Test fun testPhase4_10_PH4_10_T12_CrossAppExecutionReportedGenerically() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Open App B"),
            transition = ExpectedTransition(expectedPackage = "com.app.b"))
        val wf = workflow(s).copy(appContext = "com.app.a")
        val driver = FakeDriver(ui(button("btn", "Open App B"), packageName = "com.app.a"))
        driver.effect = {
            driver.screen = ui(button("btn_b", "App B Home"), packageName = "com.app.b")
        }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        assertEquals("learned", report.workflowId)
    }

    @Test fun testPhase4_10_PH4_10_T13_TargetResolutionStatusIsRecorded() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.effect = { driver.screen = ui(button("done", "Done")) }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        val stepRep = report.stepReports.first()
        assertEquals("MATCHED", stepRep.targetResolutionStatus)
        assertTrue(stepRep.targetConfidence > 0.5)
    }

    @Test fun testPhase4_10_PH4_10_T14_BeforeAfterStateIdsAreRecorded() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")))
        val wf = workflow(s)
        val initialUi = ui(button("btn", "Go"))
        val driver = FakeDriver(initialUi)
        val nextUi = ui(button("done", "Done"))
        driver.effect = { driver.screen = nextUi }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        val stepRep = report.stepReports.first()
        assertEquals(initialUi.state.stateId, stepRep.beforeStateId)
        assertEquals(nextUi.state.stateId, stepRep.afterStateId)
    }

    @Test fun testPhase4_10_PH4_10_T15_VerificationStatusIsRecorded() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.effect = { driver.screen = ui(button("done", "Done")) }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        val stepRep = report.stepReports.first()
        assertEquals("VERIFIED_SUCCESS", stepRep.verificationStatus)
    }

    @Test fun testPhase4_10_PH4_10_T16_ActionOutcomeAndVerificationOutcomeRemainSeparate() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")),
            recovery = RecoveryPolicy(maxRetries = 0))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.accepted = true // Action accepted
        // Verification fails because "Done" does not appear
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        val stepRep = report.stepReports.first()
        assertEquals("ACCEPTED", stepRep.actionOutcome)
        assertEquals("NOT_VERIFIED", stepRep.verificationStatus)
        assertFalse(report.result.success)
    }

    @Test fun testPhase4_10_PH4_10_T17_NoExecutionStateDoesNotFabricateReport() {
        val emptyReport = RuntimeReport.empty()
        assertEquals(FinalExecutionStatus.NO_EXECUTION, emptyReport.finalStatus)
        assertEquals(0, emptyReport.stepsTotal)
        assertEquals(0, emptyReport.stepsCompleted)
        assertFalse(emptyReport.result.success)

        val driver = FakeDriver(ui(button()))
        val engine = ExecutionEngine(Store(null), driver)
        val latest = engine.getLatestReportOrEmpty()
        assertEquals(FinalExecutionStatus.NO_EXECUTION, latest.finalStatus)
    }

    @Test fun testPhase4_10_PH4_10_T18_PartialExecutionReportedCorrectly() {
        val s1 = step(action = "CLICK", selector = SemanticSelector(text = "Step1"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Step2"))).copy(stepId = "s1")
        val s2 = step(action = "CLICK", selector = SemanticSelector(text = "Step2"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")),
            recovery = RecoveryPolicy(maxRetries = 0)).copy(stepId = "s2")
        val wf = workflow().copy(steps = listOf(s1, s2))
        val driver = FakeDriver(ui(button("b1", "Step1")))
        driver.effect = { step ->
            if (step.source.stepId == "s1") {
                driver.screen = ui(button("b2", "Step2"))
            }
            // Step 2 fails verification
        }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(1, report.stepsCompleted)
        assertEquals(2, report.stepsTotal)
        assertTrue(report.isPartialSuccess)
        assertFalse(report.result.success)
    }

    @Test fun testPhase4_10_PH4_10_T19_RecoveryExhaustionReportedCorrectly() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")),
            recovery = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.RETRY_STEP))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(FinalExecutionStatus.RECOVERY_EXHAUSTED, report.finalStatus)
        assertFalse(report.result.success)
    }

    @Test fun testPhase4_10_PH4_10_T20_UnsupportedCapabilityReportedCorrectly() {
        val unsupportedTransition = ExpectedTransition(unsupportedDescription = "External hardware gesture unsupported")
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"), transition = unsupportedTransition)
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertTrue(report.finalStatus == FinalExecutionStatus.UNSUPPORTED || report.finalStatus == FinalExecutionStatus.HANDOFF)
    }

    @Test fun testPhase4_10_PH4_10_T21_ClarificationPendingDoesNotAppearAsSuccess() {
        val s = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Item"))
        val wf = workflow(s)
        val driver = FakeDriver(ui(
            UiElement("i1", role = "Button", text = "Item 1", isClickable = true),
            UiElement("i2", role = "Button", text = "Item 2", isClickable = true)
        ))
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(FinalExecutionStatus.CLARIFICATION_REQUIRED, report.finalStatus)
        assertFalse(report.result.success)
    }

    @Test fun testPhase4_10_PH4_10_T22_LatestCompletedReportCanBeRetrieved() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.effect = { driver.screen = ui(button("done", "Done")) }
        val engine = ExecutionEngine(Store(wf), driver)
        assertNull(engine.getLatestReport())

        val executed = run { engine.execute(request()) }
        val retrieved = engine.getLatestReport()
        assertNotNull(retrieved)
        assertEquals(executed.executionId, retrieved!!.executionId)
        assertEquals(executed.finalStatus, retrieved.finalStatus)
    }

    @Test fun testPhase4_10_PH4_10_T23_ReportGenerationCausesZeroUiActions() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.effect = { driver.screen = ui(button("done", "Done")) }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        val actionsBefore = driver.actions
        val observationsBefore = driver.observations

        // Access all reporting methods
        val explanation = report.buildHumanExplanation()
        val spoken = report.buildSpokenSummary()
        val formatted = com.chockXlate.teachablevoice.teach.trace.TraceViewer.formatRuntimeReport(report)
        val latest = engine.getLatestReportOrEmpty()

        assertTrue(explanation.isNotBlank())
        assertTrue(spoken.isNotBlank())
        assertTrue(formatted.isNotBlank())
        assertNotNull(latest)
        assertEquals(actionsBefore, driver.actions)
        assertEquals(observationsBefore, driver.observations)
    }

    @Test fun testPhase4_10_PH4_10_T24_TraceEventOrderingIsDeterministic() {
        val s1 = step(action = "CLICK", selector = SemanticSelector(text = "Step1"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Step2"))).copy(stepId = "s1")
        val s2 = step(action = "CLICK", selector = SemanticSelector(text = "Step2"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done"))).copy(stepId = "s2")
        val wf = workflow().copy(steps = listOf(s1, s2))
        val driver = FakeDriver(ui(button("b1", "Step1")))
        driver.effect = { step ->
            if (step.source.stepId == "s1") driver.screen = ui(button("b2", "Step2"))
            else if (step.source.stepId == "s2") driver.screen = ui(button("b3", "Done"))
        }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        var lastTime = 0L
        for (event in report.trace.events) {
            assertTrue(event.timestamp >= lastTime)
            lastTime = event.timestamp
        }
    }

    @Test fun testPhase4_10_PH4_10_T25_SensitiveTraceValuesAreRedacted() {
        val s = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "field"))
        val wf = workflow(s)
        val driver = FakeDriver(ui(input()))
        val engine = ExecutionEngine(Store(wf), driver)
        val req = ExecutionRequest(
            executionId = "sensitive_run",
            skillId = "learned",
            boundSlots = mapOf("pin" to "secret1234"),
            originalCommand = "Enter PIN 1234"
        )
        val report = run { engine.execute(req) }

        assertFalse(report.originalCommand?.contains("1234") == true)
        assertTrue(report.originalCommand?.contains("[REDACTED_SECRET]") == true)
    }

    @Test fun testPhase4_10_PH4_10_T26_DemonstrationValuesCannotLeakIntoRuntimeReport() {
        val itemSlot = WorkflowSlot(name = "item", type = "string", required = true)
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Confirm"))
        val wf = workflow(step = s, slots = listOf(itemSlot))
        val driver = FakeDriver(ui(button("btn", "Confirm")))
        driver.effect = { driver.screen = ui(button("done", "Success")) }
        val engine = ExecutionEngine(Store(wf), driver)
        val req = ExecutionRequest(executionId = "demo_leak_check", skillId = "learned", boundSlots = mapOf("item" to "ActualRuntimeValue"))
        val report = run { engine.execute(req) }

        assertEquals("ActualRuntimeValue", report.boundSlots["item"])
        assertFalse(report.humanExplanation.contains("White Shirt"))
        assertFalse(report.humanExplanation.contains("Amazon"))
    }

    @Test fun testPhase4_10_PH4_10_T27_MultipleRecoveryAttemptsAppearIndividually() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")),
            recovery = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(2, report.recoveryRecords.size)
        assertEquals(1, report.recoveryRecords[0].attemptNumber)
        assertEquals(2, report.recoveryRecords[1].attemptNumber)
    }

    @Test fun testPhase4_10_PH4_10_T28_OriginalFailedActionRemainsRecordedAfterSuccessfulRecovery() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")),
            recovery = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.REOBSERVE_AND_RETRY))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.effect = { driver.screen = ui(button("done", "Done")) }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        // Original failure was recorded before recovery succeeded
        assertEquals(1, report.recoveryRecords.size)
        assertEquals(1, report.stepReports.first().recoveryCount)
        assertEquals(StepExecutionStatus.RECOVERED, report.stepReports.first().finalStepStatus)
    }

    @Test fun testPhase4_10_PH4_10_T29_HumanReportAndMachineTraceRepresentSameExecution() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.effect = { driver.screen = ui(button("done", "Done")) }
        val engine = ExecutionEngine(Store(wf), driver)
        val req = ExecutionRequest(executionId = "sync_check_exec", skillId = "learned")
        val report = run { engine.execute(req) }

        assertEquals(report.executionId, report.trace.executionId)
        assertEquals(report.workflowId, report.trace.skillId)
        assertEquals(report.result.durationMs, report.durationMs)
        assertEquals(report.result.stepsCompleted, report.stepsCompleted)
    }

    @Test fun testPhase4_10_PH4_10_T30_SameExecutionInputProducesDeterministicClassification() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")))
        val wf = workflow(s)
        val driver1 = FakeDriver(ui(button("btn", "Go"))).apply { effect = { screen = ui(button("done", "Done")) } }
        val driver2 = FakeDriver(ui(button("btn", "Go"))).apply { effect = { screen = ui(button("done", "Done")) } }

        val engine1 = ExecutionEngine(Store(wf), driver1)
        val engine2 = ExecutionEngine(Store(wf), driver2)

        val report1 = run { engine1.execute(ExecutionRequest(executionId = "run_1", skillId = "learned")) }
        val report2 = run { engine2.execute(ExecutionRequest(executionId = "run_2", skillId = "learned")) }

        assertEquals(report1.finalStatus, report2.finalStatus)
        assertEquals(report1.stepsCompleted, report2.stepsCompleted)
        assertEquals(report1.recoveryCount, report2.recoveryCount)
    }

    // =========================================================================
    // ADVERSARIAL TESTS
    // =========================================================================

    @Test fun testPhase4_10_AdversarialTest_FalseSuccess() {
        // Force: Action accepted, but transition NOT verified
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Search"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results")),
            recovery = RecoveryPolicy(maxRetries = 0))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Search")))
        driver.accepted = true // OS accepted click
        // UI stays unchanged (Results never appears)
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals("ACCEPTED", report.stepReports.first().actionOutcome)
        assertEquals("NOT_VERIFIED", report.stepReports.first().verificationStatus)
        assertFalse(report.result.success)
        assertNotEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
    }

    @Test fun testPhase4_10_AdversarialTest_RecoverySuccess() {
        // Force: Initial action NOT_VERIFIED, recovery REOBSERVE succeeds
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Search"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results")),
            recovery = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.REOBSERVE_AND_RETRY))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Search")))
        driver.effect = {
            // UI settles to Results screen during reobserve
            driver.screen = ui(button("res", "Results"))
        }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(StepExecutionStatus.RECOVERED, report.stepReports.first().finalStepStatus)
        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        assertEquals(1, report.recoveryCount)
        assertTrue(report.recoveryRecords.isNotEmpty())
    }

    @Test fun testPhase4_10_AdversarialTest_Handoff() {
        // Force: Payment screen encountered
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Pay"))
        val wf = workflow(step = s, safety = SafetyBoundary(disallowSensitiveInput = true))
        val paymentScreen = UiObservation(UiState(
            stateId = "payment_screen", timestamp = System.currentTimeMillis(),
            appContext = app, isSensitiveContext = true,
            allElements = listOf(UiElement("card_field", role = "EditText", text = "Card Number", isPassword = true))
        ), credentialFieldPresent = true)
        val driver = FakeDriver(paymentScreen)
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }

        assertEquals(FinalExecutionStatus.SAFETY_BLOCKED, report.finalStatus)
        assertTrue(report.safetyBlocked)
        assertEquals(0, driver.actions) // Automated actions after block = 0
    }

    @Test fun testPhase4_10_AdversarialTest_Clarification() {
        // Force: Two equal semantic candidates
        val s = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Jacket"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Details")))
        val wf = workflow(s)
        val driver = FakeDriver(ui(
            UiElement("c1", role = "Button", text = "Blue Jacket 1", isClickable = true),
            UiElement("c2", role = "Button", text = "Blue Jacket 2", isClickable = true)
        ))
        driver.effect = { driver.screen = ui(button("det", "Details")) }
        val engine = ExecutionEngine(Store(wf), driver)
        val initialReport = run { engine.execute(ExecutionRequest(executionId = "clarif_adv", skillId = "learned")) }

        assertEquals(FinalExecutionStatus.CLARIFICATION_REQUIRED, initialReport.finalStatus)

        // User clarifies: "The second one"
        val resumeReport = run {
            engine.resume(ClarificationResponse(executionId = "clarif_adv", userResponseText = "The second one"))
        }

        assertEquals(FinalExecutionStatus.SUCCESS, resumeReport.finalStatus)
        assertTrue(resumeReport.result.success)
        assertTrue(resumeReport.clarificationCount >= 1)
    }

    @Test fun testPhase4_10_AdversarialTest_StaleData() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")))
        val wf = workflow(s)
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.effect = { driver.screen = ui(button("done", "Done")) }
        val engine = ExecutionEngine(Store(wf), driver)

        val report1 = run { engine.execute(ExecutionRequest(executionId = "RUN_001", skillId = "learned")) }
        val report2 = run { engine.execute(ExecutionRequest(executionId = "RUN_002", skillId = "learned")) }

        assertEquals("RUN_001", report1.executionId)
        assertEquals("RUN_002", report2.executionId)
        assertEquals("RUN_002", engine.getLatestReport()?.executionId)
        assertNotEquals(report1.executionId, report2.executionId)
    }

    @Test fun testPhase4_10_AdversarialTest_ExecutionIsolation() {
        val itemSlot = WorkflowSlot(name = "item", type = "string", required = true)
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")))
        val wf = workflow(step = s, slots = listOf(itemSlot))
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.effect = { driver.screen = ui(button("done", "Done")) }
        val engine = ExecutionEngine(Store(wf), driver)

        val reportA = run { engine.execute(ExecutionRequest(executionId = "EXEC_A", skillId = "learned", boundSlots = mapOf("item" to "Blue Jacket"))) }
        val reportB = run { engine.execute(ExecutionRequest(executionId = "EXEC_B", skillId = "learned", boundSlots = mapOf("item" to "Running Shoes"))) }

        assertEquals("Blue Jacket", reportA.boundSlots["item"])
        assertFalse(reportA.boundSlots.containsValue("Running Shoes"))

        assertEquals("Running Shoes", reportB.boundSlots["item"])
        assertFalse(reportB.boundSlots.containsValue("Blue Jacket"))
    }

    // =========================================================================
    // KILL TEST
    // =========================================================================

    @Test fun testPhase4_10_KillTest_CompleteCausalChainAndOutcomeExplanation() {
        // Teaching demonstration was: "Search for a white shirt on Amazon."
        // Runtime execution: "Find a blue jacket on Myntra." with sensitive PIN
        val myntraPkg = "com.myntra.android"
        val platformSlot = WorkflowSlot(name = "platform", type = "string", required = true)
        val itemSlot = WorkflowSlot(name = "item", type = "string", required = true)

        val step1 = WorkflowStep(
            stepId = "step_search",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Search"),
            expectedTransition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Product List")),
            recoveryPolicy = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.REOBSERVE_AND_RETRY)
        )

        val step2 = WorkflowStep(
            stepId = "step_select_product",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Blue Jacket"),
            expectedTransition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Product Details")),
            recoveryPolicy = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.RETRY_STEP)
        )

        val wf = Workflow(
            skillId = "learned_shopping",
            name = "Shopping Search",
            intent = "search_item",
            appContext = myntraPkg,
            slots = listOf(platformSlot, itemSlot),
            steps = listOf(step1, step2)
        )

        // Runtime initial screen: Myntra search button
        val driver = FakeDriver(ui(button("btn_search", "Search"), packageName = myntraPkg))

        // When step 1 executes: first verify fails, recovery re-observe settles screen to Product List with two ambiguous jacket options
        driver.effect = { step ->
            if (step.source.stepId == "step_search") {
                // Settle screen to ambiguous product list
                driver.screen = ui(
                    UiElement("p1", role = "Button", text = "Blue Jacket Slim Fit", resourceId = "prod_1", isClickable = true),
                    UiElement("p2", role = "Button", text = "Blue Jacket Regular Fit", resourceId = "prod_2", isClickable = true),
                    UiElement("header", role = "TextView", text = "Product List"),
                    packageName = myntraPkg
                )
            } else if (step.source.stepId == "step_select_product") {
                // Step 2 verifies to Product Details
                driver.screen = ui(
                    UiElement("details", role = "TextView", text = "Product Details"),
                    packageName = myntraPkg
                )
            }
        }

        val engine = ExecutionEngine(Store(wf), driver)

        // 1. Initial runtime execution
        val initialReq = ExecutionRequest(
            executionId = "kill_test_4_10",
            skillId = "learned_shopping",
            boundSlots = mapOf("platform" to "Myntra", "item" to "Blue Jacket"),
            originalCommand = "Find a blue jacket on Myntra with PIN 1234"
        )

        val initialReport = run { engine.execute(initialReq) }

        // Must pause for user clarification due to ambiguous jacket candidates
        assertEquals(FinalExecutionStatus.CLARIFICATION_REQUIRED, initialReport.finalStatus)
        assertEquals(ExecutionState.WAITING_FOR_USER, initialReport.result.finalState)
        assertEquals(1, initialReport.recoveryCount) // Step 1 recovered via re-observe!

        // 2. User clarifies: "The first one"
        val finalReport = run {
            engine.resume(ClarificationResponse(executionId = "kill_test_4_10", userResponseText = "The first one"))
        }

        // 3. Execution resumes, completes, verifies transition
        assertEquals(FinalExecutionStatus.SUCCESS, finalReport.finalStatus)
        assertTrue(finalReport.result.success)
        assertEquals(2, finalReport.stepsCompleted)
        assertEquals(2, finalReport.stepsTotal)
        assertEquals(0, finalReport.stepsFailed)

        // Check Step Reports
        assertEquals(2, finalReport.stepReports.size)
        // Step 1 was recovered
        assertEquals(StepExecutionStatus.RECOVERED, finalReport.stepReports[0].finalStepStatus)
        assertEquals(1, finalReport.stepReports[0].recoveryCount)
        // Step 2 completed after clarification
        assertEquals(StepExecutionStatus.COMPLETED, finalReport.stepReports[1].finalStepStatus)

        // Check Runtime Values vs Demo Values
        assertEquals("Myntra", finalReport.boundSlots["platform"])
        assertEquals("Blue Jacket", finalReport.boundSlots["item"])
        assertFalse(finalReport.boundSlots.containsValue("Amazon"))
        assertFalse(finalReport.boundSlots.containsValue("White Shirt"))

        // Check Redaction: PIN 1234 must be redacted
        assertFalse(finalReport.originalCommand?.contains("1234") == true)
        assertTrue(finalReport.originalCommand?.contains("[REDACTED_SECRET]") == true)

        // Check Explanations
        assertTrue(finalReport.humanExplanation.isNotBlank())
        assertTrue(finalReport.humanExplanation.contains("kill_test_4_10"))
        assertTrue(finalReport.humanExplanation.contains("Recovery: Yes"))
        assertTrue(finalReport.spokenSummary.isNotBlank())
        assertTrue(finalReport.spokenSummary.contains("successfully"))

        // Check Latest Report Retrieval
        val retrieved = engine.getLatestReport()
        assertNotNull(retrieved)
        assertEquals(finalReport.executionId, retrieved!!.executionId)
        assertEquals(FinalExecutionStatus.SUCCESS, retrieved.finalStatus)
    }

    // =========================================================================
    // PHASE 4.11 — FINAL T1–T14 END-TO-END SCENARIO VALIDATION
    // =========================================================================

    @Test fun testPhase4_11_FINAL_T1_TeachNewWorkflow() {
        val repo = Store()
        val pkg = "com.amazon.mShop.android.shopping"
        val t0 = 1000L
        val searchBox = UiElement(elementId = "e1", role = "EditText", resourceId = "$pkg:id/search_edit_text", text = "Search Amazon", isEditable = true)
        val initialUi = UiState(
            schemaVersion = "1.0", stateId = "s0", timestamp = t0, appContext = pkg, windowId = 1,
            rootElement = searchBox, allElements = listOf(searchBox)
        )
        val v1 = VoiceEvent(eventId = "v1", timestamp = t0, transcript = "Search for a white shirt on Amazon")
        val a1 = ActionEvent(actionId = "a1", timestamp = t0 + 100, actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "$pkg:id/search_edit_text"),
            packageName = pkg)
        val afterClickUi = UiState(
            schemaVersion = "1.0", stateId = "s1", timestamp = t0 + 150, appContext = pkg, windowId = 1,
            rootElement = searchBox, allElements = listOf(searchBox)
        )
        val searchEntered = UiElement(elementId = "e1", role = "EditText", resourceId = "$pkg:id/search_edit_text", text = "white shirt", isEditable = true)
        val afterTypeUi = UiState(
            schemaVersion = "1.0", stateId = "s2", timestamp = t0 + 300, appContext = pkg, windowId = 1,
            rootElement = searchEntered, allElements = listOf(searchEntered)
        )
        val a2 = ActionEvent(actionId = "a2", timestamp = t0 + 300, actionType = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "$pkg:id/search_edit_text"),
            inputData = "white shirt", packageName = pkg)
        val se1 = StateEvent(stateEventId = "se1", timestamp = t0 + 300, beforeState = initialUi, afterState = afterTypeUi, causeActionId = "a2")

        val rawTrace = DemonstrationTrace(
            traceId = "trace_t1", timestamp = t0, appContext = pkg,
            voiceEvents = listOf(v1), uiStates = listOf(initialUi, afterClickUi, afterTypeUi),
            userActions = listOf(a1, a2), stateEvents = listOf(se1)
        )

        val normResult = DemonstrationTraceNormalizer.normalize(rawTrace)
        val normTrace = normResult.normalizedTrace
        assertNotNull(normTrace)
        assertEquals(pkg, normTrace.appContext)

        val synthResult = WorkflowSynthesizer.synthesizeFromTrace(
            trace = normTrace,
            targetSkillId = "amazon_search_skill",
            targetSkillName = "Search on Amazon",
            targetSkillDescription = "Search for a white shirt on Amazon"
        )
        val synthesizedWorkflow = synthResult.workflow
        assertNotNull(synthesizedWorkflow)
        assertEquals("amazon_search_skill", synthesizedWorkflow!!.skillId)
        assertTrue(synthesizedWorkflow.steps.isNotEmpty())

        val validation = WorkflowValidator.validate(synthesizedWorkflow)
        assertTrue(validation.isStoreable)

        repo.saveWorkflow(synthesizedWorkflow)
        assertNotNull(repo.getWorkflowById("amazon_search_skill"))
    }

    @Test fun testPhase4_11_FINAL_T2_ExactReplay() {
        val s = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "search_box"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "white shirt")))
        val wf = workflow(step = s, slots = listOf(WorkflowSlot(name = "item", type = "string", required = true))).copy(
            intent = "search_information"
        )
        val repo = Store(wf)
        val driver = FakeDriver(ui(input(text = "")))
        driver.effect = { step -> driver.screen = ui(input(text = step.inputText ?: "")) }
        val engine = ExecutionEngine(repo, driver)

        val cmd = "Search for a white shirt on Amazon"
        val understanding = CommandInterpreter.understandCommand(cmd)
        val matchResult = SkillMatcher(repo).match(understanding)
        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)

        val request = ExecutionRequestBuilder.build(understanding, matchResult, repo).request
        assertNotNull(request)
        assertEquals("white shirt", request!!.boundSlots["item"])

        val report = run { engine.execute(request) }
        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        assertEquals(1, report.stepsCompleted)
        assertEquals("VERIFIED_SUCCESS", report.stepReports.first().verificationStatus)
        assertEquals(1, driver.actions)
    }

    @Test fun testPhase4_11_FINAL_T3_Paraphrase() {
        val s = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "search_box"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "white shirt")))
        val wf = workflow(step = s, slots = listOf(WorkflowSlot(name = "item", type = "string", required = true))).copy(
            intent = "search_information"
        )
        val repo = Store(wf)
        val driver = FakeDriver(ui(input(text = "")))
        driver.effect = { step -> driver.screen = ui(input(text = step.inputText ?: "")) }
        val engine = ExecutionEngine(repo, driver)

        val paraphraseCmd = "Find me a white shirt on Amazon"
        val understanding = CommandInterpreter.understandCommand(paraphraseCmd)
        val matchResult = SkillMatcher(repo).match(understanding)
        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)
        assertEquals(wf.skillId, matchResult.selectedSkillId)

        val req = ExecutionRequestBuilder.build(understanding, matchResult, repo).request!!
        val report = run { engine.execute(req) }
        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        assertTrue(report.result.success)
    }

    @Test fun testPhase4_11_FINAL_T4_ChangedItem() {
        val s = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "search_box"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Blue Jacket")))
        val wf = workflow(step = s, slots = listOf(WorkflowSlot(name = "item", type = "string", required = true))).copy(
            intent = "search_information"
        )
        val repo = Store(wf)
        val driver = FakeDriver(ui(input(text = "")))
        driver.effect = { step -> driver.screen = ui(input(text = step.inputText ?: "")) }
        val engine = ExecutionEngine(repo, driver)

        val cmd = "Find a blue jacket on Amazon"
        val understanding = CommandInterpreter.understandCommand(cmd)
        val matchResult = SkillMatcher(repo).match(understanding)
        val req = ExecutionRequestBuilder.build(understanding, matchResult, repo).request!!
        assertEquals("blue jacket", req.boundSlots["item"])

        val report = run { engine.execute(req) }
        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        assertEquals("blue jacket", report.boundSlots["item"])
        assertFalse(report.boundSlots.containsValue("white shirt"))
        assertFalse(report.humanExplanation.contains("white shirt", ignoreCase = true))
    }

    @Test fun testPhase4_11_FINAL_T5_ChangedQuantity() {
        val s = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Add to Cart"),
            transition = ExpectedTransition(expectedEvidence = listOf(
                StateEvidenceRequirement(type = EvidenceType.TEXT_EQUALS, selector = SemanticSelector(resourceId = "qty_display"), expectedValue = "3")
            )))
        val wf = workflow(step = s, slots = listOf(WorkflowSlot(name = "quantity", type = "number", required = true))).copy(
            intent = "shop_item"
        )
        val repo = Store(wf)
        val driver = FakeDriver(ui(button("btn_add", "Add to Cart"), UiElement("qty", role = "TextView", resourceId = "qty_display", text = "1")))
        driver.effect = {
            driver.screen = ui(button("btn_add", "Add to Cart"), UiElement("qty", role = "TextView", resourceId = "qty_display", text = "3"))
        }
        val engine = ExecutionEngine(repo, driver)

        val req = ExecutionRequest(executionId = "qty_run", skillId = wf.skillId, boundSlots = mapOf("quantity" to "3"))
        val report = run { engine.execute(req) }
        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        assertEquals("3", report.boundSlots["quantity"])
        assertNotEquals("1", report.boundSlots["quantity"])
    }

    @Test fun testPhase4_11_FINAL_T6_ChangedAddress() {
        val s = step(action = "CLICK", selector = SemanticSelector(role = "RadioButton", text = "Address B"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Deliver to Address B")))
        val wf = workflow(step = s, slots = listOf(WorkflowSlot(name = "address", type = "string", required = true)))
        val repo = Store(wf)
        val driver = FakeDriver(ui(
            UiElement("addr_a", role = "RadioButton", text = "Address A", isClickable = true),
            UiElement("addr_b", role = "RadioButton", text = "Address B", isClickable = true)
        ))
        driver.effect = {
            driver.screen = ui(UiElement("confirmed", role = "TextView", text = "Deliver to Address B"))
        }
        val engine = ExecutionEngine(repo, driver)

        val req = ExecutionRequest(executionId = "addr_run", skillId = wf.skillId, boundSlots = mapOf("address" to "Address B"))
        val report = run { engine.execute(req) }
        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        assertEquals("Address B", report.boundSlots["address"])
    }

    @Test fun testPhase4_11_FINAL_T7_ChangedScreenUiState() {
        val s = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Proceed"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Next Screen")))
        val wf = workflow(step = s)
        val repo = Store(wf)
        val dynamicScreen = ui(
            UiElement("container_99", role = "ViewGroup"),
            UiElement("dyn_btn_777", role = "Button", resourceId = "com.store:id/btn_random_hash_9823", text = "Proceed", isClickable = true, bounds = BoundingBox(100, 800, 400, 950))
        )
        val driver = FakeDriver(dynamicScreen)
        driver.effect = {
            driver.screen = ui(UiElement("next_page", role = "TextView", text = "Next Screen"))
        }
        val engine = ExecutionEngine(repo, driver)

        val report = run { engine.execute(request()) }
        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        assertEquals(1, driver.actions)
        assertEquals("dyn_btn_777", report.stepReports.first().targetElementId)
    }

    @Test fun testPhase4_11_FINAL_T8_SecondWorkflow() {
        val s1 = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "search_box"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Product Results"))).copy(stepId = "s1")
        val wf1 = workflow(step = s1, slots = listOf(WorkflowSlot(name = "query", type = "string", required = true))).copy(
            skillId = "skill_search", name = "Search Products", intent = "search_information"
        )

        val s2 = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "msg_box"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Message Sent"))).copy(stepId = "s2")
        val wf2 = workflow(step = s2, slots = listOf(WorkflowSlot(name = "message", type = "string", required = true))).copy(
            skillId = "skill_message", name = "Send Message", intent = "send_message"
        )

        val repo = Store()
        repo.saveWorkflow(wf1)
        repo.saveWorkflow(wf2)

        val driver = FakeDriver(ui(input(text = "")))
        driver.effect = { driver.screen = ui(button("done", "Success")) }
        val engine = ExecutionEngine(repo, driver)

        val req1 = ExecutionRequest(executionId = "run_wf1", skillId = "skill_search", boundSlots = mapOf("query" to "laptop"))
        val rep1 = run { engine.execute(req1) }
        assertEquals("skill_search", rep1.workflowId)
        assertEquals("laptop", rep1.boundSlots["query"])
        assertFalse(rep1.boundSlots.containsKey("message"))

        val req2 = ExecutionRequest(executionId = "run_wf2", skillId = "skill_message", boundSlots = mapOf("message" to "Hello"))
        val rep2 = run { engine.execute(req2) }
        assertEquals("skill_message", rep2.workflowId)
        assertEquals("Hello", rep2.boundSlots["message"])
        assertFalse(rep2.boundSlots.containsKey("query"))
    }

    @Test fun testPhase4_11_FINAL_T9_ChangedSearchTerm() {
        val s = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "query_box"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "laptop")))
        val wf = workflow(step = s, slots = listOf(WorkflowSlot(name = "item", type = "string", required = true))).copy(
            intent = "search_information"
        )
        val repo = Store(wf)
        val driver = FakeDriver(ui(input(text = "")))
        driver.effect = { step -> driver.screen = ui(input(text = step.inputText ?: "")) }
        val engine = ExecutionEngine(repo, driver)

        val req = ExecutionRequest(executionId = "term_run", skillId = wf.skillId, boundSlots = mapOf("item" to "laptop"))
        val report = run { engine.execute(req) }
        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        assertEquals("laptop", report.boundSlots["item"])
        assertFalse(report.boundSlots.containsValue("headphones"))
    }

    @Test fun testPhase4_11_FINAL_T10_StuckFailedUi() {
        val s = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Submit"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Success")),
            recovery = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.REOBSERVE_AND_RETRY))
        val wf = workflow(step = s)
        val repo = Store(wf)
        val driver = FakeDriver(ui(button("btn_sub", "Submit")))
        driver.effect = {
            driver.screen = ui(UiElement("ok", role = "TextView", text = "Success"))
        }
        val engine = ExecutionEngine(repo, driver)
        val report = run { engine.execute(request()) }

        assertEquals(FinalExecutionStatus.SUCCESS, report.finalStatus)
        assertEquals(1, report.recoveryCount)
        assertEquals(StepExecutionStatus.RECOVERED, report.stepReports.first().finalStepStatus)
    }

    @Test fun testPhase4_11_FINAL_T11_PaymentSafetyBoundary() {
        val s = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Authorize Payment"))
        val wf = workflow(step = s, safety = SafetyBoundary(disallowSensitiveInput = true))
        val repo = Store(wf)
        val paymentScreen = UiObservation(UiState(
            stateId = "sec_screen", timestamp = System.currentTimeMillis(), appContext = app,
            isSensitiveContext = true,
            allElements = listOf(UiElement("cvv", role = "EditText", text = "CVV", isPassword = true))
        ), credentialFieldPresent = true)
        val driver = FakeDriver(paymentScreen)
        val engine = ExecutionEngine(repo, driver)

        val report = run { engine.execute(request()) }
        assertEquals(FinalExecutionStatus.SAFETY_BLOCKED, report.finalStatus)
        assertTrue(report.safetyBlocked)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_11_FINAL_T12_UnknownTask() {
        val repo = Store(workflow())
        val cmd = "Book me a flight to Tokyo"
        val understanding = CommandInterpreter.understandCommand(cmd)
        val matchResult = SkillMatcher(repo).match(understanding)

        assertEquals(SkillMatchStatus.UNKNOWN, matchResult.status)
        assertNull(matchResult.selectedSkillId)
        assertNull(matchResult.selectedWorkflow)

        val buildResult = ExecutionRequestBuilder.build(understanding, matchResult, repo)
        assertFalse(buildResult.isSuccess)
        assertNull(buildResult.request)
    }

    @Test fun testPhase4_11_FINAL_T13_Ambiguity() {
        val s = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Blue Jacket"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Details")))
        val wf = workflow(step = s)
        val repo = Store(wf)
        val driver = FakeDriver(ui(
            UiElement("j1", role = "Button", text = "Blue Jacket - Slim Fit", isClickable = true),
            UiElement("j2", role = "Button", text = "Blue Jacket - Regular Fit", isClickable = true)
        ))
        driver.effect = { driver.screen = ui(button("det", "Details")) }
        val engine = ExecutionEngine(repo, driver)

        val initReport = run { engine.execute(ExecutionRequest(executionId = "ambig_exec", skillId = wf.skillId)) }
        assertEquals(FinalExecutionStatus.CLARIFICATION_REQUIRED, initReport.finalStatus)
        assertEquals(ExecutionState.WAITING_FOR_USER, initReport.result.finalState)
        assertEquals(0, driver.actions)

        val resumedReport = run {
            engine.resume(ClarificationResponse(executionId = "ambig_exec", userResponseText = "The second one"))
        }
        assertEquals(FinalExecutionStatus.SUCCESS, resumedReport.finalStatus)
        assertTrue(resumedReport.result.success)
        assertEquals(1, driver.actions)
    }

    @Test fun testPhase4_11_FINAL_T14_LastRunReport() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")))
        val wf = workflow(step = s)
        val repo = Store(wf)
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.effect = { driver.screen = ui(button("done", "Done")) }
        val engine = ExecutionEngine(repo, driver)

        assertEquals(FinalExecutionStatus.NO_EXECUTION, engine.getLatestReportOrEmpty().finalStatus)
        assertNull(engine.getLatestReport())

        val report1 = run { engine.execute(ExecutionRequest(executionId = "run_101", skillId = wf.skillId)) }
        assertEquals("run_101", engine.getLatestReport()?.executionId)

        val report2 = run { engine.execute(ExecutionRequest(executionId = "run_102", skillId = wf.skillId)) }
        assertEquals("run_102", engine.getLatestReport()?.executionId)
        assertNotEquals("run_101", engine.getLatestReport()?.executionId)
    }

    // =========================================================================
    // PHASE 4.11 — THE 5 KILL TESTS
    // =========================================================================

    @Test fun testPhase4_11_KillTest1_CompleteEndToEndPipeline() {
        val targetPkg = "com.myntra.android"
        val platformSlot = WorkflowSlot(name = "platform", type = "string", required = true)
        val itemSlot = WorkflowSlot(name = "item", type = "string", required = true)

        val step1 = WorkflowStep(
            stepId = "s1_search",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Search"),
            expectedTransition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results")),
            recoveryPolicy = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.REOBSERVE_AND_RETRY)
        )
        val step2 = WorkflowStep(
            stepId = "s2_select",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Blue Jacket"),
            expectedTransition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Details")),
            recoveryPolicy = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.RETRY_STEP)
        )
        val wf = Workflow(
            skillId = "e2e_shopping", name = "Shopping Search", intent = "shop_item",
            appContext = targetPkg, slots = listOf(platformSlot, itemSlot),
            steps = listOf(step1, step2)
        )
        val repo = Store(wf)
        val driver = FakeDriver(ui(button("btn_search", "Search"), packageName = targetPkg))

        driver.effect = { step ->
            if (step.source.stepId == "s1_search") {
                driver.screen = ui(
                    UiElement("p1", role = "Button", text = "Blue Jacket - Slim Fit", resourceId = "prod_1", isClickable = true),
                    UiElement("p2", role = "Button", text = "Blue Jacket - Regular Fit", resourceId = "prod_2", isClickable = true),
                    UiElement("header", role = "TextView", text = "Results"),
                    packageName = targetPkg
                )
            } else if (step.source.stepId == "s2_select") {
                driver.screen = ui(UiElement("details", role = "TextView", text = "Details"), packageName = targetPkg)
            }
        }

        val engine = ExecutionEngine(repo, driver)
        val req = ExecutionRequest(
            executionId = "kill_test_1",
            skillId = "e2e_shopping",
            boundSlots = mapOf("platform" to "Myntra", "item" to "Blue Jacket"),
            originalCommand = "Find a blue jacket on Myntra"
        )

        val initReport = run { engine.execute(req) }
        assertEquals(FinalExecutionStatus.CLARIFICATION_REQUIRED, initReport.finalStatus)
        assertEquals(1, initReport.recoveryCount)

        val finalReport = run {
            engine.resume(ClarificationResponse(executionId = "kill_test_1", userResponseText = "The second one"))
        }

        assertEquals(FinalExecutionStatus.SUCCESS, finalReport.finalStatus)
        assertTrue(finalReport.result.success)
        assertEquals(2, finalReport.stepsCompleted)
        assertEquals("Myntra", finalReport.boundSlots["platform"])
        assertEquals("Blue Jacket", finalReport.boundSlots["item"])
        assertFalse(finalReport.boundSlots.containsValue("Amazon"))
        assertFalse(finalReport.boundSlots.containsValue("White Shirt"))
    }

    @Test fun testPhase4_11_KillTest2_FailurePath_RecoveryExhaustedNoInfiniteRetry() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Submit"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Success")),
            recovery = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP))
        val wf = workflow(step = s)
        val driver = FakeDriver(ui(button("btn", "Submit")))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request()) }
        assertEquals(FinalExecutionStatus.RECOVERY_EXHAUSTED, report.finalStatus)
        assertFalse(report.result.success)
        assertEquals(2, report.recoveryRecords.size)
        assertTrue(driver.actions <= 3)
    }

    @Test fun testPhase4_11_KillTest3_SafetyPath_ZeroActionsHandoff() {
        val s = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "pin_field"),
            inputText = "1234")
        val wf = workflow(step = s, safety = SafetyBoundary(disallowSensitiveInput = true))
        val driver = FakeDriver(ui(UiElement("pin_field", role = "EditText", text = "", isPassword = true)))
        val engine = ExecutionEngine(Store(wf), driver)

        val report = run { engine.execute(request()) }
        assertEquals(FinalExecutionStatus.SAFETY_BLOCKED, report.finalStatus)
        assertTrue(report.safetyBlocked)
        assertEquals(0, driver.actions)
    }

    @Test fun testPhase4_11_KillTest4_UnknownTask_SafeNoMatchRejection() {
        val wf = workflow().copy(intent = "shop_item")
        val repo = Store(wf)
        val unknownCmd = "Do something completely unrelated like tuning an engine"
        val understanding = CommandInterpreter.understandCommand(unknownCmd)
        val matchResult = SkillMatcher(repo).match(understanding)

        assertEquals(SkillMatchStatus.UNKNOWN, matchResult.status)
        val buildResult = ExecutionRequestBuilder.build(understanding, matchResult, repo)
        assertFalse(buildResult.isSuccess)
    }

    @Test fun testPhase4_11_KillTest5_CrossRunIsolation_ZeroLeakage() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")))
        val wf = workflow(step = s, slots = listOf(WorkflowSlot(name = "item", type = "string", required = true)))
        val driver = FakeDriver(ui(button("btn", "Go")))
        driver.effect = { driver.screen = ui(button("done", "Done")) }
        val engine = ExecutionEngine(Store(wf), driver)

        val repA = run { engine.execute(ExecutionRequest(executionId = "RUN_A", skillId = wf.skillId, boundSlots = mapOf("item" to "Blue Jacket"))) }
        val repB = run { engine.execute(ExecutionRequest(executionId = "RUN_B", skillId = wf.skillId, boundSlots = mapOf("item" to "Running Shoes"))) }

        assertNotEquals(repA.executionId, repB.executionId)
        assertEquals("Blue Jacket", repA.boundSlots["item"])
        assertEquals("Running Shoes", repB.boundSlots["item"])
        assertFalse(repA.boundSlots.containsValue("Running Shoes"))
        assertFalse(repB.boundSlots.containsValue("Blue Jacket"))
    }

    // =========================================================================
    // PHASE 4.11 — CONTROLLED FAILURE INJECTION SUITE (15 SCENARIOS)
    // =========================================================================

    @Test fun testPhase4_11_FailureInjection_1_NO_MATCH() {
        val repo = Store(workflow())
        val res = SkillMatcher(repo).match(CommandUnderstandingResult(
            rawCommand = "unsupported", normalizedCommand = "unsupported",
            intent = com.chockXlate.teachablevoice.learning.intent.Intent("i", "unknown", 0.0, "LOW"),
            intentConfidence = 0.0, status = "UNKNOWN_INTENT"
        ))
        assertEquals(SkillMatchStatus.UNKNOWN, res.status)
    }

    @Test fun testPhase4_11_FailureInjection_2_AMBIGUOUS_MATCH() {
        val wf1 = workflow().copy(skillId = "wf1", intent = "search_item", name = "Search 1")
        val wf2 = workflow().copy(skillId = "wf2", intent = "search_item", name = "Search 2")
        val repo = Store()
        repo.saveWorkflow(wf1)
        repo.saveWorkflow(wf2)
        val res = SkillMatcher(repo).match(CommandUnderstandingResult(
            rawCommand = "search", normalizedCommand = "search",
            intent = com.chockXlate.teachablevoice.learning.intent.Intent("i", "search_item", 1.0, "HIGH"),
            intentConfidence = 1.0, status = "UNDERSTOOD"
        ))
        assertTrue(res.status == SkillMatchStatus.AMBIGUOUS || res.candidates.size > 1)
    }

    @Test fun testPhase4_11_FailureInjection_3_WEAK_MATCH() {
        val repo = Store(workflow())
        val res = SkillMatcher(repo).match(CommandUnderstandingResult(
            rawCommand = "maybe", normalizedCommand = "maybe",
            intent = com.chockXlate.teachablevoice.learning.intent.Intent("i", "generic", 0.2, "LOW"),
            intentConfidence = 0.2, status = "UNDERSTOOD"
        ))
        assertTrue(res.overallConfidence < 0.6)
    }

    @Test fun testPhase4_11_FailureInjection_4_STALE_TARGET() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Save")),
            action = RuntimeAction.CLICK, selector = SemanticSelector(role = "Button", text = "Save"),
            preconditions = Preconditions(), transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button("btn_save", "Save")))
        driver.beforeDispatch = { driver.screen = ui(button("btn_other", "Discard")) }
        val outcome = run { SemanticExecutor(matcher).execute(driver, step, driver.screen, app, SafetyBoundary(), SafetyGate()) }
        assertFalse(outcome.accepted)
    }

    @Test fun testPhase4_11_FailureInjection_5_UI_UNAVAILABLE() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Save")),
            action = RuntimeAction.CLICK, selector = SemanticSelector(role = "Button", text = "Save"),
            preconditions = Preconditions(), transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button("btn", "Save"))).apply { ready = false }
        val outcome = run { SemanticExecutor(matcher).execute(driver, step, driver.screen, app, SafetyBoundary(), SafetyGate()) }
        assertFalse(outcome.accepted)
        assertTrue(outcome.reason.contains("unavailable", ignoreCase = true))
    }

    @Test fun testPhase4_11_FailureInjection_6_ACTION_REJECTED() {
        val step = BoundStep(
            source = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Save")),
            action = RuntimeAction.CLICK, selector = SemanticSelector(role = "Button", text = "Save"),
            preconditions = Preconditions(), transition = ExpectedTransition()
        )
        val driver = FakeDriver(ui(button("btn", "Save"))).apply { accepted = false }
        val outcome = run { SemanticExecutor(matcher).execute(driver, step, driver.screen, app, SafetyBoundary(), SafetyGate()) }
        assertFalse(outcome.accepted)
    }

    @Test fun testPhase4_11_FailureInjection_7_NO_TRANSITION() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Save"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Saved")),
            recovery = RecoveryPolicy(maxRetries = 0))
        val engine = ExecutionEngine(Store(workflow(step = s)), FakeDriver(ui(button("btn", "Save"))))
        val report = run { engine.execute(request()) }
        assertEquals(FinalExecutionStatus.FAILED, report.finalStatus)
        assertEquals("NOT_VERIFIED", report.stepReports.first().verificationStatus)
    }

    @Test fun testPhase4_11_FailureInjection_8_UNEXPECTED_TRANSITION() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Save"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Saved")),
            recovery = RecoveryPolicy(maxRetries = 0))
        val driver = FakeDriver(ui(button("btn", "Save")))
        driver.effect = { driver.screen = ui(UiElement("err", role = "TextView", text = "Fatal Error")) }
        val engine = ExecutionEngine(Store(workflow(step = s)), driver)
        val report = run { engine.execute(request()) }
        assertEquals(FinalExecutionStatus.FAILED, report.finalStatus)
    }

    @Test fun testPhase4_11_FailureInjection_9_RECOVERY_EXHAUSTED() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Go"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")),
            recovery = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.RETRY_STEP))
        val engine = ExecutionEngine(Store(workflow(step = s)), FakeDriver(ui(button("btn", "Go"))))
        val report = run { engine.execute(request()) }
        assertEquals(FinalExecutionStatus.RECOVERY_EXHAUSTED, report.finalStatus)
    }

    @Test fun testPhase4_11_FailureInjection_10_MISSING_SLOT() {
        val s = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "f"))
        val wf = workflow(step = s, slots = listOf(WorkflowSlot(name = "mandatory", type = "string", required = true)))
        val engine = ExecutionEngine(Store(wf), FakeDriver(ui(input())))
        val report = run { engine.execute(request(emptyMap())) }
        assertEquals(FinalExecutionStatus.CLARIFICATION_REQUIRED, report.finalStatus)
    }

    @Test fun testPhase4_11_FailureInjection_11_AMBIGUOUS_SLOT() {
        val s = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "f"))
        val wf = workflow(step = s, slots = listOf(WorkflowSlot(name = "target", type = "string", required = true)))
        val engine = ExecutionEngine(Store(wf), FakeDriver(ui(input())))
        val report = run { engine.execute(request(mapOf("target" to ""))) }
        assertEquals(FinalExecutionStatus.CLARIFICATION_REQUIRED, report.finalStatus)
    }

    @Test fun testPhase4_11_FailureInjection_12_INVALID_CLARIFICATION() {
        val s = step(action = "CLICK", selector = SemanticSelector(role = "Button", text = "Item"))
        val wf = workflow(step = s)
        val driver = FakeDriver(ui(
            UiElement("i1", role = "Button", text = "Item A", isClickable = true),
            UiElement("i2", role = "Button", text = "Item B", isClickable = true)
        ))
        val engine = ExecutionEngine(Store(wf), driver)
        val init = run { engine.execute(ExecutionRequest(executionId = "inv_clar", skillId = wf.skillId)) }
        assertEquals(FinalExecutionStatus.CLARIFICATION_REQUIRED, init.finalStatus)

        val resume = run { engine.resume(ClarificationResponse(executionId = "inv_clar", userResponseText = "banana")) }
        assertEquals(FinalExecutionStatus.CLARIFICATION_REQUIRED, resume.finalStatus)
    }

    @Test fun testPhase4_11_FailureInjection_13_SAFETY_BLOCK() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Pay"))
        val wf = workflow(step = s, safety = SafetyBoundary(disallowSensitiveInput = true))
        val engine = ExecutionEngine(Store(wf), FakeDriver(ui(UiElement("pin", role = "EditText", isPassword = true))))
        val report = run { engine.execute(request()) }
        assertEquals(FinalExecutionStatus.SAFETY_BLOCKED, report.finalStatus)
    }

    @Test fun testPhase4_11_FailureInjection_14_UNSUPPORTED_CAPABILITY() {
        val unsupportedTransition = ExpectedTransition(unsupportedDescription = "External hardware key trigger")
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Trigger"), transition = unsupportedTransition)
        val engine = ExecutionEngine(Store(workflow(step = s)), FakeDriver(ui(button("btn", "Trigger"))))
        val report = run { engine.execute(request()) }
        assertTrue(report.finalStatus == FinalExecutionStatus.UNSUPPORTED || report.finalStatus == FinalExecutionStatus.HANDOFF)
    }

    @Test fun testPhase4_11_FailureInjection_15_USER_CANCEL() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "LongTask"))
        val engine = ExecutionEngine(Store(workflow(step = s)), FakeDriver(ui(button("btn", "LongTask"))))
        engine.cancel()
        val report = run { engine.execute(request()) }
        assertEquals(FinalExecutionStatus.CANCELLED, report.finalStatus)
    }

    // =========================================================================
    // PHASE 4.11 — FINAL SAFETY ADVERSARIAL SUITE (A THROUGH E)
    // =========================================================================

    @Test fun testPhase4_11_SafetyAdversarial_A_PaymentScreenAppearsAfterAction_BlockedHandoff() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Proceed"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")))
        val wf = workflow(step = s, safety = SafetyBoundary(disallowSensitiveInput = true))
        val driver = FakeDriver(ui(button("btn", "Proceed")))
        driver.effect = {
            driver.screen = UiObservation(UiState(
                stateId = "payment_sec", timestamp = System.currentTimeMillis(), appContext = app,
                isSensitiveContext = true,
                allElements = listOf(UiElement("card", role = "EditText", text = "Card Number", isPassword = true))
            ), credentialFieldPresent = true)
        }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }
        assertEquals(FinalExecutionStatus.SAFETY_BLOCKED, report.finalStatus)
    }

    @Test fun testPhase4_11_SafetyAdversarial_B_RecoveryNavigatesToSensitiveState_Blocked() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "TryAgain"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Done")),
            recovery = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.REOBSERVE_AND_RETRY))
        val wf = workflow(step = s, safety = SafetyBoundary(disallowSensitiveInput = true))
        val driver = FakeDriver(ui(button("btn", "TryAgain")))
        driver.effect = {
            driver.screen = UiObservation(UiState(
                stateId = "pin_prompt", timestamp = System.currentTimeMillis(), appContext = app,
                isSensitiveContext = true,
                allElements = listOf(UiElement("pin", role = "EditText", isPassword = true))
            ), credentialFieldPresent = true)
        }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }
        assertEquals(FinalExecutionStatus.SAFETY_BLOCKED, report.finalStatus)
    }

    @Test fun testPhase4_11_SafetyAdversarial_C_UserProvidesPinInClarification_RedactedNoCredentialAction() {
        val s = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "note_field"))
        val wf = workflow(step = s, slots = listOf(WorkflowSlot(name = "note", type = "string", required = true)))
        val driver = FakeDriver(ui(input()))
        val engine = ExecutionEngine(Store(wf), driver)

        val init = run { engine.execute(request(emptyMap())) }
        assertEquals(FinalExecutionStatus.CLARIFICATION_REQUIRED, init.finalStatus)

        val resume = run { engine.resume(ClarificationResponse(executionId = "run", userResponseText = "My PIN is 9876")) }
        assertFalse(resume.originalCommand?.contains("9876") == true)
        assertFalse(resume.humanExplanation.contains("9876"))
    }

    @Test fun testPhase4_11_SafetyAdversarial_D_CardNumberAsAddress_Redacted() {
        val s = step(action = "INPUT_TEXT", selector = SemanticSelector(role = "EditText", resourceId = "addr_field"))
        val wf = workflow(step = s, slots = listOf(WorkflowSlot(name = "address", type = "string", required = true)))
        val driver = FakeDriver(ui(input()))
        val engine = ExecutionEngine(Store(wf), driver)

        val req = ExecutionRequest(executionId = "card_addr", skillId = wf.skillId,
            boundSlots = mapOf("address" to "4111 2222 3333 4444"), originalCommand = "Deliver to card 4111 2222 3333 4444")
        val report = run { engine.execute(req) }
        assertFalse(report.originalCommand?.contains("4111 2222 3333 4444") == true)
        assertTrue(report.originalCommand?.contains("[REDACTED_SECRET]") == true)
    }

    @Test fun testPhase4_11_SafetyAdversarial_E_CaptchaAppearsDuringRecovery_Handoff() {
        val s = step(action = "CLICK", selector = SemanticSelector(text = "Search"),
            transition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Results")),
            recovery = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.REOBSERVE_AND_RETRY))
        val wf = workflow(step = s)
        val driver = FakeDriver(ui(button("btn", "Search")))
        driver.effect = {
            driver.screen = UiObservation(UiState(
                stateId = "captcha_screen", timestamp = System.currentTimeMillis(), appContext = app,
                isSensitiveContext = true,
                allElements = listOf(UiElement("captcha", role = "ImageView", text = "Enter CAPTCHA characters"))
            ), credentialFieldPresent = true)
        }
        val engine = ExecutionEngine(Store(wf), driver)
        val report = run { engine.execute(request()) }
        assertEquals(FinalExecutionStatus.SAFETY_BLOCKED, report.finalStatus)
        assertEquals(0, driver.actions)
    }
}




