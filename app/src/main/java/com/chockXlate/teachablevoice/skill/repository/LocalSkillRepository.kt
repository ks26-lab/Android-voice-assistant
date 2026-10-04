package com.chockXlate.teachablevoice.skill.repository

import com.chockXlate.teachablevoice.contract.skill.SkillRecord
import com.chockXlate.teachablevoice.contract.skill.SkillStatus
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.skill.validation.ReplayAdmission
import com.chockXlate.teachablevoice.skill.validation.ValidationStatus
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.chockXlate.teachablevoice.runtime.cache.WorkflowRuntimeCache

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
    private val skillRecordStore = ConcurrentHashMap<String, SkillRecord>()

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    init {
        loadPersistedWorkflows()
    }

    /**
     * Loads all valid persisted skills and workflows from [storageDir] into memory caches.
     */
    @Synchronized
    fun loadPersistedWorkflows() {
        if (storageDir == null) return
        try {
            if (!storageDir.exists()) {
                storageDir.mkdirs()
                return
            }
            val files = storageDir.listFiles() ?: return
            for (file in files) {
                try {
                    val fileName = file.name
                    if (fileName.endsWith(".skill.json")) {
                        val jsonContent = file.readText()
                        val record = jsonFormatter.decodeFromString(SkillRecord.serializer(), jsonContent)
                        skillRecordStore[record.id] = record
                    } else if (fileName.endsWith(".json")) {
                        val jsonContent = file.readText()
                        val workflow = jsonFormatter.decodeFromString(Workflow.serializer(), jsonContent)
                        val validation = WorkflowValidator.validate(workflow)
                        if (validation.status == ValidationStatus.VALID && validation.isStoreable) {
                            store[workflow.skillId] = workflow
                            versionStore[workflow.skillId] = versionStore.getOrDefault(workflow.skillId, 1)
                            if (!skillRecordStore.containsKey(workflow.skillId)) {
                                skillRecordStore[workflow.skillId] = SkillRecord(
                                    id = workflow.skillId,
                                    name = workflow.name.ifBlank { workflow.intent },
                                    description = workflow.intent,
                                    status = SkillStatus.TRAINED,
                                    workflowId = workflow.skillId
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore corrupted file during startup without crashing
                }
            }
        } catch (e: Exception) {
            // Storage directory access failure handled gracefully
        }
        WorkflowRuntimeCache.hydrate(store.values.toList())
    }

    // ==========================================
    // Dynamic Skill Entity Management (Phase 2)
    // ==========================================

    override fun createSkill(name: String, description: String, id: String): SkillRecord {
        require(name.isNotBlank()) { "Skill name cannot be blank." }
        val skillId = id.ifBlank { generateUniqueSkillId(name) }
        val record = SkillRecord(
            id = skillId,
            name = name.trim(),
            description = description.trim(),
            status = SkillStatus.TEACHING,
            workflowId = null,
            createdAt = System.currentTimeMillis()
        )
        skillRecordStore[skillId] = record
        persistSkillRecordToDisk(record)
        return record
    }

    override fun getSkill(id: String): SkillRecord? {
        val cached = skillRecordStore[id]
        if (cached != null) return cached
        val wf = store[id]
        if (wf != null) {
            val record = SkillRecord(
                id = wf.skillId,
                name = wf.name.ifBlank { wf.intent },
                description = wf.intent,
                status = SkillStatus.TRAINED,
                workflowId = wf.skillId
            )
            skillRecordStore[id] = record
            return record
        }
        return null
    }

    override fun listSkills(): List<SkillRecord> {
        return skillRecordStore.values.sortedBy { it.createdAt }
    }

    override fun updateSkill(skill: SkillRecord): Boolean {
        skillRecordStore[skill.id] = skill
        persistSkillRecordToDisk(skill)
        return true
    }

    private fun persistSkillRecordToDisk(record: SkillRecord) {
        if (storageDir == null) return
        try {
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }
            val file = File(storageDir, "${sanitizeFileName(record.id)}.skill.json")
            val jsonContent = jsonFormatter.encodeToString(SkillRecord.serializer(), record)
            file.writeText(jsonContent)
        } catch (e: Exception) {
            // Handle disk write gracefully
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
            com.chockXlate.teachablevoice.ui.components.ActivityEventStream.emit(
                id = "store_${workflow.skillId.ifBlank { "pending" }}",
                label = "Persist Workflow",
                status = com.chockXlate.teachablevoice.ui.components.ActivityStatus.FAILED,
                metadata = "Rejected: Validation ${validation.status}"
            )
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

        com.chockXlate.teachablevoice.ui.components.ActivityEventStream.emit(
            id = "store_${canonicalWorkflow.skillId}",
            label = "Persist Workflow",
            status = com.chockXlate.teachablevoice.ui.components.ActivityStatus.COMPLETED,
            metadata = "${canonicalWorkflow.name.ifBlank { canonicalWorkflow.skillId }} (v$newVersion)"
        )

        WorkflowRuntimeCache.put(canonicalWorkflow)

        println("[WORKFLOW][SAVE]")
        println("ID=${canonicalWorkflow.skillId}")
        println("SKILL_ID=${canonicalWorkflow.skillId}")
        println("INTENT=${canonicalWorkflow.intent}")
        println("APP=${canonicalWorkflow.appContext}")
        println("SLOTS=${canonicalWorkflow.slots.size}")
        println("STEPS=${canonicalWorkflow.steps.size}")
        println("STORAGE_SUCCESS=true")

        println("[WORKFLOW][CACHE]")
        println("STATE=READY")
        println("COUNT=${store.size}")
        println("REQUESTED_ID=${canonicalWorkflow.skillId}")
        println("FOUND=true")
        println("AVAILABLE_IDS=${store.keys.joinToString()}")

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
        val workflow = store[skillId]
        if (workflow != null) {
            println("[RUNTIME]\nWORKFLOW_CACHE_HIT")
        } else {
            println("[RUNTIME]\nWORKFLOW_CACHE_MISS")
        }
        return workflow
    }

    override fun getAllWorkflows(): List<Workflow> {
        return store.values.sortedBy { it.skillId }
    }

    override fun deleteWorkflow(skillId: String): Boolean {
        val removedWf = store.remove(skillId) != null
        val removedRecord = skillRecordStore.remove(skillId) != null
        versionStore.remove(skillId)
        if (storageDir != null) {
            try {
                val wfFile = File(storageDir, "${sanitizeFileName(skillId)}.json")
                if (wfFile.exists()) {
                    wfFile.delete()
                }
                val skillFile = File(storageDir, "${sanitizeFileName(skillId)}.skill.json")
                if (skillFile.exists()) {
                    skillFile.delete()
                }
            } catch (e: Exception) {
                // Ignore delete error
            }
        }
        val removed = removedWf || removedRecord
        if (removed) {
            com.chockXlate.teachablevoice.ui.components.ActivityEventStream.emit(
                id = "delete_${skillId}",
                label = "Delete Skill",
                status = com.chockXlate.teachablevoice.ui.components.ActivityStatus.COMPLETED,
                metadata = "Deleted skill ID: $skillId"
            )
        }
        WorkflowRuntimeCache.remove(skillId)
        return removed
    }

    override fun contains(skillId: String): Boolean {
        return store.containsKey(skillId) || skillRecordStore.containsKey(skillId)
    }

    override fun getSkillVersion(skillId: String): Int {
        return versionStore.getOrDefault(skillId, 0)
    }

    fun clear() {
        store.clear()
        skillRecordStore.clear()
        versionStore.clear()
        if (storageDir != null) {
            try {
                storageDir.listFiles { _, name -> name.endsWith(".json") || name.endsWith(".skill.json") }?.forEach { it.delete() }
            } catch (e: Exception) {
                // Ignore clear error
            }
        }
    }

    fun getWorkflowCount(): Int {
        return store.size
    }

    fun getSkillCount(): Int {
        return skillRecordStore.size
    }

    companion object {
        fun generateUniqueSkillId(name: String): String {
            val sanitized = name.lowercase().trim().replace(Regex("[^a-z0-9]"), "_").take(16).trim('_')
            val prefix = if (sanitized.isNotBlank()) sanitized else "custom"
            val uniqueSuffix = UUID.randomUUID().toString().replace("-", "").take(8)
            return "skill_${prefix}_${uniqueSuffix}"
        }

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


