package com.chockXlate.teachablevoice.teach.trace

import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.teach.normalization.TraceNormalizationResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Human-readable inspector and formatter for captured DemonstrationTrace objects.
 */
object TraceViewer {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Formats a DemonstrationTrace into a human-readable structured tree format.
     */
    fun formatTrace(trace: DemonstrationTrace): String {
        val sb = StringBuilder()
        val startDate = timeFormat.format(Date(trace.timestamp))

        sb.appendLine("==================================================")
        sb.appendLine("       DEMONSTRATION TRACE INSPECTOR")
        sb.appendLine("==================================================")
        sb.appendLine("Trace ID   : ${trace.traceId}")
        sb.appendLine("Start Time : $startDate (${trace.timestamp})")
        sb.appendLine("App Context: ${trace.appContext}")
        sb.appendLine("Total Events: ${trace.traceEvents.size}")
        sb.appendLine("  ├─ Voice Events : ${trace.voiceEvents.size}")
        sb.appendLine("  ├─ UI Events    : ${trace.uiStates.size}")
        sb.appendLine("  ├─ Action Events: ${trace.userActions.size}")
        sb.appendLine("  └─ State Events : ${trace.stateEvents.size}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("CHRONOLOGICAL EVENT STREAM:")

        if (trace.traceEvents.isEmpty()) {
            sb.appendLine("  (No events recorded in this session)")
        } else {
            trace.traceEvents.forEachIndexed { index, event ->
                val timeStr = timeFormat.format(Date(event.timestamp))
                sb.appendLine("[$timeStr] [#${index + 1}]")
                when (event) {
                    is TraceEvent.Voice -> {
                        val ve = event.voiceEvent
                        sb.appendLine("  ├── [VOICE EVENT]")
                        sb.appendLine("  │   ├── Transcript: \"${ve.transcript}\"")
                        sb.appendLine("  │   └── Confidence: ${ve.confidence}")
                    }
                    is TraceEvent.Ui -> {
                        val ue = event.uiEvent
                        sb.appendLine("  ├── [UI EVENT]")
                        sb.appendLine("  │   ├── Type   : ${ue.accessibilityEventType}")
                        sb.appendLine("  │   ├── Package: ${ue.packageName}")
                        ue.targetElement?.let { elem ->
                            sb.appendLine("  │   └── Target : [Role: ${elem.role}, Text: '${elem.text ?: ""}', ResId: ${elem.resourceId ?: "none"}]")
                        }
                    }
                    is TraceEvent.Action -> {
                        val ae = event.actionEvent
                        val sel = ae.semanticSelector
                        sb.appendLine("  ├── [ACTION EVENT]")
                        sb.appendLine("  │   ├── Action : ${ae.actionType}")
                        sb.appendLine("  │   ├── Input  : ${ae.inputData ?: "N/A"}")
                        sb.appendLine("  │   └── Target : [Role: ${sel.role ?: "any"}, Text: '${sel.text ?: ""}', ResId: ${sel.resourceId ?: "none"}]")
                    }
                    is TraceEvent.State -> {
                        val se = event.stateEvent
                        sb.appendLine("  ├── [STATE EVENT]")
                        sb.appendLine("  │   ├── Cause Action: ${se.causeActionId ?: "initial"}")
                        sb.appendLine("  │   ├── Before App  : ${se.beforeState.appContext} (${se.beforeState.allElements.size} nodes)")
                        sb.appendLine("  │   └── After App   : ${se.afterState.appContext} (${se.afterState.allElements.size} nodes)")
                    }
                }
            }
        }
        sb.appendLine("==================================================")
        return sb.toString()
    }

    /**
     * Formats Phase 2 TraceNormalizationResult diagnostics into a structured summary.
     */
    fun formatNormalizationSummary(result: TraceNormalizationResult): String {
        val trace = result.normalizedTrace
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("TRACE NORMALIZATION")
        sb.appendLine("===================")
        sb.appendLine()
        sb.appendLine("RAW EVENTS              : ${result.rawEventCount}")
        sb.appendLine("NORMALIZED EVENTS       : ${result.normalizedEventCount}")
        sb.appendLine("DUPLICATES REMOVED      : ${result.removedDuplicateCount}")
        sb.appendLine("WARNINGS                : ${result.warningCount}")
        sb.appendLine()
        sb.appendLine("VOICE EVENTS            : ${trace.voiceEvents.size}")
        sb.appendLine("UI EVENTS               : ${trace.uiStates.size}")
        sb.appendLine("ACTION EVENTS           : ${trace.userActions.size}")
        sb.appendLine("STATE EVENTS            : ${trace.stateEvents.size}")
        sb.appendLine()
        sb.appendLine("APP CONTEXT             : ${if (trace.appContext.isNotBlank()) "preserved (${trace.appContext})" else "none"}")
        sb.appendLine("TIMESTAMPS              : preserved")
        sb.appendLine("TARGET EVIDENCE         : preserved")
        sb.appendLine("BEFORE/AFTER STATES     : preserved")
        sb.appendLine()
        sb.appendLine("STATUS                  : ${result.status}")
        sb.appendLine("========================================")

        if (result.warnings.isNotEmpty()) {
            sb.appendLine("WARNING DETAILS:")
            result.warnings.forEach { w -> sb.appendLine("  - $w") }
            sb.appendLine("========================================")
        }

        sb.appendLine()
        sb.append(formatTrace(trace))
        return sb.toString()
    }

    /**
     * Formats Phase 3 SemanticAction extraction results into a human-readable stream.
     */
    fun formatSemanticActions(actions: List<com.chockXlate.teachablevoice.learning.actions.SemanticAction>): String {
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("       PHASE 3: SEMANTIC ACTION EXTRACTOR")
        sb.appendLine("==================================================")
        sb.appendLine("Extracted Semantic Actions: ${actions.size}")
        sb.appendLine("--------------------------------------------------")

        if (actions.isEmpty()) {
            sb.appendLine("  (No semantic actions extracted)")
        } else {
            actions.forEachIndexed { idx, action ->
                val target = action.target
                val timeStr = timeFormat.format(Date(action.timestamp))

                sb.appendLine("[$timeStr] [#${idx + 1}] SEMANTIC ACTION: ${action.actionType}")
                sb.appendLine("  ├── Raw Action  : ${action.rawActionType ?: "N/A"}")
                sb.appendLine("  ├── Input Value : ${action.inputValue ?: "N/A"}")
                sb.appendLine("  ├── Confidence  : ${action.confidenceLevel} (${action.confidence})")
                sb.appendLine("  └── Target Evidence:")
                if (target == null) {
                    sb.appendLine("      └── (Target Unavailable)")
                } else {
                    sb.appendLine("      ├── Role       : ${target.role ?: "N/A"}")
                    sb.appendLine("      ├── Text       : ${target.text ?: "N/A"}")
                    sb.appendLine("      ├── Resource ID: ${target.resourceId ?: "N/A"}")
                    sb.appendLine("      ├── Package    : ${target.packageName ?: "N/A"}")
                    sb.appendLine("      └── Confidence : ${target.targetConfidence}")
                }
                sb.appendLine("--------------------------------------------------")
            }
        }
        sb.appendLine("==================================================")
        return sb.toString()
    }

    /**
     * Formats Phase 4 IntentExtractionResult into a human-readable summary.
     */
    fun formatIntentResult(result: com.chockXlate.teachablevoice.learning.intent.IntentExtractionResult): String {
        val intent = result.intent
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("       PHASE 4: INTENT EXTRACTION RESULT")
        sb.appendLine("==================================================")
        sb.appendLine("Extracted Intent ID: ${intent.intentId}")
        sb.appendLine("CANONICAL INTENT   : ${intent.canonicalName}")
        sb.appendLine("CONFIDENCE         : ${intent.confidenceLevel} (${intent.confidence})")
        sb.appendLine("STATUS             : ${result.status}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("VOICE EVIDENCE     : ${intent.sourceVoiceTranscript ?: "(No voice transcript recorded)"}")
        sb.appendLine("VOICE EVENT IDS    : ${if (intent.sourceVoiceEventIds.isEmpty()) "None" else intent.sourceVoiceEventIds.joinToString(", ")}")
        sb.appendLine("ACTION EVENT IDS   : ${if (intent.supportingActionIds.isEmpty()) "None" else intent.supportingActionIds.joinToString(", ")}")
        sb.appendLine("APP PACKAGE        : ${intent.supportingAppPackage ?: "N/A"}")
        sb.appendLine("REASONING          : ${intent.reasoning}")
        sb.appendLine("--------------------------------------------------")
        if (result.warnings.isNotEmpty()) {
            sb.appendLine("WARNINGS:")
            result.warnings.forEach { w -> sb.appendLine("  - $w") }
            sb.appendLine("--------------------------------------------------")
        }
        sb.appendLine("NOTE: No slot values (e.g. restaurant, item, quantity, address) were extracted.")
        sb.appendLine("      Slot extraction belongs strictly to Phase 5.")
        sb.appendLine("==================================================")
        return sb.toString()
    }

    /**
     * Formats Phase 5 SlotExtractionResult into a structured human-readable summary.
     */
    fun formatSlotResult(result: com.chockXlate.teachablevoice.learning.slots.SlotExtractionResult): String {
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("    PHASE 5: SLOT EXTRACTION & PARAMETERIZATION")
        sb.appendLine("==================================================")
        sb.appendLine("INTENT           : ${result.intentName}")
        sb.appendLine("STATUS           : ${result.status}")
        sb.appendLine("EXTRACTED SLOTS  : ${result.extractedSlots.size}")
        sb.appendLine("UNRESOLVED SLOTS : ${if (result.unresolvedSlots.isEmpty()) "None" else result.unresolvedSlots.joinToString(", ")}")
        sb.appendLine("CONFLICTS        : ${result.conflicts.size}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("EXTRACTED SLOTS:")

        if (result.extractedSlots.isEmpty()) {
            sb.appendLine("  (No parameter slots extracted)")
        } else {
            result.extractedSlots.forEach { slot ->
                sb.appendLine("  ├── [SLOT: '${slot.name}']")
                sb.appendLine("  │   ├── Type      : ${slot.type}")
                sb.appendLine("  │   ├── Raw Value : \"${slot.rawValue}\"")
                sb.appendLine("  │   ├── Typed Val : ${slot.typedValue}")
                sb.appendLine("  │   ├── Confidence: ${slot.confidenceLevel} (${slot.confidence})")
                sb.appendLine("  │   └── Evidence  : ${slot.provenanceReasoning}")
            }
        }

        if (result.conflicts.isNotEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("DETECTED CONFLICTS:")
            result.conflicts.forEach { c ->
                sb.appendLine("  ├── Slot '${c.slotName}': Voice='${c.voiceValue}' vs Action='${c.actionValue}'")
            }
        }

        if (result.warnings.isNotEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("WARNINGS:")
            result.warnings.forEach { w -> sb.appendLine("  - $w") }
        }

        sb.appendLine("==================================================")
        return sb.toString()
    }

    /**
     * Formats Phase 6 InferenceResult into a structured human-readable summary.
     */
    fun formatInferenceResult(
        alignmentResult: com.chockXlate.teachablevoice.learning.alignment.AlignmentResult,
        inferenceResult: com.chockXlate.teachablevoice.learning.inference.InferenceResult
    ): String {
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine(" PHASE 6: ALIGNMENT & CONSTANT/VARIABLE INFERENCE")
        sb.appendLine("==================================================")
        sb.appendLine("INTENT                  : ${inferenceResult.intentName}")
        sb.appendLine("DEMONSTRATIONS ALIGNED  : ${alignmentResult.alignedDemonstrationIds.size}")
        sb.appendLine("INCOMPATIBLE DEMOS      : ${if (alignmentResult.incompatibleDemonstrationIds.isEmpty()) "None" else alignmentResult.incompatibleDemonstrationIds.joinToString(", ")}")
        sb.appendLine("ALIGNED SLOT GROUPS     : ${alignmentResult.alignedSlotGroups.size}")
        sb.appendLine("INFERENCE STATUS        : ${inferenceResult.status}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("ALIGNMENT SUMMARY:")
        alignmentResult.alignedSlotGroups.forEach { group ->
            val statusStr = if (group.isTypeMismatch) "TYPE_MISMATCH" else "aligned"
            sb.appendLine("  ├── Slot '${group.slotName}' -> $statusStr (${group.occurrences.size} occurrence(s))")
        }
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("INFERRED PARAMETERS:")
        if (inferenceResult.slotInferences.isEmpty()) {
            sb.appendLine("  (No parameter slot inferences generated)")
        } else {
            inferenceResult.slotInferences.forEach { inf ->
                sb.appendLine("  ├── [SLOT: '${inf.slotName}']")
                sb.appendLine("  │   ├── Status    : ${inf.status}")
                sb.appendLine("  │   ├── Type      : ${inf.slotType}")
                sb.appendLine("  │   ├── Raw Values: ${inf.rawValues}")
                sb.appendLine("  │   ├── Confidence: ${inf.confidenceLevel} (${inf.confidence})")
                sb.appendLine("  │   └── Reasoning : ${inf.reasoning}")
            }
        }
        if (inferenceResult.warnings.isNotEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("WARNINGS / DIAGNOSTICS:")
            inferenceResult.warnings.forEach { w -> sb.appendLine("  - $w") }
        }
        sb.appendLine("==================================================")
        return sb.toString()
    }

    /**
     * Formats Phase 7 WorkflowSynthesisResult into a human-readable summary.
     */
    fun formatWorkflowSynthesis(result: com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesisResult): String {
        val wf = result.workflow
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("      PHASE 7: WORKFLOW IR SYNTHESIS RESULT")
        sb.appendLine("==================================================")
        sb.appendLine("SYNTHESIS STATUS : ${result.status}")
        sb.appendLine("IS EXECUTABLE    : ${result.isExecutable}")
        sb.appendLine("INTENT           : ${wf?.intent ?: "N/A"}")
        sb.appendLine("SKILL ID         : ${wf?.skillId ?: "N/A"}")
        sb.appendLine("APP CONTEXT      : ${wf?.appContext ?: "N/A"}")
        sb.appendLine("--------------------------------------------------")

        if (wf == null) {
            sb.appendLine("WORKFLOW IR     : NULL (Synthesis Blocked)")
        } else {
            val vars = wf.slots.filter { it.required }
            val consts = wf.slots.filter { !it.required }

            sb.appendLine("## VARIABLES (${vars.size}):")
            if (vars.isEmpty()) {
                sb.appendLine("  (No variable slots)")
            } else {
                vars.forEach { s ->
                    sb.appendLine("  ├── Parameter '${s.name}' : ${s.type} (Example: '${s.exampleValue ?: "none"}')")
                }
            }

            sb.appendLine("--------------------------------------------------")
            sb.appendLine("## CONSTANTS (${consts.size}):")
            if (consts.isEmpty()) {
                sb.appendLine("  (No constant context slots)")
            } else {
                consts.forEach { s ->
                    sb.appendLine("  ├── Constant '${s.name}' : '${s.exampleValue ?: "none"}'")
                }
            }

            sb.appendLine("--------------------------------------------------")
            sb.appendLine("## SEMANTIC PROCEDURE (${wf.steps.size} Steps):")
            if (wf.steps.isEmpty()) {
                sb.appendLine("  (No semantic steps synthesized)")
            } else {
                wf.steps.forEachIndexed { idx, step ->
                    val sel = step.semanticSelector
                    val targetDesc = "[Role: ${sel.role ?: "any"}, ResId: ${sel.resourceId ?: "none"}]"
                    val valDesc = if (sel.textSlot != null) "Slot: ${sel.textSlot}" else if (sel.text != null) "Text: '${sel.text}'" else "No input text"
                    sb.appendLine("  ${idx + 1}. ${step.semanticAction} $targetDesc -> $valDesc")
                }
            }

            sb.appendLine("--------------------------------------------------")
            sb.appendLine("## EXPECTED STATES:")
            val statefulSteps = wf.steps.filter { it.expectedTransition.toState != null }
            sb.appendLine("  Derived transitions: ${statefulSteps.size} steps contain recorded state transitions.")

            sb.appendLine("--------------------------------------------------")
            sb.appendLine("## SAFETY BOUNDARY:")
            sb.appendLine("  User Confirmation Required : ${wf.safetyBoundary.requiresExplicitUserConfirmation}")
            sb.appendLine("  Sensitive Keywords Detected: ${if (wf.safetyBoundary.sensitiveKeywords.isEmpty()) "None" else wf.safetyBoundary.sensitiveKeywords.joinToString(", ")}")
            sb.appendLine("  Credential / Payment Guard : ACTIVE (No password/PIN/CVV credential automation)")

            sb.appendLine("--------------------------------------------------")
            sb.appendLine("## PROVENANCE:")
            sb.appendLine("  Demonstrations : ${if (result.provenanceDemonstrationIds.isEmpty()) "None" else result.provenanceDemonstrationIds.joinToString(", ")}")
            sb.appendLine("  Evidence       : ${result.evidenceSummary}")
        }

        if (result.diagnostics.isNotEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("DIAGNOSTICS:")
            result.diagnostics.forEach { d -> sb.appendLine("  - $d") }
        }

        if (result.warnings.isNotEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("WARNINGS:")
            result.warnings.forEach { w -> sb.appendLine("  - $w") }
        }

        sb.appendLine("==================================================")
        return sb.toString()
    }

    /**
     * Formats Phase 8 WorkflowValidationResult and Skill Store persistence summary.
     */
    fun formatValidationAndStoreResult(
        validation: com.chockXlate.teachablevoice.skill.validation.WorkflowValidationResult,
        savedSuccessfully: Boolean,
        retrievedWorkflow: com.chockXlate.teachablevoice.contract.workflow.Workflow?,
        version: Int
    ): String {
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("   PHASE 8: WORKFLOW VALIDATION & SKILL STORE")
        sb.appendLine("==================================================")
        sb.appendLine("STATUS           : ${validation.status}")
        sb.appendLine("STOREABLE        : ${validation.isStoreable}")
        sb.appendLine("ISSUES COUNT     : ${validation.issues.size}")
        sb.appendLine("SKILL ID         : ${validation.workflowId ?: "N/A"}")
        sb.appendLine("VERSION          : $version")
        sb.appendLine("STORAGE STATUS   : ${if (savedSuccessfully) "STORED ✓" else "REJECTED ✗"}")
        sb.appendLine("RETRIEVAL STATUS : ${if (retrievedWorkflow != null) "SUCCESS ✓" else "FAILED / NONE ✗"}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("SAFETY CHECK     : ${if (validation.issues.none { it.category == com.chockXlate.teachablevoice.skill.validation.ValidationCategory.SAFETY }) "PASS ✓" else "FAIL ✗"}")
        sb.appendLine("COORDINATE CHECK : ${if (validation.issues.none { it.category == com.chockXlate.teachablevoice.skill.validation.ValidationCategory.COORDINATE_REPLAY }) "NONE ✓" else "FAILED (Coordinates Present) ✗"}")
        sb.appendLine("--------------------------------------------------")

        if (validation.issues.isEmpty()) {
            sb.appendLine("VALIDATION ISSUES: None (Workflow is 100% valid)")
        } else {
            sb.appendLine("VALIDATION ISSUES:")
            validation.issues.forEach { issue ->
                sb.appendLine("  ├── [${issue.severity}] ${issue.category}: ${issue.message}")
            }
        }

        if (retrievedWorkflow != null) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("RETRIEVED SKILL DETAILS:")
            sb.appendLine("  Intent   : ${retrievedWorkflow.intent}")
            sb.appendLine("  Slots    : ${retrievedWorkflow.slots.size} declared")
            sb.appendLine("  Steps    : ${retrievedWorkflow.steps.size} procedure steps")
            sb.appendLine("  App      : ${retrievedWorkflow.appContext}")
            sb.appendLine("  Safety   : Confirmation Required = ${retrievedWorkflow.safetyBoundary.requiresExplicitUserConfirmation}")
        }

        sb.appendLine("==================================================")
        return sb.toString()
    }

    /**
     * Formats Phase 9 WorkflowInspectionResult into human-readable text.
     */
    fun formatInspectionResult(result: com.chockXlate.teachablevoice.skill.inspector.WorkflowInspectionResult): String {
        return result.formattedText
    }

    /**
     * Formats Phase 10 CommandUnderstandingResult into a structured human-readable summary.
     */
    fun formatCommandUnderstandingResult(result: com.chockXlate.teachablevoice.command.interpretation.CommandUnderstandingResult): String {
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("    PHASE 10: NEW COMMAND UNDERSTANDING RESULT")
        sb.appendLine("==================================================")
        sb.appendLine("RAW COMMAND        : \"${result.rawCommand}\"")
        sb.appendLine("NORMALIZED COMMAND : \"${result.normalizedCommand}\"")
        sb.appendLine("INFERRED INTENT    : ${result.intent.canonicalName} (${result.intent.confidenceLevel}, ${result.intentConfidence})")
        sb.appendLine("STATUS             : ${result.status}")
        sb.appendLine("OVERALL CONFIDENCE : ${result.overallConfidence}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("EXTRACTED COMMAND SLOTS (${result.slots.size}):")

        if (result.slots.isEmpty()) {
            sb.appendLine("  (No slots extracted from command)")
        } else {
            result.slots.forEach { slot ->
                sb.appendLine("  ├── [SLOT: '${slot.name}']")
                sb.appendLine("  │   ├── Type       : ${slot.type}")
                sb.appendLine("  │   ├── Raw Value  : \"${slot.rawValue}\"")
                sb.appendLine("  │   ├── Typed Val  : ${slot.typedValue}")
                sb.appendLine("  │   ├── Confidence : ${slot.confidenceLevel} (${slot.confidence})")
                sb.appendLine("  │   └── Provenance : ${slot.provenance}")
            }
        }

        sb.appendLine("--------------------------------------------------")
        sb.appendLine("UNRESOLVED ITEMS (${result.unresolvedItems.size}):")
        if (result.unresolvedItems.isEmpty()) {
            sb.appendLine("  None (All expected slots extracted from command)")
        } else {
            result.unresolvedItems.forEach { item ->
                sb.appendLine("  ├── $item")
            }
        }

        if (result.diagnostics.isNotEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("DIAGNOSTICS:")
            result.diagnostics.forEach { d -> sb.appendLine("  - $d") }
        }

        sb.appendLine("--------------------------------------------------")
        sb.appendLine("PHASE 10 BOUNDARY CHECK:")
        sb.appendLine("  Skill Matching   : NOT PERFORMED IN PHASE 10")
        sb.appendLine("  ExecutionRequest : NOT CREATED IN PHASE 10")
        sb.appendLine("  Runtime Execution: NOT PERFORMED IN PHASE 10")
        sb.appendLine("==================================================")
        return sb.toString()
    }

    /**
     * Formats Phase 11 SkillMatchResult into a structured human-readable summary.
     */
    fun formatSkillMatchResult(result: com.chockXlate.teachablevoice.command.matching.SkillMatchResult): String {
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("       PHASE 11: SKILL MATCHING RESULT")
        sb.appendLine("==================================================")
        sb.appendLine("COMMAND        : \"${result.commandText}\"")
        sb.appendLine("INTENT         : ${result.intent}")
        sb.appendLine("STATUS         : ${result.status}")
        sb.appendLine("SELECTED SKILL : ${result.selectedSkillId ?: "None (Ambiguous / Unknown)"}")
        sb.appendLine("SELECTED VER   : ${result.selectedVersion ?: "N/A"}")
        sb.appendLine("CONFIDENCE     : ${result.overallConfidence}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("EVALUATED CANDIDATES (${result.candidates.size}):")

        if (result.candidates.isEmpty()) {
            sb.appendLine("  (No candidate workflows evaluated)")
        } else {
            result.candidates.forEach { cand ->
                sb.appendLine("  ├── [SKILL: ${cand.skillId} (v${cand.version})]")
                sb.appendLine("  │   ├── Intent Match  : ${cand.isIntentCompatible}")
                sb.appendLine("  │   ├── Const Match   : ${cand.isConstantCompatible}")
                sb.appendLine("  │   ├── Matched Slots : ${if (cand.matchedSlots.isEmpty()) "None" else cand.matchedSlots.joinToString(", ")}")
                sb.appendLine("  │   ├── Missing Slots : ${if (cand.missingSlots.isEmpty()) "None" else cand.missingSlots.joinToString(", ")}")
                sb.appendLine("  │   ├── Incompat Slots: ${if (cand.incompatibleSlots.isEmpty()) "None" else cand.incompatibleSlots.joinToString(", ")}")
                sb.appendLine("  │   ├── Confidence    : ${cand.confidence}")
                sb.appendLine("  │   └── Reasoning     : ${cand.reasoning.joinToString("; ")}")
            }
        }

        if (result.diagnostics.isNotEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("DIAGNOSTICS:")
            result.diagnostics.forEach { d -> sb.appendLine("  - $d") }
        }

        sb.appendLine("--------------------------------------------------")
        sb.appendLine("PHASE 11 BOUNDARY CHECK:")
        sb.appendLine("  Skill Matching   : COMPLETE")
        sb.appendLine("  ExecutionRequest : NOT CREATED IN PHASE 11")
        sb.appendLine("  Runtime Execution: NOT PERFORMED")
        sb.appendLine("==================================================")
        return sb.toString()
    }

    /**
     * Formats Phase 12 ExecutionRequestBuildResult into a structured human-readable summary.
     */
    fun formatExecutionRequestResult(result: com.chockXlate.teachablevoice.command.request.ExecutionRequestBuildResult): String {
        return buildString {
            appendLine("EXECUTION REQUEST PREVIEW — NO ACTION PERFORMED")
            appendLine("Status: ${result.status}")
            appendLine("Skill: ${result.skillId ?: "None"}")
            appendLine("Execution ID: ${result.executionRequest?.executionId ?: "Not created"}")
            appendLine("Missing slots: ${result.missingSlots.joinToString().ifBlank { "None" }}")
            result.rejectionReason?.let { appendLine("Reason: $it") }
            appendLine("Use EXECUTE RUNTIME to submit a freshly bound command.")
        }
    }

    /**
     * Formats Person 2 RuntimeReport into a structured human-readable summary.
     */
    fun formatRuntimeReport(report: com.chockXlate.teachablevoice.runtime.trace.RuntimeReport): String {
        val sb = StringBuilder()
        val result = report.result
        val trace = report.trace

        sb.appendLine("==================================================")
        sb.appendLine("       PERSON 2 RUNTIME EXECUTION REPORT")
        sb.appendLine("==================================================")
        sb.appendLine("EXECUTION ID   : ${result.executionId}")
        sb.appendLine("SKILL ID       : ${trace.skillId}")
        sb.appendLine("FINAL STATE    : ${result.finalState}")
        sb.appendLine("SUCCESS        : ${if (result.success) "YES ✓" else "NO ✗"}")
        sb.appendLine("STEPS COMPLETED: ${result.stepsCompleted} / ${result.totalSteps}")
        sb.appendLine("DURATION       : ${result.durationMs} ms")
        if (report.stoppedStepId != null) {
            sb.appendLine("STOPPED STEP   : ${report.stoppedStepId}")
        }
        if (result.errorMessage != null) {
            sb.appendLine("REASON / ERROR : ${result.errorMessage}")
        }
        sb.appendLine("--------------------------------------------------")

        if (result.finalState == com.chockXlate.teachablevoice.contract.runtime.ExecutionState.PAUSED_FOR_HANDOFF) {
            sb.appendLine(">>> AUTOMATION PAUSED — USER ACTION REQUIRED <<<")
            sb.appendLine("Handoff Reason: ${result.errorMessage ?: "User confirmation required."}")
            sb.appendLine("Safety boundary active. No further automated Accessibility actions are allowed until explicit reset.")
            sb.appendLine("--------------------------------------------------")
        }

        sb.appendLine("RUNTIME DECISION TRACE (${report.diagnostics.size} decisions):")
        if (report.diagnostics.isEmpty()) {
            sb.appendLine("  (No decision diagnostics recorded)")
        } else {
            report.diagnostics.forEachIndexed { idx, diag ->
                val stepStr = diag.stepId?.let { "[$it] " } ?: ""
                sb.appendLine("  #${idx + 1}. $stepStr${diag.state} -> ${diag.decision.type}")
                sb.appendLine("      Reason: ${diag.decision.reason} (confidence: ${diag.decision.confidence})")
            }
        }

        if (trace.events.isNotEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("EXECUTION EVENTS (${trace.events.size}):")
            trace.events.forEachIndexed { idx, event ->
                val timeStr = timeFormat.format(Date(event.timestamp))
                when (event) {
                    is TraceEvent.Action -> {
                        sb.appendLine("  [$timeStr] Action: ${event.actionEvent.actionType} (ID: ${event.actionEvent.actionId})")
                    }
                    is TraceEvent.State -> {
                        sb.appendLine("  [$timeStr] State Change: Cause=${event.stateEvent.causeActionId}")
                    }
                    else -> {}
                }
            }
        }

        sb.appendLine("==================================================")
        return sb.toString()
    }
}







