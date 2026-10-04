package com.chockXlate.teachablevoice.runtime.workflow

import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.skill.repository.SkillRepository
import com.chockXlate.teachablevoice.runtime.cache.WorkflowRuntimeCache

data class WorkflowResolution(val workflow: Workflow? = null, val error: String? = null)

/** Uses the caller's learned-skill store. The frozen API provides current content only. */
class WorkflowResolver(private val repository: SkillRepository) {
    fun resolve(request: ExecutionRequest): WorkflowResolution {
        val workflow = WorkflowRuntimeCache.get(request.skillId)
            ?: return WorkflowResolution(error = "No learned workflow exists for the requested skill.")
        val error = when {
            workflow.skillId != request.skillId -> "Repository returned a different skill."
            workflow.schemaVersion != "1.0" -> "Unsupported workflow schema."
            workflow.appContext.isBlank() || workflow.appContext == "unknown" -> "Workflow has no usable app package."
            workflow.steps.isEmpty() -> "Workflow contains no executable steps."
            workflow.steps.any { it.stepId.isBlank() || it.schemaVersion != "1.0" } -> "Workflow contains an invalid step."
            workflow.steps.map { it.stepId }.distinct().size != workflow.steps.size -> "Workflow step IDs are not unique."
            workflow.slots.any { it.name.isBlank() || it.schemaVersion != "1.0" } -> "Workflow contains an invalid slot."
            workflow.slots.map { it.name }.distinct().size != workflow.slots.size -> "Workflow slot names are not unique."
            else -> null
        }
        return WorkflowResolution(if (error == null) workflow else null, error)
    }
}
