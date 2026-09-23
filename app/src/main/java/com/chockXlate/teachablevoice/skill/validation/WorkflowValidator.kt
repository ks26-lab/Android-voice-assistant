package com.chockXlate.teachablevoice.skill.validation

import com.chockXlate.teachablevoice.contract.workflow.RecoveryStrategy
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import kotlinx.serialization.json.Json

/**
 * Deterministic validator for verifying Workflow IR structural integrity,
 * semantic variable references, coordinate-free execution, and safety boundaries.
 */
object WorkflowValidator {

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val SENSITIVE_KEYWORDS = listOf(
        "otp", "pin", "cvv", "card", "password", "passcode", "secret",
        "ssn", "cvv2", "credit_card", "debit_card", "payment_confirm"
    )

    fun validate(workflow: Workflow?): WorkflowValidationResult {
        if (workflow == null) {
            return WorkflowValidationResult(
                workflowId = null,
                status = ValidationStatus.INVALID,
                issues = listOf(
                    WorkflowValidationIssue(
                        severity = ValidationSeverity.ERROR,
                        category = ValidationCategory.STRUCTURE,
                        message = "Workflow instance is null."
                    )
                ),
                isStoreable = false
            )
        }

        val issues = mutableListOf<WorkflowValidationIssue>()
        val warnings = mutableListOf<String>()

        // 1. Structural Validation
        if (workflow.schemaVersion.isBlank()) {
            issues.add(
                WorkflowValidationIssue(
                    severity = ValidationSeverity.ERROR,
                    category = ValidationCategory.STRUCTURE,
                    message = "Schema version is missing or blank."
                )
            )
        } else if (workflow.schemaVersion != "1.0") {
            issues.add(
                WorkflowValidationIssue(
                    severity = ValidationSeverity.ERROR,
                    category = ValidationCategory.STRUCTURE,
                    message = "Unsupported schema version '${workflow.schemaVersion}'. Expected '1.0'."
                )
            )
        }

        if (workflow.skillId.isBlank()) {
            issues.add(
                WorkflowValidationIssue(
                    severity = ValidationSeverity.ERROR,
                    category = ValidationCategory.STRUCTURE,
                    message = "Skill ID is missing or blank."
                )
            )
        }

        if (workflow.intent.isBlank() || workflow.intent.equals("unknown", ignoreCase = true)) {
            issues.add(
                WorkflowValidationIssue(
                    severity = ValidationSeverity.ERROR,
                    category = ValidationCategory.STRUCTURE,
                    message = "Canonical intent is missing, blank, or 'unknown'."
                )
            )
        }

        if (workflow.steps.isEmpty()) {
            issues.add(
                WorkflowValidationIssue(
                    severity = ValidationSeverity.ERROR,
                    category = ValidationCategory.STRUCTURE,
                    message = "Workflow contains an empty semantic procedure (0 steps)."
                )
            )
        }

        // Validate unique step IDs
        val seenStepIds = mutableSetOf<String>()
        for (step in workflow.steps) {
            if (step.stepId.isBlank()) {
                issues.add(
                    WorkflowValidationIssue(
                        severity = ValidationSeverity.ERROR,
                        category = ValidationCategory.STRUCTURE,
                        message = "Step contains blank or missing stepId."
                    )
                )
            } else if (!seenStepIds.add(step.stepId)) {
                issues.add(
                    WorkflowValidationIssue(
                        severity = ValidationSeverity.ERROR,
                        category = ValidationCategory.STRUCTURE,
                        message = "Duplicate step ID '${step.stepId}' detected.",
                        affectedStepId = step.stepId
                    )
                )
            }

            if (step.semanticAction.isBlank()) {
                issues.add(
                    WorkflowValidationIssue(
                        severity = ValidationSeverity.ERROR,
                        category = ValidationCategory.STRUCTURE,
                        message = "Step '${step.stepId}' contains blank semantic action type.",
                        affectedStepId = step.stepId
                    )
                )
            }

            val sel = step.semanticSelector
            if (sel.role == null && sel.resourceId == null && sel.text == null && sel.textSlot == null) {
                issues.add(
                    WorkflowValidationIssue(
                        severity = ValidationSeverity.ERROR,
                        category = ValidationCategory.STRUCTURE,
                        message = "Step '${step.stepId}' contains an invalid/empty SemanticSelector.",
                        affectedStepId = step.stepId
                    )
                )
            }
        }

        // 2. Semantic Validation & Variable Reference Checks
        val declaredSlotNames = workflow.slots.map { it.name }.toSet()
        val referencedSlotsInSteps = mutableSetOf<String>()

        for (step in workflow.steps) {
            val sel = step.semanticSelector
            val slotRef = sel.textSlot ?: step.parameters["input_parameter"]

            if (slotRef != null) {
                // Extract clean slot name (e.g. "${item}" -> "item")
                val cleanName = slotRef.removePrefix("\${").removeSuffix("}")
                referencedSlotsInSteps.add(cleanName)

                if (cleanName !in declaredSlotNames) {
                    issues.add(
                        WorkflowValidationIssue(
                            severity = ValidationSeverity.ERROR,
                            category = ValidationCategory.SEMANTICS,
                            message = "Step '${step.stepId}' references undeclared variable slot '\${$cleanName}'.",
                            affectedStepId = step.stepId,
                            affectedSlotName = cleanName
                        )
                    )
                }

                if (sel.text != null && sel.text.isNotBlank()) {
                    issues.add(
                        WorkflowValidationIssue(
                            severity = ValidationSeverity.ERROR,
                            category = ValidationCategory.SEMANTICS,
                            message = "Step '${step.stepId}' simultaneously defines literal text '${sel.text}' and variable slot reference '\${$cleanName}'.",
                            affectedStepId = step.stepId,
                            affectedSlotName = cleanName
                        )
                    )
                }
            }
        }

        // 3. Coordinate Replay Check
        val jsonString = try {
            jsonFormatter.encodeToString(Workflow.serializer(), workflow)
        } catch (e: Exception) {
            ""
        }

        val containsCoordinates = jsonString.contains("\"x\":") ||
                jsonString.contains("\"y\":") ||
                jsonString.contains("boundsInScreen") ||
                jsonString.contains("tapPosition")

        if (containsCoordinates) {
            issues.add(
                WorkflowValidationIssue(
                    severity = ValidationSeverity.CRITICAL_SECURITY_BLOCK,
                    category = ValidationCategory.COORDINATE_REPLAY,
                    message = "Coordinate-based execution truth detected in Workflow IR. Execution truth must be 100% semantic."
                )
            )
        }

        // 4. Safety Boundary Check
        val sb = workflow.safetyBoundary
        val fullWorkflowText = jsonString.lowercase()

        for (step in workflow.steps) {
            val sel = step.semanticSelector
            val targetDesc = (sel.resourceId ?: "") + " " + (sel.text ?: "") + " " + step.parameters.values.joinToString(" ")
            val lowerDesc = targetDesc.lowercase()

            val touchesSensitiveKeyword = SENSITIVE_KEYWORDS.any { kw -> lowerDesc.contains(kw) }

            if (touchesSensitiveKeyword) {
                if (!sb.requiresExplicitUserConfirmation) {
                    issues.add(
                        WorkflowValidationIssue(
                            severity = ValidationSeverity.CRITICAL_SECURITY_BLOCK,
                            category = ValidationCategory.SAFETY,
                            message = "Sensitive keyword detected in step '${step.stepId}', but SafetyBoundary.requiresExplicitUserConfirmation is false.",
                            affectedStepId = step.stepId
                        )
                    )
                }

                if (step.recoveryPolicy.strategy != RecoveryStrategy.HANDOFF_TO_USER) {
                    issues.add(
                        WorkflowValidationIssue(
                            severity = ValidationSeverity.CRITICAL_SECURITY_BLOCK,
                            category = ValidationCategory.SAFETY,
                            message = "Sensitive step '${step.stepId}' must use RecoveryStrategy.HANDOFF_TO_USER, but found ${step.recoveryPolicy.strategy}.",
                            affectedStepId = step.stepId
                        )
                    )
                }

                if (step.recoveryPolicy.maxRetries != 0) {
                    issues.add(
                        WorkflowValidationIssue(
                            severity = ValidationSeverity.CRITICAL_SECURITY_BLOCK,
                            category = ValidationCategory.SAFETY,
                            message = "Sensitive step '${step.stepId}' permits automatic retries (maxRetries = ${step.recoveryPolicy.maxRetries}). Must be 0.",
                            affectedStepId = step.stepId
                        )
                    )
                }

                // Check raw secret values in step parameters
                if (step.parameters.values.any { v -> v.isNotBlank() && !v.startsWith("\${") && SENSITIVE_KEYWORDS.any { kw -> step.stepId.lowercase().contains(kw) || lowerDesc.contains(kw) } }) {
                    // Check if explicit literal credential value is stored
                    val hasLiteralSecret = step.parameters["input_literal"] != null
                    if (hasLiteralSecret) {
                        issues.add(
                            WorkflowValidationIssue(
                                severity = ValidationSeverity.CRITICAL_SECURITY_BLOCK,
                                category = ValidationCategory.SAFETY,
                                message = "Sensitive step '${step.stepId}' stores an executable raw secret value in parameters.",
                                affectedStepId = step.stepId
                            )
                        )
                    }
                }
            }
        }

        // Sort issues deterministically
        val sortedIssues = issues.sortedWith(
            compareBy<WorkflowValidationIssue> { it.severity.ordinal }
                .thenBy { it.affectedStepId ?: "" }
                .thenBy { it.message }
        )

        val status = when {
            sortedIssues.any { it.severity == ValidationSeverity.CRITICAL_SECURITY_BLOCK } -> ValidationStatus.BLOCKED
            sortedIssues.any { it.severity == ValidationSeverity.ERROR } -> ValidationStatus.INVALID
            else -> ValidationStatus.VALID
        }

        return WorkflowValidationResult(
            schemaVersion = "1.0",
            workflowId = workflow.skillId,
            status = status,
            issues = sortedIssues,
            warnings = warnings,
            isStoreable = (status == ValidationStatus.VALID)
        )
    }
}
