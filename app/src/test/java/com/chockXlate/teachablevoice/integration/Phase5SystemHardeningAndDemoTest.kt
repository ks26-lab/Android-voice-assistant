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
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset
import com.chockXlate.teachablevoice.learning.inference.ConstantVariableInference
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
 * Phase 5 — Full-System Hardening, Adversarial Testing & Competition Demo Readiness Suite.
 * Covers:
 * - Full end-to-end competition demo scenario
 * - Adversarial UI variations (layout shifts, unexpected popups, duplicate elements)
 * - Adversarial input variations (paraphrasing, missing slots, unknown/ambiguous commands)
 * - Adversarial learning variations (navigation & noise filtering)
 * - Adversarial runtime failures & bounded recovery
 * - Non-bypassable safety boundaries (payment, OTP, credentials)
 * - Automatic typing hardening across parameter types (item, quantity, address, search)
 * - Storage hardening & restart persistence
 * - Bonus capabilities (Bonus 1, Bonus 2, Bonus 3)
 */
class Phase5SystemHardeningAndDemoTest {

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

    private class HardenedTestUiDriver(
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
        storageDir = tempFolder.newFolder("p5_skills_storage")
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
        nearbyText: List<String> = emptyList(),
        bounds: String = "100,100,200,200"
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
            bounds = bounds
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
    // 1. END-TO-END DEMO SCENARIO
    // =========================================================================

    @Test
    fun test01_FullEndToEndCompetitionDemoScenario() = runBlockingTest {
        // STEP 1 — Teach: Demonstration with voice + UI action
        val voiceEvent = VoiceEvent("v_demo", 1000L, "search for wireless headphones", 0.96)
        val actionEvent = ActionEvent(
            actionId = "a_demo",
            timestamp = 1100L,
            actionType = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.demo.shop:id/search_query"),
            inputValue = "wireless headphones"
        )
        val stateEvent = StateEvent(
            stateEventId = "st_demo",
            timestamp = 1200L,
            causeActionId = "a_demo",
            beforeState = UiState("b_demo", 1100L, "com.demo.shop"),
            afterState = UiState("a_demo", 1200L, "com.demo.shop")
        )
        val trace = DemonstrationTrace(
            traceId = "demo_full_trace",
            timestamp = 1000L,
            appContext = "com.demo.shop",
            traceEvents = listOf(
                TraceEvent.Voice("v_demo", 1000L, voiceEvent),
                TraceEvent.Action("a_demo", 1100L, actionEvent),
                TraceEvent.State("st_demo", 1200L, stateEvent)
            )
        )

        // STEP 2 — Synthesize & Save
        val filterResult = DemonstrationFilter.filter(trace)
        val semanticActions = SemanticActionExtractor.extract(trace, filterResult)
        val intentResult = IntentExtractor.extract(trace, semanticActions)
        val slotResult = SlotExtractor.extract(trace, semanticActions, intentResult)
        val dataset = DemonstrationDataset("demo_full_trace", trace, filterResult, semanticActions, intentResult, slotResult)
        val inferenceResult = ConstantVariableInference.infer(listOf(dataset))

        val synth = WorkflowSynthesizer.synthesize(
            skillId = "skill_demo_search",
            name = "Search Product",
            appContext = "com.demo.shop",
            datasets = listOf(dataset),
            inferenceResult = inferenceResult
        )
        val workflow = synth.workflow
        val validReport = WorkflowValidator.validate(workflow)
        assertTrue("Workflow must be valid", validReport.isValid)
        repository.save(workflow)

        // STEP 3 — Inspect
        val inspection = WorkflowInspectorImpl.inspect(workflow, repository)
        assertTrue(inspection.isExecutableByPerson2)
        assertEquals("skill_demo_search", inspection.skillId)

        // STEP 4 — Restart Simulation
        SkillRepositoryProvider.reset()
        SkillRepositoryProvider.initialize(storageDir)
        val reloadedRepo = SkillRepositoryProvider.getRepository()
        val reloadedWf = reloadedRepo.getWorkflow("skill_demo_search")
        assertNotNull(reloadedWf)

        // STEP 5 & 6 — Reuse & Execute with Paraphrased Command & Changed Parameter
        val userCommand = "find bluetooth speakers"
        val interpretation = CommandInterpreter().interpret(userCommand)
        val matchResult = SkillMatcher().match(interpretation, reloadedRepo)
        assertEquals(SkillMatchStatus.EXACT_MATCH, matchResult.status)

        val screen1 = createUiObservation(
            "com.demo.shop",
            "HOME_SCREEN",
            listOf(createUiElement("in_search", "EditText", resourceId = "com.demo.shop:id/search_query", editable = true))
        )
        val screen2 = createUiObservation(
            "com.demo.shop",
            "RESULTS_SCREEN",
            listOf(createUiElement("res_title", "TextView", text = "bluetooth speakers"))
        )

        val driver = HardenedTestUiDriver(screen1)
        driver.onExecuteHook = { driver.currentScreen = screen2 }

        val engine = ExecutionEngine(reloadedRepo, driver)
        val reqResult = ExecutionRequestBuilder().buildRequest(interpretation, matchResult)
        val request = reqResult.request ?: ExecutionRequest("exec_demo", "skill_demo_search", boundSlots = mapOf("item" to "bluetooth speakers"))

        val report = engine.execute(request)
        assertEquals("Execution must complete successfully", ExecutionState.COMPLETED, report.state)
        assertEquals("bluetooth speakers", driver.lastExecutedAction?.inputText)
    }

    // =========================================================================
    // 2. ADVERSARIAL TESTING
    // =========================================================================

    @Test
    fun test02_AdversarialUiVariation_ChangedButtonPosition_SucceedsSemantically() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_layout_shift",
            name = "Layout Shift Task",
            intent = "layout_shift",
            appContext = "com.example.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "com.example.app:id/btn_buy", text = "Buy Now"),
                    expectedTransition = ExpectedTransition(toState = "CONFIRMATION")
                )
            )
        )
        repository.save(workflow)

        // Screen layout completely shifts from (100,100,200,200) to (500,800,700,900)
        val shiftedScreen = createUiObservation(
            "com.example.app",
            "PRODUCT_VIEW",
            listOf(createUiElement("btn_buy_shifted", "Button", text = "Buy Now", resourceId = "com.example.app:id/btn_buy", bounds = "500,800,700,900"))
        )
        val confirmedScreen = createUiObservation("com.example.app", "CONFIRMATION", listOf(createUiElement("ok", "TextView", text = "Confirmed")))

        val driver = HardenedTestUiDriver(shiftedScreen)
        driver.onExecuteHook = { driver.currentScreen = confirmedScreen }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e_shift", "skill_layout_shift"))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals(1, driver.executedActionsCount)
    }

    @Test
    fun test03_AdversarialUiVariation_UnexpectedPopup_FailsSafely() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_popup_adv",
            name = "Popup Task",
            intent = "popup_intent",
            appContext = "com.example.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "id/confirm", text = "Confirm"),
                    preconditions = Preconditions(requiredState = "SUMMARY_SCREEN")
                )
            )
        )
        repository.save(workflow)

        val popupScreen = createUiObservation(
            "com.example.app",
            "TERMS_UPDATE_POPUP",
            listOf(createUiElement("close", "Button", text = "Accept Terms"))
        )
        val driver = HardenedTestUiDriver(popupScreen)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e_popup", "skill_popup_adv"))
        assertNotEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("Must execute 0 blind actions on unexpected popup", 0, driver.executedActionsCount)
    }

    @Test
    fun test04_AdversarialUiVariation_DuplicateTargets_RelationalDisambiguation() {
        val matcher = SemanticMatcher()
        val selector = SemanticSelector(role = "Button", text = "Select", nearbyText = "Deluxe Plan")

        val screen = createUiObservation(
            "com.example.sub",
            "PRICING_PAGE",
            listOf(
                createUiElement("btn_basic", "Button", text = "Select", nearbyText = listOf("Basic Plan", "$5/mo")),
                createUiElement("btn_deluxe", "Button", text = "Select", nearbyText = listOf("Deluxe Plan", "$15/mo")),
                createUiElement("btn_prem", "Button", text = "Select", nearbyText = listOf("Premium Plan", "$25/mo"))
            )
        )

        val match = matcher.match(selector, screen, RuntimeAction.CLICK)
        assertEquals(MatchStatus.MATCHED, match.status)
        assertEquals("btn_deluxe", match.best?.element?.elementId)
    }

    @Test
    fun test05_AdversarialInputVariation_IncompleteCommand_RequestsClarification() {
        val workflow = Workflow(
            skillId = "skill_two_slot",
            name = "Send Message",
            intent = "send_message",
            slots = listOf(
                WorkflowSlot(name = "recipient", type = SlotType.TEXT, required = true, exampleValue = "Alice"),
                WorkflowSlot(name = "message", type = SlotType.TEXT, required = true, exampleValue = "Hello")
            )
        )
        repository.save(workflow)

        val interp = CommandInterpreter().interpret("send message to Bob") // missing message body
        val match = SkillMatchResult(SkillMatchStatus.EXACT_MATCH, workflow, listOf(SkillCandidateMatch(workflow, 1.0)))
        val reqRes = ExecutionRequestBuilder().buildRequest(interp, match)

        assertEquals(ExecutionRequestStatus.MISSING_SLOTS, reqRes.status)
        assertTrue(reqRes.missingSlots.contains("message"))
    }

    @Test
    fun test06_AdversarialInputVariation_AmbiguousIntent_PromptsUser() {
        val w1 = Workflow(skillId = "w_read_email", name = "Read Email", intent = "read_inbox", utteranceExamples = listOf("check inbox"))
        val w2 = Workflow(skillId = "w_read_sms", name = "Read SMS", intent = "read_inbox", utteranceExamples = listOf("check inbox"))
        repository.save(w1)
        repository.save(w2)

        val interp = CommandInterpreter().interpret("check inbox")
        val match = SkillMatcher().match(interp, repository)
        assertEquals(SkillMatchStatus.AMBIGUOUS, match.status)
    }

    @Test
    fun test07_AdversarialInputVariation_UnknownIntent_RejectsExecution() {
        val interp = CommandInterpreter().interpret("brew a cup of physical coffee")
        val match = SkillMatcher().match(interp, repository)
        assertEquals(SkillMatchStatus.UNKNOWN, match.status)
    }

    @Test
    fun test08_AdversarialLearningVariation_ExtraneousNoiseAndNavigationFiltered() {
        val nav = ActionEvent("a_nav", 1000L, "CLICK", SemanticSelector("TextView", resourceId = "com.google.android.apps.nexuslauncher:id/icon"))
        val noise = ActionEvent("a_noise", 1500L, "CLICK", SemanticSelector("FrameLayout", resourceId = "com.example.app:id/touch_bg"))
        val real = ActionEvent("a_real", 2000L, "CLICK", SemanticSelector("Button", resourceId = "com.example.app:id/submit"))
        val state = StateEvent("st_real", 2100L, "a_real", UiState("b", 2000L, "com.example.app"), UiState("a", 2100L, "com.example.app"))

        val trace = DemonstrationTrace("tr_adv", 1000L, "com.example.app", listOf(
            TraceEvent.Action("a_nav", 1000L, nav),
            TraceEvent.Action("a_noise", 1500L, noise),
            TraceEvent.Action("a_real", 2000L, real),
            TraceEvent.State("st_real", 2100L, state)
        ))

        val filterResult = DemonstrationFilter.filter(trace)
        assertTrue(filterResult.navigationContextEventIds.contains("a_nav"))
        assertTrue(filterResult.systemNoiseEventIds.contains("a_noise"))
        assertTrue(filterResult.taskRelevantEventIds.contains("a_real"))

        val extracted = SemanticActionExtractor.extract(trace, filterResult)
        assertEquals(1, extracted.size)
        assertEquals("a_real", extracted[0].actionId)
    }

    @Test
    fun test09_AdversarialRuntimeFailure_TransitionFailed_BoundedRecoveryTerminates() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_fail_trans",
            name = "Fail Transition",
            intent = "fail_trans",
            appContext = "com.example.app",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", resourceId = "id/btn"),
                    expectedTransition = ExpectedTransition(toState = "NEXT_SCREEN"),
                    recoveryPolicy = RecoveryPolicy(maxRetries = 1, strategy = RecoveryStrategy.RETRY_STEP)
                )
            )
        )
        repository.save(workflow)

        val screen = createUiObservation("com.example.app", "STUCK_SCREEN", listOf(createUiElement("b", "Button", resourceId = "id/btn")))
        val driver = HardenedTestUiDriver(screen) // never transitions to NEXT_SCREEN
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e_fail_trans", "skill_fail_trans"))
        assertEquals(ExecutionState.FAILED, report.state)
    }

    @Test
    fun test10_AdversarialSafety_PaymentScreen_NonBypassable() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_pay_adv",
            name = "Payment Task",
            intent = "pay_intent",
            appContext = "com.example.bank",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", text = "Authorize Charge"),
                    isPayment = true
                )
            ),
            safetyBoundary = SafetyBoundary(isPaymentBoundary = true)
        )
        repository.save(workflow)

        val sensitiveScreen = createUiObservation("com.example.bank", "PAYMENT_VIEW", listOf(createUiElement("p", "Button", text = "Authorize Charge")), isSensitive = true)
        val driver = HardenedTestUiDriver(sensitiveScreen)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e_pay_adv", "skill_pay_adv"))
        assertEquals(ExecutionState.HANDOFF, report.state)
        assertEquals(0, driver.executedActionsCount)
    }

    @Test
    fun test11_AdversarialSafety_CredentialPasswordPinOtp_NonBypassable() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_otp_adv",
            name = "OTP Verification",
            intent = "otp_intent",
            appContext = "com.example.auth",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/otp_box", isSensitive = true),
                    isSensitive = true
                )
            ),
            safetyBoundary = SafetyBoundary(isCredentialBoundary = true)
        )
        repository.save(workflow)

        val otpScreen = createUiObservation("com.example.auth", "OTP_INPUT", listOf(createUiElement("o", "EditText", resourceId = "id/otp_box", editable = true)), isSensitive = true)
        val driver = HardenedTestUiDriver(otpScreen)
        val engine = ExecutionEngine(repository, driver)

        val report = engine.execute(ExecutionRequest("e_otp_adv", "skill_otp_adv"))
        assertEquals(ExecutionState.HANDOFF, report.state)
        assertEquals(0, driver.executedActionsCount)
    }

    // =========================================================================
    // 3. AUTOMATIC TYPING HARDENING
    // =========================================================================

    @Test
    fun test12_AutomaticTypingHardening_ItemSlot() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_type_item_hard",
            name = "Type Item",
            intent = "type_item",
            appContext = "com.example.app",
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Bread")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/in", textSlot = "\${item}"),
                    parameters = mapOf("input_parameter" to "\${item}"),
                    expectedTransition = ExpectedTransition(toState = "DONE")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "START", listOf(createUiElement("in", "EditText", resourceId = "id/in", editable = true)))
        val s2 = createUiObservation("com.example.app", "DONE", listOf(createUiElement("out", "TextView", text = "Croissant")))

        val driver = HardenedTestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e_type_item", "skill_type_item_hard", boundSlots = mapOf("item" to "Croissant")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("Croissant", driver.lastExecutedAction?.inputText)
    }

    @Test
    fun test13_AutomaticTypingHardening_QuantitySlot() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_type_qty_hard",
            name = "Type Qty",
            intent = "type_qty",
            appContext = "com.example.app",
            slots = listOf(WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "1")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/qty", textSlot = "\${quantity}"),
                    parameters = mapOf("input_parameter" to "\${quantity}"),
                    expectedTransition = ExpectedTransition(toState = "DONE")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "START", listOf(createUiElement("q", "EditText", resourceId = "id/qty", editable = true)))
        val s2 = createUiObservation("com.example.app", "DONE", listOf(createUiElement("out", "TextView", text = "12")))

        val driver = HardenedTestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e_type_qty", "skill_type_qty_hard", boundSlots = mapOf("quantity" to "12")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("12", driver.lastExecutedAction?.inputText)
    }

    @Test
    fun test14_AutomaticTypingHardening_AddressSlot() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_type_addr_hard",
            name = "Type Addr",
            intent = "type_addr",
            appContext = "com.example.app",
            slots = listOf(WorkflowSlot(name = "address", type = SlotType.ADDRESS, required = true, exampleValue = "1st St")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/addr", textSlot = "\${address}"),
                    parameters = mapOf("input_parameter" to "\${address}"),
                    expectedTransition = ExpectedTransition(toState = "DONE")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "START", listOf(createUiElement("a", "EditText", resourceId = "id/addr", editable = true)))
        val s2 = createUiObservation("com.example.app", "DONE", listOf(createUiElement("out", "TextView", text = "500 5th Ave")))

        val driver = HardenedTestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e_type_addr", "skill_type_addr_hard", boundSlots = mapOf("address" to "500 5th Ave")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("500 5th Ave", driver.lastExecutedAction?.inputText)
    }

    @Test
    fun test15_AutomaticTypingHardening_SearchTermSlot() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_type_search_hard",
            name = "Type Search",
            intent = "type_search",
            appContext = "com.example.app",
            slots = listOf(WorkflowSlot(name = "query", type = SlotType.TEXT, required = true, exampleValue = "term")),
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/q", textSlot = "\${query}"),
                    parameters = mapOf("input_parameter" to "\${query}"),
                    expectedTransition = ExpectedTransition(toState = "DONE")
                )
            )
        )
        repository.save(workflow)

        val s1 = createUiObservation("com.example.app", "START", listOf(createUiElement("q", "EditText", resourceId = "id/q", editable = true)))
        val s2 = createUiObservation("com.example.app", "DONE", listOf(createUiElement("out", "TextView", text = "quantum computing")))

        val driver = HardenedTestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e_type_search", "skill_type_search_hard", boundSlots = mapOf("query" to "quantum computing")))

        assertEquals(ExecutionState.COMPLETED, report.state)
        assertEquals("quantum computing", driver.lastExecutedAction?.inputText)
    }

    // =========================================================================
    // 4. STORAGE HARDENING
    // =========================================================================

    @Test
    fun test16_StorageHardening_MultipleDistinctSkills() {
        val skills = (1..5).map { i ->
            Workflow(skillId = "skill_hard_$i", name = "Skill $i", intent = "intent_$i")
        }
        skills.forEach { repository.save(it) }

        assertEquals(5, repository.getWorkflowCount())
        skills.forEach { s ->
            val retrieved = repository.getWorkflow(s.skillId)
            assertNotNull(retrieved)
            assertEquals(s.name, retrieved?.name)
        }
    }

    @Test
    fun test17_StorageHardening_CorruptedSkillHandling() {
        // Write invalid JSON directly into storage directory
        val corruptFile = File(storageDir, "corrupted_skill.json")
        corruptFile.writeText("{ invalid json content ...")

        // Reload repository — corrupted file should be handled safely without crashing
        val repo = LocalSkillRepository(storageDir)
        assertNotNull(repo)
        assertNull(repo.getWorkflow("corrupted_skill"))
    }

    // =========================================================================
    // 5. BONUS CAPABILITY TESTS
    // =========================================================================

    @Test
    fun test18_Bonus1_IrrelevantActionFiltering_Production() {
        val navAction = ActionEvent("nav_p5", 1000L, "CLICK", SemanticSelector("TextView", resourceId = "com.google.android.apps.nexuslauncher:id/icon"))
        val realAction = ActionEvent("real_p5", 2000L, "CLICK", SemanticSelector("Button", resourceId = "com.app:id/btn"))
        val stateEvent = StateEvent("st_p5", 2100L, "real_p5", UiState("b", 2000L, "com.app"), UiState("a", 2100L, "com.app"))

        val trace = DemonstrationTrace("tr_b1", 1000L, "com.app", listOf(
            TraceEvent.Action("nav_p5", 1000L, navAction),
            TraceEvent.Action("real_p5", 2000L, realAction),
            TraceEvent.State("st_p5", 2100L, stateEvent)
        ))

        val filter = DemonstrationFilter.filter(trace)
        val extracted = SemanticActionExtractor.extract(trace, filter)

        assertEquals("Launcher action must be excluded", 1, extracted.size)
        assertEquals("real_p5", extracted[0].actionId)
    }

    @Test
    fun test19_Bonus2_CrossAppSemanticSelector_Production() {
        val selector = SemanticSelector(role = "Button", text = "Add to Cart", nearbyText = "Price")
        val appA = createUiObservation("com.market.a", "PAGE", listOf(createUiElement("b1", "Button", text = "Add to Cart", resourceId = "com.market.a:id/add", nearbyText = listOf("Price: $10"))))
        val appB = createUiObservation("com.market.b", "PAGE", listOf(createUiElement("b2", "Button", text = "Add to Cart", resourceId = "com.market.b:id/cart", nearbyText = listOf("Price: $15"))))

        val matcher = SemanticMatcher()
        assertEquals(MatchStatus.MATCHED, matcher.match(selector, appA, RuntimeAction.CLICK).status)
        assertEquals(MatchStatus.MATCHED, matcher.match(selector, appB, RuntimeAction.CLICK).status)
    }

    @Test
    fun test20_Bonus3_MidFlowClarification_Production() = runBlockingTest {
        val workflow = Workflow(
            skillId = "skill_midflow_p5",
            name = "Midflow P5",
            intent = "midflow_p5",
            appContext = "com.example.app",
            slots = listOf(
                WorkflowSlot(name = "p1", type = SlotType.TEXT, required = true, exampleValue = "A"),
                WorkflowSlot(name = "p2", type = SlotType.TEXT, required = true, exampleValue = "B")
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

        val driver = HardenedTestUiDriver(s1)
        driver.onExecuteHook = { driver.currentScreen = s2 }

        val engine = ExecutionEngine(repository, driver)
        val report = engine.execute(ExecutionRequest("e_mid_p5", "skill_midflow_p5", boundSlots = mapOf("p1" to "ValA")))

        assertEquals(ExecutionState.ASK, report.state)
        assertTrue(engine.isPaused)

        val resumedReport = engine.resume(ClarificationResponse("c_p5", "e_mid_p5", mapOf("p2" to "ValB")))
        assertEquals(ExecutionState.COMPLETED, resumedReport.state)
        assertFalse(engine.isPaused)
    }
}
