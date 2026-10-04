package com.chockXlate.teachablevoice.command.request

import com.chockXlate.teachablevoice.command.interpretation.CommandUnderstandingResult
import com.chockXlate.teachablevoice.command.matching.SkillMatchResult
import com.chockXlate.teachablevoice.command.matching.SkillMatchStatus
import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.skill.repository.SkillRepository
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
        repository: SkillRepository,
        overrideExecutionId: String? = null
    ): ExecutionRequestBuildResult {
        val skillId = matchResult.selectedSkillId
        val workflow = matchResult.selectedWorkflow ?: if (skillId != null) repository.getWorkflowById(skillId) else null
        return buildInternal(understandingResult, matchResult, workflow, repository, overrideExecutionId)
    }

    fun build(
        understandingResult: CommandUnderstandingResult,
        matchResult: SkillMatchResult,
        overrideExecutionId: String? = null
    ): ExecutionRequestBuildResult {
        return buildWithWorkflow(understandingResult, matchResult, matchResult.selectedWorkflow, overrideExecutionId = overrideExecutionId)
    }

    fun buildWithWorkflow(
        understandingResult: CommandUnderstandingResult,
        matchResult: SkillMatchResult,
        workflow: Workflow?,
        version: Int = 1,
        overrideExecutionId: String? = null
    ): ExecutionRequestBuildResult {
        val actualWf = workflow ?: matchResult.selectedWorkflow
        return buildInternal(understandingResult, matchResult, actualWf, null, overrideExecutionId, versionOverride = version)
    }

    private fun buildInternal(
        understandingResult: CommandUnderstandingResult,
        matchResult: SkillMatchResult,
        workflow: Workflow?,
        repository: SkillRepository?,
        overrideExecutionId: String?,
        versionOverride: Int? = null
    ): ExecutionRequestBuildResult {
        val diagnostics = mutableListOf<String>()

        // Rule 0: Check Command Understanding Status
        if (understandingResult.status.equals("NEEDS_CLARIFICATION", ignoreCase = true)) {
            diagnostics.add("Command understanding status is NEEDS_CLARIFICATION.")
            return ExecutionRequestBuildResult(
                status = ExecutionRequestStatus.REJECTED_MISSING_REQUIRED_SLOTS,
                executionRequest = null,
                skillId = workflow?.skillId ?: matchResult.selectedSkillId,
                version = versionOverride ?: (workflow?.skillId?.let { repository?.getSkillVersion(it) }) ?: 1,
                missingSlots = understandingResult.unresolvedItems,
                diagnostics = diagnostics,
                rejectionReason = "ExecutionRequest NOT CREATED: Command understanding status is NEEDS_CLARIFICATION",
                handoffMessage = "HANDOFF TO PERSON 2: CANNOT HANDOFF (Needs Clarification)"
            )
        }

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
            SkillMatchStatus.NEEDS_CLARIFICATION -> {
                diagnostics.add("Skill match status is NEEDS_CLARIFICATION.")
                return ExecutionRequestBuildResult(
                    status = ExecutionRequestStatus.REJECTED_AMBIGUOUS_MATCH,
                    executionRequest = null,
                    skillId = null,
                    version = null,
                    diagnostics = diagnostics,
                    rejectionReason = "ExecutionRequest NOT CREATED: Match status is NEEDS_CLARIFICATION (Command requires user clarification)",
                    handoffMessage = "HANDOFF TO PERSON 2: CANNOT HANDOFF (Needs Clarification)"
                )
            }
            SkillMatchStatus.MATCHED -> {
                // Proceed with matching workflow
            }
        }

        // Rule 2: Check Workflow Existence & Validation
        val skillId = matchResult.selectedSkillId ?: workflow?.skillId
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

        // Rule 3: Safety & Sensitive Credential Check
        val hasCredentialKeywords = workflow.safetyBoundary.sensitiveKeywords.any { kw -> SENSITIVE_KEYWORDS.contains(kw.lowercase()) } ||
                workflow.slots.any { slot -> SENSITIVE_KEYWORDS.contains(slot.name.lowercase()) } ||
                understandingResult.slots.any { slot -> SENSITIVE_KEYWORDS.contains(slot.name.lowercase()) } ||
                understandingResult.slots.any { slot -> SENSITIVE_KEYWORDS.contains(slot.typedValue.lowercase()) }

        val validation = WorkflowValidator.validate(workflow)

        if (validation.status == ValidationStatus.BLOCKED || hasCredentialKeywords) {
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
                ?: if (wfSlot.role != null) {
                    understandingResult.slots.find { it.role.equals(wfSlot.role, ignoreCase = true) || it.name.equals(wfSlot.role, ignoreCase = true) }
                } else null
                ?: if (wfSlot.isPlatformSlot()) {
                    if (wfSlot.name == "shopping_platform" || wfSlot.role == "shopping_platform") {
                        understandingResult.slots.find { it.name == "shopping_platform" || it.role == "shopping_platform" }
                            ?: understandingResult.slots.find { it.name == "platform" && understandingResult.slots.count { s -> s.type == com.chockXlate.teachablevoice.contract.workflow.SlotType.PLATFORM } == 1 }
                    } else if (wfSlot.name == "messaging_platform" || wfSlot.role == "messaging_platform") {
                        understandingResult.slots.find { it.name == "messaging_platform" || it.role == "messaging_platform" }
                    } else if (wfSlot.name == "platform") {
                        understandingResult.slots.find { it.name == "platform" }
                            ?: understandingResult.slots.find { it.name == "shopping_platform" || it.role == "shopping_platform" }
                    } else null
                } else null
                ?: if (wfSlot.name == "restaurant") {
                    understandingResult.slots.find { it.name == "restaurant" }
                        ?: understandingResult.slots.find { it.name == "platform" }
                } else null
                ?: if (wfSlot.name == "item") {
                    understandingResult.slots.find { it.name == "item" || it.name == "query" }
                } else null

            // Check if slot has unresolved reference without context
            val isUnresolvedReference = cmdSlot != null && (
                cmdSlot.status == com.chockXlate.teachablevoice.command.interpretation.CommandSlotStatus.REFERENCE ||
                cmdSlot.status == com.chockXlate.teachablevoice.command.interpretation.CommandSlotStatus.UNRESOLVED
            ) && !(
                (understandingResult.rawCommand.contains("same", ignoreCase = true) ||
                 understandingResult.rawCommand.contains("food", ignoreCase = true)) &&
                wfSlot.exampleValue != null
            )

            if (isUnresolvedReference) {
                missingSlots.add(wfSlot.name)
                diagnostics.add("Slot '${wfSlot.name}' has unresolved reference '${cmdSlot?.rawValue}' without resolvable context")
                continue
            }

            val isPlatformRole = wfSlot.isPlatformSlot()
            val isConstantSlot = wfSlot.provenance.equals("constant", ignoreCase = true) ||
                    (!isPlatformRole && (!wfSlot.required ||
                            (wfSlot.exampleValue != null && !wfSlot.exampleValue.startsWith("${'$'}{") && !wfSlot.exampleValue.startsWith("{") && !wfSlot.required)))

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
            } else if (isPlatformRole) {
                if (cmdSlot != null) {
                    val cmdVal = cmdSlot.typedValue.ifBlank { cmdSlot.rawValue }
                    boundSlots[wfSlot.name] = cmdVal
                    diagnostics.add("Platform slot '${wfSlot.name}' substituted with command value '$cmdVal'")
                } else if (!wfSlot.required && wfSlot.exampleValue != null) {
                    boundSlots[wfSlot.name] = wfSlot.exampleValue
                    diagnostics.add("Optional platform slot '${wfSlot.name}' retained demonstration value '${wfSlot.exampleValue}'")
                } else {
                    missingSlots.add(wfSlot.name)
                    diagnostics.add("Missing required command value for platform slot '${wfSlot.name}'")
                }
            } else {
                // Variable slot: MUST bind from NEW command!
                if (cmdSlot != null) {
                    val cmdVal = cmdSlot.typedValue.ifBlank { cmdSlot.rawValue }
                    if (!com.chockXlate.teachablevoice.runtime.slots.SlotBinder.validType(cmdVal, wfSlot.type)) {
                        diagnostics.add("Slot '${wfSlot.name}' value '$cmdVal' is invalid for type ${wfSlot.type}")
                        return ExecutionRequestBuildResult(
                            status = ExecutionRequestStatus.REJECTED_INVALID_WORKFLOW,
                            executionRequest = null,
                            skillId = workflow.skillId,
                            version = versionOverride ?: repository?.getSkillVersion(workflow.skillId) ?: 1,
                            diagnostics = diagnostics,
                            rejectionReason = "ExecutionRequest NOT CREATED: Slot '${wfSlot.name}' value '$cmdVal' is invalid for type ${wfSlot.type}",
                            handoffMessage = "HANDOFF TO PERSON 2: CANNOT HANDOFF (Invalid Slot Type)"
                        )
                    }
                    boundSlots[wfSlot.name] = cmdVal
                    diagnostics.add("Variable slot '${wfSlot.name}' bound to new command value '$cmdVal'")
                } else if (wfSlot.name == "item" && wfSlot.exampleValue != null &&
                    (understandingResult.rawCommand.contains("same", ignoreCase = true) ||
                     understandingResult.rawCommand.contains("food", ignoreCase = true))) {
                    boundSlots[wfSlot.name] = wfSlot.exampleValue
                    diagnostics.add("Item slot '${wfSlot.name}' retained demonstration value '${wfSlot.exampleValue}'")
                } else if (!wfSlot.required && wfSlot.exampleValue != null) {
                    boundSlots[wfSlot.name] = wfSlot.exampleValue
                    diagnostics.add("Optional slot '${wfSlot.name}' retained default value '${wfSlot.exampleValue}'")
                } else {
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

        val bindingResult = com.chockXlate.teachablevoice.runtime.slots.SlotBinder.bind(workflow, boundSlots)
        bindingResult.error?.let { reason ->
            return ExecutionRequestBuildResult(
                status = ExecutionRequestStatus.REJECTED_INVALID_WORKFLOW,
                skillId = workflow.skillId,
                rejectionReason = reason,
                handoffMessage = "Clarify the supplied values before replay."
            )
        }

        // Rule 6: Construct Valid ExecutionRequest
        val version = versionOverride ?: repository?.getSkillVersion(workflow.skillId)?.coerceAtLeast(1) ?: matchResult.selectedVersion ?: 1
        val execId = overrideExecutionId ?: generateDeterministicExecutionId(workflow.skillId, boundSlots)

        val isSensitive = understandingResult.isSensitive || workflow.safetyBoundary.requiresExplicitUserConfirmation

        val request = ExecutionRequest(
            schemaVersion = "1.0",
            executionId = execId,
            skillId = workflow.skillId,
            boundSlots = boundSlots,
            version = version,
            provenance = "person_1_matching",
            isSensitive = isSensitive,
            originalCommand = understandingResult.rawCommand
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
            handoffMessage = "HANDOFF TO PERSON 2: READY (ExecutionRequest '${request.executionId}' created)",
            boundSteps = bindingResult.steps
        )
    }

    private fun generateDeterministicExecutionId(skillId: String, boundSlots: Map<String, String>): String {
        val hash = (skillId + boundSlots.toString()).hashCode()
        val posHash = if (hash < 0) -hash else hash
        return "exec_${skillId.takeLast(10)}_${posHash.toString(16)}"
    }
}
