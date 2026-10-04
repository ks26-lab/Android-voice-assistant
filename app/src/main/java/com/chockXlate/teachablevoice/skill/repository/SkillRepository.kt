package com.chockXlate.teachablevoice.skill.repository

import com.chockXlate.teachablevoice.contract.workflow.Workflow

/**
 * Skill Store interface for persisting and retrieving learned workflows.
 */
interface SkillRepository {
    fun saveWorkflow(workflow: Workflow): Boolean
    fun getWorkflowById(skillId: String): Workflow?
    fun getAllWorkflows(): List<Workflow>
    fun deleteWorkflow(skillId: String): Boolean
    fun getSkillVersion(skillId: String): Int = 1
    fun contains(skillId: String): Boolean = getWorkflowById(skillId) != null
}
