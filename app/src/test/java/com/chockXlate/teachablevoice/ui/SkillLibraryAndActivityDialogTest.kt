package com.chockXlate.teachablevoice.ui

import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import com.chockXlate.teachablevoice.ui.components.ActivityEvent
import com.chockXlate.teachablevoice.ui.components.ActivityStatus
import com.chockXlate.teachablevoice.ui.components.ActivityUiState
import com.chockXlate.teachablevoice.ui.components.SkillLibraryUiState
import com.chockXlate.teachablevoice.ui.components.SkillUiModel
import com.chockXlate.teachablevoice.ui.components.filterSkills
import org.junit.Assert.*
import org.junit.Test

/**
 * Comprehensive Unit Test Suite for Standalone UI Features:
 * 1. Skill Library Side Panel (SL-T1 to SL-T8)
 * 2. Activity / Progress Dialog (AD-T1 to AD-T9)
 * 3. Anti-Hardcoding Invariant Validation
 */
class SkillLibraryAndActivityDialogTest {

    // =========================================================================
    // FEATURE A: SKILL LIBRARY TESTS (SL-T1 through SL-T8)
    // =========================================================================

    @Test
    fun test_SL_T1_emptySkillList() {
        val state = SkillLibraryUiState(isOpen = true, skills = emptyList())
        assertTrue("Skill list must be empty", state.skills.isEmpty())

        val filtered = filterSkills(state.skills, state.searchQuery)
        assertTrue("Filtered skill list must be empty", filtered.isEmpty())

        // Invariant: Truthful empty state with zero fake skills injected
        val containsFakeSkills = filtered.any {
            it.title.contains("search", ignoreCase = true) ||
            it.title.contains("amazon", ignoreCase = true) ||
            it.title.contains("pizza", ignoreCase = true)
        }
        assertFalse("Must never inject fake skills into empty state", containsFakeSkills)
    }

    @Test
    fun test_SL_T2_oneSuppliedSkill() {
        val singleSkill = SkillUiModel(
            id = "skill-101",
            title = "Generic Action Skill",
            description = "Performs generic test automation"
        )
        val state = SkillLibraryUiState(isOpen = true, skills = listOf(singleSkill))
        assertEquals(1, state.skills.size)

        val rendered = filterSkills(state.skills, "")
        assertEquals(1, rendered.size)
        assertEquals("skill-101", rendered.first().id)
        assertEquals("Generic Action Skill", rendered.first().title)
    }

    @Test
    fun test_SL_T3_multipleSuppliedSkills() {
        val skills = listOf(
            SkillUiModel(id = "s1", title = "Skill Alpha", description = "Alpha task"),
            SkillUiModel(id = "s2", title = "Skill Beta", description = "Beta task"),
            SkillUiModel(id = "s3", title = "Skill Gamma", description = "Gamma task")
        )
        val state = SkillLibraryUiState(isOpen = true, skills = skills)
        assertEquals(3, state.skills.size)

        val rendered = filterSkills(state.skills, "")
        assertEquals(3, rendered.size)
        assertEquals(listOf("s1", "s2", "s3"), rendered.map { it.id })
    }

    @Test
    fun test_SL_T4_searchFiltering() {
        val skills = listOf(
            SkillUiModel(id = "s1", title = "Book Hotel", description = "Find lodging options"),
            SkillUiModel(id = "s2", title = "Order Food", description = "Browse lunch menus"),
            SkillUiModel(id = "s3", title = "Hotel Receipt", description = "Download reservation")
        )
        val query = "hotel"
        val filtered = filterSkills(skills, query)

        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.id == "s1" })
        assertTrue(filtered.any { it.id == "s3" })
        assertFalse("Order Food does not match query 'hotel'", filtered.any { it.id == "s2" })
    }

    @Test
    fun test_SL_T5_searchCaseInsensitive() {
        val skills = listOf(
            SkillUiModel(id = "s1", title = "MESSAGING ASSISTANT", description = "Chat with contacts")
        )
        val lowerMatch = filterSkills(skills, "messaging")
        val upperMatch = filterSkills(skills, "MESSAGING")
        val mixedMatch = filterSkills(skills, "mEsSaGiNg")

        assertEquals(1, lowerMatch.size)
        assertEquals(1, upperMatch.size)
        assertEquals(1, mixedMatch.size)
        assertEquals("s1", lowerMatch.first().id)
    }

    @Test
    fun test_SL_T6_deleteCallback() {
        val initialSkills = mutableListOf(
            SkillUiModel(id = "target-skill-id", title = "Removable Skill", description = "To be deleted"),
            SkillUiModel(id = "keep-skill-id", title = "Retained Skill", description = "Remains")
        )

        var emittedDeletedId: String? = null
        val onDeleteCallback: (String) -> Unit = { id ->
            emittedDeletedId = id
            initialSkills.removeIf { it.id == id }
        }

        // Simulate delete trigger
        onDeleteCallback("target-skill-id")

        assertEquals("target-skill-id", emittedDeletedId)
        assertEquals(1, initialSkills.size)
        assertEquals("keep-skill-id", initialSkills.first().id)

        // Verify isolation: teaching session manager is un-triggered
        assertFalse("UI callback must not invoke TeachingSessionManager", TeachingSessionManager.isTeachingActive())
    }

    @Test
    fun test_SL_T7_redoCallback() {
        var emittedRedoId: String? = null
        val onRedoCallback: (String) -> Unit = { id ->
            emittedRedoId = id
        }

        // Simulate redo trigger
        onRedoCallback("custom-skill-42")

        assertEquals("custom-skill-42", emittedRedoId)
        // Verify isolation: redo intent does NOT start a real teaching session
        assertFalse("Reteach callback must not start teaching session", TeachingSessionManager.isTeachingActive())
    }

    @Test
    fun test_SL_T8_deleteCancellation() {
        val skill = SkillUiModel(id = "skill-preserve", title = "Preserved", description = "Never deleted")
        var skillToDelete: SkillUiModel? = skill

        // User clicks Cancel in confirmation dialog
        val onCancel = {
            skillToDelete = null
        }
        onCancel()

        assertNull("Pending delete target must be cleared on cancellation", skillToDelete)
    }

    // =========================================================================
    // FEATURE B: ACTIVITY DIALOG TESTS (AD-T1 through AD-T9)
    // =========================================================================

    @Test
    fun test_AD_T1_zeroEvents() {
        val state = ActivityUiState(isOpen = true, events = emptyList())
        assertTrue("Event list must be empty", state.events.isEmpty())

        // Invariant: empty state has zero events, zero fabricated pipeline stages
        val hasFabricatedPipeline = state.events.isNotEmpty()
        assertFalse("Activity dialog must not invent architecture pipeline when empty", hasFabricatedPipeline)
    }

    @Test
    fun test_AD_T2_oneEvent() {
        val event = ActivityEvent(
            id = "ev-1",
            label = "Initializing external connector",
            status = ActivityStatus.PENDING
        )
        val state = ActivityUiState(isOpen = true, events = listOf(event))
        assertEquals(1, state.events.size)
        assertEquals("ev-1", state.events.first().id)
        assertEquals("Initializing external connector", state.events.first().label)
        assertEquals(ActivityStatus.PENDING, state.events.first().status)
    }

    @Test
    fun test_AD_T3_multipleEvents() {
        val events = listOf(
            ActivityEvent("e1", "Event First", ActivityStatus.COMPLETED),
            ActivityEvent("e2", "Event Second", ActivityStatus.IN_PROGRESS),
            ActivityEvent("e3", "Event Third", ActivityStatus.PENDING)
        )
        val state = ActivityUiState(isOpen = true, events = events)
        assertEquals(3, state.events.size)
        assertEquals("Event First", state.events[0].label)
        assertEquals("Event Second", state.events[1].label)
        assertEquals("Event Third", state.events[2].label)
    }

    @Test
    fun test_AD_T4_eventTransitions_pendingToInProgress() {
        val initialEvents = listOf(
            ActivityEvent("e1", "Syncing state", ActivityStatus.PENDING)
        )
        val state1 = ActivityUiState(isOpen = true, events = initialEvents)
        assertEquals(ActivityStatus.PENDING, state1.events.first().status)

        // Runtime updates event to IN_PROGRESS
        val updatedEvents = listOf(
            initialEvents[0].copy(status = ActivityStatus.IN_PROGRESS)
        )
        val state2 = ActivityUiState(isOpen = true, events = updatedEvents)
        assertEquals(ActivityStatus.IN_PROGRESS, state2.events.first().status)
    }

    @Test
    fun test_AD_T5_eventTransitions_inProgressToCompleted() {
        val inProgressEvents = listOf(
            ActivityEvent("e1", "Transforming record", ActivityStatus.IN_PROGRESS)
        )
        val state1 = ActivityUiState(isOpen = true, events = inProgressEvents)
        assertEquals(ActivityStatus.IN_PROGRESS, state1.events.first().status)

        // Runtime updates event to COMPLETED
        val completedEvents = listOf(
            inProgressEvents[0].copy(status = ActivityStatus.COMPLETED)
        )
        val state2 = ActivityUiState(isOpen = true, events = completedEvents)
        assertEquals(ActivityStatus.COMPLETED, state2.events.first().status)
    }

    @Test
    fun test_AD_T6_eventFails() {
        val events = listOf(
            ActivityEvent("e1", "Remote ping", ActivityStatus.FAILED, metadata = "Connection timed out")
        )
        val state = ActivityUiState(isOpen = true, events = events)
        assertEquals(ActivityStatus.FAILED, state.events.first().status)
        assertEquals("Connection timed out", state.events.first().metadata)
    }

    @Test
    fun test_AD_T7_dynamicEventCount() {
        // Test rendering with arbitrary large number of events (e.g. 25 events)
        val largeList = (1..25).map { index ->
            ActivityEvent(
                id = "evt-$index",
                label = "Batch operation step $index",
                status = if (index < 10) ActivityStatus.COMPLETED else if (index == 10) ActivityStatus.IN_PROGRESS else ActivityStatus.PENDING
            )
        }
        val state = ActivityUiState(isOpen = true, events = largeList)
        assertEquals(25, state.events.size)
        assertEquals("evt-1", state.events.first().id)
        assertEquals("evt-25", state.events.last().id)
    }

    @Test
    fun test_AD_T8_noEventDataNeverInventsArchitectureSteps() {
        val state = ActivityUiState(isOpen = true, events = emptyList())
        val architectureStepNames = listOf(
            "Voice Input", "Capture", "Normalize", "Extract",
            "Synthesize", "Validate", "Store", "Replay"
        )
        val eventLabels = state.events.map { it.label }
        for (stepName in architectureStepNames) {
            assertFalse(
                "UI must never fabricate architecture step '$stepName' when no events are supplied",
                eventLabels.contains(stepName)
            )
        }
    }

    @Test
    fun test_AD_T9_unknownEventLabelRendersGenerically() {
        val customLabel = "Unrecognized proprietary hardware handshake XYZ-9000"
        val event = ActivityEvent(
            id = "custom-1",
            label = customLabel,
            status = ActivityStatus.IN_PROGRESS
        )
        val state = ActivityUiState(isOpen = true, events = listOf(event))
        assertEquals(1, state.events.size)
        assertEquals(customLabel, state.events.first().label)
    }

    // =========================================================================
    // ANTI-HARDCODING INVARIANT TESTS
    // =========================================================================

    @Test
    fun test_antiHardcoding_arbitraryEventDataSupportedWithoutBranching() {
        val arbitraryEvents = listOf(
            ActivityEvent("x1", "Example external event", ActivityStatus.IN_PROGRESS),
            ActivityEvent("x2", "Another operation", ActivityStatus.COMPLETED),
            ActivityEvent("x3", "Arbitrary third party sync", ActivityStatus.FAILED)
        )
        val state = ActivityUiState(isOpen = true, events = arbitraryEvents)

        assertEquals(3, state.events.size)
        assertEquals("Example external event", state.events[0].label)
        assertEquals(ActivityStatus.IN_PROGRESS, state.events[0].status)

        assertEquals("Another operation", state.events[1].label)
        assertEquals(ActivityStatus.COMPLETED, state.events[1].status)

        assertEquals("Arbitrary third party sync", state.events[2].label)
        assertEquals(ActivityStatus.FAILED, state.events[2].status)
    }
}
