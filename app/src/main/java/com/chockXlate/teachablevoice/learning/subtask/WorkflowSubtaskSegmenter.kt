package com.chockXlate.teachablevoice.learning.subtask

import com.chockXlate.teachablevoice.contract.workflow.EvidenceType
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSubtask

/**
 * Deterministically segments a flat list of synthesized WorkflowSteps into
 * Subtask-Aware Workflow IR groupings based on:
 * - Package / Activity transitions
 * - State transition boundaries
 * - Semantic action clusters (e.g. text input followed by submission)
 * - Quantity step groupings
 * - Outcome boundaries
 *
 * App-agnostic: does not hard-code vendor or application names.
 */
object WorkflowSubtaskSegmenter {

    fun segment(steps: List<WorkflowStep>): List<WorkflowSubtask> {
        if (steps.isEmpty()) return emptyList()

        val clusters = mutableListOf<MutableList<WorkflowStep>>()
        var currentCluster = mutableListOf<WorkflowStep>()

        for (i in steps.indices) {
            val current = steps[i]
            if (currentCluster.isEmpty()) {
                currentCluster.add(current)
                continue
            }

            val previous = currentCluster.last()
            val shouldStartNewSubtask = isSubtaskBoundary(previous, current)

            if (shouldStartNewSubtask) {
                clusters.add(currentCluster)
                currentCluster = mutableListOf(current)
            } else {
                currentCluster.add(current)
            }
        }
        if (currentCluster.isNotEmpty()) {
            clusters.add(currentCluster)
        }

        return clusters.mapIndexed { index, clusterSteps ->
            val clusterIndex = index + 1
            val firstStep = clusterSteps.first()
            val lastStep = clusterSteps.last()

            val label = inferSubtaskLabel(clusterIndex, clusterSteps)
            val subtaskId = "subtask_${clusterIndex}_${firstStep.stepId}"
            val avgConfidence = clusterSteps.map { it.confidence }.average()

            WorkflowSubtask(
                schemaVersion = "1.0",
                subtaskId = subtaskId,
                label = label,
                stepIds = clusterSteps.map { it.stepId },
                preconditions = firstStep.preconditions,
                expectedOutcome = lastStep.expectedTransition,
                confidence = (avgConfidence * 100.0).toInt() / 100.0,
                provenance = "Inferred from ${clusterSteps.size} semantic step(s) with boundary outcome '${lastStep.expectedTransition.toState ?: "terminal"}'"
            )
        }
    }

    private fun isSubtaskBoundary(previous: WorkflowStep, current: WorkflowStep): Boolean {
        // 1. Quantity steps remain logically grouped together
        val prevIsQuantity = isQuantityStep(previous)
        val currIsQuantity = isQuantityStep(current)
        if (prevIsQuantity && currIsQuantity) {
            return false // Keep quantity adjustments in the same subtask
        }

        // 2. Package transition boundary: always creates a new subtask
        val prevExecPkg = previous.preconditions.requiredPackage
        val currExecPkg = current.preconditions.requiredPackage
        if (!prevExecPkg.isNullOrBlank() && !currExecPkg.isNullOrBlank() && prevExecPkg != currExecPkg) {
            return true
        }
        val prevExpectedPkg = previous.expectedTransition.expectedPackage
        if (!prevExpectedPkg.isNullOrBlank() && !prevExecPkg.isNullOrBlank() && prevExpectedPkg != prevExecPkg) {
            return true
        }

        // 3. Related consecutive actions: Input text followed immediately by tap/click or submit remains grouped
        if (previous.semanticAction == "INPUT_TEXT" && (current.semanticAction == "CLICK" || current.semanticAction == "TAP" || current.semanticAction == "SUBMIT")) {
            return false
        }

        // 4. Repeated actions on the same semantic target remain grouped
        if (isSameTarget(previous, current)) {
            return false
        }

        // 5. Significant state transition boundary:
        // When previous step expects an element to appear or disappear or package transition, and next step starts from a distinct state
        val prevToState = previous.expectedTransition.toState
        val currFromState = current.preconditions.fromState
        val hasOutcomeEvidence = previous.expectedTransition.expectedEvidence.any {
            it.type in setOf(EvidenceType.ELEMENT_APPEARED, EvidenceType.ELEMENT_DISAPPEARED, EvidenceType.EXPECTED_PACKAGE)
        }

        if (hasOutcomeEvidence && prevToState != null && currFromState != null && prevToState == currFromState) {
            return true
        }

        // 6. Default conservative grouping: if previous step has generic state change without boundary evidence, keep together up to 3 actions
        return false
    }

    private fun isQuantityStep(step: WorkflowStep): Boolean {
        return step.parameters.containsKey("quantity") ||
            step.parameters.containsKey("delta") ||
            step.expectedTransition.expectedEvidence.any { it.type == EvidenceType.COUNTER_CHANGE }
    }

    private fun isSameTarget(s1: WorkflowStep, s2: WorkflowStep): Boolean {
        val t1 = s1.semanticSelector
        val t2 = s2.semanticSelector
        if (!t1.resourceId.isNullOrBlank() && t1.resourceId == t2.resourceId) return true
        if (!t1.text.isNullOrBlank() && t1.text == t2.text && t1.role == t2.role) return true
        if (!t1.contentDescription.isNullOrBlank() && t1.contentDescription == t2.contentDescription) return true
        return false
    }

    private fun inferSubtaskLabel(clusterIndex: Int, steps: List<WorkflowStep>): String {
        return when {
            steps.any { isQuantityStep(it) } -> "SUBTASK_${clusterIndex}_QUANTITY_ADJUSTMENT"
            steps.any { it.semanticAction == "INPUT_TEXT" } -> "SUBTASK_${clusterIndex}_TEXT_ENTRY"
            steps.any { it.preconditions.requiredPackage != null && steps.first().preconditions.requiredPackage != it.preconditions.requiredPackage } -> "SUBTASK_${clusterIndex}_CROSS_APP_HANDOFF"
            steps.any { it.expectedTransition.expectedEvidence.any { ev -> ev.type == EvidenceType.ELEMENT_APPEARED } } -> "SUBTASK_${clusterIndex}_ACTION_AND_TRANSITION"
            else -> "SUBTASK_${clusterIndex}_STEP_SEQUENCE"
        }
    }
}
