package com.chockXlate.teachablevoice.learning.synthesis

import com.chockXlate.teachablevoice.contract.workflow.Workflow
import kotlinx.serialization.Serializable

/**
 * Enumerates the diagnostic synthesis status of a learned Workflow IR.
 */
@Serializable
enum class SynthesisStatus {
    VALID,
    DEGRADED,
    BLOCKED
}

/**
 * Diagnostic container for Phase 7 Workflow IR synthesis results.
 */
@Serializable
data class WorkflowSynthesisResult(
    val schemaVersion: String = "1.0",
    val workflow: Workflow? = null,
    val status: SynthesisStatus,
    val diagnostics: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val evidenceSummary: String = "",
    val provenanceDemonstrationIds: List<String> = emptyList(),
    val isExecutable: Boolean = true
)
