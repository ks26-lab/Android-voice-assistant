package com.chockXlate.teachablevoice.learning.synthesis

import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.workflow.ExpectedTransition
import com.chockXlate.teachablevoice.contract.workflow.Preconditions
import com.chockXlate.teachablevoice.contract.workflow.RecoveryPolicy
import com.chockXlate.teachablevoice.contract.workflow.RecoveryStrategy
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot
import com.chockXlate.teachablevoice.contract.workflow.WorkflowStep
import com.chockXlate.teachablevoice.learning.actions.SemanticAction
import com.chockXlate.teachablevoice.learning.alignment.AlignmentResult
import com.chockXlate.teachablevoice.learning.inference.InferenceResult
import com.chockXlate.teachablevoice.learning.inference.SlotInferenceStatus
import com.chockXlate.teachablevoice.learning.intent.IntentExtractionResult
import com.chockXlate.teachablevoice.learning.slots.SlotExtractionResult
import java.util.UUID

/**
 * Deterministic Workflow IR Synthesizer for Phase 7.
 * Combines evidence from Phases 1–6 into a reusable, semantic Workflow IR.
 * Does NOT perform Skill Store persistence (Phase 8), Inspector (Phase 9), or Person 2 runtime execution.
 */
object WorkflowSynthesizer {

    private val SENSITIVE_KEYWORDS = listOf(
        "otp", "pin", "cvv", "card", "password", "passcode", "secret",
        "ssn", "cvv2", "credit_card", "debit_card", "payment_confirm"
    )

    fun synthesize(
        intentResult: IntentExtractionResult,
        semanticActions: List<SemanticAction>,
        slotResult: SlotExtractionResult,
        alignmentResult: AlignmentResult,
        inferenceResult: InferenceResult,
        trace: DemonstrationTrace
    ): WorkflowSynthesisResult {
        val diagnostics = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val intentName = intentResult.intent.canonicalName
        if (intentName.equals("unknown", ignoreCase = true) || intentResult.confidence < 0.3) {
            diagnostics.add("Synthesis blocked: Unknown or low-confidence intent ('$intentName').")
            return WorkflowSynthesisResult(
                workflow = null,
                status = SynthesisStatus.BLOCKED,
                diagnostics = diagnostics,
                warnings = warnings,
                isExecutable = false
            )
        }

        // 1. Synthesize Workflow Slots from Phase 6 Inferences
        val workflowSlots = mutableListOf<WorkflowSlot>()
        val variableSlotNames = mutableSetOf<String>()
        val constantSlotNames = mutableSetOf<String>()

        for (inf in inferenceResult.slotInferences) {
            val slotName = inf.slotName
            val isSensitiveSlot = SENSITIVE_KEYWORDS.any { kw -> slotName.lowercase().contains(kw) }
            val sanitizedExample = if (isSensitiveSlot) "[REDACTED_SENSITIVE]" else inf.rawValues.firstOrNull()?.trim()

            when (inf.status) {
                SlotInferenceStatus.VARIABLE -> {
                    if (isSensitiveSlot) {
                        warnings.add("Sensitive parameter '$slotName' detected. Redacted raw example value.")
                    }
                    variableSlotNames.add(slotName)
                    workflowSlots.add(
                        WorkflowSlot(
                            schemaVersion = "1.0",
                            name = slotName,
                            type = inf.slotType,
                            required = true,
                            exampleValue = sanitizedExample,
                            confidence = inf.confidence,
                            provenance = if (isSensitiveSlot) "Phase 6 Variable Inference (Redacted Sensitive Slot)" else "Phase 6 Variable Inference (${inf.demonstrationIds.joinToString(", ")})"
                        )
                    )
                }
                SlotInferenceStatus.CONSTANT -> {
                    if (isSensitiveSlot) {
                        warnings.add("Sensitive context '$slotName' detected. Redacted raw example value.")
                    }
                    constantSlotNames.add(slotName)
                    workflowSlots.add(
                        WorkflowSlot(
                            schemaVersion = "1.0",
                            name = slotName,
                            type = inf.slotType,
                            required = false,
                            exampleValue = sanitizedExample,
                            confidence = inf.confidence,
                            provenance = if (isSensitiveSlot) "Phase 6 Constant Context (Redacted Sensitive Slot)" else "Phase 6 Constant Context (${sanitizedExample})"
                        )
                    )
                }
                SlotInferenceStatus.UNKNOWN -> {
                    warnings.add("Slot '$slotName' has UNKNOWN inference status. Not converted to a variable parameter.")
                }
                SlotInferenceStatus.CONFLICTING -> {
                    warnings.add("Slot '$slotName' has CONFLICTING inference status. Retained diagnostic conflict.")
                }
            }
        }

        // 2. Build Safety Boundary & Payment/Credential Guard
        val detectedSensitiveKeywords = mutableListOf<String>()
        val restrictedActionTypes = mutableListOf<String>()
        var requiresConfirmation = false

        // 3. Synthesize Semantic Workflow Steps from Phase 3 Actions
        val workflowSteps = mutableListOf<WorkflowStep>()
        val stateEvents = trace.stateEvents
        val stateMap = stateEvents.associateBy { it.causeActionId }

        for ((index, action) in semanticActions.withIndex()) {
            val stepId = "step_${index + 1}_${action.actionId}"
            val target = action.target
            val inputVal = action.inputValue?.trim() ?: ""

            // Check sensitive security keywords in input or target
            val targetResId = target?.resourceId?.lowercase() ?: ""
            val targetText = target?.text?.lowercase() ?: ""
            val lowerInput = inputVal.lowercase()

            val containsSensitiveKeyword = SENSITIVE_KEYWORDS.any { kw ->
                targetResId.contains(kw) || targetText.contains(kw) || lowerInput.contains(kw)
            }

            if (containsSensitiveKeyword) {
                requiresConfirmation = true
                SENSITIVE_KEYWORDS.filter { kw ->
                    targetResId.contains(kw) || targetText.contains(kw) || lowerInput.contains(kw)
                }.forEach { kw ->
                    if (kw !in detectedSensitiveKeywords) detectedSensitiveKeywords.add(kw)
                }
                restrictedActionTypes.add("${action.actionType}_SENSITIVE")
            }

            // Map target attributes to SemanticSelector
            var textSlotRef: String? = null
            var selectorText: String? = if (containsSensitiveKeyword) null else target?.text

            // Check if input value matches a VARIABLE slot
            val matchingVarSlot = variableSlotNames.find { vSlot ->
                val inf = inferenceResult.slotInferences.find { it.slotName == vSlot }
                inf != null && inf.rawValues.any { rv -> rv.equals(inputVal, ignoreCase = true) }
            }

            if (matchingVarSlot != null) {
                textSlotRef = "\${$matchingVarSlot}"
                selectorText = null // Parameterized variable step
            } else if (!containsSensitiveKeyword) {
                val matchingConstSlot = constantSlotNames.find { cSlot ->
                    val inf = inferenceResult.slotInferences.find { it.slotName == cSlot }
                    inf != null && inf.rawValues.any { rv -> rv.equals(inputVal, ignoreCase = true) }
                }
                if (matchingConstSlot != null || (selectorText == null && inputVal.isNotBlank())) {
                    if (selectorText == null && inputVal.isNotBlank()) {
                        selectorText = inputVal
                    }
                }
            }

            val selector = SemanticSelector(
                schemaVersion = "1.0",
                role = target?.role,
                text = selectorText,
                textSlot = textSlotRef,
                contentDescription = null,
                resourceId = target?.resourceId,
                parentRole = null,
                ancestorRole = null,
                nearbyText = null,
                relativePosition = null
            )

            // Parameters map for step action
            val params = mutableMapOf<String, String>()
            if (textSlotRef != null) {
                params["input_parameter"] = textSlotRef
            } else if (inputVal.isNotBlank() && !containsSensitiveKeyword) {
                params["input_literal"] = inputVal
            }

            // Preconditions
            val preconditions = Preconditions(
                schemaVersion = "1.0",
                fromState = if (index > 0) "state_step_${index}" else "INITIAL_STATE",
                requiredPackage = trace.appContext.ifBlank { target?.packageName },
                requiredElementPresent = selector
            )

            // Expected Transition from actual state events
            val matchingState = stateMap[action.actionId]
            val expectedTransition = if (matchingState != null) {
                ExpectedTransition(
                    schemaVersion = "1.0",
                    fromState = "state_${matchingState.causeActionId}",
                    toState = matchingState.afterState.stateId,
                    transitionType = "UI_STATE_CHANGE",
                    expectedPackage = matchingState.afterState.appContext,
                    timeoutMs = 5000L
                )
            } else {
                ExpectedTransition(
                    schemaVersion = "1.0",
                    fromState = if (index > 0) "state_step_${index}" else "INITIAL_STATE",
                    toState = "state_step_${index + 1}",
                    transitionType = "SEMANTIC_ACTION"
                )
            }

            // Recovery Policy
            val recoveryPolicy = if (containsSensitiveKeyword) {
                RecoveryPolicy(
                    schemaVersion = "1.0",
                    maxRetries = 0,
                    strategy = RecoveryStrategy.HANDOFF_TO_USER
                )
            } else {
                RecoveryPolicy(
                    schemaVersion = "1.0",
                    maxRetries = 2,
                    strategy = RecoveryStrategy.RETRY_STEP
                )
            }

            val step = WorkflowStep(
                schemaVersion = "1.0",
                stepId = stepId,
                semanticAction = action.actionType.name,
                semanticSelector = selector,
                parameters = params,
                preconditions = preconditions,
                expectedTransition = expectedTransition,
                recoveryPolicy = recoveryPolicy,
                confidence = action.confidence,
                provenance = "Phase 3 SemanticAction ${action.actionId} (Voice: ${action.rawEventId ?: "none"})"
            )
            workflowSteps.add(step)
        }

        // Synthesize canonical semantic fallback step when actions are empty but valid intent exists
        if (workflowSteps.isEmpty()) {
            val stepId = "step_1_${intentName}"
            val varSlot = workflowSlots.firstOrNull { it.required && it.name in variableSlotNames }
            val textSlotRef = varSlot?.let { "\${${it.name}}" }
            val selector = SemanticSelector(
                schemaVersion = "1.0",
                role = "Action",
                text = if (textSlotRef != null) null else intentName,
                textSlot = textSlotRef,
                resourceId = "${trace.appContext.ifBlank { "com.teachablevoice.app" }}:id/action_${intentName}"
            )
            val params = mutableMapOf<String, String>()
            if (textSlotRef != null) {
                params["input_parameter"] = textSlotRef
            }
            val step = WorkflowStep(
                schemaVersion = "1.0",
                stepId = stepId,
                semanticAction = "EXECUTE_INTENT",
                semanticSelector = selector,
                parameters = params,
                preconditions = Preconditions(
                    schemaVersion = "1.0",
                    fromState = "INITIAL_STATE",
                    requiredPackage = trace.appContext.ifBlank { "com.teachablevoice.app" },
                    requiredElementPresent = selector
                ),
                expectedTransition = ExpectedTransition(
                    schemaVersion = "1.0",
                    fromState = "INITIAL_STATE",
                    toState = "COMPLETED_STATE",
                    transitionType = "INTENT_EXECUTION"
                ),
                recoveryPolicy = RecoveryPolicy(
                    schemaVersion = "1.0",
                    maxRetries = 2,
                    strategy = RecoveryStrategy.RETRY_STEP
                ),
                confidence = intentResult.confidence,
                provenance = "Synthesized from voice demonstration intent '$intentName'"
            )
            workflowSteps.add(step)
        }

        val safetyBoundary = SafetyBoundary(
            schemaVersion = "1.0",
            requiresExplicitUserConfirmation = requiresConfirmation,
            sensitiveKeywords = detectedSensitiveKeywords,
            restrictedActions = restrictedActionTypes
        )

        val rawSeed = "${intentName}_${trace.traceId}_${trace.appContext}_${workflowSlots.joinToString { it.name }}_${workflowSteps.joinToString { it.stepId }}"
        val hashBytes = java.security.MessageDigest.getInstance("SHA-256").digest(rawSeed.toByteArray(Charsets.UTF_8))
        val skillHash = hashBytes.take(4).joinToString("") { "%02x".format(it) }

        val workflow = Workflow(
            schemaVersion = "1.0",
            skillId = "skill_${intentName}_$skillHash",
            name = "Workflow for $intentName",
            intent = intentName,
            appContext = trace.appContext.ifBlank { "com.teachablevoice.app" },
            slots = workflowSlots.sortedBy { it.name },
            steps = workflowSteps,
            safetyBoundary = safetyBoundary
        )

        val overallStatus = when {
            requiresConfirmation -> {
                diagnostics.add("Safety boundary active: Credential/payment keywords detected. Handoff policy enabled.")
                SynthesisStatus.DEGRADED
            }
            warnings.isNotEmpty() -> SynthesisStatus.DEGRADED
            else -> SynthesisStatus.VALID
        }

        val provDemoIds = (alignmentResult.alignedDemonstrationIds +
            inferenceResult.slotInferences.flatMap { it.demonstrationIds } +
            listOf(trace.traceId)).filter { it.isNotBlank() }.distinct()

        return WorkflowSynthesisResult(
            schemaVersion = "1.0",
            workflow = workflow,
            status = overallStatus,
            diagnostics = diagnostics,
            warnings = warnings,
            evidenceSummary = "Synthesized ${workflowSteps.size} steps and ${workflowSlots.size} slots for intent '$intentName'.",
            provenanceDemonstrationIds = provDemoIds,
            isExecutable = true
        )
    }
}
