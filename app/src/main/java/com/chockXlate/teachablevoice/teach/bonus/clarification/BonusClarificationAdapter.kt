package com.chockXlate.teachablevoice.teach.bonus.clarification

import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.runtime.slots.BindingResult
import com.chockXlate.teachablevoice.runtime.slots.SlotBinder

/**
 * Adapter bridging Bonus Mid-Flow Clarification with frozen SlotBinder.
 */
class BonusClarificationAdapter {

    fun bindWithClarification(
        workflow: Workflow,
        clarificationController: MidFlowClarificationController
    ): BindingResult {
        val slots = clarificationController.getCurrentBoundSlots()
        return SlotBinder.bind(workflow, slots)
    }
}
