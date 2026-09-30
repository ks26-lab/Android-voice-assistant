package com.chockXlate.teachablevoice.runtime

import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.*
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
import com.chockXlate.teachablevoice.skill.repository.SkillRepository
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class TargetAppHandoffAndResumeTest {

    private val assistantPackage = "com.chockXlate.teachablevoice"
    private val targetPackage = "com.google.android.googlequicksearchbox"
    private val launcherPackage = "com.google.android.apps.nexuslauncher"

    private fun ui(
        vararg elements: UiElement,
        packageName: String = targetPackage,
        credentialField: Boolean = false
    ) = UiObservation(
        state = UiState(
            stateId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            appContext = packageName,
            allElements = elements.toList()
        ),
        credentialFieldPresent = credentialField
    )

    private class Store(var workflow: Workflow?) : SkillRepository {
        override fun getWorkflowById(skillId: String) = workflow?.takeIf { it.skillId == skillId }
        override fun getAllWorkflows() = listOfNotNull(workflow)
        override fun saveWorkflow(workflow: Workflow): Boolean { this.workflow = workflow; return true }
        override fun deleteWorkflow(skillId: String): Boolean { workflow = null; return true }
    }

    private class TestDriver(var screen: UiObservation) : UiDriver {
        var ready = true
        var actionsDispatched = 0
        var waits = 0
        var lastStep: BoundStep? = null
        var lastInputText: String? = null
        var waitingHook: (() -> Unit)? = null
        val dispatchedSteps = mutableListOf<String>()

        override fun isReady() = ready
        override suspend fun observe(): UiObservation? = if (ready) screen else null
        override suspend fun awaitChange(delayMs: Long) {
            waits++
            waitingHook?.invoke()
        }

        override suspend fun execute(
            step: BoundStep,
            expectedPackage: String,
            boundary: SafetyBoundary,
            matcher: SemanticMatcher,
            gate: SafetyGate
        ): ActionOutcome {
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
            val result = gate.dispatch(boundary, before, step) {
                actionsDispatched++
                lastStep = step
                lastInputText = step.inputText
                dispatchedSteps.add(step.source.stepId)
                if (step.action == RuntimeAction.INPUT_TEXT && step.inputText != null) {
                    val updatedElements = screen.state.allElements.map { el ->
                        if (el.resourceId == step.selector.resourceId || (step.selector.role != null && el.role == step.selector.role)) {
                            el.copy(text = step.inputText)
                        } else el
                    }
                    screen = screen.copy(
                        state = screen.state.copy(
                            stateId = UUID.randomUUID().toString(),
                            allElements = updatedElements
                        )
                    )
                } else {
                    val updatedElements = screen.state.allElements + UiElement(
                        elementId = "transition_result_${UUID.randomUUID()}",
                        role = "TextView",
                        text = "Done"
                    )
                    screen = screen.copy(
                        state = screen.state.copy(
                            stateId = UUID.randomUUID().toString(),
                            allElements = updatedElements
                        )
                    )
                }
                true
            }
            return ActionOutcome(result.attempted, result.accepted, result.reason, before)
        }
    }

    private fun <T> runSync(block: suspend () -> T): T {
        var completion: Result<T>? = null
        val done = CountDownLatch(1)
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completion = result; done.countDown() }
        })
        check(done.await(5, TimeUnit.SECONDS)) { "Test coroutine timed out" }
        return completion!!.getOrThrow()
    }

    private fun createSearchWorkflow(
        exampleValue: String = "headphones",
        safety: SafetyBoundary = SafetyBoundary()
    ): Workflow {
        val slot = WorkflowSlot(
            name = "item",
            type = SlotType.TEXT,
            required = true,
            exampleValue = exampleValue,
            provenance = "variable"
        )
        val step = WorkflowStep(
            stepId = "step_search_input",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(
                role = "EditText",
                resourceId = "com.google.android.googlequicksearchbox:id/search_box",
                textSlot = "\${item}"
            ),
            parameters = mapOf("input_parameter" to "\${item}"),
            preconditions = Preconditions(
                requiredPackage = targetPackage
            ),
            expectedTransition = ExpectedTransition(
                expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.GENERIC_STATE_CHANGE))
            )
        )
        return Workflow(
            skillId = "skill_search_info",
            name = "Search Item",
            intent = "search_information",
            appContext = targetPackage,
            slots = listOf(slot),
            steps = listOf(step),
            safetyBoundary = safety
        )
    }

    // 1. execution begins while assistant app is foreground
    @Test
    fun test1_executionBeginsWhileAssistantAppIsForeground() {
        val wf = createSearchWorkflow()
        val driver = TestDriver(ui(UiElement(elementId = "btn_cancel", role = "Button", text = "Cancel"), packageName = assistantPackage))
        driver.waitingHook = {
            // User switches to target app
            driver.screen = ui(
                UiElement(elementId = "search_input", role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", isEditable = true),
                packageName = targetPackage
            )
        }

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 10, packagePollIntervalMs = 10)
        val req = ExecutionRequest(
            executionId = "exec_1",
            skillId = "skill_search_info",
            boundSlots = mapOf("item" to "phone case")
        )

        val report = runSync { engine.execute(req) }

        assertTrue("Report failed: ${report.result.errorMessage}, state: ${report.result.finalState}, waits: ${driver.waits}, actions: ${driver.actionsDispatched}", report.result.success)
        assertEquals(ExecutionState.COMPLETED, report.result.finalState)
        assertEquals(1, driver.actionsDispatched)
        // Verify diagnostics has WAITING_FOR_USER recorded when assistant app was active
        val hasWaitingDecision = report.diagnostics.any {
            it.state == ExecutionState.WAITING_FOR_USER || it.decision.reason.contains("required app")
        }
        assertTrue(hasWaitingDecision)
    }

    // 2. required external package mismatch -> safe waiting/handoff, no dispatch
    @Test
    fun test2_requiredExternalPackageMismatchSafeWaitingNoDispatch() {
        val wf = createSearchWorkflow()
        val driver = TestDriver(ui(UiElement(elementId = "btn_assistant", role = "Button", text = "Tap Me"), packageName = assistantPackage))

        var checkedNoDispatchDuringWait = false
        driver.waitingHook = {
            assertEquals("No action must be dispatched while waiting on wrong package", 0, driver.actionsDispatched)
            checkedNoDispatchDuringWait = true
        }

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 3, packagePollIntervalMs = 10)
        val req = ExecutionRequest(
            executionId = "exec_2",
            skillId = "skill_search_info",
            boundSlots = mapOf("item" to "phone case")
        )

        val report = runSync { engine.execute(req) }

        assertTrue(checkedNoDispatchDuringWait)
        assertEquals(0, driver.actionsDispatched)
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertTrue(report.result.errorMessage!!.contains("required app"))
    }

    // 3. same ExecutionRequest survives waiting
    @Test
    fun test3_sameExecutionRequestSurvivesWaiting() {
        val wf = createSearchWorkflow()
        val driver = TestDriver(ui(UiElement(elementId = "idle", role = "View"), packageName = assistantPackage))

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 5, packagePollIntervalMs = 10)
        val req = ExecutionRequest(
            executionId = "exec_custom_id_12345",
            skillId = "skill_search_info",
            boundSlots = mapOf("item" to "phone case")
        )

        driver.waitingHook = {
            // Request is active and preserved during waiting
            assertTrue(engine.isPaused)
            assertEquals("exec_custom_id_12345", engine.pausedExecutionId)
            assertEquals(targetPackage, engine.pausedExpectedPackage)
        }

        val report = runSync { engine.execute(req) }

        assertEquals("exec_custom_id_12345", report.result.executionId)
        assertEquals("exec_custom_id_12345", report.trace.executionId)
    }

    // 4. expected package becomes active -> execution resumes
    @Test
    fun test4_expectedPackageBecomesActiveExecutionResumes() {
        val wf = createSearchWorkflow()
        val driver = TestDriver(ui(packageName = assistantPackage))

        var ticks = 0
        driver.waitingHook = {
            ticks++
            if (ticks >= 2) {
                driver.screen = ui(
                    UiElement(elementId = "search_box_id", role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", isEditable = true),
                    packageName = targetPackage
                )
            }
        }

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 10, packagePollIntervalMs = 10)
        val report = runSync {
            engine.execute(ExecutionRequest(executionId = "exec_4", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }

        assertTrue(report.result.success)
        assertEquals(ExecutionState.COMPLETED, report.result.finalState)
        assertEquals(1, driver.actionsDispatched)
    }

    // 5. correct semantic target is re-resolved after resume
    @Test
    fun test5_correctSemanticTargetReResolvedAfterResume() {
        val wf = createSearchWorkflow()
        // Assistant screen has unrelated EditText
        val driver = TestDriver(ui(
            UiElement(elementId = "assistant_voice_input", role = "EditText", resourceId = "com.chockXlate.teachablevoice:id/voice_input", isEditable = true),
            packageName = assistantPackage
        ))

        driver.waitingHook = {
            // Target app screen has the real search box
            driver.screen = ui(
                UiElement(elementId = "real_target_search_box", role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", isEditable = true),
                packageName = targetPackage
            )
        }

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 10, packagePollIntervalMs = 10)
        val report = runSync {
            engine.execute(ExecutionRequest(executionId = "exec_5", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }

        assertTrue(report.result.success)
        assertEquals("com.google.android.googlequicksearchbox:id/search_box", driver.lastStep?.selector?.resourceId)
    }

    // 6. changed slot item="phone case" survives resume
    @Test
    fun test6_changedSlotItemSurvivesResume() {
        val wf = createSearchWorkflow(exampleValue = "headphones")
        val driver = TestDriver(ui(packageName = assistantPackage))

        driver.waitingHook = {
            driver.screen = ui(
                UiElement(elementId = "target_input", role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", isEditable = true),
                packageName = targetPackage
            )
        }

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 10, packagePollIntervalMs = 10)
        val report = runSync {
            engine.execute(ExecutionRequest(executionId = "exec_6", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }

        assertTrue(report.result.success)
        assertEquals("phone case", driver.lastStep?.inputText)
    }

    // 7. resumed INPUT_TEXT uses "phone case", never "headphones"
    @Test
    fun test7_resumedInputTextUsesPhoneCaseNeverHeadphones() {
        val wf = createSearchWorkflow(exampleValue = "headphones")
        val driver = TestDriver(ui(packageName = assistantPackage))

        driver.waitingHook = {
            driver.screen = ui(
                UiElement(elementId = "target_input", role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", isEditable = true),
                packageName = targetPackage
            )
        }

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 10, packagePollIntervalMs = 10)
        val report = runSync {
            engine.execute(ExecutionRequest(executionId = "exec_7", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }

        assertTrue(report.result.success)
        assertEquals("phone case", driver.lastInputText)
        assertNotEquals("headphones", driver.lastInputText)
    }

    // 8. wrong package does not resume
    @Test
    fun test8_wrongPackageDoesNotResume() {
        val wf = createSearchWorkflow()
        // Switch from assistant to launcher (still wrong package!)
        val driver = TestDriver(ui(packageName = assistantPackage))

        driver.waitingHook = {
            // User went to home screen / launcher, NOT the target app
            driver.screen = ui(
                UiElement(elementId = "launcher_icon", role = "TextView", text = "Chrome"),
                packageName = launcherPackage
            )
        }

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 4, packagePollIntervalMs = 10)
        val report = runSync {
            engine.execute(ExecutionRequest(executionId = "exec_8", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }

        assertFalse(report.result.success)
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertEquals(0, driver.actionsDispatched)

        // Attempting explicit resume while on wrong package also fails safely
        val resumeReport = runSync { engine.resume() }
        assertFalse(resumeReport.result.success)
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, resumeReport.result.finalState)
        assertEquals(0, driver.actionsDispatched)
    }

    // 9. ambiguous target does not execute
    @Test
    fun test9_ambiguousTargetDoesNotExecute() {
        val wf = createSearchWorkflow()
        val driver = TestDriver(ui(packageName = assistantPackage))

        driver.waitingHook = {
            // Target app has TWO identical search boxes -> ambiguous!
            driver.screen = ui(
                UiElement(elementId = "search_1", role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", isEditable = true),
                UiElement(elementId = "search_2", role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", isEditable = true),
                packageName = targetPackage
            )
        }

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 10, packagePollIntervalMs = 10)
        val report = runSync {
            engine.execute(ExecutionRequest(executionId = "exec_9", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }

        // Must pause for user clarification, NEVER execute blindly
        assertEquals(ExecutionState.WAITING_FOR_USER, report.result.finalState)
        assertEquals(0, driver.actionsDispatched)
        assertNotNull(report.result.clarificationRequest)
    }

    // 10. SafetyGate remains mandatory after resume
    @Test
    fun test10_safetyGateRemainsMandatoryAfterResume() {
        // Workflow safety boundary requires explicit user confirmation
        val safety = SafetyBoundary(requiresExplicitUserConfirmation = true)
        val wf = createSearchWorkflow(safety = safety)
        val driver = TestDriver(ui(packageName = assistantPackage))

        driver.waitingHook = {
            driver.screen = ui(
                UiElement(elementId = "target_input", role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", isEditable = true),
                packageName = targetPackage
            )
        }

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 10, packagePollIntervalMs = 10)
        val report = runSync {
            engine.execute(ExecutionRequest(executionId = "exec_10", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }

        assertFalse(report.result.success)
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertEquals(0, driver.actionsDispatched)
    }

    // 11. credential boundary after resume still hard-stops
    @Test
    fun test11_credentialBoundaryAfterResumeStillHardStops() {
        val wf = createSearchWorkflow()
        val driver = TestDriver(ui(packageName = assistantPackage))

        driver.waitingHook = {
            // Target app screen presents a credential password field!
            driver.screen = ui(
                UiElement(elementId = "pwd_field", role = "EditText", resourceId = "password", text = "enter password", isEditable = true),
                packageName = targetPackage,
                credentialField = true
            )
        }

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 10, packagePollIntervalMs = 10)
        val report = runSync {
            engine.execute(ExecutionRequest(executionId = "exec_11", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }

        assertFalse(report.result.success)
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
        assertEquals(0, driver.actionsDispatched)
    }

    // 12. no action is dispatched while waiting
    @Test
    fun test12_noActionDispatchedWhileWaiting() {
        val wf = createSearchWorkflow()
        val driver = TestDriver(ui(
            UiElement(elementId = "clickable_btn", role = "Button", text = "Submit", isClickable = true),
            packageName = assistantPackage
        ))

        var waitTicks = 0
        driver.waitingHook = {
            waitTicks++
            assertEquals("No actions dispatched during tick $waitTicks", 0, driver.actionsDispatched)
        }

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 5, packagePollIntervalMs = 10)
        runSync {
            engine.execute(ExecutionRequest(executionId = "exec_12", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }

        assertEquals(5, waitTicks)
        assertEquals(0, driver.actionsDispatched)
    }

    // 13. already-completed steps are not repeated
    @Test
    fun test13_alreadyCompletedStepsAreNotRepeated() {
        val step1 = WorkflowStep(
            stepId = "step_1_first",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "First Action"),
            preconditions = Preconditions(requiredPackage = targetPackage),
            expectedTransition = ExpectedTransition(expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.GENERIC_STATE_CHANGE)))
        )
        val step2 = WorkflowStep(
            stepId = "step_2_second",
            semanticAction = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "Second Action"),
            preconditions = Preconditions(requiredPackage = "com.other.target"),
            expectedTransition = ExpectedTransition(expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.GENERIC_STATE_CHANGE)))
        )
        val wf = Workflow(
            skillId = "skill_multi_step",
            name = "Multi Step",
            intent = "multi",
            appContext = targetPackage,
            steps = listOf(step1, step2)
        )

        // Initially in targetPackage with Button 1
        val driver = TestDriver(ui(UiElement(elementId = "b1", role = "Button", text = "First Action", isClickable = true), packageName = targetPackage))

        // Step 1 completes immediately. When Step 2 checks preconditions, requiredPackage is "com.other.target".
        // It enters wait loop. Inside wait hook, we switch to "com.other.target" with Button 2.
        driver.waitingHook = {
            assertEquals("Step 1 must have run exactly once before Step 2 waits", 1, driver.actionsDispatched)
            assertEquals("step_1_first", driver.dispatchedSteps.first())
            driver.screen = ui(UiElement(elementId = "b2", role = "Button", text = "Second Action", isClickable = true), packageName = "com.other.target")
        }

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 5, packagePollIntervalMs = 10)
        val report = runSync {
            engine.execute(ExecutionRequest(executionId = "exec_13", skillId = "skill_multi_step"))
        }

        assertTrue(report.result.success)
        assertEquals(ExecutionState.COMPLETED, report.result.finalState)
        assertEquals(2, driver.actionsDispatched)
        assertEquals(listOf("step_1_first", "step_2_second"), driver.dispatchedSteps)
    }

    // 14. timeout/cancel produces safe failure/handoff
    @Test
    fun test14_timeoutAndCancelProduceSafeFailure() {
        val wf = createSearchWorkflow()
        val driver = TestDriver(ui(packageName = assistantPackage))

        // Subtest A: Timeout
        val engineTimeout = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 3, packagePollIntervalMs = 10)
        val timeoutReport = runSync {
            engineTimeout.execute(ExecutionRequest(executionId = "exec_timeout", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, timeoutReport.result.finalState)
        assertEquals(0, driver.actionsDispatched)

        // Subtest B: Cancel
        val engineCancel = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 20, packagePollIntervalMs = 10)
        driver.waitingHook = {
            engineCancel.cancel()
        }
        val cancelReport = runSync {
            engineCancel.execute(ExecutionRequest(executionId = "exec_cancel", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }
        assertEquals(ExecutionState.ABORTED, cancelReport.result.finalState)
        assertEquals(0, driver.actionsDispatched)
    }

    // 15. manual resume control when paused
    @Test
    fun test15_manualResumeControlWhenPaused() {
        val wf = createSearchWorkflow(exampleValue = "headphones")
        val driver = TestDriver(ui(packageName = assistantPackage))

        // Initial execute times out because user hasn't switched yet
        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 2, packagePollIntervalMs = 10)
        val initialReport = runSync {
            engine.execute(ExecutionRequest(executionId = "exec_manual_resume", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, initialReport.result.finalState)
        assertEquals(0, driver.actionsDispatched)
        assertTrue(engine.isPaused)

        // User opens target app
        driver.screen = ui(
            UiElement(elementId = "target_input", role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", isEditable = true),
            packageName = targetPackage
        )

        // Explicit resume is triggered
        val resumeReport = runSync { engine.resume() }
        assertTrue(resumeReport.result.success)
        assertEquals(ExecutionState.COMPLETED, resumeReport.result.finalState)
        assertEquals(1, driver.actionsDispatched)
        assertEquals("phone case", driver.lastInputText)
    }

    // 16. INITIAL_STATE with required element does not fail against assistant UI before handoff
    @Test
    fun test16_initialStateWithElementEvidenceDoesNotFailAgainstAssistantUI() {
        val slot = WorkflowSlot(
            name = "item",
            type = SlotType.TEXT,
            required = true,
            exampleValue = "headphones",
            provenance = "variable"
        )
        val sel = SemanticSelector(
            role = "EditText",
            resourceId = "com.google.android.googlequicksearchbox:id/search_box",
            textSlot = "\${item}"
        )
        val step = WorkflowStep(
            stepId = "step_search_input",
            semanticAction = "INPUT_TEXT",
            semanticSelector = sel,
            parameters = mapOf("input_parameter" to "\${item}"),
            preconditions = Preconditions(
                fromState = "INITIAL_STATE",
                requiredPackage = targetPackage,
                requiredElementPresent = sel
            ),
            expectedTransition = ExpectedTransition(
                expectedEvidence = listOf(StateEvidenceRequirement(type = EvidenceType.GENERIC_STATE_CHANGE))
            )
        )
        val wf = Workflow(
            skillId = "skill_search_info_starting_state",
            name = "Search Item",
            intent = "search_information",
            appContext = targetPackage,
            slots = listOf(slot),
            steps = listOf(step),
            safetyBoundary = SafetyBoundary()
        )

        val driver = TestDriver(ui(UiElement(elementId = "btn_demo", role = "Button", text = "EXECUTE"), packageName = assistantPackage))
        driver.waitingHook = {
            // User switches to target app with search box
            driver.screen = ui(
                UiElement(elementId = "search_input", role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", isEditable = true),
                packageName = targetPackage
            )
        }

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 10, packagePollIntervalMs = 10)
        val report = runSync {
            engine.execute(ExecutionRequest(executionId = "exec_initial_state", skillId = "skill_search_info_starting_state", boundSlots = mapOf("item" to "phone case")))
        }

        assertTrue("Execution should succeed without premature starting-state failure: ${report.result.errorMessage}", report.result.success)
        assertEquals(ExecutionState.COMPLETED, report.result.finalState)
        assertEquals(1, driver.actionsDispatched)
    }

    // 17. Repeated execute after handoff does not latch SafetyGate or fail with 0/0 steps
    @Test
    fun test17_repeatedExecuteAfterHandoffDoesNotLatchSafetyGate() {
        val wf = createSearchWorkflow(exampleValue = "headphones")
        val driver = TestDriver(ui(packageName = assistantPackage))

        val engine = ExecutionEngine(Store(wf), driver, maxPackageWaitAttempts = 2, packagePollIntervalMs = 10)
        // First execute times out waiting for target app
        val report1 = runSync {
            engine.execute(ExecutionRequest(executionId = "exec_rep_1", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }
        assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report1.result.finalState)

        // Second execute while user has switched to target app must NOT be blocked by 0/0 steps in 3ms
        driver.screen = ui(
            UiElement(elementId = "search_input", role = "EditText", resourceId = "com.google.android.googlequicksearchbox:id/search_box", isEditable = true),
            packageName = targetPackage
        )

        val report2 = runSync {
            engine.execute(ExecutionRequest(executionId = "exec_rep_2", skillId = "skill_search_info", boundSlots = mapOf("item" to "phone case")))
        }
        assertTrue("Second execution must succeed on target app without latched gate: ${report2.result.errorMessage}", report2.result.success)
        assertEquals(ExecutionState.COMPLETED, report2.result.finalState)
        assertEquals(1, driver.actionsDispatched)
    }
}
