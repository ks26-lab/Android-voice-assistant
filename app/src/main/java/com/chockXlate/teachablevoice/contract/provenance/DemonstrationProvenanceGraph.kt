package com.chockXlate.teachablevoice.contract.provenance

import com.chockXlate.teachablevoice.contract.filter.DemonstrationFilterClassification
import kotlinx.serialization.Serializable

@Serializable
data class TargetProvenanceNode(
    val schemaVersion: String = "1.0",
    val role: String? = null,
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val textSnippet: String? = null,
    val targetConfidence: Double = 1.0,
    val rationale: String = "Extracted from demonstrated UI target"
)

@Serializable
data class ParameterProvenanceNode(
    val schemaVersion: String = "1.0",
    val slotName: String,
    val isVariable: Boolean,
    val inferenceStatus: String,
    val rationale: String,
    val demonstratedValuesCount: Int = 1
)

@Serializable
data class TransitionEvidenceProvenanceNode(
    val schemaVersion: String = "1.0",
    val evidenceType: String,
    val description: String,
    val beforeStateId: String? = null,
    val afterStateId: String? = null
)

@Serializable
data class StepProvenanceLink(
    val schemaVersion: String = "1.0",
    val stepId: String,
    val rawActionId: String? = null,
    val rawEventIds: List<String> = emptyList(),
    val semanticActionType: String,
    val target: TargetProvenanceNode? = null,
    val parameter: ParameterProvenanceNode? = null,
    val transitionEvidence: List<TransitionEvidenceProvenanceNode> = emptyList(),
    val filterClassification: DemonstrationFilterClassification = DemonstrationFilterClassification.TASK_RELEVANT,
    val filterReason: String? = null,
    val isFilteredOrUncertain: Boolean = false,
    val synthesisRationale: String = "Synthesized from demonstration evidence"
)

@Serializable
data class DemonstrationProvenanceGraph(
    val schemaVersion: String = "1.0",
    val workflowSkillId: String,
    val demonstrationTraceId: String,
    val stepLinks: List<StepProvenanceLink> = emptyList(),
    val filteredOrUncertainEvents: List<StepProvenanceLink> = emptyList()
) {
    fun findStepProvenance(stepId: String): StepProvenanceLink? = stepLinks.find { it.stepId == stepId }
}
