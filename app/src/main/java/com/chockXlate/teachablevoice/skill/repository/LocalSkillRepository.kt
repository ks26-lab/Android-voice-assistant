package com.chockXlate.teachablevoice.skill.repository

import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.skill.validation.ValidationStatus
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe, deterministic local in-memory implementation of [SkillRepository].
 * Enforces validation prior to storage and rejects INVALID or BLOCKED workflows.
 */
class LocalSkillRepository : SkillRepository {

    private val store = ConcurrentHashMap<String, Workflow>()
    private val versionStore = ConcurrentHashMap<String, Int>()

    /** Production admission. saveWorkflow retains structural draft/import compatibility. */
    fun saveReplayableWorkflow(workflow: Workflow): Boolean {
        if (!com.chockXlate.teachablevoice.skill.validation.ReplayAdmission.validate(workflow).isStoreable) return false
        return saveWorkflow(workflow)
    }

    override fun saveWorkflow(workflow: Workflow): Boolean {
        // Step 1: Validate Workflow prior to storage
        val validation = WorkflowValidator.validate(workflow)
        if (validation.status != ValidationStatus.VALID || !validation.isStoreable) {
            return false // Reject storage for INVALID or BLOCKED workflows
        }

        val skillId = workflow.skillId.ifBlank { generateDeterministicSkillId(workflow) }
        val currentVersion = versionStore.getOrDefault(skillId, 0)

        // Check duplicate save: if exact same workflow exists, retain without duplicate creation
        val existing = store[skillId]
        if (existing == workflow) {
            return true
        }

        // Save valid workflow and increment version count
        store[skillId] = workflow
        versionStore[skillId] = currentVersion + 1
        return true
    }

    override fun getWorkflowById(skillId: String): Workflow? {
        return store[skillId]
    }

    override fun getAllWorkflows(): List<Workflow> {
        return store.values.sortedBy { it.skillId }
    }

    override fun deleteWorkflow(skillId: String): Boolean {
        val removed = store.remove(skillId) != null
        versionStore.remove(skillId)
        return removed
    }

    fun contains(skillId: String): Boolean {
        return store.containsKey(skillId)
    }

    fun getSkillVersion(skillId: String): Int {
        return versionStore.getOrDefault(skillId, 0)
    }

    fun clear() {
        store.clear()
        versionStore.clear()
    }

    fun getWorkflowCount(): Int {
        return store.size
    }

    companion object {
        fun generateDeterministicSkillId(workflow: Workflow): String {
            val intent = workflow.intent.lowercase().trim()
            val stepCount = workflow.steps.size
            val slotCount = workflow.slots.size
            val hash = (workflow.appContext + workflow.steps.joinToString { it.semanticAction }).hashCode()
            val posHash = if (hash < 0) -hash else hash
            return "skill_${intent}_s${stepCount}_p${slotCount}_${posHash.toString(16)}"
        }
    }
}
