package com.chockXlate.teachablevoice.adversarial

import com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter
import com.chockXlate.teachablevoice.command.interpretation.CommandUnderstandingResult
import com.chockXlate.teachablevoice.command.matching.SkillCandidateMatch
import com.chockXlate.teachablevoice.command.matching.SkillMatchResult
import com.chockXlate.teachablevoice.command.matching.SkillMatchStatus
import com.chockXlate.teachablevoice.command.matching.SkillMatcher
import com.chockXlate.teachablevoice.command.request.ExecutionRequestBuilder
import com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus
import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.filter.DemonstrationFilterClassification
import com.chockXlate.teachablevoice.contract.filter.FilteredDemonstrationResult
import com.chockXlate.teachablevoice.contract.filter.FilteredTraceEvent
import com.chockXlate.teachablevoice.contract.provenance.DemonstrationProvenanceGraph
import com.chockXlate.teachablevoice.contract.provenance.StepProvenanceLink
import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.learning.actions.SemanticAction
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.learning.actions.SemanticActionType
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationAlignment
import com.chockXlate.teachablevoice.learning.inference.AlignedSlotInference
import com.chockXlate.teachablevoice.learning.inference.InferenceResult
import com.chockXlate.teachablevoice.learning.inference.SlotInferenceStatus
import com.chockXlate.teachablevoice.learning.intent.Intent
import com.chockXlate.teachablevoice.learning.intent.IntentExtractionResult
import com.chockXlate.teachablevoice.learning.slots.SlotExtractionResult
import com.chockXlate.teachablevoice.learning.subtask.WorkflowSubtaskSegmenter
import com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer
import com.chockXlate.teachablevoice.learning.targets.SemanticTarget
import com.chockXlate.teachablevoice.runtime.ExecutionEngine
import com.chockXlate.teachablevoice.runtime.evaluation.IndependentOutcomeEvaluator
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.recovery.RecoveryAction
import com.chockXlate.teachablevoice.runtime.recovery.RecoveryController
import com.chockXlate.teachablevoice.runtime.recovery.RecoveryDecision
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.slots.SlotBinder
import com.chockXlate.teachablevoice.runtime.ui.ActionOutcome
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction
import com.chockXlate.teachablevoice.runtime.ui.UiDriver
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import com.chockXlate.teachablevoice.runtime.verification.EvidenceEvaluationResult
import com.chockXlate.teachablevoice.runtime.verification.EvidenceEvaluationStatus
import com.chockXlate.teachablevoice.runtime.verification.PreconditionEvaluator
import com.chockXlate.teachablevoice.runtime.verification.TransitionVerifier
import com.chockXlate.teachablevoice.runtime.verification.VerificationResult
import com.chockXlate.teachablevoice.safety.SafetyGate
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.skill.validation.ReplayAdmission
import com.chockXlate.teachablevoice.teach.filter.DemonstrationFilter
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Section H: Final Genericity / Adversarial Evaluation Suite
 * 25 Minimum Required Scenarios verifying end-to-end architecture robustness,
 * semantic integrity, zero coordinates, strict safety gates, and genericity.
 */
class AdversarialGenericitySuiteTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
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

    private class TestUiDriver(var screen: UiObservation) : UiDriver {
        var ready = true
        val actionLog = mutableListOf<BoundStep>()
        var onExecuteAction: ((BoundStep, UiObservation) -> ActionOutcome)? = null
        var effect: (BoundStep) -> Unit = { step ->
            val updated = screen.state.allElements.map { el ->
                if (step.action == RuntimeAction.INPUT_TEXT && (el.role == step.selector.role || el.resourceId == step.selector.resourceId)) {
                    el.copy(text = step.inputText ?: "")
                } else if (step.action == RuntimeAction.CLICK && (el.role == step.selector.role || el.resourceId == step.selector.resourceId || el.text == step.selector.text)) {
                    el.copy(isSelected = true)
                } else el
            }
            val appearedSel = step.transition.expectedElementAppeared 
                ?: step.transition.expectedEvidence.find { it.type == EvidenceType.ELEMENT_APPEARED }?.selector
            val extraList = if (appearedSel != null && updated.none { it.text == appearedSel.text && it.role == appearedSel.role }) {
                listOf(UiElement(
                    elementId = UUID.randomUUID().toString(),
                    role = appearedSel.role ?: "TextView",
                    text = appearedSel.text,
                    resourceId = appearedSel.resourceId
                ))
            } else emptyList()

            screen = screen.copy(
                state = screen.state.copy(
                    stateId = UUID.randomUUID().toString(),
                    allElements = updated + extraList
                )
            )
        }

        override fun isReady(): Boolean = ready

        override suspend fun observe(): UiObservation? = if (ready) screen else null

        override suspend fun awaitChange(delayMs: Long) {}

        override suspend fun execute(
            step: BoundStep,
            expectedPackage: String,
            boundary: SafetyBoundary,
            matcher: SemanticMatcher,
            gate: SafetyGate
        ): ActionOutcome {
            val before = screen
            onExecuteAction?.let {
                val outcome = it(step, before)
                actionLog.add(step)
                return outcome
            }

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
                actionLog.add(step)
                effect(step)
                true
            }
            return ActionOutcome(result.attempted, result.accepted, result.reason, before)
        }
    }

    private fun ui(pkg: String, vararg elements: UiElement, credField: Boolean = false): UiObservation {
        val normalized = elements.map { el ->
            if (el.role == "Button" && !el.isClickable) el.copy(isClickable = true)
            else if (el.role == "EditText" && !el.isEditable) el.copy(isEditable = true)
            else el
        }
        return UiObservation(
            state = UiState(
                stateId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                appContext = pkg,
                allElements = normalized
            ),
            credentialFieldPresent = credField
        )
    }

    // ------------------------------------------------------------------------
    // Scenario 1: One-shot learned workflow exact replay
    // ------------------------------------------------------------------------
    @Test
    fun test01_oneShotLearnedWorkflowExactReplay() = runSuspend {
        val app = "com.generic.shop"
        val step1 = WorkflowStep(
            stepId = "step_search",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Search", resourceId = "$app:id/btn_search")
        )
        val step2 = WorkflowStep(
            stepId = "step_type",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "$app:id/input_query"),
            parameters = mapOf("input_literal" to "shoes")
        )
        val workflow = Workflow(
            skillId = "skill_search",
            name = "Search Items",
            intent = "search_information",
            appContext = app,
            steps = listOf(step1, step2),
            subtasks = listOf(WorkflowSubtask(subtaskId = "sub_search", label = "SEARCH", stepIds = listOf("step_search", "step_type")))
        )

        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        val driver = TestUiDriver(ui(
            app,
            UiElement(elementId = "e1", role = "Button", text = "Search", resourceId = "$app:id/btn_search"),
            UiElement(elementId = "e2", role = "EditText", text = "", resourceId = "$app:id/input_query")
        ))

        val engine = ExecutionEngine(repo, driver)
        val request = ExecutionRequest(
            executionId = "exec_01",
            skillId = "skill_search"
        )

        val report = engine.execute(request)
        assertTrue("Failed: state=${report.result.finalState}, err=${report.result.errorMessage}, diag=${report.diagnostics}", report.result.success)
        assertEquals(ExecutionState.COMPLETED, report.result.finalState)
        assertEquals(2, driver.actionLog.size)
    }

    // ------------------------------------------------------------------------
    // Scenario 2: Paraphrased command matches and executes
    // ------------------------------------------------------------------------
    @Test
    fun test02_paraphrasedCommandMatchesAndExecutes() = runSuspend {
        val app = "com.generic.catalog"
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Catalog", resourceId = "$app:id/cat")
        )
        val workflow = Workflow(
            skillId = "skill_browse",
            name = "browse items",
            intent = "search_information",
            appContext = app,
            steps = listOf(step),
            subtasks = listOf(WorkflowSubtask(subtaskId = "sub_browse", label = "BROWSE", stepIds = listOf("s1")))
        )
        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        // Paraphrased natural-language command
        val understanding = CommandInterpreter.understandCommand("search for items in catalog")
        val matcher = SkillMatcher(repo)
        val matchResult = matcher.match(understanding)
        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)
        assertEquals("skill_browse", matchResult.selectedSkillId)

        val reqResult = ExecutionRequestBuilder.build(understanding, matchResult, repo)
        assertEquals(ExecutionRequestStatus.READY_FOR_PERSON_2, reqResult.status)

        val driver = TestUiDriver(ui(app, UiElement(elementId = "e1", role = "Button", text = "Catalog", resourceId = "$app:id/cat")))
        val engine = ExecutionEngine(repo, driver)
        val report = engine.execute(reqResult.executionRequest!!)
        assertTrue(report.result.success)
    }

    // ------------------------------------------------------------------------
    // Scenario 3: Changed text/item slot binds and executes
    // ------------------------------------------------------------------------
    @Test
    fun test03_changedTextItemSlot() = runSuspend {
        val app = "com.generic.shop"
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "$app:id/search_query"),
            parameters = mapOf("input_parameter" to "item")
        )
        val workflow = Workflow(
            skillId = "skill_slot",
            name = "Search",
            intent = "search_information",
            appContext = app,
            slots = listOf(WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "shoes")),
            steps = listOf(step)
        )
        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        val driver = TestUiDriver(ui(app, UiElement(elementId = "e1", role = "EditText", text = "", resourceId = "$app:id/search_query")))
        val engine = ExecutionEngine(repo, driver)
        // Replay with changed slot value: "boots" instead of "shoes"
        val request = ExecutionRequest(
            executionId = "exec_slot",
            skillId = "skill_slot",
            boundSlots = mapOf("item" to "boots")
        )

        val report = engine.execute(request)
        assertTrue(report.result.success)
        assertEquals("boots", driver.actionLog.first().inputText)
    }

    // ------------------------------------------------------------------------
    // Scenario 4: Changed quantity bounded expansion executes expected clicks
    // ------------------------------------------------------------------------
    @Test
    fun test04_changedQuantityExpandsAndExecutes() = runSuspend {
        val app = "com.generic.shop"
        val counterSelector = SemanticSelector(role = "TextView", text = "1", resourceId = "$app:id/counter")
        val step = WorkflowStep(
            stepId = "step_inc",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "+", resourceId = "$app:id/btn_add"),
            parameters = mapOf(
                "repeat_slot" to "quantity",
                "quantity_start" to "1",
                "quantity_selector" to json.encodeToString(SemanticSelector.serializer(), counterSelector)
            )
        )
        val workflow = Workflow(
            skillId = "skill_qty",
            name = "Add Items",
            intent = "order_food",
            appContext = app,
            slots = listOf(WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "1")),
            steps = listOf(step)
        )
        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        var currentCount = 1
        val driver = TestUiDriver(ui(
            app,
            UiElement(elementId = "e1", role = "Button", text = "+", resourceId = "$app:id/btn_add"),
            UiElement(elementId = "e2", role = "TextView", text = "1", resourceId = "$app:id/counter")
        ))
        driver.onExecuteAction = { _, before ->
            currentCount++
            driver.screen = ui(
                app,
                UiElement(elementId = "e1", role = "Button", text = "+", resourceId = "$app:id/btn_add"),
                UiElement(elementId = "e2", role = "TextView", text = currentCount.toString(), resourceId = "$app:id/counter")
            )
            ActionOutcome(attempted = true, accepted = true, reason = "Success", before = before)
        }

        val engine = ExecutionEngine(repo, driver)
        // Request quantity = 3 (expands from 1 to 3 -> 2 clicks)
        val request = ExecutionRequest(
            executionId = "exec_qty",
            skillId = "skill_qty",
            boundSlots = mapOf("quantity" to "3")
        )

        val report = engine.execute(request)
        assertTrue("Failed: state=${report.result.finalState}, err=${report.result.errorMessage}, diag=${report.diagnostics}", report.result.success)
        assertEquals(2, driver.actionLog.size)
        assertEquals(3, currentCount)
    }

    // ------------------------------------------------------------------------
    // Scenario 5: Changed address/text slot where supported
    // ------------------------------------------------------------------------
    @Test
    fun test05_changedAddressTextSlot() = runSuspend {
        val app = "com.generic.delivery"
        val step = WorkflowStep(
            stepId = "s_addr",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "$app:id/dest_input"),
            parameters = mapOf("input_parameter" to "delivery_address")
        )
        val workflow = Workflow(
            skillId = "skill_address",
            name = "Set Address",
            intent = "order_item",
            appContext = app,
            slots = listOf(WorkflowSlot(name = "delivery_address", type = SlotType.TEXT, required = true, exampleValue = "10 Downing St")),
            steps = listOf(step)
        )
        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        val driver = TestUiDriver(ui(app, UiElement(elementId = "e1", role = "EditText", text = "", resourceId = "$app:id/dest_input")))
        val engine = ExecutionEngine(repo, driver)
        val request = ExecutionRequest(
            executionId = "exec_addr",
            skillId = "skill_address",
            boundSlots = mapOf("delivery_address" to "42 Wallaby Way")
        )

        val report = engine.execute(request)
        assertTrue(report.result.success)
        assertEquals("42 Wallaby Way", driver.actionLog.single().inputText)
    }

    // ------------------------------------------------------------------------
    // Scenario 6: Moderate selector/UI variation matches stably
    // ------------------------------------------------------------------------
    @Test
    fun test06_moderateSelectorUiVariation() = runSuspend {
        val app = "com.generic.shop"
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            // Learned selector had contentDescription "Submit Order"
            semanticSelector = SemanticSelector(role = "Button", contentDescription = "Submit Order", resourceId = "$app:id/btn_action")
        )
        val workflow = Workflow(skillId = "skill_var", name = "Submit", intent = "search_information", appContext = app, steps = listOf(step))
        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        // Live screen has slightly different contentDescription "Submit Order Now" but same resource ID and role
        val driver = TestUiDriver(ui(app, UiElement(elementId = "e1", role = "Button", contentDescription = "Submit Order Now", resourceId = "$app:id/btn_action")))
        val engine = ExecutionEngine(repo, driver)
        val report = engine.execute(ExecutionRequest(executionId = "exec_var", skillId = "skill_var"))
        assertTrue(report.result.success)
        assertEquals(1, driver.actionLog.size)
    }

    // ------------------------------------------------------------------------
    // Scenario 7: Unrelated UI mutation does not prove success
    // ------------------------------------------------------------------------
    @Test
    fun test07_unrelatedUiMutationDoesNotProveSuccess() = runSuspend {
        val app = "com.generic.shop"
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Pay", resourceId = "$app:id/pay"),
            expectedTransition = ExpectedTransition(
                expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.ELEMENT_APPEARED, selector = SemanticSelector(role = "TextView", text = "Order Confirmed")))
            )
        )
        val workflow = Workflow(skillId = "skill_unrelated", name = "Pay", intent = "order_item", appContext = app, steps = listOf(step))
        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        val driver = TestUiDriver(ui(app, UiElement(elementId = "e1", role = "Button", text = "Pay", resourceId = "$app:id/pay")))
        driver.onExecuteAction = { _, before ->
            // Screen mutates by showing an unrelated ad banner, NOT "Order Confirmed"
            driver.screen = ui(
                app,
                UiElement(elementId = "e1", role = "Button", text = "Pay", resourceId = "$app:id/pay"),
                UiElement(elementId = "e_ad", role = "TextView", text = "Sponsored Advertisement 50% Off")
            )
            ActionOutcome(attempted = true, accepted = true, reason = "Unrelated change", before = before)
        }

        val engine = ExecutionEngine(repo, driver)
        val report = engine.execute(ExecutionRequest(executionId = "exec_unrelated", skillId = "skill_unrelated"))
        // Must NOT report success when required evidence is missing
        assertFalse(report.result.success)
        assertNotEquals(ExecutionState.COMPLETED, report.result.finalState)
    }

    // ------------------------------------------------------------------------
    // Scenario 8: Navigation noise excluded from executable workflow
    // ------------------------------------------------------------------------
    @Test
    fun test08_navigationNoiseExcludedFromExecutableWorkflow() {
        val app = "com.generic.notes"
        val launcherAction = ActionEvent(
            actionId = "a_launcher",
            timestamp = 1000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(
                role = "TextView",
                text = "Notes App",
                resourceId = "com.android.launcher3:id/icon"
            )
        )
        val targetAction1 = ActionEvent(
            actionId = "a_task1",
            timestamp = 2000L,
            actionType = "CLICK",
            semanticSelector = SemanticSelector(
                role = "Button",
                text = "New Note",
                resourceId = "$app:id/create"
            )
        )
        val targetAction2 = ActionEvent(
            actionId = "a_task2",
            timestamp = 3000L,
            actionType = "INPUT_TEXT",
            semanticSelector = SemanticSelector(
                role = "EditText",
                resourceId = "$app:id/title"
            ),
            inputData = "Meeting Notes"
        )

        val stateEv1 = StateEvent(
            stateEventId = "st1",
            timestamp = 2050L,
            causeActionId = "a_task1",
            beforeState = UiState(stateId = "s1", timestamp = 2000L, appContext = app),
            afterState = UiState(stateId = "s2", timestamp = 2050L, appContext = app)
        )
        val stateEv2 = StateEvent(
            stateEventId = "st2",
            timestamp = 3050L,
            causeActionId = "a_task2",
            beforeState = UiState(stateId = "s2", timestamp = 3000L, appContext = app),
            afterState = UiState(stateId = "s3", timestamp = 3050L, appContext = app)
        )

        val rawTrace = DemonstrationTrace(
            traceId = "demo_noise",
            timestamp = 1000L,
            appContext = app,
            traceEvents = listOf(
                TraceEvent.Action("a_launcher", 1000L, launcherAction),
                TraceEvent.Action("a_task1", 2000L, targetAction1),
                TraceEvent.State("st1", 2050L, stateEv1),
                TraceEvent.Action("a_task2", 3000L, targetAction2),
                TraceEvent.State("st2", 3050L, stateEv2)
            ),
            userActions = listOf(launcherAction, targetAction1, targetAction2),
            stateEvents = listOf(stateEv1, stateEv2)
        )

        val filtered = DemonstrationFilter.filter(rawTrace)
        val extractedActions = SemanticActionExtractor.extract(rawTrace, filtered)

        // Synthesize workflow with filtered actions
        val intentResult = IntentExtractionResult(
            intent = Intent(intentId = "int_1", canonicalName = "search_information", confidence = 1.0, confidenceLevel = "HIGH"),
            confidence = 1.0,
            evidenceSummary = "summary"
        )
        val synth = WorkflowSynthesizer.synthesize(
            intentResult = intentResult,
            semanticActions = extractedActions,
            slotResult = SlotExtractionResult(intentName = "search_information"),
            alignmentResult = DemonstrationAlignment.align(emptyList()),
            inferenceResult = InferenceResult(intentName = "search_information", demonstrationsAnalyzedCount = 1, slotInferences = emptyList()),
            trace = rawTrace
        )

        val workflow = synth.workflow!!
        // Zero launcher steps in the executable workflow
        assertEquals(2, workflow.steps.size)
        assertTrue(workflow.steps.all { it.semanticSelector.resourceId?.startsWith(app) == true })
        assertFalse(workflow.steps.any { it.semanticSelector.text == "Notes App" })
    }

    // ------------------------------------------------------------------------
    // Scenario 9: Unknown command rejected
    // ------------------------------------------------------------------------
    @Test
    fun test09_unknownCommandRejected() {
        val app = "com.generic.shop"
        val repo = LocalSkillRepository()
        repo.saveWorkflow(Workflow(skillId = "skill_1", name = "Shop", intent = "search_information", appContext = app))

        val understanding = CommandInterpreter.understandCommand("book an interstellar rocket flight to Neptune")
        val matcher = SkillMatcher(repo)
        val matchResult = matcher.match(understanding)
        assertEquals(SkillMatchStatus.UNKNOWN, matchResult.status)

        val reqResult = ExecutionRequestBuilder.build(understanding, matchResult, repo)
        assertEquals(ExecutionRequestStatus.REJECTED_UNKNOWN_MATCH, reqResult.status)
        assertNull(reqResult.executionRequest)
    }

    // ------------------------------------------------------------------------
    // Scenario 10: Ambiguous command asks clarification
    // ------------------------------------------------------------------------
    @Test
    fun test10_ambiguousCommandAsksClarification() {
        val app = "com.generic.food"
        val repo = LocalSkillRepository()
        // Two workflows with same intent and score
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Order", resourceId = "$app:id/order")
        )
        repo.saveWorkflow(Workflow(skillId = "skill_pizza", name = "order food", intent = "order_food", appContext = app, steps = listOf(step)))
        repo.saveWorkflow(Workflow(skillId = "skill_burger", name = "order food", intent = "order_food", appContext = app, steps = listOf(step.copy(stepId = "s2"))))

        val understanding = CommandInterpreter.understandCommand("order food")
        val matcher = SkillMatcher(repo)
        val matchResult = matcher.match(understanding)
        assertEquals(SkillMatchStatus.AMBIGUOUS, matchResult.status)

        val reqResult = ExecutionRequestBuilder.build(understanding, matchResult, repo)
        assertEquals(ExecutionRequestStatus.REJECTED_AMBIGUOUS_MATCH, reqResult.status)
        assertNull(reqResult.executionRequest)
    }

    // ------------------------------------------------------------------------
    // Scenario 11: Missing required slot asks clarification
    // ------------------------------------------------------------------------
    @Test
    fun test11_missingRequiredSlotAsksClarification() = runSuspend {
        val app = "com.generic.shop"
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = "$app:id/search"),
            parameters = mapOf("input_parameter" to "query")
        )
        val workflow = Workflow(
            skillId = "skill_query",
            name = "Search",
            intent = "search_information",
            appContext = app,
            slots = listOf(WorkflowSlot(name = "query", type = SlotType.TEXT, required = true, exampleValue = "default")),
            steps = listOf(step)
        )
        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        val driver = TestUiDriver(ui(app, UiElement(elementId = "e1", role = "EditText", text = "", resourceId = "$app:id/search")))
        val engine = ExecutionEngine(repo, driver)
        // Request without required "query" slot
        val request = ExecutionRequest(executionId = "exec_missing", skillId = "skill_query", boundSlots = emptyMap())

        val report = engine.execute(request)
        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertNotNull(report.result.clarificationRequest)
        assertEquals("query", report.result.clarificationRequest?.requiredSlot)
    }

    // ------------------------------------------------------------------------
    // Scenario 12: Ambiguous runtime target asks/handoffs
    // ------------------------------------------------------------------------
    @Test
    fun test12_ambiguousRuntimeTargetTriggersClarificationOrHandoff() = runSuspend {
        val app = "com.generic.shop"
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Choose")
        )
        val workflow = Workflow(skillId = "skill_ambig_tgt", name = "Choose", intent = "order_item", appContext = app, steps = listOf(step))
        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        // Live screen has TWO identical "Choose" buttons without unique IDs
        val driver = TestUiDriver(ui(
            app,
            UiElement(elementId = "e1", role = "Button", text = "Choose"),
            UiElement(elementId = "e2", role = "Button", text = "Choose")
        ))
        val engine = ExecutionEngine(repo, driver)
        val report = engine.execute(ExecutionRequest(executionId = "exec_ambig_ui", skillId = "skill_ambig_tgt"))

        // Must NOT blind-tap! Pauses for clarification or handoff
        assertTrue(report.result.finalState == ExecutionState.WAITING_FOR_USER || report.result.finalState == ExecutionState.PAUSED_FOR_HANDOFF)
        assertEquals(0, driver.actionLog.size)
    }

    // ------------------------------------------------------------------------
    // Scenario 13: Safety-sensitive UI hard locks execution
    // ------------------------------------------------------------------------
    @Test
    fun test13_safetySensitiveUiHardLocksExecution() = runSuspend {
        val app = "com.generic.bank"
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Continue", resourceId = "$app:id/btn_continue")
        )
        val workflow = Workflow(skillId = "skill_bank", name = "Transfer", intent = "search_information", appContext = app, steps = listOf(step))
        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        // Screen has credential field present
        val driver = TestUiDriver(ui(
            app,
            UiElement(elementId = "e1", role = "Button", text = "Continue", resourceId = "$app:id/btn_continue"),
            credField = true
        ))
        val engine = ExecutionEngine(repo, driver)
        val report = engine.execute(ExecutionRequest(executionId = "exec_bank", skillId = "skill_bank"))

        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertEquals(0, driver.actionLog.size)
    }

    // ------------------------------------------------------------------------
    // Scenario 14: No action after safety handoff
    // ------------------------------------------------------------------------
    @Test
    fun test14_noActionAfterSafetyHandoff() = runSuspend {
        val app = "com.generic.shop"
        val boundary = SafetyBoundary(requiresExplicitUserConfirmation = true)
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Send", resourceId = "$app:id/send")
        )
        val workflow = Workflow(
            skillId = "skill_sec",
            name = "Sec",
            intent = "search_information",
            appContext = app,
            safetyBoundary = boundary,
            steps = listOf(step)
        )
        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        val driver = TestUiDriver(ui(app, UiElement(elementId = "e1", role = "Button", text = "Send", resourceId = "$app:id/send")))
        val engine = ExecutionEngine(repo, driver)
        val report = engine.execute(ExecutionRequest(executionId = "exec_sec", skillId = "skill_sec"))

        // Paused for safety handoff; zero actions dispatched
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertEquals(0, driver.actionLog.size)
    }

    // ------------------------------------------------------------------------
    // Scenario 15: Uncertain side effect is never automatically repeated
    // ------------------------------------------------------------------------
    @Test
    fun test15_uncertainSideEffectNeverAutomaticallyRepeated() {
        val controller = RecoveryController()
        val decision = controller.decideSubtaskRecovery(
            policy = RecoveryPolicy(strategy = RecoveryStrategy.RETRY_STEP, maxRetries = 2),
            retries = 0,
            actionAttempted = true // Action was attempted!
        )
        // Invariant: Never retry when action was attempted with uncertain outcome!
        assertNotEquals(RecoveryAction.RETRY, decision.action)
        assertNotEquals(RecoveryAction.RETRY_NON_SIDE_EFFECTING_STEP_IF_PROVEN_SAFE, decision.action)
        assertEquals(RecoveryAction.HANDOFF, decision.action)
    }

    // ------------------------------------------------------------------------
    // Scenario 16: Subtask progress survives clarification
    // ------------------------------------------------------------------------
    @Test
    fun test16_subtaskProgressSurvivesClarification() = runSuspend {
        val app = "com.generic.shop"
        val step1 = WorkflowStep(stepId = "s1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "Search", resourceId = "$app:id/search"))
        val step2 = WorkflowStep(stepId = "s2", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "Select"))

        val sub1 = WorkflowSubtask(subtaskId = "sub_search", label = "SEARCH", stepIds = listOf("s1"))
        val sub2 = WorkflowSubtask(subtaskId = "sub_select", label = "SELECT", stepIds = listOf("s2"))

        val workflow = Workflow(
            skillId = "skill_prog",
            name = "Prog",
            intent = "search_information",
            appContext = app,
            steps = listOf(step1, step2),
            subtasks = listOf(sub1, sub2)
        )
        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        // Screen 1: search button present
        val driver = TestUiDriver(ui(app, UiElement(elementId = "e1", role = "Button", text = "Search", resourceId = "$app:id/search")))
        driver.onExecuteAction = { step, before ->
            if (step.source.stepId == "s1") {
                // Mutate to Screen 2: ambiguous "Select" buttons
                driver.screen = ui(
                    app,
                    UiElement(elementId = "e_sel_1", role = "Button", text = "Select"),
                    UiElement(elementId = "e_sel_2", role = "Button", text = "Select")
                )
                ActionOutcome(attempted = true, accepted = true, reason = "Success", before = before)
            } else {
                ActionOutcome(attempted = true, accepted = true, reason = "Success", before = before)
            }
        }

        val engine = ExecutionEngine(repo, driver)
        val report = engine.execute(ExecutionRequest(executionId = "exec_prog", skillId = "skill_prog"))

        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertNotNull(report.result.progress)
        val progress = report.result.progress!!
        assertEquals(listOf("sub_search"), progress.completedSubtaskIds)
        assertEquals(ProgressStatus.COMPLETED, progress.subtasks[0].status)
        assertEquals(ProgressStatus.WAITING_FOR_USER, progress.subtasks[1].status)
    }

    // ------------------------------------------------------------------------
    // Scenario 17: Completed subtask is not replayed on resume
    // ------------------------------------------------------------------------
    @Test
    fun test17_completedSubtaskIsNotReplayed() = runSuspend {
        val app = "com.generic.shop"
        val step1 = WorkflowStep(stepId = "s1", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "Search", resourceId = "$app:id/search"))
        val step2 = WorkflowStep(stepId = "s2", semanticAction = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "$app:id/input"), parameters = mapOf("input_parameter" to "q"))

        val sub1 = WorkflowSubtask(subtaskId = "sub1", label = "STEP1", stepIds = listOf("s1"))
        val sub2 = WorkflowSubtask(subtaskId = "sub2", label = "STEP2", stepIds = listOf("s2"))

        val workflow = Workflow(
            skillId = "skill_resume",
            name = "Resume",
            intent = "search_information",
            appContext = app,
            slots = listOf(WorkflowSlot(name = "q", type = SlotType.TEXT, required = true, exampleValue = "foo")),
            steps = listOf(step1, step2),
            subtasks = listOf(sub1, sub2)
        )
        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        val driver = TestUiDriver(ui(
            app,
            UiElement(elementId = "e1", role = "Button", text = "Search", resourceId = "$app:id/search"),
            UiElement(elementId = "e2", role = "EditText", text = "", resourceId = "$app:id/input")
        ))

        val engine = ExecutionEngine(repo, driver)
        // Request missing "q" slot pauses at start of subtask 2 or execution
        val report = engine.execute(ExecutionRequest(executionId = "exec_resume_test", skillId = "skill_resume"))
        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)

        // Resume with clarification response providing "q"
        val resumeReport = engine.resume(ClarificationResponse(
            executionId = "exec_resume_test",
            providedSlotValue = "resolved_value"
        ))
        assertTrue(resumeReport.result.success)
        assertEquals(ExecutionState.COMPLETED, resumeReport.result.finalState)
    }

    // ------------------------------------------------------------------------
    // Scenario 18: Second independently learned workflow
    // ------------------------------------------------------------------------
    @Test
    fun test18_secondIndependentlyLearnedWorkflow() = runSuspend {
        val app1 = "com.generic.shop"
        val app2 = "com.generic.notes"
        val wf1 = Workflow(
            skillId = "skill_shop",
            name = "Shop",
            intent = "search_information",
            appContext = app1,
            steps = listOf(WorkflowStep(stepId = "s_shop", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "Cart", resourceId = "$app1:id/cart")))
        )
        val wf2 = Workflow(
            skillId = "skill_notes",
            name = "Notes",
            intent = "search_information",
            appContext = app2,
            steps = listOf(WorkflowStep(stepId = "s_notes", semanticAction = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "New", resourceId = "$app2:id/new")))
        )
        val repo = LocalSkillRepository()
        assertTrue(repo.saveWorkflow(wf1))
        assertTrue(repo.saveWorkflow(wf2))

        val driver = TestUiDriver(ui(app2, UiElement(elementId = "e1", role = "Button", text = "New", resourceId = "$app2:id/new")))
        val engine = ExecutionEngine(repo, driver)
        val report = engine.execute(ExecutionRequest(executionId = "exec_notes", skillId = "skill_notes"))

        assertTrue(report.result.success)
        assertEquals("s_notes", driver.actionLog.single().source.stepId)
    }

    // ------------------------------------------------------------------------
    // Scenario 19: Invalid/unexecutable learned workflow rejected
    // ------------------------------------------------------------------------
    @Test
    fun test19_invalidUnexecutableLearnedWorkflowRejected() {
        val invalidWf = Workflow(
            skillId = "skill_bad",
            name = "Bad",
            intent = "search_information",
            appContext = "com.generic.bad",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1",
                    semanticAction = "UNSUPPORTED_ACTION_TYPE",
                    semanticSelector = SemanticSelector(role = "Unknown")
                )
            )
        )
        val admission = ReplayAdmission.validate(invalidWf)
        assertFalse(admission.isStoreable)

        val repo = LocalSkillRepository()
        assertFalse(repo.saveReplayableWorkflow(invalidWf))
    }

    // ------------------------------------------------------------------------
    // Scenario 20: Provenance exists for every executable learned step
    // ------------------------------------------------------------------------
    @Test
    fun test20_provenanceExistsForEveryExecutableLearnedStep() {
        val app = "com.generic.shop"
        val rawTrace = DemonstrationTrace(
            traceId = "demo_prov_full",
            timestamp = 1000L,
            appContext = app,
            userActions = listOf(
                ActionEvent(actionId = "act_1", timestamp = 1000L, actionType = "CLICK",
                    semanticSelector = SemanticSelector(role = "Button", text = "Browse", resourceId = "$app:id/btn_browse")),
                ActionEvent(actionId = "act_2", timestamp = 1100L, actionType = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "$app:id/search"),
                    inputData = "Books")
            ),
            stateEvents = listOf(
                StateEvent(stateEventId = "st1", timestamp = 1050L, causeActionId = "act_1",
                    beforeState = UiState(stateId = "s0", timestamp = 1000L, appContext = app),
                    afterState = UiState(stateId = "s1", timestamp = 1050L, appContext = app)),
                StateEvent(stateEventId = "st2", timestamp = 1150L, causeActionId = "act_2",
                    beforeState = UiState(stateId = "s1", timestamp = 1050L, appContext = app),
                    afterState = UiState(stateId = "s2", timestamp = 1150L, appContext = app))
            )
        )

        val actions = SemanticActionExtractor.extract(rawTrace)
        val synth = WorkflowSynthesizer.synthesize(
            intentResult = IntentExtractionResult(
                intent = Intent(intentId = "i", canonicalName = "search_information", confidence = 1.0, confidenceLevel = "HIGH"),
                confidence = 1.0,
                evidenceSummary = "s"
            ),
            semanticActions = actions,
            slotResult = SlotExtractionResult(intentName = "search_information"),
            alignmentResult = DemonstrationAlignment.align(emptyList()),
            inferenceResult = InferenceResult(intentName = "search_information", demonstrationsAnalyzedCount = 1, slotInferences = emptyList()),
            trace = rawTrace
        )

        val wf = synth.workflow!!
        assertNotNull(wf.provenanceGraph)
        assertEquals(2, wf.steps.size)
        for (step in wf.steps) {
            assertNotNull("Step ${step.stepId} must have provenance link", step.provenanceLink)
            assertTrue(step.provenanceLink!!.rawActionId in listOf("act_1", "act_2"))
        }
    }

    // ------------------------------------------------------------------------
    // Scenario 21: Independent evaluator catches false-positive success
    // ------------------------------------------------------------------------
    @Test
    fun test21_independentEvaluatorCatchesFalsePositiveSuccess() {
        val app = "com.generic.shop"
        val step = WorkflowStep(
            stepId = "s1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Order", resourceId = "$app:id/order"),
            expectedTransition = ExpectedTransition(
                expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.ELEMENT_APPEARED, selector = SemanticSelector(role = "TextView", text = "Receipt")))
            )
        )
        val workflow = Workflow(skillId = "wf_fraud", name = "Fraud", intent = "order_item", appContext = app, steps = listOf(step))

        // Result claims success, but trace contains zero state events verifying the outcome!
        val falseSuccessResult = ExecutionResult(
            executionId = "exec_false",
            success = true,
            finalState = ExecutionState.COMPLETED,
            stepsCompleted = 1,
            totalSteps = 1
        )
        val traceWithoutEvidence = ExecutionTrace(
            executionId = "exec_false",
            skillId = "wf_fraud",
            startTime = 1000L,
            events = emptyList(), // No state verification in trace
            result = falseSuccessResult
        )

        val evaluation = IndependentOutcomeEvaluator.evaluate(workflow, traceWithoutEvidence, falseSuccessResult, null)
        // Evaluator must reject false positive!
        assertNotEquals(OutcomeAssessment.SUCCESS_SUPPORTED, evaluation.assessment)
        assertEquals(OutcomeAssessment.UNCERTAIN, evaluation.assessment)
    }

    // ------------------------------------------------------------------------
    // Scenario 22: Quantity safety still works
    // ------------------------------------------------------------------------
    @Test
    fun test22_quantitySafetyStillWorks() {
        val app = "com.generic.shop"
        val counterSelector = SemanticSelector(role = "TextView", text = "1", resourceId = "$app:id/counter")
        val step = WorkflowStep(
            stepId = "step_inc",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "+", resourceId = "$app:id/btn_add"),
            parameters = mapOf(
                "repeat_slot" to "quantity",
                "quantity_start" to "1",
                "quantity_selector" to json.encodeToString(SemanticSelector.serializer(), counterSelector)
            )
        )
        val workflow = Workflow(
            skillId = "skill_qty_safe",
            name = "Add Items",
            intent = "order_item",
            appContext = app,
            slots = listOf(WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "1")),
            steps = listOf(step)
        )

        // Attempt quantity = 500 (exceeds bounded limit of 20)
        val bindingResult = SlotBinder.bind(workflow, mapOf("quantity" to "500"))
        assertNotNull(bindingResult.error)
        assertTrue(bindingResult.error!!.contains("1 to 20") || bindingResult.error!!.contains("Quantity"))
    }

    // ------------------------------------------------------------------------
    // Scenario 23: Legacy workflows remain compatible
    // ------------------------------------------------------------------------
    @Test
    fun test23_legacyWorkflowsRemainCompatible() = runSuspend {
        val app = "com.generic.notes"
        // Legacy workflow without subtasks (emptyList) and without provenanceGraph (null)
        val legacyStep = WorkflowStep(
            stepId = "legacy_step_1",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Save", resourceId = "$app:id/save")
        )
        val legacyWorkflow = Workflow(
            skillId = "skill_legacy",
            name = "Save Note",
            intent = "search_information",
            appContext = app,
            steps = listOf(legacyStep),
            subtasks = emptyList(),
            provenanceGraph = null
        )
        val repo = LocalSkillRepository()
        repo.saveWorkflow(legacyWorkflow)

        val driver = TestUiDriver(ui(app, UiElement(elementId = "e1", role = "Button", text = "Save", resourceId = "$app:id/save")))
        val engine = ExecutionEngine(repo, driver)
        val report = engine.execute(ExecutionRequest(executionId = "exec_legacy", skillId = "skill_legacy"))

        assertTrue(report.result.success)
        assertEquals(ExecutionState.COMPLETED, report.result.finalState)
    }

    // ------------------------------------------------------------------------
    // Scenario 24: Serialization round trips for all new contracts
    // ------------------------------------------------------------------------
    @Test
    fun test24_serializationRoundTripsForAllNewContracts() {
        // 1. DemonstrationProvenanceGraph
        val provGraph = DemonstrationProvenanceGraph(
            workflowSkillId = "wf1",
            demonstrationTraceId = "t1",
            stepLinks = listOf(StepProvenanceLink(stepId = "s1", rawActionId = "a1", semanticActionType = "CLICK"))
        )
        val jsonProv = json.encodeToString(DemonstrationProvenanceGraph.serializer(), provGraph)
        assertEquals(provGraph, json.decodeFromString(DemonstrationProvenanceGraph.serializer(), jsonProv))

        // 2. WorkflowSubtask
        val subtask = WorkflowSubtask(subtaskId = "sub1", label = "CHECKOUT", stepIds = listOf("s1", "s2"))
        val jsonSubtask = json.encodeToString(WorkflowSubtask.serializer(), subtask)
        assertEquals(subtask, json.decodeFromString(WorkflowSubtask.serializer(), jsonSubtask))

        // 3. ExecutionProgress
        val progress = ExecutionProgress(
            executionId = "e1",
            completedSubtaskIds = listOf("sub1"),
            subtasks = listOf(SubtaskProgress(subtaskId = "sub1", label = "CHECKOUT", status = ProgressStatus.COMPLETED)),
            overallStatus = ProgressStatus.COMPLETED
        )
        val jsonProgress = json.encodeToString(ExecutionProgress.serializer(), progress)
        assertEquals(progress, json.decodeFromString(ExecutionProgress.serializer(), jsonProgress))

        // 4. ClarificationRequest
        val req = ClarificationRequest(executionId = "e1", reason = "Ambiguous target", question = "Pick one")
        val jsonReq = json.encodeToString(ClarificationRequest.serializer(), req)
        assertEquals(req, json.decodeFromString(ClarificationRequest.serializer(), jsonReq))

        // 5. ClarificationResponse
        val resp = ClarificationResponse(executionId = "e1", selectedCandidateIndex = 0)
        val jsonResp = json.encodeToString(ClarificationResponse.serializer(), resp)
        assertEquals(resp, json.decodeFromString(ClarificationResponse.serializer(), jsonResp))

        // 6. OutcomeEvaluation
        val eval = OutcomeEvaluation(assessment = OutcomeAssessment.SUCCESS_SUPPORTED, reason = "All verified")
        val jsonEval = json.encodeToString(OutcomeEvaluation.serializer(), eval)
        assertEquals(eval, json.decodeFromString(OutcomeEvaluation.serializer(), jsonEval))
    }

    // ------------------------------------------------------------------------
    // Scenario 25: No coordinate identity required for learned execution
    // ------------------------------------------------------------------------
    @Test
    fun test25_noCoordinateIdentityRequiredForLearnedExecution() = runSuspend {
        val app = "com.generic.shop"
        val step = WorkflowStep(
            stepId = "s_sem",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Details", resourceId = "$app:id/details")
        )
        val workflow = Workflow(skillId = "skill_no_coord", name = "Details", intent = "search_information", appContext = app, steps = listOf(step))
        val repo = LocalSkillRepository()
        repo.saveWorkflow(workflow)

        // UiElement with NO coordinate information (bounds = null)
        val elementWithoutBounds = UiElement(
            elementId = "e1",
            role = "Button",
            text = "Details",
            resourceId = "$app:id/details",
            bounds = null
        )
        val driver = TestUiDriver(ui(app, elementWithoutBounds))
        val engine = ExecutionEngine(repo, driver)
        val report = engine.execute(ExecutionRequest(executionId = "exec_no_coord", skillId = "skill_no_coord"))

        assertTrue("Execution failed: state=${report.result.finalState}, err=${report.result.errorMessage}, diag=${report.diagnostics}", report.result.success)
        assertEquals(ExecutionState.COMPLETED, report.result.finalState)
    }
}
