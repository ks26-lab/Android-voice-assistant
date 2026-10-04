package com.chockXlate.teachablevoice.skill.repository

import com.chockXlate.teachablevoice.contract.skill.SkillRecord
import com.chockXlate.teachablevoice.contract.workflow.Workflow

/**
 * Skill Store interface for creating, persisting, and retrieving skills and learned workflows.
 */
interface SkillRepository {
    // Dynamic Skill Entity Management (Phase 2)
    fun createSkill(name: String, description: String = "", id: String = ""): SkillRecord
    fun getSkill(id: String): SkillRecord?
    fun listSkills(): List<SkillRecord>
    fun updateSkill(skill: SkillRecord): Boolean

    // Workflow IR Persistence (Phase 8)
    fun saveWorkflow(workflow: Workflow): Boolean
    fun getWorkflowById(skillId: String): Workflow?
    fun getAllWorkflows(): List<Workflow>
    fun deleteWorkflow(skillId: String): Boolean
    fun getSkillVersion(skillId: String): Int = 1
    fun contains(skillId: String): Boolean = getWorkflowById(skillId) != null || getSkill(skillId) != null
}

