package com.chockXlate.teachablevoice.command.request

import com.chockXlate.teachablevoice.command.interpretation.CommandUnderstandingResult
import com.chockXlate.teachablevoice.command.matching.SkillMatchResult
import com.chockXlate.teachablevoice.command.matching.SkillMatchStatus
import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.skill.validation.ValidationStatus
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator

/**
 * Generic deterministic Execution Request Builder for Phase 12.
 * Converts [CommandUnderstandingResult] + MATCHED [SkillMatchResult] + [Workflow]
 * into a validated [ExecutionRequest] for Person 2 handoff.
 *
 * Enforces strict safety boundary:
 * - NO real UI execution (Person 2 runtime responsibility).
 * - NO value fabrication for missing variable slots.
 * - NO usage of demonstration example values for variable slots.
 * - NO sensitive credential leakage into bound slots.
 * - NO coordinate execution data in the request.
 */
object ExecutionRequestBuilder {

    private val SENSITIVE_KEYWORDS = setOf(
        "password", "pin", "otp", "cvv", "cvv2", "card number", "passcode", "secret", "auth_token"
    )

    fun build(
        understandingResult: CommandUnderstandingResult,
        matchResult: SkillMatchResult,
        repository: LocalSkillRepository,
        overrideExecutionId: String? = null
    ): ExecutionRequestBuildResult {

        val skillId = matchResult.selectedSkillId
        val workflow = if (skillId != null) repository.getWorkflowById(skillId) else null

        return buildInternal(understandingResult, matchResult, workflow, repository, overrideExecutionId)
    }

    fun buildWithWorkflow(
        understandingResult: CommandUnderstandingResult,
        matchResult: SkillMatchResult,
        workflow: Workflow?,
        version: Int = 1,
        overrideExecutionId: String? = null
    ): ExecutionRequestBuildResult {
        return buildInternal(understandingResult, matchResult, workflow, null, overrideExecutionId, versionOverride = version)
    }

    private fun buildInternal(
        understandingResult: CommandUnderstandingResult,
        matchResult: SkillMatchResult,
        workflow: Workflow?,
        repository: LocalSkillRepository?,
        overrideExecutionId: String?,
        versionOverride: Int? = null
    ): ExecutionRequestBuildResult {
        val diagnostics = mutableListOf<String>()

        // Rule 1: Check Skill Match Status
        when (matchResult.status) {
            SkillMatchStatus.UNKNOWN -> {
                diagnostics.add("Skill match status is UNKNOWN.")
                return ExecutionRequestBuildResult(
                    status = ExecutionRequestStatus.REJECTED_UNKNOWN_MATCH,
                    executionRequest = null,
                    skillId = null,
                    version = null,
                    diagnostics = diagnostics,
                    rejectionReason = "ExecutionRequest NOT CREATED: Match status is UNKNOWN (No compatible learned skill found)",
                    handoffMessage = "HANDOFF TO PERSON 2: CANNOT HANDOFF (Skill UNKNOWN)"
                )
            }
            SkillMatchStatus.AMBIGUOUS -> {
                diagnostics.add("Skill match status is AMBIGUOUS with ${matchResult.candidates.size} candidates.")
                return ExecutionRequestBuildResult(
                    status = ExecutionRequestStatus.REJECTED_AMBIGUOUS_MATCH,
                    executionRequest = null,
                    skillId = null,
                    version = null,
                    diagnostics = diagnostics,
                    rejectionReason = "ExecutionRequest NOT CREATED: Match status is AMBIGUOUS (Multiple compatible skills require clarification)",
                    handoffMessage = "HANDOFF TO PERSON 2: CANNOT HANDOFF (Skill AMBIGUOUS)"
                )
            }
            SkillMatchStatus.MATCHED -> {
                // Proceed with matching workflow
            }
        }

        // Rule 2: Check Workflow Existence & Validation
        val skillId = matchResult.selectedSkillId
        if (skillId == null || workflow == null) {
            diagnostics.add("Selected workflow '$skillId' not found in store.")
            return ExecutionRequestBuildResult(
                status = ExecutionRequestStatus.REJECTED_INVALID_WORKFLOW,
                executionRequest = null,
                skillId = skillId,
                version = null,
                diagnostics = diagnostics,
                rejectionReason = "ExecutionRequest NOT CREATED: Selected workflow '$skillId' not found in skill store",
                handoffMessage = "HANDOFF TO PERSON 2: CANNOT HANDOFF (Workflow Missing)"
            )
        }

        // Rule 2: Safety & Sensitive Credential Check (Blocked check takes precedence over generic invalid)
        val isSensitiveWorkflow = workflow.safetyBoundary.requiresExplicitUserConfirmation ||
                workflow.safetyBoundary.sensitiveKeywords.any { kw -> SENSITIVE_KEYWORDS.contains(kw.lowercase()) } ||
                workflow.slots.any { slot -> SENSITIVE_KEYWORDS.contains(slot.name.lowercase()) } ||
                understandingResult.slots.any { slot -> SENSITIVE_KEYWORDS.contains(slot.name.lowercase()) }

        val validation = WorkflowValidator.validate(workflow)

        if (validation.status == ValidationStatus.BLOCKED || isSensitiveWorkflow) {
            diagnostics.add("Sensitive workflow or credential parameters detected in safety boundary.")
            return ExecutionRequestBuildResult(
                status = ExecutionRequestStatus.REJECTED_SAFETY_BLOCKED,
                executionRequest = null,
                skillId = workflow.skillId,
                version = versionOverride ?: repository?.getSkillVersion(workflow.skillId) ?: 1,
                diagnostics = diagnostics,
                rejectionReason = "ExecutionRequest NOT CREATED: Sensitive/credential workflow requires user handoff.",
                handoffMessage = "HANDOFF TO PERSON 2: BLOCKED (Sensitive Credential Workflow)"
            )
        }

        if (validation.status != ValidationStatus.VALID || !validation.isStoreable) {
            diagnostics.add("Workflow validation status is ${validation.status}")
            return ExecutionRequestBuildResult(
                status = ExecutionRequestStatus.REJECTED_INVALID_WORKFLOW,
                executionRequest = null,
                skillId = workflow.skillId,
                version = versionOverride ?: repository?.getSkillVersion(workflow.skillId) ?: 1,
                diagnostics = diagnostics,
                rejectionReason = "ExecutionRequest NOT CREATED: Selected workflow '${workflow.skillId}' fails validation (${validation.status})",
                handoffMessage = "HANDOFF TO PERSON 2: CANNOT HANDOFF (Workflow Invalid)"
            )
        }

        // Rule 4: Slot Binding
        val boundSlots = mutableMapOf<String, String>()
        val missingSlots = mutableListOf<String>()

        for (wfSlot in workflow.slots) {
            val cmdSlot = understandingResult.slots.find { it.name.equals(wfSlot.name, ignoreCase = true) }

            val isConstantSlot = (!wfSlot.required) ||
                    wfSlot.provenance.equals("constant", ignoreCase = true) ||
                    (wfSlot.exampleValue != null && !wfSlot.exampleValue.startsWith("\${") && !wfSlot.required)

            if (isConstantSlot) {
                val constantValue = wfSlot.exampleValue ?: ""
                if (cmdSlot != null) {
                    val cmdVal = cmdSlot.typedValue.ifBlank { cmdSlot.rawValue }
                    if (constantValue.isNotBlank() &&
                        !constantValue.equals(cmdVal, ignoreCase = true) &&
                        !constantValue.equals(cmdSlot.rawValue, ignoreCase = true)
                    ) {
                        diagnostics.add("Constant mismatch for slot '${wfSlot.name}': expected '$constantValue', command provided '$cmdVal'")
                        return ExecutionRequestBuildResult(
                            status = ExecutionRequestStatus.REJECTED_CONSTANT_MISMATCH,
                            executionRequest = null,
                            skillId = workflow.skillId,
                            version = versionOverride ?: repository?.getSkillVersion(workflow.skillId) ?: 1,
                            diagnostics = diagnostics,
                            rejectionReason = "ExecutionRequest NOT CREATED: Command value '$cmdVal' conflicts with workflow constant '$constantValue' for slot '${wfSlot.name}'",
                            handoffMessage = "HANDOFF TO PERSON 2: CANNOT HANDOFF (Constant Mismatch)"
                        )
                    }
                    boundSlots[wfSlot.name] = constantValue.ifBlank { cmdVal }
                } else {
                    boundSlots[wfSlot.name] = constantValue
                }
                diagnostics.add("Constant slot '${wfSlot.name}' bound to workflow value '${boundSlots[wfSlot.name]}'")
            } else {
                // Variable slot: MUST bind from NEW command!
                if (cmdSlot != null) {
                    val cmdVal = cmdSlot.typedValue.ifBlank { cmdSlot.rawValue }
                    boundSlots[wfSlot.name] = cmdVal
                    diagnostics.add("Variable slot '${wfSlot.name}' bound to new command value '$cmdVal'")
                } else {
                    // Command did not supply required variable slot!
                    missingSlots.add(wfSlot.name)
                    diagnostics.add("Missing required command value for variable slot '${wfSlot.name}'")
                }
            }
        }

        // Rule 5: Reject if any required variable slots are missing
        if (missingSlots.isNotEmpty()) {
            return ExecutionRequestBuildResult(
                status = ExecutionRequestStatus.REJECTED_MISSING_REQUIRED_SLOTS,
                executionRequest = null,
                skillId = workflow.skillId,
                version = versionOverride ?: repository?.getSkillVersion(workflow.skillId) ?: 1,
                missingSlots = missingSlots,
                diagnostics = diagnostics,
                rejectionReason = "ExecutionRequest NOT CREATED: Missing required slot(s): ${missingSlots.joinToString(", ")}",
                handoffMessage = "HANDOFF TO PERSON 2: CANNOT HANDOFF (Missing Required Slots)"
            )
        }

        val admission = com.chockXlate.teachablevoice.skill.validation.ReplayAdmission.validate(workflow)
        if (!admission.isStoreable) return ExecutionRequestBuildResult(
            status = ExecutionRequestStatus.REJECTED_INVALID_WORKFLOW,
            skillId = workflow.skillId,
            rejectionReason = admission.issues.joinToString(" ") { it.message },
            diagnostics = admission.issues.map { it.message },
            handoffMessage = "Clarification or reteaching is required before replay."
        )

        com.chockXlate.teachablevoice.runtime.slots.SlotBinder.bind(workflow, boundSlots).error?.let { reason ->
            return ExecutionRequestBuildResult(status = ExecutionRequestStatus.REJECTED_INVALID_WORKFLOW,
                skillId = workflow.skillId, rejectionReason = reason, handoffMessage = "Clarify the supplied values before replay.")
        }

        // Rule 6: Construct Valid ExecutionRequest
        val version = versionOverride ?: repository?.getSkillVersion(workflow.skillId)?.coerceAtLeast(1) ?: matchResult.selectedVersion ?: 1
        val execId = overrideExecutionId ?: generateDeterministicExecutionId(workflow.skillId, boundSlots)

        val request = ExecutionRequest(
            schemaVersion = "1.0",
            executionId = execId,
            skillId = workflow.skillId,
            boundSlots = boundSlots,
            version = version,
            provenance = "person_1_matching"
        )

        diagnostics.add("Successfully built ExecutionRequest '${request.executionId}' for skill '${request.skillId}' (v${request.version})")

        return ExecutionRequestBuildResult(
            schemaVersion = "1.0",
            status = ExecutionRequestStatus.READY_FOR_PERSON_2,
            executionRequest = request,
            skillId = workflow.skillId,
            version = version,
            boundSlots = boundSlots,
            missingSlots = emptyList(),
            diagnostics = diagnostics,
            rejectionReason = null,
            handoffMessage = "HANDOFF TO PERSON 2: READY (ExecutionRequest '${request.executionId}' created)"
        )
    }

    private fun generateDeterministicExecutionId(skillId: String, boundSlots: Map<String, String>): String {
        val hash = (skillId + boundSlots.toString()).hashCode()
        val posHash = if (hash < 0) -hash else hash
        return "exec_${skillId.takeLast(10)}_${posHash.toString(16)}"
    }
}
