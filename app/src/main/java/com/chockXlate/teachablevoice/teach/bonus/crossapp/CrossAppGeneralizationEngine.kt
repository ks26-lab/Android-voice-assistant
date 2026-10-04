package com.chockXlate.teachablevoice.teach.bonus.crossapp

import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.workflow.Workflow

/**
 * Isolated Standalone Adapter Engine for analyzing cross-app semantic generalization.
 *
 * SAFETY PROPERTY:
 * Never authorizes execution directly. Execution authorization MUST remain with SafetyGate.
 */
class CrossAppGeneralizationEngine(
    private val matcher: CrossAppSemanticMatcher = CrossAppSemanticMatcher(),
    private val analyzer: CrossAppCompatibilityAnalyzer = CrossAppCompatibilityAnalyzer(),
    private val config: CrossAppConfiguration = CrossAppConfiguration()
) {

    fun analyze(
        learnedWorkflow: Workflow,
        targetPackage: String,
        targetUiElements: List<UiElement>
    ): CrossAppGeneralizationResult {
        val sourcePackage = learnedWorkflow.appContext

        // 1. Safety Check: Verify target UI is NOT a sensitive screen (payment/credential/OTP)
        val isSensitive = analyzer.isSensitiveScreen(targetUiElements)
        if (isSensitive) {
            return CrossAppGeneralizationResult(
                sourcePackage = sourcePackage,
                targetPackage = targetPackage,
                decision = TransferabilityDecision.NOT_TRANSFERABLE,
                overallConfidence = 0.0,
                totalSteps = learnedWorkflow.steps.size,
                compatibleStepCount = 0,
                stepEvidences = emptyList(),
                preservesSafetyBoundary = false,
                executionAuthorized = false,
                summaryReason = "Safety Gate Blocked: Target UI contains sensitive elements (payment/password/OTP). Cross-app generalization aborted."
            )
        }

        // 2. Evaluate step-by-step semantic compatibility
        val flattenTargetElements = flattenElements(targetUiElements)
        val evidences = mutableListOf<CrossAppEvidence>()
        var totalConfidenceSum = 0.0
        var compatibleCount = 0

        for ((index, step) in learnedWorkflow.steps.withIndex()) {
            val selector = step.semanticSelector

            // Find best matching candidate element in target UI
            val bestMatchEvidence = flattenTargetElements.map { element ->
                matcher.scoreElement(selector, element, targetPackage)
            }.maxByOrNull { it.overallStepConfidence }

            val stepEvidence = if (bestMatchEvidence != null && bestMatchEvidence.overallStepConfidence >= config.partialThreshold) {
                if (bestMatchEvidence.overallStepConfidence >= config.transferableThreshold) {
                    compatibleCount++
                }
                bestMatchEvidence.copy(stepIndex = index, stepId = step.stepId)
            } else {
                CrossAppEvidence(
                    stepIndex = index,
                    stepId = step.stepId,
                    sourceRole = selector.role,
                    matchedTargetElementId = null,
                    roleMatchScore = 0.0,
                    semanticTextScore = 0.0,
                    contentDescScore = 0.0,
                    structuralContextScore = 0.0,
                    actionCompatibilityScore = 0.0,
                    overallStepConfidence = 0.0,
                    reason = "No semantically compatible target element found in target application for step '${step.stepId}'."
                )
            }

            evidences.add(stepEvidence)
            totalConfidenceSum += stepEvidence.overallStepConfidence
        }

        val totalSteps = learnedWorkflow.steps.size
        val averageConfidence = if (totalSteps > 0) totalConfidenceSum / totalSteps else 0.0

        // 3. Determine Overall Transferability Decision
        val decision = when {
            totalSteps == 0 -> TransferabilityDecision.NOT_TRANSFERABLE
            compatibleCount == totalSteps && averageConfidence >= config.transferableThreshold -> TransferabilityDecision.TRANSFERABLE
            compatibleCount > 0 && averageConfidence >= config.partialThreshold -> TransferabilityDecision.PARTIALLY_TRANSFERABLE
            else -> TransferabilityDecision.NOT_TRANSFERABLE
        }

        val summaryReason = when (decision) {
            TransferabilityDecision.TRANSFERABLE -> "Workflow successfully generalized from '$sourcePackage' to '$targetPackage' with high semantic evidence ($averageConfidence)."
            TransferabilityDecision.PARTIALLY_TRANSFERABLE -> "Workflow is partially transferable ($compatibleCount/$totalSteps steps compatible, confidence $averageConfidence). User clarification required."
            TransferabilityDecision.NOT_TRANSFERABLE -> "Workflow cannot be transferred to '$targetPackage'. Insufficient semantic equivalence or missing target steps."
        }

        return CrossAppGeneralizationResult(
            sourcePackage = sourcePackage,
            targetPackage = targetPackage,
            decision = decision,
            overallConfidence = averageConfidence,
            totalSteps = totalSteps,
            compatibleStepCount = compatibleCount,
            stepEvidences = evidences,
            preservesSafetyBoundary = true,
            executionAuthorized = false, // CRITICAL: NEVER grants execution authorization
            summaryReason = summaryReason
        )
    }

    private fun flattenElements(elements: List<UiElement>): List<UiElement> {
        val list = mutableListOf<UiElement>()
        for (e in elements) {
            list.add(e)
            if (e.children.isNotEmpty()) {
                list.addAll(flattenElements(e.children))
            }
        }
        return list
    }
}
