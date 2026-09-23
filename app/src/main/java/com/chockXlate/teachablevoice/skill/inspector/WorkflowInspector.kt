package com.chockXlate.teachablevoice.skill.inspector

import com.chockXlate.teachablevoice.contract.workflow.Workflow

/**
 * Inspector for viewing, validating, and debugging synthesized workflows.
 */
interface WorkflowInspector {
    fun inspectWorkflow(workflow: Workflow): String
    fun validateWorkflowStructure(workflow: Workflow): List<String>
}
