package com.chockXlate.teachablevoice.integration

import com.chockXlate.teachablevoice.command.interpretation.*
import com.chockXlate.teachablevoice.command.matching.SkillMatcher
import com.chockXlate.teachablevoice.command.request.*
import com.chockXlate.teachablevoice.contract.event.*
import com.chockXlate.teachablevoice.contract.trace.*
import com.chockXlate.teachablevoice.contract.ui.*
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.learning.actions.*
import com.chockXlate.teachablevoice.learning.alignment.*
import com.chockXlate.teachablevoice.learning.inference.*
import com.chockXlate.teachablevoice.learning.intent.*
import com.chockXlate.teachablevoice.learning.slots.*
import com.chockXlate.teachablevoice.learning.synthesis.*
import com.chockXlate.teachablevoice.runtime.*
import com.chockXlate.teachablevoice.runtime.matching.*
import com.chockXlate.teachablevoice.runtime.slots.*
import com.chockXlate.teachablevoice.runtime.ui.*
import com.chockXlate.teachablevoice.runtime.verification.*
import com.chockXlate.teachablevoice.runtime.trace.RuntimeReport
import com.chockXlate.teachablevoice.safety.SafetyGate
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.skill.validation.ReplayAdmission
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.*

class CorrectnessHardeningTest {
    private val app = "example.generic"
    private fun state(count: Int, value: String = "tea") = UiState(stateId = "s$count$value", timestamp = 1,
        appContext = app, allElements = listOf(
            UiElement(elementId = "input", role = "EditText", resourceId = "id/search", text = value, isEditable = true),
            UiElement(elementId = "counter", role = "TextView", resourceId = "id/count", text = count.toString()),
            UiElement(elementId = "button", role = "Button", contentDescription = "Increase amount", isClickable = true)))

    private fun trace(command: String = "Search tea", value: String = "tea", resource: String = "id/search",
                      quantityClicks: Int = 0, button: SemanticSelector = SemanticSelector(role = "Button", contentDescription = "Increase amount")): DemonstrationTrace {
        val voice = VoiceEvent(eventId = "v", timestamp = 0, transcript = command)
        val input = ActionEvent(actionId = "input", timestamp = 1, actionType = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", resourceId = resource, text = value), inputData = value)
        val clicks = (1..quantityClicks).map { n -> ActionEvent(actionId = "click$n", timestamp = n + 1L,
            actionType = "CLICK", semanticSelector = button) }
        return DemonstrationTrace(traceId = "trace", timestamp = 0, appContext = app,
            voiceEvents = listOf(voice), userActions = listOf(input) + clicks,
            stateEvents = clicks.mapIndexed { index, action -> StateEvent(stateEventId = "e$index", timestamp = index + 2L,
                beforeState = state(index), afterState = state(index + 1), causeActionId = action.actionId) })
    }

    private fun learn(trace: DemonstrationTrace = trace(), variables: Set<String> = setOf("item")): WorkflowSynthesisResult {
        val actions = SemanticActionExtractor.extract(trace)
        val intent = IntentExtractor.extract(trace, actions)
        val slots = SlotExtractor.extract(trace, actions, intent)
        val alignment = DemonstrationAlignment.align(listOf(DemonstrationDataset(trace.traceId, trace.traceId, intent, slots)))
        val inference = SingleDemonstrationConfirmation.confirm(ConstantVariableInference.infer(alignment), variables)
        return WorkflowSynthesizer.synthesize(intent, actions, slots, alignment, inference, trace)
    }
    private fun build(repo: LocalSkillRepository, command: String): ExecutionRequestBuildResult {
        val parsed = CommandInterpreter.understandCommand(command)
        return ExecutionRequestBuilder.build(parsed, SkillMatcher(repo).match(parsed), repo)
    }

    @Test fun conflictingVoiceAndActionCannotBecomeStoredLiteral() {
        val result = learn(trace(command = "Search coffee", value = "tea"))
        assertNull(result.workflow)
        assertFalse(result.isExecutable)
        assertEquals(SynthesisStatus.BLOCKED, result.status)
        assertFalse(ReplayAdmission.validate(result.workflow).isStoreable)
    }
    @Test fun confirmedVariableStoresAndUsesChangedCommand() {
        val result = learn()
        assertTrue(result.diagnostics.toString(), result.isExecutable)
        val repo = LocalSkillRepository()
        assertTrue(repo.saveReplayableWorkflow(result.workflow!!))
        val request = build(repo, "Find coffee").executionRequest!!
        val bound = SlotBinder.bind(result.workflow!!, request.boundSlots).steps.single()
        assertEquals("coffee", bound.inputText)
        assertNull(bound.selector.text)
        assertFalse(bound.source.parameters.containsKey("input_literal"))
    }
    @Test fun confirmedConstantStoresAndRejectsChangedCommand() {
        val result = learn(variables = emptySet())
        val repo = LocalSkillRepository()
        assertTrue(repo.saveReplayableWorkflow(result.workflow!!))
        assertNotNull(build(repo, "Search tea").executionRequest)
        assertNull(build(repo, "Search coffee").executionRequest)
        assertEquals("tea", SlotBinder.bind(result.workflow!!, emptyMap()).steps.single().inputText)
    }
    @Test fun unknownConfirmationCannotSilentlyBecomeExecutable() {
        val t = trace(); val a = SemanticActionExtractor.extract(t); val i = IntentExtractor.extract(t, a)
        val s = SlotExtractor.extract(t, a, i)
        val aligned = DemonstrationAlignment.align(listOf(DemonstrationDataset(t.traceId, t.traceId, i, s)))
        val result = WorkflowSynthesizer.synthesize(i, a, s, aligned, ConstantVariableInference.infer(aligned), t)
        assertFalse(result.isExecutable)
    }
    @Test fun teachingAndReplayShareIntentAndVoiceSlots() {
        for (command in listOf("Search pizza", "Find burgers", "Look up tea", "Order 2 tea from Cafe to Home",
            "Get me 3 coffee from Cafe to Work", "Send message to Alice", "Remind me to call",
            "Book a cab to airport", "Navigate to Park")) {
            val t = trace(command, value = "pizza")
            val actions = SemanticActionExtractor.extract(t)
            val replay = CommandInterpreter.understandCommand(command)
            val teaching = IntentExtractor.extract(t, actions)
            assertEquals(command, replay.intent.canonicalName, teaching.intent.canonicalName)
            val voiceOnly = SlotExtractor.extract(t, emptyList(), teaching)
            assertEquals(command, replay.slots.associate { it.name to it.rawValue }, voiceOnly.extractedSlots.associate { it.name to it.rawValue })
        }
    }
    @Test fun addressNumberIsNotQuantity() {
        val parsed = CommandInterpreter.understandCommand("Order tea to 123 Main Road")
        assertFalse(parsed.slots.any { it.name == "quantity" })
        assertEquals("123 Main Road", parsed.slots.single { it.name == "address" }.rawValue)
    }
    @Test fun genericInputMapsOnlyWhenCommandValueIsUnique() {
        val result = learn(trace(resource = "id/plain"))
        assertTrue(result.isExecutable)
        assertEquals("item", result.workflow!!.slots.single().name)
    }
    @Test fun unbindableGenericVariableRequiresClarification() {
        val result = learn(trace(value = "different", resource = "id/plain"), setOf("input_text"))
        assertFalse(result.isExecutable)
        assertFalse(LocalSkillRepository().saveReplayableWorkflow(result.workflow!!))
        assertTrue(result.diagnostics.any { it.contains("input_text") })
    }
    @Test fun unsupportedMessagingAndReminderSlotsCannotBeAdvertisedReplayable() {
        for ((intent, slot) in listOf("send_message" to "recipient", "create_reminder" to "task", "order_food" to "amount")) {
            val wf = learn().workflow!!.copy(intent = intent,
                slots = listOf(WorkflowSlot(name = slot, type = SlotType.TEXT, required = true)),
                steps = listOf(learn().workflow!!.steps.single().copy(semanticSelector = SemanticSelector(resourceId = "id/input", textSlot = slot))))
            assertFalse(LocalSkillRepository().saveReplayableWorkflow(wf))
            assertTrue(ReplayAdmission.problems(wf).any { it.contains(slot) })
        }
    }
    @Test fun unsupportedActionsFailReplayAdmission() {
        val wf = learn().workflow!!
        for (action in listOf("EXECUTE_INTENT", "TOGGLE", "SUBMIT", "FOCUS", "UNKNOWN")) {
            assertFalse(LocalSkillRepository().saveReplayableWorkflow(wf.copy(steps = listOf(wf.steps.single().copy(semanticAction = action)))))
        }
    }
    @Test fun unsupportedSelectorsAndParametersFailReplayAdmission() {
        val wf = learn().workflow!!; val step = wf.steps.single()
        for (bad in listOf(step.copy(semanticSelector = SemanticSelector(role = "EditText", textSlot = "item")),
            step.copy(semanticSelector = SemanticSelector(role = "Button", resourceId = "id/button", textSlot = "item")),
            step.copy(parameters = mapOf("unexpected" to "x")),
            step.copy(expectedTransition = ExpectedTransition(verification = "custom")))) {
            assertFalse(ReplayAdmission.validate(wf.copy(steps = listOf(bad))).isStoreable)
        }
    }
    @Test fun voiceOnlyDraftIsNotReplayableOrRequestable() {
        val t = trace().copy(userActions = emptyList())
        val result = learn(t)
        assertFalse(result.isExecutable)
        assertFalse(LocalSkillRepository().saveReplayableWorkflow(result.workflow!!))
        val repo = LocalSkillRepository()
        assertTrue(repo.saveWorkflow(result.workflow!!)) // Structural draft storage is not execution admission.
        assertNull(build(repo, "Search tea").executionRequest)
    }
    @Test fun unusedRequiredVariableCannotBeIgnored() {
        val wf = learn().workflow!!
        val broken = wf.copy(steps = wf.steps.map { it.copy(semanticSelector = it.semanticSelector.copy(textSlot = null), parameters = mapOf("input_literal" to "tea")) })
        assertFalse(ReplayAdmission.validate(broken).isStoreable)
        val repo = LocalSkillRepository(); assertTrue(repo.saveWorkflow(broken))
        assertNull(build(repo, "Search coffee").executionRequest)
    }

    private class Driver(var screen: UiObservation, val change: (BoundStep, UiObservation) -> UiObservation) : UiDriver {
        var actions = 0
        override fun isReady() = true
        override suspend fun observe() = screen
        override suspend fun awaitChange(delayMs: Long) = Unit
        override suspend fun execute(step: BoundStep, expectedPackage: String, boundary: SafetyBoundary, matcher: SemanticMatcher, gate: SafetyGate): ActionOutcome {
            val before = screen
            gate.check(boundary, before, step)?.let { return ActionOutcome(false, reason = it) }
            PreconditionEvaluator(matcher).evaluate(step.preconditions, before, expectedPackage, step.stateEvidence)?.let { return ActionOutcome(false, reason = it) }
            TransitionVerifier(matcher).startingStateError(step, before)?.let { return ActionOutcome(false, reason = it) }
            if (matcher.match(step.selector, before, step.action).status != MatchStatus.MATCHED) return ActionOutcome(false, reason = "No unique control")
            val dispatch = gate.dispatch(boundary, before, step) { actions++; screen = change(step, screen); true }
            return ActionOutcome(dispatch.attempted, dispatch.accepted, dispatch.reason, before)
        }
    }
    private fun execute(engine: ExecutionEngine, request: ExecutionRequest): RuntimeReport {
        var result: Result<RuntimeReport>? = null
        suspend { engine.execute(request) }.startCoroutine(object : Continuation<RuntimeReport> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(r: Result<RuntimeReport>) { result = r }
        })
        return result!!.getOrThrow()
    }
    private fun quantityWorkflow(clicks: Int = 1, button: SemanticSelector = SemanticSelector(role = "Button", contentDescription = "Increase amount")): Workflow {
        val result = learn(trace("Order $clicks tea", quantityClicks = clicks, button = button), setOf("item", "quantity"))
        assertTrue(result.diagnostics.toString(), result.isExecutable)
        return result.workflow!!
    }
    private fun quantityDriver(button: SemanticSelector = SemanticSelector(role = "Button", contentDescription = "Increase amount"), sensitiveAt: Int? = null, wrongIncrement: Boolean = false): Driver {
        var count = 0; var value = ""
        return Driver(UiObservation(state(0, value).copy(allElements = state(0, value).allElements.map {
            if (it.elementId == "button") it.copy(text = button.text, resourceId = button.resourceId, contentDescription = button.contentDescription) else it
        }))) { step, before ->
            if (step.action == RuntimeAction.INPUT_TEXT) value = step.inputText!! else count += if (wrongIncrement) 2 else 1
            val elements = before.state.allElements.map {
                when (it.elementId) { "input" -> it.copy(text = value); "counter" -> if (step.action == RuntimeAction.INPUT_TEXT) it else it.copy(text = count.toString()); else -> it }
            } + if (sensitiveAt == count) listOf(UiElement(elementId = "protected", role = "TextView", text = "Password")) else emptyList()
            UiObservation(before.state.copy(allElements = elements))
        }
    }
    private fun runQuantity(desired: Int, clicks: Int = 1, button: SemanticSelector = SemanticSelector(role = "Button", contentDescription = "Increase amount")): Pair<RuntimeReport, Driver> {
        val wf = quantityWorkflow(clicks, button); val repo = LocalSkillRepository(); assertTrue(repo.saveReplayableWorkflow(wf))
        val req = build(repo, "Order $desired tea").executionRequest!!
        val driver = quantityDriver(button)
        return execute(ExecutionEngine(repo, driver), req) to driver
    }
    @Test fun demonstratedOneReplaysTwo() {
        val (report, driver) = runQuantity(2)
        assertTrue(report.result.errorMessage, report.result.success)
        assertEquals(3, driver.actions) // One text action, two verified increments.
        assertEquals("2", driver.screen.state.allElements.single { it.elementId == "counter" }.text)
    }
    @Test fun demonstratedOneReplaysThree() {
        val (report, driver) = runQuantity(3)
        assertTrue(report.result.errorMessage, report.result.success)
        assertEquals(4, driver.actions)
        assertEquals(4, report.result.stepsCompleted)
    }
    @Test fun repeatedDemonstratedClicksCollapseToOneQuantityOperation() {
        val wf = quantityWorkflow(3)
        assertEquals(2, wf.steps.size)
        assertEquals("quantity", wf.steps.last().parameters["repeat_slot"])
        val (report, driver) = runQuantity(2, clicks = 3)
        assertTrue(report.result.success)
        assertEquals(3, driver.actions)
    }
    @Test fun quantityOperationIsGenericAcrossSelectors() {
        for (selector in listOf(SemanticSelector(role = "Button", text = "+"), SemanticSelector(role = "Button", resourceId = "id/advance", contentDescription = "Add one"))) {
            val (report, driver) = runQuantity(3, button = selector)
            assertTrue(report.result.errorMessage, report.result.success)
            assertEquals(4, driver.actions)
        }
    }
    @Test fun invalidQuantitiesFailBeforeAnyAction() {
        val wf = quantityWorkflow(); val repo = LocalSkillRepository(); assertTrue(repo.saveReplayableWorkflow(wf))
        for (value in listOf("0", "-1", "21", "99999999999999999999", "two", "1.5")) {
            val driver = quantityDriver()
            val result = execute(ExecutionEngine(repo, driver), ExecutionRequest(executionId = "invalid$value", skillId = wf.skillId,
                boundSlots = mapOf("item" to "Tea", "quantity" to value)))
            assertFalse(result.result.success); assertEquals(0, driver.actions)
        }
        assertNull(build(repo, "Order -1 tea").executionRequest)
        assertNull(build(repo, "Order 21 tea").executionRequest)
    }
    @Test fun handoffDuringQuantityBlocksRemainingClicksAndLaterRequests() {
        val wf = quantityWorkflow(); val repo = LocalSkillRepository(); assertTrue(repo.saveReplayableWorkflow(wf))
        val req = build(repo, "Order 3 tea").executionRequest!!
        val driver = quantityDriver(sensitiveAt = 1); val engine = ExecutionEngine(repo, driver)
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, execute(engine, req).result.finalState)
        assertEquals(2, driver.actions)
        driver.screen = UiObservation(state(0, ""))
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, execute(engine, req).result.finalState)
        assertEquals(2, driver.actions)
    }
    @Test fun wrongCounterTransitionDoesNotTriggerMoreClicks() {
        val wf = quantityWorkflow(); val repo = LocalSkillRepository(); assertTrue(repo.saveReplayableWorkflow(wf))
        val driver = quantityDriver(wrongIncrement = true)
        val result = execute(ExecutionEngine(repo, driver), build(repo, "Order 3 tea").executionRequest!!)
        assertFalse(result.result.success); assertEquals(2, driver.actions)
    }
    @Test fun plusLabelWithoutCounterEvidenceDoesNotCreateQuantityLoop() {
        val t = trace("Order 1 tea", quantityClicks = 1).copy(stateEvents = emptyList())
        val result = learn(t, setOf("item", "quantity"))
        assertFalse(result.isExecutable)
        assertFalse(LocalSkillRepository().saveReplayableWorkflow(result.workflow!!))
    }
    @Test fun quantityTextInputStillBindsOnce() {
        val result = learn(trace("Order 1 food", value = "1", resource = "id/quantity"), setOf("quantity"))
        assertTrue(result.diagnostics.toString(), result.isExecutable)
        val bound = SlotBinder.bind(result.workflow!!, mapOf("quantity" to "3"))
        assertNull(bound.error); assertEquals(1, bound.steps.size); assertEquals("3", bound.steps.single().inputText)
    }
    @Test fun conflictingValuesAcrossTwoFieldsCannotDisappear() {
        val t = trace()
        val first = t.userActions.single()
        val second = first.copy(actionId = "other", timestamp = 2,
            semanticSelector = first.semanticSelector.copy(resourceId = "id/search_other", text = "coffee"), inputData = "coffee")
        val result = learn(t.copy(userActions = listOf(first, second)))
        assertNull(result.workflow)
        assertFalse(result.isExecutable)
    }
    @Test fun wrongInitialQuantityStopsBeforeIncrement() {
        val wf = quantityWorkflow(); val repo = LocalSkillRepository(); assertTrue(repo.saveReplayableWorkflow(wf))
        val driver = quantityDriver()
        driver.screen = driver.screen.copy(state = driver.screen.state.copy(allElements = driver.screen.state.allElements.map {
            if (it.elementId == "counter") it.copy(text = "5") else it
        }))
        val report = execute(ExecutionEngine(repo, driver), build(repo, "Order 3 tea").executionRequest!!)
        assertFalse(report.result.success)
        assertEquals(1, driver.actions) // Text can execute; no quantity increment is allowed.
    }
    @Test fun ambiguousCounterStopsBeforeIncrement() {
        val wf = quantityWorkflow(); val repo = LocalSkillRepository(); assertTrue(repo.saveReplayableWorkflow(wf))
        val driver = quantityDriver()
        val counter = driver.screen.state.allElements.single { it.elementId == "counter" }
        driver.screen = driver.screen.copy(state = driver.screen.state.copy(allElements = driver.screen.state.allElements + counter.copy(elementId = "duplicate")))
        val report = execute(ExecutionEngine(repo, driver), build(repo, "Order 3 tea").executionRequest!!)
        assertFalse(report.result.success)
        assertEquals(1, driver.actions)
    }
    @Test fun unrelatedNumericChangeCannotTeachQuantity() {
        val t = trace("Order 1 tea", quantityClicks = 1, button = SemanticSelector(role = "Button", text = "Next page"))
        val result = learn(t, setOf("item", "quantity"))
        assertFalse(result.isExecutable)
    }
    @Test fun generatedStepAfterRepeatedQuantityUsesVerifiedFinalState() {
        val t = trace("Order 1 tea", quantityClicks = 1)
        val last = ActionEvent(actionId = "summary", timestamp = 10, actionType = "CLICK", semanticSelector = SemanticSelector(text = "Show summary"))
        val learned = learn(t.copy(userActions = t.userActions + last), setOf("item", "quantity"))
        assertTrue(learned.diagnostics.toString(), learned.isExecutable)
        val repo = LocalSkillRepository(); assertTrue(repo.saveReplayableWorkflow(learned.workflow!!))
        var count = 0
        val initial = state(0, "").copy(allElements = state(0, "").allElements + UiElement(elementId = "summary", role = "Button", text = "Show summary", isClickable = true))
        val driver = Driver(UiObservation(initial)) { step, before ->
            if (step.quantityBefore != null) count++
            before.copy(state = before.state.copy(allElements = before.state.allElements.map {
                when {
                    step.action == RuntimeAction.INPUT_TEXT && it.elementId == "input" -> it.copy(text = step.inputText)
                    step.quantityBefore != null && it.elementId == "counter" -> it.copy(text = count.toString())
                    step.selector.text == "Show summary" && it.elementId == "summary" -> it.copy(text = "Summary shown")
                    else -> it
                }
            }))
        }
        val result = execute(ExecutionEngine(repo, driver), build(repo, "Order 3 tea").executionRequest!!)
        assertTrue(result.result.errorMessage, result.result.success)
        assertEquals(5, driver.actions)
        assertEquals(5, result.result.stepsCompleted)
    }

    @Test fun malformedQuantityEvidenceFailsWithoutLeakingItsContents() {
        val wf = quantityWorkflow()
        val malformed = wf.copy(steps = wf.steps.map { if (it.parameters.containsKey("repeat_slot"))
            it.copy(parameters = it.parameters + ("quantity_selector" to "private-malformed-value")) else it })
        val bound = SlotBinder.bind(malformed, mapOf("item" to "Tea", "quantity" to "3"))
        assertNotNull(bound.error)
        assertFalse(bound.error!!.contains("private-malformed-value"))
        assertFalse(ReplayAdmission.validate(malformed).isStoreable)
    }
    @Test fun scrollWithoutDirectionRequiresReteaching() {
        val wf = learn().workflow!!
        val scroll = wf.copy(slots = emptyList(), steps = listOf(wf.steps.single().copy(
            semanticAction = "SCROLL", semanticSelector = SemanticSelector(resourceId = "id/list"), parameters = emptyMap())))
        assertFalse(ReplayAdmission.validate(scroll).isStoreable)
        assertTrue(ReplayAdmission.problems(scroll).any { it.contains("direction") })
    }

    @Test fun recognizedIntentWithoutSlotGrammarCannotSilentlyReplayConstants() {
        val constant = learn(variables = emptySet()).workflow!!
        for (intent in listOf("send_message", "create_reminder", "navigate", "book_appointment")) {
            assertFalse(ReplayAdmission.validate(constant.copy(intent = intent)).isStoreable)
        }
    }

}
