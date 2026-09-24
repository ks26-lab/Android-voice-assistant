package com.chockXlate.teachablevoice.skill.inspector

import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.skill.repository.SkillRepository
import com.chockXlate.teachablevoice.skill.validation.ValidationCategory
import com.chockXlate.teachablevoice.skill.validation.ValidationStatus
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator
import kotlinx.serialization.json.Json

/**
 * Read-only, deterministic inspector for viewing, debugging, and auditing stored Workflow IRs.
 * Has zero side effects on workflows, slots, steps, or repository state.
 */
object WorkflowInspectorImpl : WorkflowInspector {

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val SENSITIVE_KEYWORDS = listOf(
        "otp", "pin", "cvv", "card", "password", "passcode", "secret",
        "ssn", "cvv2", "credit_card", "debit_card", "payment_confirm"
    )

    override fun inspectWorkflow(workflow: Workflow): String {
        val result = inspect(workflow)
        return result.formattedText
    }

    override fun validateWorkflowStructure(workflow: Workflow): List<String> {
        val validation = WorkflowValidator.validate(workflow)
        return validation.issues.map { "[${it.severity}] ${it.category}: ${it.message}" }
    }

    fun inspect(workflow: Workflow, repository: SkillRepository? = null): WorkflowInspectionResult {
        val validation = WorkflowValidator.validate(workflow)

        // Store status
        val isStored = repository?.getWorkflowById(workflow.skillId) != null
        val skillVersion = if (isStored && repository is com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository) repository.getSkillVersion(workflow.skillId) else 1
        val storeStatus = when {
            isStored -> "STORED"
            validation.status == ValidationStatus.BLOCKED || validation.status == ValidationStatus.INVALID -> "REJECTED"
            else -> "UNSTORED"
        }

        // Coordinate Replay Check
        val jsonString = try {
            jsonFormatter.encodeToString(Workflow.serializer(), workflow)
        } catch (e: Exception) {
            ""
        }

        val containsCoordinates = jsonString.contains("\"x\":") ||
                jsonString.contains("\"y\":") ||
                jsonString.contains("boundsInScreen") ||
                jsonString.contains("tapPosition")

        val coordinateReplayPass = !containsCoordinates && validation.issues.none { it.category == ValidationCategory.COORDINATE_REPLAY }

        // Inspect Slots
        val inspectedSlots = workflow.slots.sortedBy { it.name }.map { slot ->
            val slotName = slot.name
            val isSensitive = SENSITIVE_KEYWORDS.any { kw -> slotName.lowercase().contains(kw) }

            val role = if (slot.required) "VARIABLE" else "CONSTANT"
            val refForm = if (slot.required) "\${$slotName}" else null

            val exVal = when {
                isSensitive -> "[REDACTED_SENSITIVE]"
                slot.exampleValue != null && SENSITIVE_KEYWORDS.any { kw -> slot.exampleValue.lowercase().contains(kw) } -> "[REDACTED_SENSITIVE]"
                else -> slot.exampleValue
            }

            InspectedSlot(
                name = slotName,
                type = slot.type,
                role = role,
                exampleValue = exVal,
                referenceForm = refForm,
                provenance = slot.provenance,
                confidence = slot.confidence
            )
        }

        // Inspect Procedure Steps
        val inspectedSteps = workflow.steps.map { step ->
            val sel = step.semanticSelector
            val roleStr = sel.role ?: "any"
            val resIdStr = sel.resourceId ?: "none"
            val textStr = sel.text ?: "none"

            val targetDesc = "[Role: $roleStr, ResId: $resIdStr, Text: '$textStr']"

            // Sanitize parameters for inspection output
            val sanitizedParams = step.parameters.mapValues { (k, v) ->
                if (SENSITIVE_KEYWORDS.any { kw -> k.lowercase().contains(kw) || v.lowercase().contains(kw) }) {
                    "[REDACTED_SENSITIVE]"
                } else {
                    v
                }
            }

            val precDesc = if (step.preconditions.requiredPackage != null) {
                "Package: ${step.preconditions.requiredPackage}, FromState: ${step.preconditions.fromState ?: "initial"}"
            } else {
                "FromState: ${step.preconditions.fromState ?: "initial"}"
            }

            val transDesc = if (step.expectedTransition.toState != null) {
                "ToState: ${step.expectedTransition.toState}, Type: ${step.expectedTransition.transitionType}"
            } else {
                "SEMANTIC_ACTION"
            }

            val recDesc = "Strategy: ${step.recoveryPolicy.strategy}, MaxRetries: ${step.recoveryPolicy.maxRetries}"

            InspectedStep(
                stepId = step.stepId,
                semanticAction = step.semanticAction,
                targetDescription = targetDesc,
                textSlotReference = sel.textSlot,
                textLiteral = if (SENSITIVE_KEYWORDS.any { kw -> textStr.lowercase().contains(kw) }) "[REDACTED_SENSITIVE]" else sel.text,
                parameters = sanitizedParams,
                preconditionsDescription = precDesc,
                expectedTransitionDescription = transDesc,
                recoveryPolicyDescription = recDesc,
                provenance = step.provenance,
                confidence = step.confidence
            )
        }

        // Inspect Safety Boundary
        val sb = workflow.safetyBoundary
        val inspectedSafety = InspectedSafety(
            requiresExplicitUserConfirmation = sb.requiresExplicitUserConfirmation,
            sensitiveKeywords = sb.sensitiveKeywords.sorted(),
            restrictedActions = sb.restrictedActions.sorted(),
            credentialAutomationBlocked = true
        )

        // Errors & Warnings
        val errors = validation.issues.filter { it.severity != com.chockXlate.teachablevoice.skill.validation.ValidationSeverity.WARNING }
            .map { "[${it.severity}] ${it.category}: ${it.message}" }
        val warnings = validation.warnings + validation.issues.filter { it.severity == com.chockXlate.teachablevoice.skill.validation.ValidationSeverity.WARNING }
            .map { it.message }

        val isExecutable = validation.status == ValidationStatus.VALID && coordinateReplayPass

        val formattedText = formatHumanReadable(
            skillId = workflow.skillId,
            version = skillVersion,
            validationStatus = validation.status,
            storeStatus = storeStatus,
            intent = workflow.intent,
            slots = inspectedSlots,
            steps = inspectedSteps,
            safety = inspectedSafety,
            coordinatePass = coordinateReplayPass,
            isExecutable = isExecutable,
            errors = errors,
            warnings = warnings
        )

        return WorkflowInspectionResult(
            schemaVersion = "1.0",
            skillId = workflow.skillId,
            version = skillVersion,
            intent = workflow.intent,
            validationStatus = validation.status,
            storeStatus = storeStatus,
            slots = inspectedSlots,
            steps = inspectedSteps,
            safety = inspectedSafety,
            coordinateReplayPass = coordinateReplayPass,
            isExecutableByPerson2 = isExecutable,
            warnings = warnings,
            errors = errors,
            formattedText = formattedText
        )
    }

    private fun formatHumanReadable(
        skillId: String,
        version: Int,
        validationStatus: ValidationStatus,
        storeStatus: String,
        intent: String,
        slots: List<InspectedSlot>,
        steps: List<InspectedStep>,
        safety: InspectedSafety,
        coordinatePass: Boolean,
        isExecutable: Boolean,
        errors: List<String>,
        warnings: List<String>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== WORKFLOW INSPECTOR ===")
        sb.appendLine()
        sb.appendLine("Skill:")
        sb.appendLine("  ID: ${if (skillId.isNotBlank()) skillId else "N/A"}")
        sb.appendLine("  Version: $version")
        sb.appendLine("  Validation: $validationStatus")
        sb.appendLine("  Stored: $storeStatus")
        sb.appendLine()
        sb.appendLine("Intent:")
        sb.appendLine("  ${if (intent.isNotBlank()) intent else "N/A"}")
        sb.appendLine()
        sb.appendLine("Slots:")
        if (slots.isEmpty()) {
            sb.appendLine("  (No slots declared)")
        } else {
            slots.forEach { slot ->
                sb.appendLine("  ${slot.name}")
                sb.appendLine("    Type: ${slot.type}")
                sb.appendLine("    Role: ${slot.role}")
                if (slot.role == "VARIABLE") {
                    sb.appendLine("    Reference: ${slot.referenceForm}")
                    sb.appendLine("    Example: ${slot.exampleValue ?: "none"}")
                } else {
                    sb.appendLine("    Value: ${slot.exampleValue ?: "none"}")
                }
            }
        }
        sb.appendLine()
        sb.appendLine("Procedure:")
        if (steps.isEmpty()) {
            sb.appendLine("  (No semantic steps)")
        } else {
            steps.forEachIndexed { idx, step ->
                sb.appendLine("  ${idx + 1}. ${step.semanticAction}")
                sb.appendLine("     Target: ${step.targetDescription}")
                if (step.textSlotReference != null) {
                    sb.appendLine("     Parameter: ${step.textSlotReference}")
                } else if (step.textLiteral != null) {
                    sb.appendLine("     Parameter: \"${step.textLiteral}\"")
                }
            }
        }
        sb.appendLine()
        sb.appendLine("Safety:")
        sb.appendLine("  Explicit confirmation required: ${if (safety.requiresExplicitUserConfirmation) "YES" else "NO"}")
        sb.appendLine("  Restricted actions: ${if (safety.restrictedActions.isEmpty()) "NONE" else safety.restrictedActions.joinToString(", ")}")
        sb.appendLine("  Credential automation: BLOCKED")
        sb.appendLine()
        sb.appendLine("Coordinate Replay:")
        sb.appendLine("  Execution truth contains coordinates: ${if (coordinatePass) "NO (PASS)" else "YES (BLOCKED)"}")
        sb.appendLine()
        sb.appendLine("Status:")
        sb.appendLine("  ${if (isExecutable) "READY FOR PERSON 2 EXECUTION CONTRACT" else "NOT READY FOR EXECUTION (${validationStatus})"})")
        sb.appendLine("==========================")

        if (errors.isNotEmpty()) {
            sb.appendLine("INSPECTION ERRORS:")
            errors.forEach { err -> sb.appendLine("  - $err") }
        }

        if (warnings.isNotEmpty()) {
            sb.appendLine("INSPECTION WARNINGS:")
            warnings.forEach { w -> sb.appendLine("  - $w") }
        }

        return sb.toString()
    }
}
