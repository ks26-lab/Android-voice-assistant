package com.chockXlate.teachablevoice.skill.repository

import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.skill.validation.ReplayAdmission
import com.chockXlate.teachablevoice.skill.validation.ValidationStatus
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe, deterministic implementation of [SkillRepository] with persistent disk storage backing.
 * Enforces validation prior to storage and rejects INVALID or BLOCKED workflows.
 * Survives Activity recreation and process restarts when [storageDir] is provided.
 */
class LocalSkillRepository(
    private val storageDir: File? = null
) : SkillRepository {

    private val store = ConcurrentHashMap<String, Workflow>()
    private val versionStore = ConcurrentHashMap<String, Int>()

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    init {
        loadPersistedWorkflows()
    }

    /**
     * Loads all valid persisted workflows from [storageDir] into the memory cache.
     */
    @Synchronized
    fun loadPersistedWorkflows() {
        if (storageDir == null) return
        try {
            if (!storageDir.exists()) {
                storageDir.mkdirs()
                return
            }
            val files = storageDir.listFiles { _, name -> name.endsWith(".json") } ?: return
            for (file in files) {
                try {
                    val jsonContent = file.readText()
                    val workflow = jsonFormatter.decodeFromString(Workflow.serializer(), jsonContent)
                    val validation = WorkflowValidator.validate(workflow)
                    if (validation.status == ValidationStatus.VALID && validation.isStoreable) {
                        store[workflow.skillId] = workflow
                        versionStore[workflow.skillId] = versionStore.getOrDefault(workflow.skillId, 1)
                    }
                } catch (e: Exception) {
                    // Ignore corrupted file during startup without crashing
                }
            }
        } catch (e: Exception) {
            // Storage directory access failure handled gracefully
        }
    }

    /** Production admission. saveWorkflow retains structural draft/import compatibility. */
    fun saveReplayableWorkflow(workflow: Workflow): Boolean {
        if (!ReplayAdmission.validate(workflow).isStoreable) return false
        return saveWorkflow(workflow)
    }

    override fun saveWorkflow(workflow: Workflow): Boolean {
        // Step 1: Validate Workflow prior to storage
        val validation = WorkflowValidator.validate(workflow)
        if (validation.status != ValidationStatus.VALID || !validation.isStoreable) {
            return false // Reject storage for INVALID or BLOCKED workflows
        }

        val skillId = workflow.skillId.ifBlank { generateDeterministicSkillId(workflow) }
        val canonicalWorkflow = if (workflow.skillId != skillId) workflow.copy(skillId = skillId) else workflow
        val currentVersion = versionStore.getOrDefault(skillId, 0)

        // Check duplicate save: if exact same workflow exists, retain without duplicate creation
        val existing = store[skillId]
        if (existing == canonicalWorkflow) {
            return true
        }

        // Save valid workflow and increment version count
        store[skillId] = canonicalWorkflow
        val newVersion = currentVersion + 1
        versionStore[skillId] = newVersion

        // Persist to disk if storage directory is configured
        persistWorkflowToDisk(canonicalWorkflow)

        return true
    }

    private fun persistWorkflowToDisk(workflow: Workflow) {
        if (storageDir == null) return
        try {
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }
            val file = File(storageDir, "${sanitizeFileName(workflow.skillId)}.json")
            val jsonContent = jsonFormatter.encodeToString(Workflow.serializer(), workflow)
            file.writeText(jsonContent)
        } catch (e: Exception) {
            // Handle disk write gracefully
        }
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
        if (storageDir != null) {
            try {
                val file = File(storageDir, "${sanitizeFileName(skillId)}.json")
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                // Ignore delete error
            }
        }
        return removed
    }

    override fun contains(skillId: String): Boolean {
        return store.containsKey(skillId)
    }

    override fun getSkillVersion(skillId: String): Int {
        return versionStore.getOrDefault(skillId, 0)
    }

    fun clear() {
        store.clear()
        versionStore.clear()
        if (storageDir != null) {
            try {
                storageDir.listFiles { _, name -> name.endsWith(".json") }?.forEach { it.delete() }
            } catch (e: Exception) {
                // Ignore clear error
            }
        }
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

        private fun sanitizeFileName(name: String): String {
            return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        }
    }
}

