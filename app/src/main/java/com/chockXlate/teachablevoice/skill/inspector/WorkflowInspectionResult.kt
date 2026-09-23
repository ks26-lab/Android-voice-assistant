package com.chockXlate.teachablevoice.skill.inspector

import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.skill.validation.ValidationStatus
import kotlinx.serialization.Serializable

/**
 * Inspection detail for a single workflow slot parameter or constant context.
 */
@Serializable
data class InspectedSlot(
    val name: String,
    val type: SlotType,
    val role: String, // VARIABLE, CONSTANT, UNKNOWN, CONFLICTING
    val exampleValue: String? = null,
    val referenceForm: String? = null, // e.g. ${item}
    val provenance: String = "",
    val confidence: Double = 1.0
)

/**
 * Inspection detail for a single semantic step in the procedure.
 */
@Serializable
data class InspectedStep(
    val stepId: String,
    val semanticAction: String,
    val targetDescription: String,
    val textSlotReference: String? = null,
    val textLiteral: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val preconditionsDescription: String = "",
    val expectedTransitionDescription: String = "",
    val recoveryPolicyDescription: String = "",
    val provenance: String = "",
    val confidence: Double = 1.0
)

/**
 * Inspection detail for safety boundary and credential guard status.
 */
@Serializable
data class InspectedSafety(
    val requiresExplicitUserConfirmation: Boolean,
    val sensitiveKeywords: List<String> = emptyList(),
    val restrictedActions: List<String> = emptyList(),
    val credentialAutomationBlocked: Boolean = true
)

/**
 * Comprehensive, human-readable inspection container for Phase 9.
 * Read-only diagnostic view of a stored Workflow IR.
 */
@Serializable
data class WorkflowInspectionResult(
    val schemaVersion: String = "1.0",
    val skillId: String,
    val version: Int,
    val intent: String,
    val validationStatus: ValidationStatus,
    val storeStatus: String, // STORED, UNSTORED, REJECTED
    val slots: List<InspectedSlot> = emptyList(),
    val steps: List<InspectedStep> = emptyList(),
    val safety: InspectedSafety,
    val coordinateReplayPass: Boolean,
    val isExecutableByPerson2: Boolean,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val formattedText: String = ""
)
