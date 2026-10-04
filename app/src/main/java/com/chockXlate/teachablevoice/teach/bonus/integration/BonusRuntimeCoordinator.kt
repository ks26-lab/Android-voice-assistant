package com.chockXlate.teachablevoice.teach.bonus.integration

import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.teach.bonus.clarification.ClarificationRequest
import com.chockXlate.teachablevoice.teach.bonus.clarification.MidFlowClarificationController
import com.chockXlate.teachablevoice.teach.bonus.clarification.MidFlowClarificationResult
import com.chockXlate.teachablevoice.teach.bonus.crossapp.CrossAppGeneralizationEngine
import com.chockXlate.teachablevoice.teach.bonus.crossapp.CrossAppGeneralizationResult
import com.chockXlate.teachablevoice.teach.bonus.irrelevant.BonusFilteredDemonstration
import com.chockXlate.teachablevoice.teach.bonus.irrelevant.BonusTeachingFilter

/**
 * Isolated Bonus Runtime Coordinator for orchestrating real data flows across Bonus 1, 2, and 3.
 *
 * CRITICAL ARCHITECTURAL SAFETY PROPERTIES:
 * 1. Zero hardcoded scenario data or app-specific rules.
 * 2. Does NOT modify or replace ExecutionEngine or SafetyGate.
 * 3. Does NOT perform direct Accessibility actions.
 * 4. Provides clean opt-in runtime orchestration for live Android sessions.
 */
class BonusRuntimeCoordinator(
    val policy: BonusFeaturePolicy = BonusFeaturePolicy(),
    private val crossAppEngine: CrossAppGeneralizationEngine = CrossAppGeneralizationEngine(),
    private val clarificationController: MidFlowClarificationController = MidFlowClarificationController()
) {

    private val diagnosticEvents = mutableListOf<BonusRuntimeEvent>()

    // FLOW A: Irrelevant-Action Filtering (Bonus 1)
    fun processCapturedTeachingTrace(trace: DemonstrationTrace): BonusFilteredDemonstration? {
        if (!policy.irrelevantFilteringEnabled) {
            logDiagnostic("Bonus1_Filtering", "DISABLED", false, "BYPASSED", 1.0, "Irrelevant-Action filtering is disabled by policy.")
            return null
        }

        val filtered = BonusTeachingFilter.filter(trace)

        logDiagnostic(
            bonusName = "Bonus1_Filtering",
            stage = "TEACHING_COMPLETED",
            inputAvailable = true,
            decision = "RETAINED_${filtered.retainedEventCount}_FILTERED_${filtered.removedEvents.size}",
            confidence = 1.0,
            reason = "Trace ${trace.traceId} processed. Input count: ${filtered.originalEventCount}, Retained: ${filtered.retainedEventCount}, Filtered: ${filtered.removedEventCount}."
        )

        return filtered
    }

    // FLOW B: Cross-App Generalization (Bonus 2)
    fun evaluateCrossAppGeneralization(
        sourceWorkflow: Workflow,
        targetPackageName: String,
        targetUiElements: List<UiElement>
    ): CrossAppGeneralizationResult? {
        if (!policy.crossAppGeneralizationEnabled) {
            logDiagnostic("Bonus2_CrossApp", "DISABLED", false, "BYPASSED", 1.0, "Cross-app generalization is disabled by policy.")
            return null
        }

        val result = crossAppEngine.analyze(sourceWorkflow, targetPackageName, targetUiElements)

        logDiagnostic(
            bonusName = "Bonus2_CrossApp",
            stage = "CROSS_APP_ANALYSIS",
            inputAvailable = targetUiElements.isNotEmpty(),
            decision = result.decision.name,
            confidence = result.overallConfidence,
            reason = result.summaryReason
        )

        return result
    }

    // FLOW C: Mid-Flow Clarification (Bonus 3)
    fun evaluateExecutionForMissingSlots(
        workflow: Workflow,
        suppliedSlots: Map<String, String>,
        startStepIndex: Int = 0
    ): MidFlowClarificationResult? {
        if (!policy.midFlowClarificationEnabled) {
            logDiagnostic("Bonus3_Clarification", "DISABLED", false, "BYPASSED", 1.0, "Mid-flow clarification is disabled by policy.")
            return null
        }

        val result = clarificationController.startWorkflowExecution(workflow, suppliedSlots, startStepIndex)

        logDiagnostic(
            bonusName = "Bonus3_Clarification",
            stage = "EXECUTION_STARTED",
            inputAvailable = true,
            decision = result.finalState.name,
            confidence = 1.0,
            reason = result.summaryReason
        )

        return result
    }

    fun submitUserClarificationResponse(
        workflow: Workflow,
        userResponseText: String
    ): MidFlowClarificationResult? {
        if (!policy.midFlowClarificationEnabled) return null

        val result = clarificationController.submitUserResponse(workflow, userResponseText)

        logDiagnostic(
            bonusName = "Bonus3_Clarification",
            stage = "USER_RESPONSE_PROCESSED",
            inputAvailable = userResponseText.isNotBlank(),
            decision = result.finalState.name,
            confidence = 1.0,
            reason = result.summaryReason
        )

        return result
    }

    fun getPendingClarificationRequest(): ClarificationRequest? = clarificationController.getActiveRequest()

    fun getDiagnosticEvents(): List<BonusRuntimeEvent> = diagnosticEvents.toList()

    private fun logDiagnostic(
        bonusName: String,
        stage: String,
        inputAvailable: Boolean,
        decision: String,
        confidence: Double,
        reason: String
    ) {
        val event = BonusRuntimeEvent(
            bonusName = bonusName,
            stage = stage,
            inputAvailable = inputAvailable,
            decision = decision,
            confidence = confidence,
            reason = reason
        )
        diagnosticEvents.add(event)
    }
}
