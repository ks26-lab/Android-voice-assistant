package com.chockXlate.teachablevoice.runtime

import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.ui.*
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.runtime.matching.*
import com.chockXlate.teachablevoice.runtime.recovery.*
import com.chockXlate.teachablevoice.runtime.slots.*
import com.chockXlate.teachablevoice.runtime.ui.*
import com.chockXlate.teachablevoice.runtime.verification.*
import com.chockXlate.teachablevoice.runtime.workflow.WorkflowResolver
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
    private class Store(var workflow: Workflow?) : SkillRepository {
        override fun getWorkflowById(skillId: String) = workflow?.takeIf { it.skillId == skillId }
        override fun getAllWorkflows() = listOfNotNull(workflow)
        override fun saveWorkflow(workflow: Workflow): Boolean { this.workflow = workflow; return true }
        override fun deleteWorkflow(skillId: String): Boolean { workflow = null; return true }
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

}
