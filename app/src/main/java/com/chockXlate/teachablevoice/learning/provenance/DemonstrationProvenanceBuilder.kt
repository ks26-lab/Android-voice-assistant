package com.chockXlate.teachablevoice.learning.provenance

import com.chockXlate.teachablevoice.contract.filter.DemonstrationFilterClassification
import com.chockXlate.teachablevoice.contract.filter.FilteredDemonstrationResult
import com.chockXlate.teachablevoice.contract.provenance.DemonstrationProvenanceGraph
import com.chockXlate.teachablevoice.contract.provenance.ParameterProvenanceNode
import com.chockXlate.teachablevoice.contract.provenance.StepProvenanceLink
import com.chockXlate.teachablevoice.contract.provenance.TargetProvenanceNode
import com.chockXlate.teachablevoice.contract.provenance.TransitionEvidenceProvenanceNode
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import com.chockXlate.teachablevoice.learning.actions.SemanticAction
import com.chockXlate.teachablevoice.learning.inference.InferenceResult
import com.chockXlate.teachablevoice.learning.inference.SlotInferenceStatus

/**
 * Builds deterministic, privacy-preserving provenance links and graphs connecting
 * raw demonstration evidence -> normalized event -> semantic action -> semantic target
 * -> inferred parameter -> synthesized workflow step -> expected transition evidence.
 */
object DemonstrationProvenanceBuilder {

    private val SENSITIVE_KEYWORDS = listOf(
        "otp", "pin", "cvv", "card", "password", "passcode", "secret",
        "ssn", "cvv2", "credit_card", "debit_card", "payment_confirm"
    )

    private fun isSensitive(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase()
        return SENSITIVE_KEYWORDS.any { lower.contains(it) }
    }

    fun build(
        workflowSkillId: String,
        trace: DemonstrationTrace,
        steps: List<WorkflowStep>,
        semanticActions: List<SemanticAction>,
        inferenceResult: InferenceResult,
        filterResult: FilteredDemonstrationResult
    ): DemonstrationProvenanceGraph {
        val actionMap = semanticActions.associateBy { it.actionId }
        val stateMap = trace.stateEvents.associateBy { it.causeActionId }

        val stepLinks = mutableListOf<StepProvenanceLink>()

        for (step in steps) {
            // Find corresponding semantic action
            val matchedAction = actionMap.values.find { action ->
                step.stepId.contains(action.actionId) || step.provenance.contains(action.actionId)
            } ?: actionMap[step.stepId]

            val rawActionId = matchedAction?.actionId
            val matchingState = if (rawActionId != null) stateMap[rawActionId] else null

            val rawEvents = mutableListOf<String>()
            if (rawActionId != null) rawEvents.add(rawActionId)
            matchedAction?.rawEventId?.let { if (it !in rawEvents) rawEvents.add(it) }
            matchingState?.stateEventId?.let { if (it !in rawEvents) rawEvents.add(it) }

            // Target Provenance
            val target = matchedAction?.target
            val targetNode = if (target != null) {
                val isSens = isSensitive(target.resourceId) || isSensitive(target.text) || isSensitive(target.contentDescription)
                TargetProvenanceNode(
                    schemaVersion = "1.0",
                    role = target.role,
                    resourceId = target.resourceId,
                    contentDescription = if (isSens) "[REDACTED_SENSITIVE]" else target.contentDescription,
                    textSnippet = if (isSens) "[REDACTED_SENSITIVE]" else target.text,
                    targetConfidence = target.targetConfidence,
                    rationale = "Extracted from UI target in package '${target.packageName}'"
                )
            } else if (step.semanticSelector.role != null || step.semanticSelector.resourceId != null) {
                val sel = step.semanticSelector
                val isSens = isSensitive(sel.resourceId) || isSensitive(sel.text) || isSensitive(sel.contentDescription)
                TargetProvenanceNode(
                    schemaVersion = "1.0",
                    role = sel.role,
                    resourceId = sel.resourceId,
                    contentDescription = if (isSens) "[REDACTED_SENSITIVE]" else sel.contentDescription,
                    textSnippet = if (isSens) "[REDACTED_SENSITIVE]" else sel.text,
                    targetConfidence = 0.8,
                    rationale = "Derived from step semantic selector"
                )
            } else null

            // Parameter Provenance
            var paramNode: ParameterProvenanceNode? = null
            val inputParam = step.parameters["input_parameter"] ?: step.semanticSelector.textSlot
            if (inputParam != null) {
                val slotName = inputParam.removePrefix("\${").removeSuffix("}")
                val inf = inferenceResult.slotInferences.find { it.slotName == slotName }
                if (inf != null) {
                    paramNode = ParameterProvenanceNode(
                        schemaVersion = "1.0",
                        slotName = slotName,
                        isVariable = inf.status == SlotInferenceStatus.VARIABLE,
                        inferenceStatus = inf.status.name,
                        rationale = if (inf.reasoning.isNotBlank()) "${inf.reasoning} (Inferred as ${inf.status})" else "Inferred as ${inf.status} across demonstrations (${inf.demonstrationIds.size} traces)",
                        demonstratedValuesCount = inf.rawValues.size
                    )
                }
            } else if (step.parameters.containsKey("input_literal")) {
                paramNode = ParameterProvenanceNode(
                    schemaVersion = "1.0",
                    slotName = "literal_input",
                    isVariable = false,
                    inferenceStatus = "CONSTANT",
                    rationale = "Literal input inferred as constant across single demonstration",
                    demonstratedValuesCount = 1
                )
            }

            // Transition Evidence Provenance
            val evidenceNodes = mutableListOf<TransitionEvidenceProvenanceNode>()
            for (req in step.expectedTransition.expectedEvidence) {
                evidenceNodes.add(
                    TransitionEvidenceProvenanceNode(
                        schemaVersion = "1.0",
                        evidenceType = req.type.name,
                        description = req.description ?: "Expected ${req.type} transition",
                        beforeStateId = step.expectedTransition.fromState,
                        afterStateId = step.expectedTransition.toState
                    )
                )
            }

            val filterClassification = if (rawActionId != null) {
                filterResult.allEvents.find { it.eventId == rawActionId }?.classification
                    ?: DemonstrationFilterClassification.TASK_RELEVANT
            } else DemonstrationFilterClassification.TASK_RELEVANT

            val filterReason = if (rawActionId != null) {
                filterResult.allEvents.find { it.eventId == rawActionId }?.reason
            } else null

            stepLinks.add(
                StepProvenanceLink(
                    schemaVersion = "1.0",
                    stepId = step.stepId,
                    rawActionId = rawActionId,
                    rawEventIds = rawEvents,
                    semanticActionType = step.semanticAction,
                    target = targetNode,
                    parameter = paramNode,
                    transitionEvidence = evidenceNodes,
                    filterClassification = filterClassification,
                    filterReason = filterReason,
                    isFilteredOrUncertain = filterClassification != DemonstrationFilterClassification.TASK_RELEVANT,
                    synthesisRationale = "Synthesized from demonstration action '${rawActionId ?: "synthetic"}' with confidence ${step.confidence}"
                )
            )
        }

        // Retain filtered or uncertain events for full inspectability without executing them
        val filteredOrUncertain = mutableListOf<StepProvenanceLink>()
        for (filteredEv in filterResult.allEvents) {
            if (filteredEv.classification != DemonstrationFilterClassification.TASK_RELEVANT) {
                filteredOrUncertain.add(
                    StepProvenanceLink(
                        schemaVersion = "1.0",
                        stepId = "filtered_${filteredEv.eventId}",
                        rawActionId = filteredEv.eventId,
                        rawEventIds = listOf(filteredEv.eventId),
                        semanticActionType = "FILTERED_EVENT",
                        target = null,
                        parameter = null,
                        transitionEvidence = emptyList(),
                        filterClassification = filteredEv.classification,
                        filterReason = filteredEv.reason,
                        isFilteredOrUncertain = true,
                        synthesisRationale = "Excluded from workflow execution by DemonstrationFilter: ${filteredEv.classification} (${filteredEv.reason})"
                    )
                )
            }
        }

        return DemonstrationProvenanceGraph(
            schemaVersion = "1.0",
            workflowSkillId = workflowSkillId,
            demonstrationTraceId = trace.traceId,
            stepLinks = stepLinks,
            filteredOrUncertainEvents = filteredOrUncertain
        )
    }
}
