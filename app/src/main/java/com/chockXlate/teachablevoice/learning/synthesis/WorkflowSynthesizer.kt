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
import com.chockXlate.teachablevoice.learning.targets.SemanticTarget
import com.chockXlate.teachablevoice.teach.filter.DemonstrationFilter
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

    fun synthesizeFromTrace(
        trace: DemonstrationTrace,
        targetSkillId: String? = null,
        targetSkillName: String? = null,
        targetSkillDescription: String? = null
    ): WorkflowSynthesisResult {
        val filterResult = DemonstrationFilter.filter(trace, targetSkillName, targetSkillDescription)
        val semanticActions = com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor.extract(trace, filterResult)
        val rawIntentResult = com.chockXlate.teachablevoice.learning.intent.IntentExtractor.extract(trace, semanticActions)
        
        val finalIntentResult = if (targetSkillDescription != null && targetSkillDescription.isNotBlank()) {
            val understood = com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy.understandCommand(targetSkillDescription)
            val userIntent = if (understood.intent.canonicalName != "unknown") understood.intent.canonicalName
                else com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy.canonicalizeIntent(targetSkillDescription)
            if (userIntent.isNotBlank() && !userIntent.equals("unknown", ignoreCase = true)) {
                rawIntentResult.copy(
                    intent = rawIntentResult.intent.copy(canonicalName = userIntent),
                    confidence = 1.0
                )
            } else {
                rawIntentResult
            }
        } else if (targetSkillName != null && targetSkillName.isNotBlank()) {
            val understood = com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy.understandCommand(targetSkillName)
            val userIntent = if (understood.intent.canonicalName != "unknown") understood.intent.canonicalName
                else com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy.canonicalizeIntent(targetSkillName)
            if (userIntent.isNotBlank() && !userIntent.equals("unknown", ignoreCase = true)) {
                rawIntentResult.copy(
                    intent = rawIntentResult.intent.copy(canonicalName = userIntent),
                    confidence = 1.0
                )
            } else {
                rawIntentResult
            }
        } else {
            rawIntentResult
        }

        val slotResult = com.chockXlate.teachablevoice.learning.slots.SlotExtractor.extract(trace, semanticActions, finalIntentResult)
        val dataset = com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset(
            demonstrationId = trace.traceId,
            traceId = trace.traceId,
            intentResult = finalIntentResult,
            slotResult = slotResult
        )
        val alignmentResult = com.chockXlate.teachablevoice.learning.alignment.DemonstrationAlignment.align(listOf(dataset))
        val inferenceResult = com.chockXlate.teachablevoice.learning.inference.ConstantVariableInference.infer(listOf(dataset))

        val result = synthesize(
            intentResult = finalIntentResult,
            semanticActions = semanticActions,
            slotResult = slotResult,
            alignmentResult = alignmentResult,
            inferenceResult = inferenceResult,
            trace = trace,
            filterResult = filterResult
        )

        if (result.workflow != null && (targetSkillId != null || targetSkillName != null)) {
            val updatedWorkflow = result.workflow.copy(
                skillId = targetSkillId ?: result.workflow.skillId,
                name = targetSkillName ?: result.workflow.name
            )
            return result.copy(workflow = updatedWorkflow)
        }
        return result
    }

    fun synthesize(
        intentResult: IntentExtractionResult,
        semanticActions: List<SemanticAction>,
        slotResult: SlotExtractionResult,
        alignmentResult: AlignmentResult,
        inferenceResult: InferenceResult,
        trace: DemonstrationTrace,
        filterResult: com.chockXlate.teachablevoice.contract.filter.FilteredDemonstrationResult? = null
    ): WorkflowSynthesisResult {
        val diagnostics = mutableListOf<String>()

        val warnings = mutableListOf<String>()

        if (inferenceResult.slotInferences.any { it.status == SlotInferenceStatus.CONFLICTING } || slotResult.conflicts.isNotEmpty()) {
            return WorkflowSynthesisResult(status = SynthesisStatus.BLOCKED, isExecutable = false,
                diagnostics = listOf("Conflicting teaching evidence must be resolved by a consistent demonstration before saving. No literal fallback is allowed."))
        }

        val intentName = com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy
            .canonicalizeIntent(intentResult.intent.canonicalName)
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
            val isSensitiveSlot = SENSITIVE_KEYWORDS.any { kw -> slotName.lowercase().contains(kw) } ||
                semanticActions.any { action ->
                    val labels = listOfNotNull(action.target?.resourceId, action.target?.text, action.target?.contentDescription)
                    labels.any { label -> SENSITIVE_KEYWORDS.any { label.contains(it, true) } } &&
                        (action.actionId in inf.sourceActionIds || inf.rawValues.any { it == action.inputValue })
                }
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

        // Phase 3B: Cross-Platform Platform Variable Inference
        val isPortableTask = intentName in setOf("order_food", "shop_item", "search_information", "send_message") ||
            intentName.contains("shop", ignoreCase = true) ||
            intentName.contains("order", ignoreCase = true)

        if (isPortableTask && workflowSlots.none { it.isPlatformSlot() }) {
            val demoDesc = trace.voiceEvents.lastOrNull()?.transcript ?: ""
            val platformFromDesc = Regex("""\b(?:from|on|through|via|using)\s+([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                .find(demoDesc)?.groupValues?.get(1)?.trim()
            val inferredPlatform = when {
                !platformFromDesc.isNullOrBlank() && !platformFromDesc.equals("home", true) && !platformFromDesc.equals("cart", true) -> {
                    platformFromDesc.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
                trace.appContext.isNotBlank() && trace.appContext != "unknown" && !DemonstrationFilter.isSystemSurface(trace.appContext) && !DemonstrationFilter.isOwnApp(trace.appContext) -> {
                    val parts = trace.appContext.split('.')
                    val cand = parts.find { it.length > 3 && it !in setOf("android", "application", "shopping", "app", "mobile", "client", "google") }
                    cand?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } ?: trace.appContext
                }
                else -> null
            }

            if (inferredPlatform != null) {
                val platSlot = WorkflowSlot(
                    schemaVersion = "1.0",
                    name = "platform",
                    type = com.chockXlate.teachablevoice.contract.workflow.SlotType.PLATFORM,
                    required = true,
                    exampleValue = inferredPlatform,
                    confidence = 1.0,
                    provenance = "Phase 3B Platform Variable Inference",
                    role = "target_platform"
                )
                variableSlotNames.add("platform")
                workflowSlots.add(platSlot)
            }
        }

        // Infer item slot for portable tasks if user entered text but item was not yet extracted as a variable slot
        if (isPortableTask && workflowSlots.none { it.name == "item" }) {
            val textAction = semanticActions.find {
                it.actionType == com.chockXlate.teachablevoice.learning.actions.SemanticActionType.INPUT_TEXT &&
                !it.inputValue.isNullOrBlank() &&
                SENSITIVE_KEYWORDS.none { kw -> it.inputValue?.lowercase()?.contains(kw) == true }
            }
            if (textAction?.inputValue != null) {
                val cleanItem = textAction.inputValue.trim()
                val itemSlot = WorkflowSlot(
                    schemaVersion = "1.0",
                    name = "item",
                    type = com.chockXlate.teachablevoice.contract.workflow.SlotType.TEXT,
                    required = true,
                    exampleValue = cleanItem,
                    confidence = 1.0,
                    provenance = "Phase 3B Item Variable Inference",
                    role = "target_item"
                )
                variableSlotNames.add("item")
                workflowSlots.add(itemSlot)
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
            val targetDescription = target?.contentDescription?.lowercase().orEmpty()

            val containsSensitiveKeyword = SENSITIVE_KEYWORDS.any { kw ->
                targetResId.contains(kw) || targetText.contains(kw) || targetDescription.contains(kw) || lowerInput.contains(kw)
            }

            if (containsSensitiveKeyword) {
                requiresConfirmation = true
                SENSITIVE_KEYWORDS.filter { kw ->
                    targetResId.contains(kw) || targetText.contains(kw) || targetDescription.contains(kw) || lowerInput.contains(kw)
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
                val slotInWf = workflowSlots.find { it.name == vSlot }
                val valueToMatch = inputVal.ifBlank { target?.text.orEmpty() }
                (inf != null && inf.rawValues.any { rv -> rv.equals(valueToMatch, ignoreCase = true) }) ||
                (slotInWf != null && slotInWf.exampleValue.equals(valueToMatch, ignoreCase = true))
            }

            val matchingConstSlot = if (!containsSensitiveKeyword) {
                constantSlotNames.find { cSlot ->
                    val inf = inferenceResult.slotInferences.find { it.slotName == cSlot }
                    inf != null && inf.rawValues.any { rv -> rv.equals(inputVal, ignoreCase = true) }
                }
            } else null

            if (matchingVarSlot != null) {
                textSlotRef = "\${$matchingVarSlot}"
                selectorText = null // Parameterized variable step
            } else if (matchingConstSlot != null || (selectorText == null && inputVal.isNotBlank())) {
                if (selectorText == null && inputVal.isNotBlank()) {
                    selectorText = inputVal
                }
            }

            val selector = SemanticSelector(
                schemaVersion = "1.0",
                role = target?.role,
                text = selectorText,
                textSlot = textSlotRef,
                contentDescription = if (containsSensitiveKeyword) null else target?.contentDescription,
                resourceId = target?.resourceId,
                parentRole = target?.parentRole,
                ancestorRole = target?.ancestorRole,
                nearbyText = null,
                relativePosition = null
            )

            // Parameters map for step action
            val params = mutableMapOf<String, String>()
            if (textSlotRef != null && action.actionType == com.chockXlate.teachablevoice.learning.actions.SemanticActionType.INPUT_TEXT) {
                params["input_parameter"] = textSlotRef
            } else if (inputVal.isNotBlank() && !containsSensitiveKeyword) {
                params["input_literal"] = inputVal
            }

            // Preconditions
            val stepPackage = target?.packageName?.takeIf { it.isNotBlank() && it != "unknown" && !DemonstrationFilter.isSystemSurface(it) && !DemonstrationFilter.isOwnApp(it) }
                ?: trace.appContext.takeIf { it.isNotBlank() && it != "unknown" && !DemonstrationFilter.isSystemSurface(it) && !DemonstrationFilter.isOwnApp(it) }
                ?: target?.packageName?.takeIf { it.isNotBlank() && it != "unknown" && !DemonstrationFilter.isOwnApp(it) }
                ?: trace.appContext.takeIf { !DemonstrationFilter.isOwnApp(it) }
            val preconditions = Preconditions(
                schemaVersion = "1.0",
                fromState = if (index > 0) "state_step_${index}" else "INITIAL_STATE",
                requiredPackage = stepPackage,
                requiredElementPresent = selector
            )

            // Expected Transition from actual state events
            val matchingState = stateMap[action.actionId]
            val expectedTransition = if (matchingState != null) {
                val beforeState = matchingState.beforeState
                val afterState = matchingState.afterState
                val evidenceList = mutableListOf<com.chockXlate.teachablevoice.contract.workflow.StateEvidenceRequirement>()

                // 1. Package transition
                if (afterState.appContext.isNotBlank() && beforeState.appContext.isNotBlank() && afterState.appContext != beforeState.appContext) {
                    evidenceList.add(com.chockXlate.teachablevoice.contract.workflow.StateEvidenceRequirement(
                        type = com.chockXlate.teachablevoice.contract.workflow.EvidenceType.EXPECTED_PACKAGE,
                        expectedPackage = afterState.appContext,
                        description = "Active package transitions to '${afterState.appContext}'"
                    ))
                }

                // 2. Action-specific outcome
                when (action.actionType) {
                    com.chockXlate.teachablevoice.learning.actions.SemanticActionType.INPUT_TEXT -> {
                        val expectedVal = when {
                            textSlotRef != null -> textSlotRef
                            inputVal.isNotBlank() && !containsSensitiveKeyword -> inputVal
                            target?.text != null && !containsSensitiveKeyword -> target.text
                            else -> null
                        }
                        if (expectedVal != null) {
                            evidenceList.add(com.chockXlate.teachablevoice.contract.workflow.StateEvidenceRequirement(
                                type = com.chockXlate.teachablevoice.contract.workflow.EvidenceType.TEXT_EQUALS,
                                selector = selector,
                                expectedValue = expectedVal,
                                description = "Target input field contains expected text"
                            ))
                        }
                    }
                    com.chockXlate.teachablevoice.learning.actions.SemanticActionType.TAP -> {
                        fun sameElement(e: com.chockXlate.teachablevoice.contract.ui.UiElement, t: com.chockXlate.teachablevoice.learning.targets.SemanticTarget?): Boolean {
                            if (t == null) return false
                            if (!t.resourceId.isNullOrBlank() && t.resourceId == e.resourceId) return true
                            if (!t.contentDescription.isNullOrBlank() && t.contentDescription == e.contentDescription) return true
                            if (!t.text.isNullOrBlank() && t.text == e.text && t.role == e.role) return true
                            return false
                        }
                        val targetBefore = beforeState.allElements.find { sameElement(it, target) }
                        val targetAfter = afterState.allElements.find { sameElement(it, target) }

                        if (targetBefore != null && targetAfter != null && targetBefore.isChecked != targetAfter.isChecked) {
                            evidenceList.add(com.chockXlate.teachablevoice.contract.workflow.StateEvidenceRequirement(
                                type = com.chockXlate.teachablevoice.contract.workflow.EvidenceType.CHECKED_STATE,
                                selector = selector,
                                expectedChecked = targetAfter.isChecked,
                                description = "Target element checked state becomes ${targetAfter.isChecked}"
                            ))
                        } else if (targetBefore != null && targetAfter != null && targetBefore.isSelected != targetAfter.isSelected) {
                            evidenceList.add(com.chockXlate.teachablevoice.contract.workflow.StateEvidenceRequirement(
                                type = com.chockXlate.teachablevoice.contract.workflow.EvidenceType.SELECTED_STATE,
                                selector = selector,
                                expectedSelected = targetAfter.isSelected,
                                description = "Target element selected state becomes ${targetAfter.isSelected}"
                            ))
                        } else if (targetBefore != null && targetAfter == null) {
                            evidenceList.add(com.chockXlate.teachablevoice.contract.workflow.StateEvidenceRequirement(
                                type = com.chockXlate.teachablevoice.contract.workflow.EvidenceType.ELEMENT_DISAPPEARED,
                                selector = selector,
                                description = "Target element disappears after click"
                            ))
                        } else {
                            val appeared = afterState.allElements.filter { a ->
                                val hasIdentity = !a.resourceId.isNullOrBlank() || (!a.text.isNullOrBlank() && a.text.length >= 2) || !a.contentDescription.isNullOrBlank()
                                hasIdentity && beforeState.allElements.none { b ->
                                    (!b.resourceId.isNullOrBlank() && b.resourceId == a.resourceId) ||
                                    (!b.contentDescription.isNullOrBlank() && b.contentDescription == a.contentDescription) ||
                                    (!b.text.isNullOrBlank() && b.text == a.text && b.role == a.role)
                                }
                            }
                            val salient = appeared.firstOrNull { it.isClickable && !it.text.isNullOrBlank() }
                                ?: appeared.firstOrNull { !it.text.isNullOrBlank() }
                                ?: appeared.firstOrNull { !it.resourceId.isNullOrBlank() }
                                ?: appeared.firstOrNull()
                            if (salient != null) {
                                val isSensitive = SENSITIVE_KEYWORDS.any { kw ->
                                    salient.text?.lowercase()?.contains(kw) == true ||
                                    salient.contentDescription?.lowercase()?.contains(kw) == true ||
                                    salient.resourceId?.lowercase()?.contains(kw) == true
                                }
                                val appearedSel = SemanticSelector(
                                    schemaVersion = "1.0",
                                    role = salient.role,
                                    text = if (isSensitive) null else salient.text,
                                    contentDescription = if (isSensitive) null else salient.contentDescription,
                                    resourceId = salient.resourceId,
                                    parentRole = salient.parentRole
                                )
                                evidenceList.add(com.chockXlate.teachablevoice.contract.workflow.StateEvidenceRequirement(
                                    type = com.chockXlate.teachablevoice.contract.workflow.EvidenceType.ELEMENT_APPEARED,
                                    selector = appearedSel,
                                    description = "Expected element appeared: ${salient.text ?: salient.contentDescription ?: salient.resourceId ?: salient.role}"
                                ))
                            }
                        }
                    }
                    else -> {}
                }

                if (evidenceList.isEmpty()) {
                    evidenceList.add(com.chockXlate.teachablevoice.contract.workflow.StateEvidenceRequirement(
                        type = com.chockXlate.teachablevoice.contract.workflow.EvidenceType.GENERIC_STATE_CHANGE,
                        description = "Semantic UI state changed after action"
                    ))
                }

                val appearedSel = evidenceList.firstOrNull { it.type == com.chockXlate.teachablevoice.contract.workflow.EvidenceType.ELEMENT_APPEARED }?.selector
                val disappearedSel = evidenceList.firstOrNull { it.type == com.chockXlate.teachablevoice.contract.workflow.EvidenceType.ELEMENT_DISAPPEARED }?.selector

                ExpectedTransition(
                    schemaVersion = "1.0",
                    fromState = preconditions.fromState,
                    toState = "state_step_${index + 1}",
                    transitionType = "UI_STATE_CHANGE",
                    expectedPackage = matchingState.afterState.appContext,
                    expectedElementAppeared = appearedSel,
                    expectedElementDisappeared = disappearedSel,
                    timeoutMs = 5000L,
                    expectedEvidence = evidenceList,
                    evidenceOperator = com.chockXlate.teachablevoice.contract.workflow.EvidenceOperator.ALL_REQUIRED
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

            val mappedAction = when (action.actionType.name) {
                "INPUT_TEXT" -> "SET_TEXT"
                "TAP" -> {
                    val label = selector.text ?: selector.contentDescription ?: selector.resourceId ?: ""
                    if (label.contains("search", ignoreCase = true)) "SEARCH"
                    else if (label.equals("+") || label.contains("increment", true)) "INCREMENT"
                    else if (label.equals("-") || label.contains("decrement", true)) "DECREMENT"
                    else "CLICK"
                }
                "TOGGLE" -> "CLICK"
                else -> action.actionType.name
            }

            val step = WorkflowStep(
                schemaVersion = "1.0",
                stepId = stepId,
                semanticAction = mappedAction,
                semanticSelector = selector,
                parameters = params,
                preconditions = preconditions,
                expectedTransition = expectedTransition,
                recoveryPolicy = recoveryPolicy,
                confidence = action.confidence,
                provenance = "Phase 3 SemanticAction ${action.actionId} (Voice: ${action.rawEventId ?: "none"})"
            )
            
            val slotName = matchingVarSlot ?: matchingConstSlot ?: "none"
            val exVal = if (slotName != "none") {
                val foundSlot = workflowSlots.find { it.name == slotName }
                foundSlot?.exampleValue ?: inputVal.ifBlank { "none" }
            } else {
                if (inputVal.isNotBlank()) inputVal else "none"
            }
            
            println("[ACTION][IDENTIFIED]")
            println("action=${step.semanticAction}")
            println("stepId=${step.stepId}")
            println("target=${selector.text ?: selector.contentDescription ?: selector.resourceId ?: selector.role ?: "unknown"}")
            println("slot=$slotName")
            println("exampleValue=$exVal")
            println("sourceEvent=${action.rawActionType}")
            println("confidence=${action.confidence}")
            
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
                resourceId = null
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

        val parameterizedSteps = QuantityPattern.parameterize(workflowSteps, semanticActions, trace, workflowSlots)
        val effectiveFilter = filterResult ?: com.chockXlate.teachablevoice.teach.filter.DemonstrationFilter.filter(trace)
        val generatedSkillId = "skill_${intentName}_$skillHash"
        val provGraph = com.chockXlate.teachablevoice.learning.provenance.DemonstrationProvenanceBuilder.build(
            workflowSkillId = generatedSkillId,
            trace = trace,
            steps = parameterizedSteps,
            semanticActions = semanticActions,
            inferenceResult = inferenceResult,
            filterResult = effectiveFilter
        )
        val stepsWithProv = parameterizedSteps.map { step ->
            val link = provGraph.findStepProvenance(step.stepId)
            if (link != null) step.copy(provenanceLink = link) else step
        }
        val subtasks = com.chockXlate.teachablevoice.learning.subtask.WorkflowSubtaskSegmenter.segment(stepsWithProv)

        val primaryApp = stepsWithProv.mapNotNull { it.preconditions.requiredPackage }
            .firstOrNull { it.isNotBlank() && it != "unknown" && !DemonstrationFilter.isSystemSurface(it) && !DemonstrationFilter.isOwnApp(it) }
            ?: trace.appContext.takeIf { !DemonstrationFilter.isSystemSurface(it) && !DemonstrationFilter.isOwnApp(it) && it.isNotBlank() && it != "unknown" }
            ?: stepsWithProv.firstOrNull()?.preconditions?.requiredPackage?.takeIf { !DemonstrationFilter.isOwnApp(it) }
            ?: "com.teachablevoice.app"

        val workflow = Workflow(
            schemaVersion = "1.0",
            skillId = generatedSkillId,
            name = "Workflow for $intentName",
            intent = intentName,
            appContext = primaryApp,
            slots = workflowSlots.sortedBy { it.name },
            steps = stepsWithProv,
            safetyBoundary = safetyBoundary,
            subtasks = subtasks,
            provenanceGraph = provGraph
        )

        val compatibility = com.chockXlate.teachablevoice.skill.validation.ReplayAdmission.problems(workflow) +
            inferenceResult.slotInferences.filter { it.status == SlotInferenceStatus.UNKNOWN }.map {
                "Confirm whether '${it.slotName}' is variable or constant before saving."
            }
        diagnostics.addAll(compatibility)
        val overallStatus = when {
            compatibility.isNotEmpty() -> SynthesisStatus.DEGRADED
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

        println("========== LEARNED WORKFLOW ==========")
        println("Workflow: ${workflow.intent}")
        println()
        if (workflow.slots.isNotEmpty()) {
            println("Slots:")
            workflow.slots.forEach { slot ->
                println("- ${slot.name}")
                println("  exampleValue = ${slot.exampleValue ?: "none"}")
            }
            println()
        }
        println("Actions:")
        workflow.steps.forEachIndexed { i, step ->
            println("${i + 1}. ${step.semanticAction}")
            val targetStr = step.semanticSelector.text ?: step.semanticSelector.contentDescription ?: step.semanticSelector.resourceId ?: step.semanticSelector.role ?: "unknown"
            println("   target = $targetStr")
            val slotParam = step.parameters["input_parameter"]?.removePrefix("\${")?.removeSuffix("}")
            if (slotParam != null) {
                println("   slot = $slotParam")
                val foundSlot = workflow.slots.find { it.name == slotParam }
                if (foundSlot != null) {
                    println("   exampleValue = ${foundSlot.exampleValue}")
                }
            } else if (step.parameters.containsKey("input_literal")) {
                println("   exampleValue = ${step.parameters["input_literal"]}")
            }
            println()
        }
        println("=======================================")

        return WorkflowSynthesisResult(
            schemaVersion = "1.0",
            workflow = workflow,
            status = overallStatus,
            diagnostics = diagnostics,
            warnings = warnings,
            evidenceSummary = "Synthesized ${workflow.steps.size} steps and ${workflowSlots.size} slots for intent '$intentName'.",
            provenanceDemonstrationIds = provDemoIds,
            isExecutable = compatibility.isEmpty() && !requiresConfirmation
        )
    }
}
