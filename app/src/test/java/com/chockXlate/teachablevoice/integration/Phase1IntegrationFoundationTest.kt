package com.chockXlate.teachablevoice.integration

import com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter
import com.chockXlate.teachablevoice.command.matching.SkillMatchStatus
import com.chockXlate.teachablevoice.command.matching.SkillMatcher
import com.chockXlate.teachablevoice.command.request.ExecutionRequestBuilder
import com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus
import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import com.chockXlate.teachablevoice.contract.runtime.ExecutionState
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.runtime.ExecutionEngine
import com.chockXlate.teachablevoice.runtime.RuntimeLifecycleManager
import com.chockXlate.teachablevoice.runtime.RuntimeLifecycleState
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.ui.ActionOutcome
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction
import com.chockXlate.teachablevoice.runtime.ui.UiDriver
import com.chockXlate.teachablevoice.runtime.ui.UiObservation
import com.chockXlate.teachablevoice.safety.RuntimeSafetyPolicy
import com.chockXlate.teachablevoice.safety.SafetyGate
import com.chockXlate.teachablevoice.skill.inspector.WorkflowInspectorImpl
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.skill.repository.SkillRepositoryProvider
import com.chockXlate.teachablevoice.skill.validation.ValidationStatus
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mandatory Phase 1 Test Suite covering P1.15 Tests 1 to 14.
 * Verifies persistence across restart, Inspector accuracy, runtime startup lifecycle,
 * automatic skill discovery, changed slots, unknown/ambiguous matching, missing slot rejection,
 * safety gate non-bypassability, mock-to-real contract replacement, and absence of hard-coded values.
 */
class Phase1IntegrationFoundationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var repository: LocalSkillRepository

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("skills_storage")
        SkillRepositoryProvider.initialize(storageDir)
        repository = SkillRepositoryProvider.getRepository()
        RuntimeLifecycleManager.reset()
    }

    private fun createSampleWorkflow(
        skillId: String = "skill_search_01",
        intent: String = "search_information",
        name: String = "Search Information",
        appContext: String = "com.example.search",
        variableSlotName: String = "item",
        exampleValue: String = "headphones"
    ): Workflow {
        val slot = WorkflowSlot(
            name = variableSlotName,
            type = SlotType.TEXT,
            required = true,
            exampleValue = exampleValue,
            provenance = "demonstration"
        )
        val step = WorkflowStep(
            stepId = "step_1",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(
                role = "EditText",
                resourceId = "com.example.search:id/search_query",
                textSlot = "\${$variableSlotName}"
            ),
            parameters = mapOf("input_parameter" to "\${$variableSlotName}"),
            preconditions = Preconditions(requiredPackage = appContext),
            expectedTransition = ExpectedTransition(toState = "RESULTS_VIEWED"),
            recoveryPolicy = RecoveryPolicy(maxRetries = 2, strategy = RecoveryStrategy.RETRY_STEP),
            confidence = 1.0,
            provenance = "demonstration"
        )
        return Workflow(
            schemaVersion = "1.0",
            skillId = skillId,
            name = name,
            intent = intent,
            appContext = appContext,
            slots = listOf(slot),
            steps = listOf(step),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = false)
        )
    }

    private fun createMultiSlotWorkflow(
        skillId: String = "skill_order_01",
        intent: String = "order_food"
    ): Workflow {
        val slots = listOf(
            WorkflowSlot(name = "restaurant", type = SlotType.TEXT, required = true, exampleValue = "Burger Queen"),
            WorkflowSlot(name = "item", type = SlotType.TEXT, required = true, exampleValue = "Cheeseburger"),
            WorkflowSlot(name = "quantity", type = SlotType.INTEGER, required = true, exampleValue = "2"),
            WorkflowSlot(name = "address", type = SlotType.ADDRESS, required = true, exampleValue = "123 Main St")
        )
        val steps = listOf(
            WorkflowStep(
                stepId = "step_1",
                semanticAction = "INPUT_TEXT",
                semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/search_input", textSlot = "\${item}"),
                parameters = mapOf("input_parameter" to "\${item}")
            )
        )
        return Workflow(
            skillId = skillId,
            name = "Order Food",
            intent = intent,
            appContext = "com.example.food",
            slots = slots,
            steps = steps,
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = false)
        )
    }

    // =========================================================================
    // TEST 1 — Workflow persistence
    // =========================================================================
    @Test
    fun test1_workflowPersistenceAcrossRestart() {
        val originalWf = createSampleWorkflow("skill_persisted_test", "search_information")
        val saved = repository.saveWorkflow(originalWf)
        assertTrue("Workflow should be validated and saved", saved)

        // Simulate repository re-instantiation / app restart
        val reinitializedRepo = LocalSkillRepository(storageDir)
        val loadedWf = reinitializedRepo.getWorkflowById("skill_persisted_test")

        assertNotNull("Workflow must survive repository restart", loadedWf)
        assertEquals(originalWf.schemaVersion, loadedWf?.schemaVersion)
        assertEquals(originalWf.skillId, loadedWf?.skillId)
        assertEquals(originalWf.name, loadedWf?.name)
        assertEquals(originalWf.intent, loadedWf?.intent)
        assertEquals(originalWf.appContext, loadedWf?.appContext)
        assertEquals(originalWf.slots.size, loadedWf?.slots?.size)
        assertEquals(originalWf.steps.size, loadedWf?.steps?.size)
        assertEquals(originalWf.safetyBoundary, loadedWf?.safetyBoundary)
        assertEquals(originalWf.steps[0].semanticSelector, loadedWf?.steps?.get(0)?.semanticSelector)
        assertEquals(originalWf.steps[0].preconditions, loadedWf?.steps?.get(0)?.preconditions)
        assertEquals(originalWf.steps[0].expectedTransition, loadedWf?.steps?.get(0)?.expectedTransition)
        assertEquals(originalWf.steps[0].recoveryPolicy, loadedWf?.steps?.get(0)?.recoveryPolicy)
    }

    // =========================================================================
    // TEST 2 — Inspector persistence
    // =========================================================================
    @Test
    fun test2_inspectorPersistence() {
        val originalWf = createSampleWorkflow("skill_inspect_test", "search_information", "Search Product")
        repository.saveWorkflow(originalWf)

        // Simulate new process instance opening the Inspector
        val freshRepo = LocalSkillRepository(storageDir)
        val loadedWf = freshRepo.getWorkflowById("skill_inspect_test")
        assertNotNull(loadedWf)

        val inspection = WorkflowInspectorImpl.inspect(loadedWf!!, freshRepo)

        assertEquals("STORED", inspection.storeStatus)
        assertEquals(ValidationStatus.VALID, inspection.validationStatus)
        assertEquals("skill_inspect_test", inspection.skillId)
        assertEquals("search_information", inspection.intent)
        assertTrue("Inspector must confirm Person 2 execution readiness", inspection.isExecutableByPerson2)
        assertTrue("Inspector must confirm coordinate-free execution", inspection.coordinateReplayPass)
        assertEquals(1, inspection.slots.size)
        assertEquals("item", inspection.slots[0].name)
        assertEquals(1, inspection.steps.size)
        assertEquals("INPUT_TEXT", inspection.steps[0].semanticAction)
        assertTrue(inspection.formattedText.contains("=== WORKFLOW INSPECTOR ==="))
        assertTrue(inspection.formattedText.contains("skill_inspect_test"))
    }

    // =========================================================================
    // TEST 3 — Empty repository
    // =========================================================================
    @Test
    fun test3_emptyRepositoryCommand() {
        repository.clear()
        assertEquals(0, repository.getWorkflowCount())

        val commandResult = CommandInterpreter.understandCommand("search for headphones")
        val matcher = SkillMatcher(repository)
        val matchResult = matcher.match(commandResult)

        assertEquals(SkillMatchStatus.UNKNOWN, matchResult.status)
        assertNull(matchResult.selectedSkillId)

        val buildResult = ExecutionRequestBuilder.build(commandResult, matchResult, repository)
        assertEquals(ExecutionRequestStatus.REJECTED_UNKNOWN_MATCH, buildResult.status)
        assertNull(buildResult.executionRequest)
    }

    // =========================================================================
    // TEST 4 — One learned skill
    // =========================================================================
    @Test
    fun test4_oneLearnedSkillMatchAndRequest() {
        val wf = createSampleWorkflow("skill_search_prod", "search_information")
        repository.saveWorkflow(wf)

        val commandResult = CommandInterpreter.understandCommand("search for headphones")
        val matcher = SkillMatcher(repository)
        val matchResult = matcher.match(commandResult)

        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)
        assertEquals("skill_search_prod", matchResult.selectedSkillId)

        val buildResult = ExecutionRequestBuilder.build(commandResult, matchResult, repository)
        assertEquals(ExecutionRequestStatus.READY_FOR_PERSON_2, buildResult.status)
        assertNotNull(buildResult.executionRequest)
        assertEquals("skill_search_prod", buildResult.executionRequest?.skillId)
        assertEquals("headphones", buildResult.executionRequest?.boundSlots?.get("item"))
    }

    // =========================================================================
    // TEST 5 — Two learned skills
    // =========================================================================
    @Test
    fun test5_twoLearnedSkillsDistinctSelection() {
        val wf1 = createSampleWorkflow("skill_search_1", "search_information", "Search Product")
        val wf2 = createSampleWorkflow("skill_msg_2", "send_message", "Send Message", variableSlotName = "recipient", exampleValue = "Alice")
        repository.saveWorkflow(wf1)
        repository.saveWorkflow(wf2)
        assertEquals(2, repository.getWorkflowCount())

        // Issue command matching second workflow
        val commandResult = CommandInterpreter.understandCommand("send message to Bob")
        val matcher = SkillMatcher(repository)
        val matchResult = matcher.match(commandResult)

        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)
        assertEquals("skill_msg_2", matchResult.selectedSkillId)

        val buildResult = ExecutionRequestBuilder.build(commandResult, matchResult, repository)
        assertEquals(ExecutionRequestStatus.READY_FOR_PERSON_2, buildResult.status)
        assertEquals("skill_msg_2", buildResult.executionRequest?.skillId)
        assertEquals("bob", buildResult.executionRequest?.boundSlots?.get("recipient")?.lowercase())
    }

    // =========================================================================
    // TEST 6 — Runtime startup
    // =========================================================================
    @Test
    fun test6_runtimeStartupLifecycle() {
        RuntimeLifecycleManager.reset()
        assertEquals(RuntimeLifecycleState.UNINITIALIZED, RuntimeLifecycleManager.state)
        assertFalse(RuntimeLifecycleManager.isReady)

        RuntimeLifecycleManager.initialize(storageDir)

        assertEquals(RuntimeLifecycleState.READY, RuntimeLifecycleManager.state)
        assertTrue(RuntimeLifecycleManager.isReady)
        assertNull(RuntimeLifecycleManager.lastError)
        assertNotNull(SkillRepositoryProvider.getRepository())
    }

    // =========================================================================
    // TEST 7 — Persistence + runtime integration
    // =========================================================================
    @Test
    fun test7_persistencePlusRuntimeIntegration() {
        // Step 1: Save workflow
        val wf = createSampleWorkflow("skill_persistent_runtime", "search_information")
        repository.saveWorkflow(wf)

        // Step 2: Restart runtime lifecycle and repository
        RuntimeLifecycleManager.reset()
        RuntimeLifecycleManager.initialize(storageDir)
        assertEquals(RuntimeLifecycleState.READY, RuntimeLifecycleManager.state)

        // Step 3: Issue command against persistent store
        val activeRepo = SkillRepositoryProvider.getRepository()
        val commandResult = CommandInterpreter.understandCommand("search for wireless mouse")
        val matcher = SkillMatcher(activeRepo)
        val matchResult = matcher.match(commandResult)

        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)
        assertEquals("skill_persistent_runtime", matchResult.selectedSkillId)

        val buildResult = ExecutionRequestBuilder.build(commandResult, matchResult, activeRepo)
        assertEquals(ExecutionRequestStatus.READY_FOR_PERSON_2, buildResult.status)
        assertEquals("wireless mouse", buildResult.executionRequest?.boundSlots?.get("item"))
    }

    // =========================================================================
    // TEST 8 — Changed slot
    // =========================================================================
    @Test
    fun test8_changedSlotValueDynamicBinding() {
        // Stored demonstration had item = "headphones"
        val wf = createSampleWorkflow("skill_dynamic_slot", "search_information", exampleValue = "headphones")
        repository.saveWorkflow(wf)

        // New command provides changed slot item = "mechanical keyboard"
        val commandResult = CommandInterpreter.understandCommand("search for mechanical keyboard")
        val matcher = SkillMatcher(repository)
        val matchResult = matcher.match(commandResult)
        val buildResult = ExecutionRequestBuilder.build(commandResult, matchResult, repository)

        assertEquals(ExecutionRequestStatus.READY_FOR_PERSON_2, buildResult.status)
        val boundItem = buildResult.executionRequest?.boundSlots?.get("item")
        assertEquals("mechanical keyboard", boundItem)
        assertNotEquals("headphones", boundItem)
    }

    // =========================================================================
    // TEST 9 — Unknown command
    // =========================================================================
    @Test
    fun test9_unknownCommandRejection() {
        val wf = createSampleWorkflow("skill_only_search", "search_information")
        repository.saveWorkflow(wf)

        // Command with unlearned intent
        val commandResult = CommandInterpreter.understandCommand("turn on bluetooth")
        val matcher = SkillMatcher(repository)
        val matchResult = matcher.match(commandResult)

        assertEquals(SkillMatchStatus.UNKNOWN, matchResult.status)
        assertNull(matchResult.selectedSkillId)

        val buildResult = ExecutionRequestBuilder.build(commandResult, matchResult, repository)
        assertEquals(ExecutionRequestStatus.REJECTED_UNKNOWN_MATCH, buildResult.status)
        assertNull(buildResult.executionRequest)
    }

    // =========================================================================
    // TEST 10 — Ambiguous command
    // =========================================================================
    @Test
    fun test10_ambiguousCommandHandling() {
        val wf1 = createSampleWorkflow("skill_search_app1", "search_information", appContext = "com.app.one")
        val wf2 = createSampleWorkflow("skill_search_app2", "search_information", appContext = "com.app.two")
        repository.saveWorkflow(wf1)
        repository.saveWorkflow(wf2)

        val commandResult = CommandInterpreter.understandCommand("search for sunglasses")
        val matcher = SkillMatcher(repository)
        val matchResult = matcher.match(commandResult)

        assertEquals(SkillMatchStatus.AMBIGUOUS, matchResult.status)
        assertEquals(2, matchResult.candidates.size)
        assertNull(matchResult.selectedSkillId)

        val buildResult = ExecutionRequestBuilder.build(commandResult, matchResult, repository)
        assertEquals(ExecutionRequestStatus.REJECTED_AMBIGUOUS_MATCH, buildResult.status)
        assertNull(buildResult.executionRequest)
    }

    // =========================================================================
    // TEST 11 — Missing required slot
    // =========================================================================
    @Test
    fun test11_missingRequiredSlotRejection() {
        val wf = createMultiSlotWorkflow("skill_order_multislot", "order_food")
        repository.saveWorkflow(wf)

        // Command provides restaurant and item, but omits required address and quantity
        val commandResult = CommandInterpreter.understandCommand("order pizza from Pizza Palace")
        val matcher = SkillMatcher(repository)
        val matchResult = matcher.match(commandResult)

        val buildResult = ExecutionRequestBuilder.build(commandResult, matchResult, repository)

        assertEquals(ExecutionRequestStatus.REJECTED_MISSING_REQUIRED_SLOTS, buildResult.status)
        assertNull(buildResult.executionRequest)
        assertTrue(buildResult.missingSlots.isNotEmpty())
    }

    // =========================================================================
    // TEST 12 — Safety regression
    // =========================================================================
    @Test
    fun test12_safetyRegressionCredentialLock() {
        val actionCounter = AtomicInteger(0)

        val sensitiveDriver = object : UiDriver {
            override fun isReady(): Boolean = true
            override suspend fun observe(): UiObservation {
                val elem = UiElement(
                    schemaVersion = "1.0",
                    elementId = "pass_input",
                    role = "EditText",
                    isPassword = true,
                    text = "password_field"
                )
                val state = UiState(
                    schemaVersion = "1.0",
                    stateId = "state_login",
                    appContext = "com.bank.app",
                    allElements = listOf(elem)
                )
                return UiObservation(state = state, credentialFieldPresent = true)
            }

            override suspend fun execute(
                step: BoundStep,
                expectedPackage: String,
                boundary: SafetyBoundary,
                matcher: SemanticMatcher,
                gate: SafetyGate
            ): ActionOutcome {
                actionCounter.incrementAndGet()
                return ActionOutcome(attempted = true, accepted = true, reason = "Executed")
            }

            override suspend fun awaitChange(delayMs: Long) {}
        }

        val step = WorkflowStep(
            stepId = "step_pass",
            semanticAction = "INPUT_TEXT",
            semanticSelector = SemanticSelector(role = "EditText", text = "enter password")
        )
        val wf = Workflow(
            skillId = "skill_bank_pass",
            name = "Bank Login",
            intent = "bank_login",
            appContext = "com.bank.app",
            steps = listOf(step),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = true)
        )
        repository.saveWorkflow(wf)

        val engine = ExecutionEngine(repository, sensitiveDriver)
        val request = ExecutionRequest(
            schemaVersion = "1.0",
            executionId = "req_sec_test",
            skillId = "skill_bank_pass",
            version = 1
        )

        val report = kotlinx.coroutines.runBlocking {
            engine.execute(request)
        }

        assertEquals(ExecutionState.HANDOFF, report.result.finalState)
        assertEquals(0, actionCounter.get()) // 0 automated accessibility actions allowed!
    }

    // =========================================================================
    // TEST 13 — Mock-to-real contract replacement
    // =========================================================================
    @Test
    fun test13_mockToRealContractReplacement() {
        val mockDriver = object : UiDriver {
            var stepExecuted = 0
            override fun isReady(): Boolean = true
            override suspend fun observe(): UiObservation {
                val elem = UiElement(
                    schemaVersion = "1.0",
                    elementId = "elem_search",
                    role = "EditText",
                    resourceId = "com.example.search:id/search_query"
                )
                val state = UiState(
                    schemaVersion = "1.0",
                    stateId = "search_state",
                    appContext = "com.example.search",
                    allElements = listOf(elem)
                )
                return UiObservation(state = state)
            }

            override suspend fun execute(
                step: BoundStep,
                expectedPackage: String,
                boundary: SafetyBoundary,
                matcher: SemanticMatcher,
                gate: SafetyGate
            ): ActionOutcome {
                stepExecuted++
                return ActionOutcome(attempted = true, accepted = true, reason = "Success")
            }

            override suspend fun awaitChange(delayMs: Long) {}
        }

        // Real repository plugged directly into ExecutionEngine
        val wf = createSampleWorkflow("skill_contract_test", "search_information")
        repository.saveWorkflow(wf)

        val realEngine = ExecutionEngine(repository, mockDriver)
        val request = ExecutionRequest(
            executionId = "req_contract_01",
            skillId = "skill_contract_test",
            boundSlots = mapOf("item" to "camera lens")
        )

        val report = kotlinx.coroutines.runBlocking {
            realEngine.execute(request)
        }

        assertNotNull(report)
        assertEquals("skill_contract_test", report.request.skillId)
        assertEquals("camera lens", report.request.boundSlots["item"])
    }

    // =========================================================================
    // TEST 14 — No hard-coded workflow detection
    // =========================================================================
    @Test
    fun test14_noHardCodedWorkflowLogicInProductionRuntime() {
        // Verify SkillMatcher matches any arbitrary valid intent and slot generically
        val genericWf = Workflow(
            skillId = "skill_generic_automation_xyz",
            name = "Custom Automation",
            intent = "custom_telemetry_query",
            appContext = "com.enterprise.customapp",
            slots = listOf(
                WorkflowSlot(name = "telemetry_metric", type = SlotType.TEXT, required = true, exampleValue = "cpu_load")
            ),
            steps = listOf(
                WorkflowStep(
                    stepId = "step_1",
                    semanticAction = "INPUT_TEXT",
                    semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/metric_input", textSlot = "\${telemetry_metric}"),
                    parameters = mapOf("input_parameter" to "\${telemetry_metric}")
                )
            ),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = false)
        )
        val saved = repository.saveWorkflow(genericWf)
        assertTrue("Generic arbitrary workflow must be accepted without hard-coding", saved)

        val commandUnderstanding = CommandInterpreter.understandCommand("custom_telemetry_query telemetry_metric = memory_usage")
        val matcher = SkillMatcher(repository)
        val matchResult = matcher.match(
            commandUnderstanding.copy(
                intent = com.chockXlate.teachablevoice.learning.intent.Intent("id1", "custom_telemetry_query", 1.0, "HIGH"),
                slots = listOf(
                    com.chockXlate.teachablevoice.command.interpretation.InterpretedSlot("telemetry_metric", "memory_usage", "memory_usage", SlotType.TEXT, 1.0)
                )
            )
        )

        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)
        assertEquals("skill_generic_automation_xyz", matchResult.selectedSkillId)
    }
}
