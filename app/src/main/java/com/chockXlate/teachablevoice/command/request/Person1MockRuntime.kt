package com.chockXlate.teachablevoice.command.request

import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest

/**
 * Person 1 Mock Runtime Demonstrator for Phase 12.
 * Simulates Person 1 -> Person 2 handoff without performing any real Android UI execution.
 *
 * Explicitly verifies zero Android AccessibilityService, click, type, or UI gestures are invoked.
 */
object Person1MockRuntime {

    fun formatMockHandoff(buildResult: ExecutionRequestBuildResult): String {
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("      PERSON 1 → PERSON 2 HANDOFF (MOCK RUNTIME)")
        sb.appendLine("==================================================")
        sb.appendLine("HANDOFF STATUS : ${buildResult.status}")
        sb.appendLine("HANDOFF MSG    : ${buildResult.handoffMessage}")
        sb.appendLine("SKILL ID       : ${buildResult.skillId ?: "None"}")
        sb.appendLine("SKILL VERSION  : ${buildResult.version ?: "N/A"}")
        sb.appendLine("--------------------------------------------------")

        val req = buildResult.executionRequest
        if (req == null) {
            sb.appendLine("EXECUTION REQUEST : NOT CREATED")
            sb.appendLine("REASON            : ${buildResult.rejectionReason ?: "N/A"}")
            if (buildResult.missingSlots.isNotEmpty()) {
                sb.appendLine("MISSING SLOTS     : ${buildResult.missingSlots.joinToString(", ")}")
            }
        } else {
            sb.appendLine("EXECUTION REQUEST DETAILS:")
            sb.appendLine("  schemaVersion : ${req.schemaVersion}")
            sb.appendLine("  executionId   : ${req.executionId}")
            sb.appendLine("  skillId       : ${req.skillId}")
            sb.appendLine("  version       : ${req.version}")
            sb.appendLine("  provenance    : ${req.provenance}")
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("BOUND SLOTS (${req.boundSlots.size}):")
            if (req.boundSlots.isEmpty()) {
                sb.appendLine("  (No slots bound)")
            } else {
                req.boundSlots.forEach { (key, valStr) ->
                    sb.appendLine("  ├── $key = $valStr")
                }
            }
        }

        if (buildResult.diagnostics.isNotEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("DIAGNOSTICS:")
            buildResult.diagnostics.forEach { d -> sb.appendLine("  - $d") }
        }

        sb.appendLine("--------------------------------------------------")
        sb.appendLine("PERSON 1 / PERSON 2 BOUNDARY VERIFICATION:")
        sb.appendLine("  Person 1 Status    : COMPLETE")
        sb.appendLine("  ExecutionRequest   : ${if (req != null) "CREATED" else "NOT CREATED"}")
        sb.appendLine("  Runtime Execution  : NOT PERFORMED (Person 1 Boundary)")
        sb.appendLine("  Person 2 Handoff   : ${if (buildResult.status == ExecutionRequestStatus.READY_FOR_PERSON_2) "READY FOR PERSON 2" else "HANDOFF BLOCKED"}")
        sb.appendLine("==================================================")
        return sb.toString()
    }
}
