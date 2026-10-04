package com.chockXlate.teachablevoice.teach

import com.chockXlate.teachablevoice.contract.skill.SkillStatus
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit Test Suite for Phase 2: Dynamic Skill Creation + Teaching Session Initialization.
 * Verifies P2-T1 through P2-T12 from the Phase 2 Test Matrix.
 */
class DynamicSkillCreationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var repository: LocalSkillRepository

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("skills_test")
        repository = LocalSkillRepository(storageDir)
        TeachingSessionManager.clearSession()
    }

    @After
    fun tearDown() {
        TeachingSessionManager.clearSession()
        repository.clear()
    }

    @Test
    fun test_P2_T1_create_search_google() {
        val skillName = "Search Google"
        val skillDesc = "Search Google for any query provided by the user."

        val skill = repository.createSkill(name = skillName, description = skillDesc)
        assertNotNull(skill)
        assertTrue("Skill ID must not be blank", skill.id.isNotBlank())
        assertTrue("Skill ID must follow pattern", skill.id.startsWith("skill_"))
        assertEquals(skillName, skill.name)
        assertEquals(skillDesc, skill.description)
        assertEquals(SkillStatus.TEACHING, skill.status)
        assertNull("New skill must have no workflow initially", skill.workflowId)

        // Start teaching session
        val session = TeachingSessionManager.startSession(
            skillName = skill.name,
            intent = skill.description,
            skillId = skill.id,
            description = skill.description
        )
        assertTrue(TeachingSessionManager.isTeachingActive())
        assertEquals(skill.id, TeachingSessionManager.getActiveSkillId())
        assertEquals(skill.id, session.skillId)
        assertEquals(skillName, session.skillName)
    }

    @Test
    fun test_P2_T2_create_open_amazon() {
        val skillName = "Open Amazon"
        val skillDesc = "Open Amazon and prepare it for a shopping task."

        val skill = repository.createSkill(name = skillName, description = skillDesc)
        assertNotNull(skill)
        assertEquals(skillName, skill.name)
        assertEquals(skillDesc, skill.description)
        assertFalse("Must not contain pizza references", skill.id.contains("pizza", ignoreCase = true))

        val session = TeachingSessionManager.startSession(
            skillName = skill.name,
            intent = skill.description,
            skillId = skill.id,
            description = skill.description
        )
        assertEquals(skill.id, session.skillId)
    }

    @Test
    fun test_P2_T3_create_order_food_generic() {
        val skillName = "Order Food"
        val skillDesc = "Order a food item using the selected application."

        val skill = repository.createSkill(name = skillName, description = skillDesc)
        assertNotNull(skill)
        assertEquals(skillName, skill.name)
        assertEquals(skillDesc, skill.description)

        // Must NOT create pizza steps or pizza IDs
        assertNull(repository.getWorkflowById(skill.id))
        assertFalse(skill.description.contains("Pizza Palace"))
        assertFalse(skill.description.contains("Margherita"))
    }

    @Test
    fun test_P2_T4_create_book_train_ticket() {
        val skillName = "Book a Train Ticket"
        val skillDesc = "Book a train ticket using the railway application."

        val skill = repository.createSkill(name = skillName, description = skillDesc)
        assertNotNull(skill)
        assertEquals(skillName, skill.name)
        assertEquals(skillDesc, skill.description)
        assertNull("Zero learned steps initially", repository.getWorkflowById(skill.id))
    }

    @Test
    fun test_P2_T5_two_skills_sequentially_separate_ids() {
        val skillA = repository.createSkill("Skill A", "First skill")
        val skillB = repository.createSkill("Skill B", "Second skill")

        assertNotEquals("Skill IDs must be unique", skillA.id, skillB.id)
        assertEquals(2, repository.listSkills().size)
        assertEquals(skillA, repository.getSkill(skillA.id))
        assertEquals(skillB, repository.getSkill(skillB.id))
    }

    @Test
    fun test_P2_T6_same_name_twice_no_accidental_overwrite() {
        val skill1 = repository.createSkill("Search Google", "Query 1")
        val skill2 = repository.createSkill("Search Google", "Query 2")

        assertNotEquals("Duplicate names must produce distinct unique IDs", skill1.id, skill2.id)
        assertEquals(2, repository.listSkills().size)
        assertNotNull(repository.getSkill(skill1.id))
        assertNotNull(repository.getSkill(skill2.id))
    }

    @Test(expected = IllegalArgumentException::class)
    fun test_P2_T7_blank_name_rejected() {
        repository.createSkill(name = "   ", description = "Test")
    }

    @Test
    fun test_P2_T8_arbitrary_description_preserved() {
        val customDesc = "Custom task instructions with special symbols: 123 @#$ & params"
        val skill = repository.createSkill("Custom Task", customDesc)
        assertEquals(customDesc, skill.description)

        val fetched = repository.getSkill(skill.id)
        assertEquals(customDesc, fetched?.description)
    }

    @Test
    fun test_P2_T9_new_skill_inspection_zero_learned_steps() {
        val skill = repository.createSkill("Untrained Skill", "Pending demonstration")
        assertNull("Untrained skill must have no workflow stored", repository.getWorkflowById(skill.id))
        assertEquals(SkillStatus.TEACHING, skill.status)
    }

    @Test
    fun test_P2_T10_teaching_session_correct_skill_id() {
        val skill = repository.createSkill("Test Skill", "Test Desc")
        val session = TeachingSessionManager.startSession(
            skillName = skill.name,
            intent = skill.description,
            skillId = skill.id,
            description = skill.description
        )

        assertEquals(skill.id, TeachingSessionManager.getActiveSkillId())
        assertEquals(skill.id, session.skillId)

        val stoppedTrace = TeachingSessionManager.stopSession()
        assertNotNull(stoppedTrace)
        assertFalse(TeachingSessionManager.isTeachingActive())
        assertNull(TeachingSessionManager.getActiveSkillId())
    }

    @Test
    fun test_P2_T11_restart_reopen_retrieval() {
        val skill = repository.createSkill("Persistent Skill", "Should survive restart")

        // Create new repository instance pointing to same storage directory
        val reloadedRepository = LocalSkillRepository(storageDir)
        val loadedSkill = reloadedRepository.getSkill(skill.id)

        assertNotNull("Skill must be retrievable from disk after reload", loadedSkill)
        assertEquals(skill.id, loadedSkill?.id)
        assertEquals(skill.name, loadedSkill?.name)
        assertEquals(skill.description, loadedSkill?.description)
    }

    @Test
    fun test_P2_T12_no_production_pizza_creation_branch() {
        val skill = repository.createSkill("Any Skill", "Any description")
        assertFalse(skill.id.contains("pizza", ignoreCase = true))
        assertFalse(skill.id.contains("order_food", ignoreCase = true))
    }
}
