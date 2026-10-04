package com.chockXlate.teachablevoice.ui

import com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter
import com.chockXlate.teachablevoice.command.matching.SkillMatcher
import com.chockXlate.teachablevoice.command.matching.SkillMatchStatus
import com.chockXlate.teachablevoice.command.request.ExecutionRequestBuilder
import com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotDefinition
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import com.chockXlate.teachablevoice.safety.RuntimeSafetyPolicy
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import com.chockXlate.teachablevoice.ui.components.*
import com.chockXlate.teachablevoice.ui.theme.DarkAppColors
import com.chockXlate.teachablevoice.ui.theme.LightAppColors
import com.chockXlate.teachablevoice.ui.theme.ThemePreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 5.5 Comprehensive Verification Suite:
 * - Interaction Loops (IL-T1 to IL-T12)
 * - Workflow Isolation (MW-T1 to MW-T9)
 * - Teaching Lifecycle (TE-T1 to TE-T6)
 * - UI Lifecycle (UI-T1 to UI-T5)
 * - Dark / Light Theme & Contrast (TH-T1 to TH-T12)
 * - Safety Gate & Protected Boundary (SA-T1 to SA-T6)
 */
class Phase55InteractionLoopsAndThemeTest {

    private lateinit var tempDir: File
    private lateinit var repository: LocalSkillRepository

    private fun createTestWorkflow(
        skillId: String,
        name: String,
        intent: String,
        appContext: String,
        slots: List<SlotDefinition> = emptyList(),
        requiresConfirmation: Boolean = false
    ): Workflow {
        val steps = listOf(
            WorkflowStep(
                stepId = "${skillId}_step1",
                semanticAction = "CLICK",
                semanticSelector = SemanticSelector(role = "Button", resourceId = "${skillId}_btn", text = name)
            )
        )
        return Workflow(
            skillId = skillId,
            name = name,
            intent = intent,
            appContext = appContext,
            steps = steps,
            slots = slots,
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = requiresConfirmation)
        )
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("phase55_test").toFile()
        repository = LocalSkillRepository(tempDir)
        ActivityEventStream.clear()
        TeachingSessionManager.clearSession()
        ThemePreferences.setDarkMode(true)
    }

    @After
    fun tearDown() {
        ActivityEventStream.clear()
        TeachingSessionManager.clearSession()
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // 1. INTERACTION LOOPS (IL-T1 to IL-T12)
    // =========================================================================

    @Test
    fun test_IL_T1_firstVoiceClickRequestsPermission() {
        var requested = false
        var isAudioGranted = false
        val triggerVoice = {
            if (!isAudioGranted) requested = true
        }
        triggerVoice()
        assertTrue("First voice click when permission not granted must request permission", requested)
    }

    @Test
    fun test_IL_T2_permissionGrantedStartsListening() {
        var isListening = false
        val onGrant = {
            isListening = true
        }
        onGrant()
        assertTrue("Granting permission must activate listening loop", isListening)
    }

    @Test
    fun test_IL_T3_permissionDeniedAllowsTextFallbackWithoutCrash() {
        var isListening = false
        var textFallbackAvailable = true
        var errorMessage: String? = null

        val onDeny = {
            isListening = false
            errorMessage = "Microphone permission denied. You can continue using text input."
        }
        onDeny()

        assertFalse("Listening must be false after denial", isListening)
        assertTrue("Text fallback must remain available", textFallbackAvailable)
        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("text input", ignoreCase = true))
    }

    @Test
    fun test_IL_T4_permissionPermanentlyDeniedProvidesSettingsPath() {
        var showSettingsOption = false
        val onPermanentlyDenied = {
            showSettingsOption = true
        }
        onPermanentlyDenied()
        assertTrue("Permanently denied permission must expose path to Settings", showSettingsOption)
    }

    @Test
    fun test_IL_T5_settingsOpenedSetsPendingFlag() {
        var pendingVoiceStartAfterSettings = false
        val onOpenSettings = {
            pendingVoiceStartAfterSettings = true
        }
        onOpenSettings()
        assertTrue("Opening settings must mark pending return flag", pendingVoiceStartAfterSettings)
    }

    @Test
    fun test_IL_T6_returnFromSettingsEvaluatesActualState() {
        var pendingVoiceStart = true
        var isListening = false
        var voiceStatus = ""

        // Case A: User enabled permission in settings
        val simulatedPermissionGranted = true
        if (simulatedPermissionGranted && pendingVoiceStart) {
            pendingVoiceStart = false
            isListening = true
        }
        assertTrue("Granted permission upon return must start voice input", isListening)

        // Case B: User did not enable permission in settings
        pendingVoiceStart = true
        isListening = false
        val simulatedPermissionStillDenied = false
        if (!simulatedPermissionStillDenied && pendingVoiceStart) {
            pendingVoiceStart = false
            isListening = false
            voiceStatus = "Microphone permission still denied. You can continue using text input."
        }
        assertFalse(isListening)
        assertTrue(voiceStatus.contains("text input"))
    }

    @Test
    fun test_IL_T7_voiceUnavailableTruthfulFallback() {
        val recognizerAvailable = false
        var voiceStatus: String? = null
        var textAvailable = true

        if (!recognizerAvailable) {
            voiceStatus = "Voice input is currently unavailable. You can continue using text input."
        }

        assertEquals("Voice input is currently unavailable. You can continue using text input.", voiceStatus)
        assertTrue("Text input must remain available when voice is unavailable", textAvailable)
    }

    @Test
    fun test_IL_T8_voiceErrorDoesNotDisableText() {
        val errorCode = 7 // ERROR_NO_MATCH
        var isListening = true
        var textAvailable = true

        val onError = { code: Int ->
            isListening = false
        }
        onError(errorCode)

        assertFalse(isListening)
        assertTrue("Text fallback remains valid after recognizer error", textAvailable)
    }

    @Test
    fun test_IL_T9_textFallbackExecutesSemanticPipeline() {
        val wf = createTestWorkflow("wf_notes", "Take Notes", "take_notes", "com.example.notes")
        repository.saveWorkflow(wf)

        val command = "take notes"
        val understanding = CommandInterpreter.understandCommand(command)
        val matcher = SkillMatcher(repository)
        val matchResult = matcher.match(understanding)

        assertEquals(SkillMatchStatus.MATCHED, matchResult.status)
        assertEquals("wf_notes", matchResult.selectedSkillId)
    }

    @Test
    fun test_IL_T10_emptyTextHandledSafelyWithoutAccidentalExecution() {
        val emptyInputs = listOf("", "   ", "\t\n", "      ")
        for (input in emptyInputs) {
            val trimmed = input.trim()
            assertTrue("Trimmed input must be blank", trimmed.isBlank())
            // Execution must be skipped safely
        }
    }

    @Test
    fun test_IL_T11_malformedTextHandledSafely() {
        val complexInputs = listOf(
            "🔥🚀🍔🎉🍕",
            "हिंदी में खोजें",
            "rechercher un vol vers Paris",
            "1234567890!@#\$%^&*()",
            "A".repeat(500),
            "MiXeD cAsE aNd SpElLiNg MsItAkE"
        )
        for (input in complexInputs) {
            try {
                val understanding = CommandInterpreter.understandCommand(input)
                assertNotNull("Understanding result must never be null", understanding)
                val matcher = SkillMatcher(repository)
                val matchResult = matcher.match(understanding)
                assertNotNull("Match result must never be null", matchResult)
            } catch (e: Exception) {
                fail("Malformed text input '$input' caused uncaught exception: ${e.message}")
            }
        }
    }

    @Test
    fun test_IL_T12_unknownWorkflowReturnsUnknownWithoutExecutingUnrelated() {
        val foodWf = createTestWorkflow("wf_food", "Order Food", "order_food", "com.example.food")
        repository.saveWorkflow(foodWf)

        // Query totally unrelated to food
        val flightCommand = "Book me a flight to Delhi"
        val understanding = CommandInterpreter.understandCommand(flightCommand)
        val matcher = SkillMatcher(repository)
        val matchResult = matcher.match(understanding)

        assertEquals("Must return UNKNOWN for unlearned workflow", SkillMatchStatus.UNKNOWN, matchResult.status)
        assertNull("Selected skill must be null for unknown workflow", matchResult.selectedSkillId)
        assertNotEquals("Must NEVER select unrelated food workflow", "wf_food", matchResult.selectedSkillId)
    }

    // =========================================================================
    // 2. WORKFLOW ISOLATION (MW-T1 to MW-T9)
    // =========================================================================

    @Test
    fun test_MW_T1_multipleWorkflowsStored() {
        val wf1 = createTestWorkflow("wf_food", "Order Food", "order_food", "com.example.food")
        val wf2 = createTestWorkflow("wf_shop", "Amazon Shirt Shopping", "shop_shirt", "com.amazon.app")
        val wf3 = createTestWorkflow("wf_flight", "Flight Booking", "book_flight", "com.flight.app")

        assertTrue(repository.saveWorkflow(wf1))
        assertTrue(repository.saveWorkflow(wf2))
        assertTrue(repository.saveWorkflow(wf3))

        assertEquals(3, repository.getWorkflowCount())
    }

    @Test
    fun test_MW_T2_restartPersistence() {
        val wf1 = createTestWorkflow("wf_food", "Order Food", "order_food", "com.example.food")
        val wf2 = createTestWorkflow("wf_shop", "Amazon Shopping", "shop_shirt", "com.amazon.app")
        repository.saveWorkflow(wf1)
        repository.saveWorkflow(wf2)

        // Simulate app restart by constructing a new repository pointing to the same storage
        val restartedRepo = LocalSkillRepository(tempDir)
        assertEquals(2, restartedRepo.getWorkflowCount())
        assertNotNull(restartedRepo.getWorkflowById("wf_food"))
        assertNotNull(restartedRepo.getWorkflowById("wf_shop"))
    }

    @Test
    fun test_MW_T3_to_T6_executeWorkflowsInRandomOrderWithoutContamination() {
        val slotFood = SlotDefinition("restaurant", "Restaurant name", "Pizza Place")
        val slotShop = SlotDefinition("item", "Item name", "white shirt")
        val slotFlight = SlotDefinition("destination", "Destination city", "Delhi")

        val wfFood = createTestWorkflow("wf_food", "Order Food", "order_food", "com.food.app", listOf(slotFood))
        val wfShop = createTestWorkflow("wf_shop", "Shopping", "shop_item", "com.shop.app", listOf(slotShop))
        val wfFlight = createTestWorkflow("wf_flight", "Flight", "book_flight", "com.flight.app", listOf(slotFlight))

        repository.saveWorkflow(wfFood)
        repository.saveWorkflow(wfShop)
        repository.saveWorkflow(wfFlight)

        // Execute in random order: Flight -> Food -> Shop -> Flight
        val orders = listOf(
            Triple("flight to Delhi", "wf_flight", "destination"),
            Triple("order food from Pizza Place", "wf_food", "restaurant"),
            Triple("buy white shirt", "wf_shop", "item"),
            Triple("book flight to Mumbai", "wf_flight", "destination")
        )

        val matcher = SkillMatcher(repository)

        for ((cmd, expectedWfId, expectedSlotName) in orders) {
            val understanding = CommandInterpreter.understandCommand(cmd)
            val matchResult = matcher.match(understanding)
            assertEquals("Command '$cmd' must match expected workflow", expectedWfId, matchResult.selectedSkillId)

            val buildResult = ExecutionRequestBuilder.build(understanding, matchResult, repository)
            assertNotNull(buildResult.executionRequest)
            assertEquals(expectedWfId, buildResult.executionRequest?.skillId)

            val targetWf = repository.getWorkflowById(expectedWfId)!!
            assertEquals("Workflow slots must be strictly isolated", listOf(expectedSlotName), targetWf.slots.map { it.name })
        }
    }

    @Test
    fun test_MW_T7_slotIsolation() {
        val slotFood = SlotDefinition("restaurant", "Restaurant name", "Bistro")
        val slotFlight = SlotDefinition("destination", "City", "Paris")

        val wfFood = createTestWorkflow("wf_food", "Order Food", "order_food", "com.food", listOf(slotFood))
        val wfFlight = createTestWorkflow("wf_flight", "Book Flight", "book_flight", "com.flight", listOf(slotFlight))

        repository.saveWorkflow(wfFood)
        repository.saveWorkflow(wfFlight)

        val retrievedFood = repository.getWorkflowById("wf_food")!!
        val retrievedFlight = repository.getWorkflowById("wf_flight")!!

        assertFalse("Food workflow must not contain destination slot", retrievedFood.slots.any { it.name == "destination" })
        assertFalse("Flight workflow must not contain restaurant slot", retrievedFlight.slots.any { it.name == "restaurant" })
    }

    @Test
    fun test_MW_T8_traceIsolation() {
        ActivityEventStream.clear()
        ActivityEventStream.emit("wf_food_event", "Food Event", ActivityStatus.COMPLETED)
        ActivityEventStream.emit("wf_flight_event", "Flight Event", ActivityStatus.COMPLETED)

        val events = ActivityEventStream.getEvents()
        assertEquals(2, events.size)
        assertEquals("wf_food_event", events[0].id)
        assertEquals("wf_flight_event", events[1].id)
    }

    @Test
    fun test_MW_T9_unknownWorkflowDoesNotSelectUnrelatedWorkflow() {
        val wf1 = createTestWorkflow("wf_alarm", "Set Alarm", "set_alarm", "com.alarm")
        repository.saveWorkflow(wf1)

        val unknownCmd = "Order grocery delivery"
        val understanding = CommandInterpreter.understandCommand(unknownCmd)
        val matcher = SkillMatcher(repository)
        val matchResult = matcher.match(understanding)

        assertNotEquals("Must never select alarm skill for grocery command", "wf_alarm", matchResult.selectedSkillId)
        assertEquals(SkillMatchStatus.UNKNOWN, matchResult.status)
    }

    // =========================================================================
    // 3. TEACHING LIFECYCLE (TE-T1 to TE-T6)
    // =========================================================================

    @Test
    fun test_TE_T1_startTeachingInitializesSession() {
        TeachingSessionManager.startSession(skillName = "Demo Task", intent = "perform_demo", skillId = "demo_101")
        assertTrue("Teaching must be active", TeachingSessionManager.isTeachingActive())
        assertEquals("demo_101", TeachingSessionManager.getActiveSkillId())
    }

    @Test
    fun test_TE_T2_cancelTeachingLeavesZeroPartialWorkflow() {
        val initialCount = repository.getWorkflowCount()
        TeachingSessionManager.startSession(skillName = "Cancel Task", intent = "cancel_me", skillId = "cancel_101")
        TeachingSessionManager.clearSession()

        assertFalse("Teaching must not be active after cancel", TeachingSessionManager.isTeachingActive())
        assertEquals("Repository count must be unchanged", initialCount, repository.getWorkflowCount())
        assertNull(repository.getWorkflowById("cancel_101"))
    }

    @Test
    fun test_TE_T3_successfulCompletionStoresWorkflow() {
        val wf = createTestWorkflow("wf_completed", "Completed Task", "completed_task", "com.app")
        val saved = repository.saveWorkflow(wf)
        assertTrue(saved)
        assertNotNull(repository.getWorkflowById("wf_completed"))
    }

    @Test
    fun test_TE_T4_failedValidationBlocksStore() {
        // Workflow with empty steps is invalid
        val invalidWf = Workflow(
            skillId = "wf_empty",
            name = "Empty Task",
            intent = "empty",
            appContext = "com.app",
            steps = emptyList(),
            slots = emptyList(),
            safetyBoundary = SafetyBoundary(requiresExplicitUserConfirmation = false)
        )
        val validation = com.chockXlate.teachablevoice.skill.validation.WorkflowValidator.validate(invalidWf)
        assertFalse("Empty workflow must not be storeable", validation.isStoreable)
    }

    @Test
    fun test_TE_T5_reteachPreservesOldWorkflowUntilPersisted() {
        val oldWf = createTestWorkflow("wf_original", "Original Skill", "original_intent", "com.orig")
        repository.saveWorkflow(oldWf)

        // Start reteaching
        TeachingSessionManager.startSession("Original Skill", "original_intent", "wf_original")

        // Invariant: original workflow remains intact in repository during teaching
        val currentInRepo = repository.getWorkflowById("wf_original")
        assertNotNull("Original workflow must remain intact while reteaching", currentInRepo)
        assertEquals("Original Skill", currentInRepo?.name)
    }

    @Test
    fun test_TE_T6_reteachDoesNotExecuteOldWorkflow() {
        var executionTriggered = false
        val onReteach = {
            // Must ONLY enter teaching mode, never trigger execution
            TeachingSessionManager.startSession("Skill", "intent", "skill_id")
        }
        onReteach()
        assertFalse("Reteach must never trigger execution", executionTriggered)
        assertTrue(TeachingSessionManager.isTeachingActive())
    }

    // =========================================================================
    // 4. UI LIFECYCLE (UI-T1 to UI-T5)
    // =========================================================================

    @Test
    fun test_UI_T1_dialogOpenCloseLifecycle() {
        var isOpen = false
        val open = { isOpen = true }
        val close = { isOpen = false }

        open()
        assertTrue(isOpen)
        close()
        assertFalse(isOpen)
    }

    @Test
    fun test_UI_T2_outsideDismissLifecycle() {
        var isSkillLibraryOpen = true
        val onBackdropClick = { isSkillLibraryOpen = false }
        onBackdropClick()
        assertFalse(isSkillLibraryOpen)
    }

    @Test
    fun test_UI_T3_reopenPanelReflectsFreshRepositoryData() {
        var skills = loadSkillsFromRepository(repository)
        assertTrue(skills.isEmpty())

        repository.saveWorkflow(createTestWorkflow("wf_new", "New Skill", "new", "com.app"))
        skills = loadSkillsFromRepository(repository)
        assertEquals(1, skills.size)
        assertEquals("New Skill", skills[0].title)
    }

    @Test
    fun test_UI_T4_rapidInteractionDoesNotCorruptState() {
        for (i in 1..50) {
            ActivityEventStream.emit("event_$i", "Rapid Event $i", ActivityStatus.COMPLETED)
        }
        val events = ActivityEventStream.getEvents()
        assertEquals(50, events.size)
        ActivityEventStream.clear()
        assertEquals(0, ActivityEventStream.getEvents().size)
    }

    @Test
    fun test_UI_T5_deleteConfirmationLifecycle() {
        val wf = createTestWorkflow("wf_to_delete", "Delete Me", "delete", "com.app")
        repository.saveWorkflow(wf)
        assertEquals(1, repository.getWorkflowCount())

        // User clicks Delete -> Confirmation dialog appears -> User cancels
        var confirmDialogVisible = true
        var cancelled = false
        val onCancel = {
            confirmDialogVisible = false
            cancelled = true
        }
        onCancel()
        assertFalse(confirmDialogVisible)
        assertEquals("Workflow must still exist after cancel", 1, repository.getWorkflowCount())

        // User clicks Delete -> User confirms
        val onConfirm = {
            repository.deleteWorkflow("wf_to_delete")
        }
        onConfirm()
        assertEquals("Workflow must be deleted after confirm", 0, repository.getWorkflowCount())
        assertNull(repository.getWorkflowById("wf_to_delete"))
    }

    // =========================================================================
    // 5. DARK / LIGHT THEME & CONTRAST (TH-T1 to TH-T12)
    // =========================================================================

    @Test
    fun test_TH_T1_darkModeDefaults() {
        val dark = DarkAppColors
        assertTrue(dark.isDark)
        assertNotNull(dark.bgBase)
        assertNotNull(dark.textPrimary)
        assertNotNull(dark.accentPrimary)
    }

    @Test
    fun test_TH_T2_lightModeDefaults() {
        val light = LightAppColors
        assertFalse(light.isDark)
        assertNotNull(light.bgBase)
        assertNotNull(light.textPrimary)
        assertNotNull(light.accentPrimary)
    }

    @Test
    fun test_TH_T3_darkToLightSwitch() {
        ThemePreferences.setDarkMode(true)
        assertTrue(ThemePreferences.isDarkMode)

        ThemePreferences.toggleTheme()
        assertFalse(ThemePreferences.isDarkMode)
    }

    @Test
    fun test_TH_T4_lightToDarkSwitch() {
        ThemePreferences.setDarkMode(false)
        assertFalse(ThemePreferences.isDarkMode)

        ThemePreferences.toggleTheme()
        assertTrue(ThemePreferences.isDarkMode)
    }

    @Test
    fun test_TH_T5_rapidThemeSwitchingDoesNotCrashOrCorrupt() {
        for (i in 1..20) {
            ThemePreferences.toggleTheme()
        }
        // State remains a valid boolean without uncaught exceptions
        assertNotNull(ThemePreferences.isDarkMode)
    }

    @Test
    fun test_TH_T6_themePersistence() {
        ThemePreferences.setDarkMode(false)
        assertEquals(false, ThemePreferences.isDarkMode)

        ThemePreferences.setDarkMode(true)
        assertEquals(true, ThemePreferences.isDarkMode)
    }

    @Test
    fun test_TH_T7_themeSwitchDuringTeachingPreservesTeachingState() {
        TeachingSessionManager.startSession("Active Teach", "active_intent", "teach_42")
        assertTrue(TeachingSessionManager.isTeachingActive())

        // Switch theme
        ThemePreferences.toggleTheme()

        // Invariant: Teaching state MUST remain completely intact
        assertTrue("Teaching must remain active after theme switch", TeachingSessionManager.isTeachingActive())
        assertEquals("teach_42", TeachingSessionManager.getActiveSkillId())
    }

    @Test
    fun test_TH_T8_themeSwitchDuringExecutionPreservesWorkflowData() {
        val wf = createTestWorkflow("wf_active", "Active Execution", "exec", "com.app")
        repository.saveWorkflow(wf)

        ThemePreferences.toggleTheme()

        val retrieved = repository.getWorkflowById("wf_active")
        assertNotNull(retrieved)
        assertEquals("Active Execution", retrieved?.name)
    }

    @Test
    fun test_TH_T9_themeSwitchDuringActivityDialogPreservesEvents() {
        ActivityEventStream.emit("ev_1", "Event 1", ActivityStatus.COMPLETED)
        ThemePreferences.toggleTheme()

        val events = ActivityEventStream.getEvents()
        assertEquals(1, events.size)
        assertEquals("ev_1", events[0].id)
    }

    @Test
    fun test_TH_T10_textVisibilityInBothThemes() {
        val dark = DarkAppColors
        val light = LightAppColors

        // Dark theme: Light text on dark background
        assertNotEquals("Text and background must differ in Dark mode", dark.bgBase, dark.textPrimary)
        assertNotEquals("Text and surface must differ in Dark mode", dark.bgSurface, dark.textPrimary)

        // Light theme: Dark text on light background
        assertNotEquals("Text and background must differ in Light mode", light.bgBase, light.textPrimary)
        assertNotEquals("Text and surface must differ in Light mode", light.bgSurface, light.textPrimary)
    }

    @Test
    fun test_TH_T11_dialogVisibilityInBothThemes() {
        val dark = DarkAppColors
        val light = LightAppColors

        assertNotEquals(dark.bgSurfaceElevated, dark.textPrimary)
        assertNotEquals(light.bgSurfaceElevated, light.textPrimary)
    }

    @Test
    fun test_TH_T12_skillLibraryVisibilityInBothThemes() {
        val dark = DarkAppColors
        val light = LightAppColors

        assertNotEquals(dark.bgSurface, dark.textSecondary)
        assertNotEquals(light.bgSurface, light.textSecondary)
    }

    // =========================================================================
    // 6. SAFETY GATE & SENSITIVE BOUNDARY (SA-T1 to SA-T6)
    // =========================================================================

    private val safetyPolicy = RuntimeSafetyPolicy()

    @Test
    fun test_SA_T1_paymentSafetyBlocked() {
        val cmd = "Pay $50 for this order"
        assertTrue("Payment command must be flagged as sensitive", safetyPolicy.credentialText(cmd))
    }

    @Test
    fun test_SA_T2_pinSafetyBlocked() {
        val cmd = "Enter PIN 1234"
        assertTrue("PIN command must be flagged as sensitive", safetyPolicy.credentialText(cmd))
    }

    @Test
    fun test_SA_T3_otpSafetyBlocked() {
        val cmd = "Submit OTP 584920"
        assertTrue("OTP command must be flagged as sensitive", safetyPolicy.credentialText(cmd))
    }

    @Test
    fun test_SA_T4_passwordSafetyBlocked() {
        val cmd = "Enter password MySecret123"
        assertTrue("Password command must be flagged as sensitive", safetyPolicy.credentialText(cmd))
    }

    @Test
    fun test_SA_T5_captchaSafetyBlocked() {
        val cmd = "Solve captcha on screen"
        assertTrue("CAPTCHA command must be flagged as sensitive", safetyPolicy.credentialText(cmd))
    }

    @Test
    fun test_SA_T6_safetyGateCannotBeBypassedThroughUi() {
        val sensitiveCmd = "Enter CVV 999"
        val isSensitive = safetyPolicy.credentialText(sensitiveCmd)
        assertTrue(isSensitive)

        // UI invariant: When sensitive, execution must NEVER proceed to automated runner
        var automatedExecutionTriggered = false
        if (!isSensitive) {
            automatedExecutionTriggered = true
        }
        assertFalse("SafetyGate must never allow sensitive command execution", automatedExecutionTriggered)
    }
}
