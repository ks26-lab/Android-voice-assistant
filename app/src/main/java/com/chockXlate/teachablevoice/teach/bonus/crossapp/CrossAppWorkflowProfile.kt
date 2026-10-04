package com.chockXlate.teachablevoice.teach.bonus.crossapp

import com.chockXlate.teachablevoice.contract.workflow.Workflow

/**
 * Encapsulates a learned workflow detached from package identity.
 */
data class CrossAppWorkflowProfile(
    val workflowId: String,
    val intent: String,
    val sourcePackage: String,
    val slotNames: List<String>,
    val originalWorkflow: Workflow
) {
    companion object {
        fun fromWorkflow(workflow: Workflow): CrossAppWorkflowProfile {
            return CrossAppWorkflowProfile(
                workflowId = workflow.skillId,
                intent = workflow.intent,
                sourcePackage = workflow.appContext,
                slotNames = workflow.slots.map { it.name },
                originalWorkflow = workflow
            )
        }
    }
}
