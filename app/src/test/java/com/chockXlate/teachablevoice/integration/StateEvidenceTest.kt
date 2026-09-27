package com.chockXlate.teachablevoice.integration

import com.chockXlate.teachablevoice.runtime.verification.*

import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.ui.*
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.runtime.*
import com.chockXlate.teachablevoice.runtime.matching.*
import com.chockXlate.teachablevoice.runtime.recovery.*
import com.chockXlate.teachablevoice.runtime.slots.*
import com.chockXlate.teachablevoice.runtime.ui.*
import com.chockXlate.teachablevoice.safety.*
import com.chockXlate.teachablevoice.skill.repository.SkillRepository
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.*

class StateEvidenceTest {

    private val app = "com.example.test"
    private val matcher = SemanticMatcher()
    private val engine = StateEvidenceEngine(matcher)

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun button(id: String = "btn", text: String = "Submit", isChecked: Boolean = false, isSelected: Boolean = false) = UiElement(
        elementId = id, role = "Button", text = text, isClickable = true, isChecked = isChecked, isSelected = isSelected
    )

    private fun input(id: String = "input", text: String = "initial") = UiElement(
        elementId = id, role = "EditText", resourceId = "id/$id", text = text, isEditable = true
    )

    private fun ui(vararg elements: UiElement, packageName: String = app) = UiObservation(UiState(
        stateId = UUID.randomUUID().toString(),
        timestamp = System.currentTimeMillis(),
        appContext = packageName,
        allElements = elements.toList()
    ))

    private fun step(
        action: String = "CLICK",
        selector: SemanticSelector = SemanticSelector(text = "Submit"),
        transition: ExpectedTransition = ExpectedTransition(timeoutMs = 200),
        pre: Preconditions = Preconditions(),
        recovery: RecoveryPolicy = RecoveryPolicy()
    ) = WorkflowStep(
        stepId = "step_test",
        semanticAction = action,
        semanticSelector = selector,
        preconditions = pre,
        expectedTransition = transition,
        recoveryPolicy = recovery
    )

    private fun workflow(step: WorkflowStep = step(), slots: List<WorkflowSlot> = emptyList()) = Workflow(
        skillId = "skill_evidence_test",
        name = "Evidence Test Workflow",
        intent = "test_intent",
        appContext = app,
        steps = listOf(step),
        slots = slots
    )

    private fun request(slots: Map<String, String> = emptyMap()) = ExecutionRequest(
        executionId = "exec_evidence_test",
        skillId = "skill_evidence_test",
        boundSlots = slots
    )

    private class Store(var workflow: Workflow?) : SkillRepository {
        override fun getWorkflowById(skillId: String) = workflow?.takeIf { it.skillId == skillId }
        override fun getAllWorkflows() = listOfNotNull(workflow)
        override fun saveWorkflow(workflow: Workflow): Boolean { this.workflow = workflow; return true }
        override fun deleteWorkflow(skillId: String): Boolean { workflow = null; return true }
    }

    private class FakeDriver(var screen: UiObservation) : UiDriver {
        var actions = 0
        var waits = 0
        var effect: (BoundStep) -> Unit = {}
        override fun isReady() = true
        override suspend fun observe() = screen
        override suspend fun awaitChange(delayMs: Long) { waits++ }
        override suspend fun execute(step: BoundStep, expectedPackage: String, boundary: SafetyBoundary, matcher: SemanticMatcher, gate: SafetyGate): ActionOutcome {
            val before = screen
            gate.check(boundary, before, step)?.let { return ActionOutcome(false, reason = it, before = before) }
            val match = matcher.match(step.selector, before, step.action)
            if (match.status != MatchStatus.MATCHED) return ActionOutcome(false, reason = match.reason, before = before)
            val result = gate.dispatch(boundary, before, step) { actions++; effect(step); true }
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
        check(done.await(5, TimeUnit.SECONDS)) { "Test coroutine timed out" }
        return completion!!.getOrThrow()
    }

    // 1. Expected element appears -> VERIFIED
    @Test
    fun testExpectedElementAppearsVerified() {
        val sel = SemanticSelector(role = "Button", text = "Confirmed")
        val req = StateEvidenceRequirement(type = EvidenceType.ELEMENT_APPEARED, selector = sel)
        val trans = ExpectedTransition(expectedEvidence = listOf(req))

        val before = ui(button("1", "Submit"))
        val after = ui(button("1", "Submit"), button("2", "Confirmed"))

        val eval = engine.evaluate(before, trans, after)
        assertEquals(EvidenceEvaluationStatus.VERIFIED, eval.status)
        assertEquals(1, eval.matched.size)
        assertEquals(EvidenceType.ELEMENT_APPEARED, eval.matched.first().type)
        assertTrue(eval.reason.contains("Declared transition evidence was observed"))
    }

    // 2. Expected element does not appear -> FAILED
    @Test
    fun testExpectedElementDoesNotAppearFailed() {
        val sel = SemanticSelector(role = "Button", text = "Confirmed")
        val req = StateEvidenceRequirement(type = EvidenceType.ELEMENT_APPEARED, selector = sel)
        val trans = ExpectedTransition(expectedEvidence = listOf(req))

        val before = ui(button("1", "Submit"))
        val after = ui(button("1", "Submit"), button("3", "Unrelated"))

        val eval = engine.evaluate(before, trans, after)
        assertEquals(EvidenceEvaluationStatus.FAILED, eval.status)
        assertEquals(1, eval.contradictory.size)
        assertTrue(eval.reason.contains("did not appear"))
    }

    // 2b. Preexisting element does not prove appearance -> FAILED
    @Test
    fun testPreexistingElementDoesNotProveAppearance() {
        val sel = SemanticSelector(role = "Button", text = "Submit")
        val req = StateEvidenceRequirement(type = EvidenceType.ELEMENT_APPEARED, selector = sel)
        val trans = ExpectedTransition(expectedEvidence = listOf(req))

        val before = ui(button("1", "Submit"))
        val after = ui(button("1", "Submit"), button("2", "Other"))

        val eval = engine.evaluate(before, trans, after)
        assertEquals(EvidenceEvaluationStatus.FAILED, eval.status)
        assertTrue(eval.reason.contains("already present"))
    }

    // 3. Expected element disappears -> VERIFIED
    @Test
    fun testExpectedElementDisappearsVerified() {
        val sel = SemanticSelector(role = "Button", text = "Banner")
        val req = StateEvidenceRequirement(type = EvidenceType.ELEMENT_DISAPPEARED, selector = sel)
        val trans = ExpectedTransition(expectedEvidence = listOf(req))

        val before = ui(button("1", "Submit"), button("banner", "Banner"))
        val after = ui(button("1", "Submit"))

        val eval = engine.evaluate(before, trans, after)
        assertEquals(EvidenceEvaluationStatus.VERIFIED, eval.status)
        assertEquals(1, eval.matched.size)
    }

    // 3b. Element disappearance requires prior presence -> FAILED
    @Test
    fun testElementDisappearanceRequiresPriorPresence() {
        val sel = SemanticSelector(role = "Button", text = "Ghost")
        val req = StateEvidenceRequirement(type = EvidenceType.ELEMENT_DISAPPEARED, selector = sel)
        val trans = ExpectedTransition(expectedEvidence = listOf(req))

        val before = ui(button("1", "Submit"))
        val after = ui(button("1", "Submit"))

        val eval = engine.evaluate(before, trans, after)
        assertEquals(EvidenceEvaluationStatus.FAILED, eval.status)
        assertTrue(eval.reason.contains("not present before action"))
    }

    // 4. Expected text/value appears -> VERIFIED
    @Test
    fun testExpectedTextValueAppearsVerified() {
        val sel = SemanticSelector(role = "EditText", resourceId = "id/input")
        val req = StateEvidenceRequirement(type = EvidenceType.TEXT_EQUALS, selector = sel, expectedValue = "Farmhouse")
        val trans = ExpectedTransition(expectedEvidence = listOf(req))

        val before = ui(input("input", ""))
        val after = ui(input("input", "Farmhouse"))

        val eval = engine.evaluate(before, trans, after)
        assertEquals(EvidenceEvaluationStatus.VERIFIED, eval.status)
        assertEquals(1, eval.matched.size)
    }

    // 5. Wrong value appears -> FAILED
    @Test
    fun testWrongValueAppearsFailed() {
        val sel = SemanticSelector(role = "EditText", resourceId = "id/input")
        val req = StateEvidenceRequirement(type = EvidenceType.TEXT_EQUALS, selector = sel, expectedValue = "Farmhouse")
        val trans = ExpectedTransition(expectedEvidence = listOf(req))

        val before = ui(input("input", ""))
        val after = ui(input("input", "Margherita"))

        val eval = engine.evaluate(before, trans, after)
        assertEquals(EvidenceEvaluationStatus.FAILED, eval.status)
        assertEquals(1, eval.contradictory.size)
        assertTrue(eval.reason.contains("contradicts expected value"))
    }

    // 6. Unrelated UI change occurs -> must NOT satisfy strong expected evidence
    @Test
    fun testUnrelatedUiChangeDoesNotSatisfyStrongEvidence() {
        val sel = SemanticSelector(role = "Button", text = "Added to Cart")
        val req = StateEvidenceRequirement(type = EvidenceType.ELEMENT_APPEARED, selector = sel)
        val trans = ExpectedTransition(expectedEvidence = listOf(req), timeoutMs = 100)

        val driver = FakeDriver(ui(button("btn", "Add to Cart"), button("clock", "12:00")))
        driver.effect = {
            // Unrelated clock/noise changes, but expected button does NOT appear
            driver.screen = ui(button("btn", "Add to Cart"), button("clock", "12:01"))
        }

        val wf = workflow(step("CLICK", SemanticSelector(text = "Add to Cart"), transition = trans))
        val report = run { ExecutionEngine(Store(wf), driver).execute(request()) }

        assertFalse("Unrelated UI mutation must not satisfy strong expected evidence", report.result.success)
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
    }

    // 7. Expected package transition occurs -> VERIFIED
    @Test
    fun testExpectedPackageTransitionOccursVerified() {
        val req = StateEvidenceRequirement(type = EvidenceType.EXPECTED_PACKAGE, expectedPackage = "com.destination.app")
        val trans = ExpectedTransition(expectedEvidence = listOf(req))

        val before = ui(button(), packageName = "com.origin.app")
        val after = ui(button(), packageName = "com.destination.app")

        val eval = engine.evaluate(before, trans, after)
        assertEquals(EvidenceEvaluationStatus.VERIFIED, eval.status)
        assertEquals(1, eval.matched.size)
    }

    // 8. Package transition does not occur -> not VERIFIED (FAILED)
    @Test
    fun testPackageTransitionDoesNotOccurFailed() {
        val req = StateEvidenceRequirement(type = EvidenceType.EXPECTED_PACKAGE, expectedPackage = "com.destination.app")
        val trans = ExpectedTransition(expectedEvidence = listOf(req))

        val before = ui(button(), packageName = "com.origin.app")
        val after = ui(button(), packageName = "com.origin.app")

        val eval = engine.evaluate(before, trans, after)
        assertEquals(EvidenceEvaluationStatus.FAILED, eval.status)
        assertTrue(eval.reason.contains("Expected package"))
    }

    // 9. INPUT_TEXT verified from actual resulting field value
    @Test
    fun testInputTextVerifiedFromResultingFieldValue() {
        val inputSelector = SemanticSelector(role = "EditText", resourceId = "id/search")
        val wf = workflow(
            step("INPUT_TEXT", inputSelector, transition = ExpectedTransition(timeoutMs = 150)).copy(
                parameters = mapOf("input_literal" to "Margherita")
            )
        )

        // Successful case: field receives "Margherita"
        val driverGood = FakeDriver(ui(input("search", "")))
        driverGood.effect = { driverGood.screen = ui(input("search", "Margherita")) }
        val reportGood = run { ExecutionEngine(Store(wf), driverGood).execute(request()) }
        assertTrue(reportGood.result.success)

        // Failed case: field receives "Farmhouse"
        val driverBad = FakeDriver(ui(input("search", "")))
        driverBad.effect = { driverBad.screen = ui(input("search", "Farmhouse")) }
        val reportBad = run { ExecutionEngine(Store(wf), driverBad).execute(request()) }
        assertFalse(reportBad.result.success)
    }

    // 10. Checked and Selected state evidence
    @Test
    fun testCheckedAndSelectedStateEvidence() {
        val selCheck = SemanticSelector(role = "CheckBox", resourceId = "id/agree")
        val reqCheck = StateEvidenceRequirement(type = EvidenceType.CHECKED_STATE, selector = selCheck, expectedChecked = true)
        val transCheck = ExpectedTransition(expectedEvidence = listOf(reqCheck))

        val checkBefore = ui(UiElement(elementId = "agree", role = "CheckBox", resourceId = "id/agree", isChecked = false))
        val checkAfter = ui(UiElement(elementId = "agree", role = "CheckBox", resourceId = "id/agree", isChecked = true))
        val evalCheck = engine.evaluate(checkBefore, transCheck, checkAfter)
        assertEquals(EvidenceEvaluationStatus.VERIFIED, evalCheck.status)

        val selSelect = SemanticSelector(role = "Tab", resourceId = "id/tab_orders")
        val reqSelect = StateEvidenceRequirement(type = EvidenceType.SELECTED_STATE, selector = selSelect, expectedSelected = true)
        val transSelect = ExpectedTransition(expectedEvidence = listOf(reqSelect))

        val selectBefore = ui(UiElement(elementId = "tab", role = "Tab", resourceId = "id/tab_orders", isSelected = false))
        val selectAfter = ui(UiElement(elementId = "tab", role = "Tab", resourceId = "id/tab_orders", isSelected = true))
        val evalSelect = engine.evaluate(selectBefore, transSelect, selectAfter)
        assertEquals(EvidenceEvaluationStatus.VERIFIED, evalSelect.status)
    }

    // 11. Multiple evidence items with deterministic aggregation (ALL_REQUIRED & ANY_SUFFICIENT)
    @Test
    fun testMultipleEvidenceItemsDeterministicAggregation() {
        val req1 = StateEvidenceRequirement(type = EvidenceType.EXPECTED_PACKAGE, expectedPackage = "com.target")
        val req2 = StateEvidenceRequirement(type = EvidenceType.ELEMENT_APPEARED, selector = SemanticSelector(text = "Success"))

        // ALL_REQUIRED: Both must match
        val transAll = ExpectedTransition(
            expectedEvidence = listOf(req1, req2),
            evidenceOperator = EvidenceOperator.ALL_REQUIRED
        )

        val before = ui(button(), packageName = "com.origin")
        val afterBoth = ui(button("succ", "Success"), packageName = "com.target")
        assertEquals(EvidenceEvaluationStatus.VERIFIED, engine.evaluate(before, transAll, afterBoth).status)

        // Only package matches -> FAILED
        val afterPkgOnly = ui(button(), packageName = "com.target")
        assertEquals(EvidenceEvaluationStatus.FAILED, engine.evaluate(before, transAll, afterPkgOnly).status)

        // ANY_SUFFICIENT: One match is enough
        val transAny = ExpectedTransition(
            expectedEvidence = listOf(req1, req2),
            evidenceOperator = EvidenceOperator.ANY_SUFFICIENT
        )
        assertEquals(EvidenceEvaluationStatus.VERIFIED, engine.evaluate(before, transAny, afterPkgOnly).status)

        // Neither matches -> FAILED
        val afterNeither = ui(button(), packageName = "com.origin")
        assertEquals(EvidenceEvaluationStatus.FAILED, engine.evaluate(before, transAny, afterNeither).status)
    }

    // 12. Insufficient / ambiguous evidence -> UNCERTAIN -> never SUCCESS
    @Test
    fun testInsufficientEvidenceIsUncertainNeverSuccess() {
        val sel = SemanticSelector(text = "Item")
        val req = StateEvidenceRequirement(type = EvidenceType.ELEMENT_EXISTS, selector = sel)
        val trans = ExpectedTransition(expectedEvidence = listOf(req), timeoutMs = 100)

        // Two identical buttons create an ambiguous match
        val before = ui()
        val after = ui(button("a", "Item"), button("b", "Item"))

        val eval = engine.evaluate(before, trans, after)
        assertEquals(EvidenceEvaluationStatus.UNCERTAIN, eval.status)

        // Execution engine must not treat uncertain as success
        val driver = FakeDriver(ui(button("btn", "Submit")))
        driver.effect = { driver.screen = after }
        val wf = workflow(step("CLICK", SemanticSelector(text = "Submit"), transition = trans))
        val report = run { ExecutionEngine(Store(wf), driver).execute(request()) }

        assertFalse("Uncertain evidence must never become execution success", report.result.success)
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
    }

    // 13. Verification failure enters existing bounded recovery
    @Test
    fun testVerificationFailureEntersBoundedRecovery() {
        val trans = ExpectedTransition(
            expectedEvidence = listOf(
                StateEvidenceRequirement(type = EvidenceType.ELEMENT_APPEARED, selector = SemanticSelector(text = "MissingResult"))
            ),
            timeoutMs = 50
        )
        val wf = workflow(step("CLICK", SemanticSelector(text = "Submit"), transition = trans,
            recovery = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP)))

        val driver = FakeDriver(ui(button("btn", "Submit")))
        val report = run { ExecutionEngine(Store(wf), driver).execute(request()) }

        assertEquals(1, driver.actions) // Action dispatched once; failure prevents duplicating side effects
        assertFalse(report.result.success)
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
    }

    // 14. Unresolved verification eventually ASK/HANDOFF/STOP rather than blind continuation
    @Test
    fun testUnresolvedVerificationHaltsSafely() {
        val trans = ExpectedTransition(
            expectedEvidence = listOf(
                StateEvidenceRequirement(type = EvidenceType.ELEMENT_APPEARED, selector = SemanticSelector(text = "WillNotAppear"))
            ),
            timeoutMs = 50
        )
        val step1 = step("CLICK", SemanticSelector(text = "Submit"), transition = trans).copy(stepId = "first")
        val step2 = step("CLICK", SemanticSelector(text = "Second")).copy(stepId = "second")
        val wf = workflow().copy(steps = listOf(step1, step2))

        val driver = FakeDriver(ui(button("btn", "Submit"), button("btn2", "Second")))
        val report = run { ExecutionEngine(Store(wf), driver).execute(request()) }

        assertFalse(report.result.success)
        assertEquals("first", report.stoppedStepId)
        assertEquals(0, report.result.stepsCompleted)
        assertEquals(1, driver.actions) // step 2 never executed
    }

    // 15. Safety handoff still prevents any subsequent action
    @Test
    fun testSafetyHandoffPreventsSubsequentAction() {
        val gate = SafetyGate()
        val bound = SlotBinder.bind(workflow(), emptyMap()).steps.single()

        gate.block("Manual security handoff required.")
        val result = gate.dispatch(SafetyBoundary(), ui(button()), bound) { true }

        assertFalse(result.attempted)
        assertFalse(result.accepted)
        assertTrue(result.reason.contains("handoff"))
    }

    // 16. Counter change evidence (e.g. quantity adjustment)
    @Test
    fun testCounterChangeEvidence() {
        val counterSel = SemanticSelector(role = "TextView", resourceId = "id/counter")
        val req = StateEvidenceRequirement(
            type = EvidenceType.COUNTER_CHANGE,
            selector = counterSel,
            expectedValue = "5",
            previousValue = "4",
            counterDelta = 1
        )
        val trans = ExpectedTransition(expectedEvidence = listOf(req))

        val before = ui(UiElement(elementId = "c", role = "TextView", resourceId = "id/counter", text = "4"))
        val afterOk = ui(UiElement(elementId = "c", role = "TextView", resourceId = "id/counter", text = "5"))
        val afterBad = ui(UiElement(elementId = "c", role = "TextView", resourceId = "id/counter", text = "6"))

        assertEquals(EvidenceEvaluationStatus.VERIFIED, engine.evaluate(before, trans, afterOk).status)
        assertEquals(EvidenceEvaluationStatus.FAILED, engine.evaluate(before, trans, afterBad).status)
    }

    // 17. Existing workflows with legacy ExpectedTransition remain compatible
    @Test
    fun testLegacyExpectedTransitionRemainsCompatible() {
        val legacy = ExpectedTransition(
            expectedPackage = "com.legacy.app",
            expectedElementAppeared = SemanticSelector(text = "AppearedLegacy"),
            expectedElementDisappeared = SemanticSelector(text = "DisappearedLegacy"),
            transitionType = "UI_STATE_CHANGE"
        )
        val effective = legacy.effectiveEvidence()

        assertEquals(3, effective.size)
        assertEquals(EvidenceType.EXPECTED_PACKAGE, effective[0].type)
        assertEquals("com.legacy.app", effective[0].expectedPackage)
        assertEquals(EvidenceType.ELEMENT_APPEARED, effective[1].type)
        assertEquals("AppearedLegacy", effective[1].selector?.text)
        assertEquals(EvidenceType.ELEMENT_DISAPPEARED, effective[2].type)
        assertEquals("DisappearedLegacy", effective[2].selector?.text)

        // Generic fallback when no explicit elements are given
        val genericLegacy = ExpectedTransition(transitionType = "STATE_CHANGE")
        val genericEff = genericLegacy.effectiveEvidence()
        assertEquals(1, genericEff.size)
        assertEquals(EvidenceType.GENERIC_STATE_CHANGE, genericEff.first().type)
    }

    // 18. Serialization/deserialization round-trip of changed contracts
    @Test
    fun testContractSerializationRoundTrip() {
        val transition = ExpectedTransition(
            schemaVersion = "1.0",
            fromState = "state_1",
            toState = "state_2",
            transitionType = "STATE_CHANGE",
            expectedPackage = "com.app",
            expectedEvidence = listOf(
                StateEvidenceRequirement(
                    schemaVersion = "1.0",
                    type = EvidenceType.TEXT_EQUALS,
                    selector = SemanticSelector(role = "EditText", resourceId = "id/input"),
                    expectedValue = "Pizza",
                    description = "Field contains Pizza"
                ),
                StateEvidenceRequirement(
                    schemaVersion = "1.0",
                    type = EvidenceType.CHECKED_STATE,
                    selector = SemanticSelector(role = "CheckBox", resourceId = "id/opt"),
                    expectedChecked = true,
                    description = "Option checked"
                )
            ),
            evidenceOperator = EvidenceOperator.ALL_REQUIRED,
            timeoutMs = 4000L
        )

        val json = jsonFormatter.encodeToString(ExpectedTransition.serializer(), transition)
        val decoded = jsonFormatter.decodeFromString(ExpectedTransition.serializer(), json)

        assertEquals("1.0", decoded.schemaVersion)
        assertEquals("state_1", decoded.fromState)
        assertEquals("state_2", decoded.toState)
        assertEquals(2, decoded.expectedEvidence.size)
        assertEquals(EvidenceType.TEXT_EQUALS, decoded.expectedEvidence[0].type)
        assertEquals("Pizza", decoded.expectedEvidence[0].expectedValue)
        assertEquals(EvidenceType.CHECKED_STATE, decoded.expectedEvidence[1].type)
        assertEquals(true, decoded.expectedEvidence[1].expectedChecked)
        assertEquals(EvidenceOperator.ALL_REQUIRED, decoded.evidenceOperator)
        assertEquals(4000L, decoded.timeoutMs)

        // Test decoding legacy JSON that lacks expectedEvidence and evidenceOperator fields
        val legacyJson = """
            {
                "schemaVersion": "1.0",
                "fromState": "s0",
                "toState": "s1",
                "transitionType": "STATE_CHANGE",
                "timeoutMs": 3000
            }
        """.trimIndent()
        val decodedLegacy = jsonFormatter.decodeFromString(ExpectedTransition.serializer(), legacyJson)
        assertEquals(emptyList<StateEvidenceRequirement>(), decodedLegacy.expectedEvidence)
        assertEquals(EvidenceOperator.ALL_REQUIRED, decodedLegacy.evidenceOperator)
        assertEquals(3000L, decodedLegacy.timeoutMs)
    }

    // 19. Trace diagnostics preserve evidence explanation without leaking sensitive text
    @Test
    fun testTraceDiagnosticsPreserveEvidenceWithoutLeakingSecrets() {
        val secretSelector = SemanticSelector(role = "EditText", resourceId = "id/password_field")
        val wf = workflow(
            step("INPUT_TEXT", secretSelector).copy(parameters = mapOf("input_literal" to "SecretPassword123"))
        )

        val driver = FakeDriver(ui(UiElement(elementId = "pwd", role = "EditText", resourceId = "id/password_field", text = "SecretPassword123", isEditable = true)))
        val report = run { ExecutionEngine(Store(wf), driver).execute(request()) }

        // Must not leak secret in trace or diagnostics string
        assertFalse(report.trace.toString().contains("SecretPassword123"))
        assertFalse(report.diagnostics.toString().contains("SecretPassword123"))
    }
}
