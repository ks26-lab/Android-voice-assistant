package com.chockXlate.teachablevoice.skill.validation

import com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.runtime.slots.SlotBinder
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.verification.TransitionVerifier

/** Pure Kotlin admission for a skill advertised as replayable through the command UI.
 * Structural validation remains available for inspecting non-executable draft IR.
 */
object ReplayAdmission {
    fun problems(workflow: Workflow): List<String> {
        val errors = mutableListOf<String>()
        if (workflow.appContext.isBlank() || workflow.appContext == "unknown") errors += "Teach an interaction in the target app first."
        com.chockXlate.teachablevoice.safety.RuntimeSafetyPolicy().admission(workflow)?.let { errors += it }
        val vocabulary = SemanticCommandPolicy.bindableSlots(workflow.intent)
        if (vocabulary.isEmpty())
            errors += "This intent has no supported replay slot grammar. Clarification or a supported ordering/search command is required."
        for (slot in workflow.slots.filter { it.required }) {
            if (slot.name !in vocabulary) errors += "Required variable '${slot.name}' cannot be supplied by this command grammar. Clarify or reteach with a supported slot."
            val used = workflow.steps.any { step ->
                listOfNotNull(step.semanticSelector.textSlot, step.parameters["input_parameter"], step.parameters["repeat_slot"], step.parameters["text"]?.takeIf { it.startsWith("{") || it.startsWith("\${") })
                    .any { SlotBinder.reference(it) == slot.name }
            }
            if (!used) errors += "Variable '${slot.name}' has no executable action binding. Reteach its UI effect."
        }
        for (step in workflow.steps) {
            val action = RuntimeAction.parse(step.semanticAction)
            if (action == null) { errors += "${step.stepId}: '${step.semanticAction}' is unsupported; record real supported UI actions."; continue }
            val s = step.semanticSelector
            val stable = !s.resourceId.isNullOrBlank() || !s.contentDescription.isNullOrBlank()
            if (!stable && (action == RuntimeAction.INPUT_TEXT || (s.text.isNullOrBlank() && s.textSlot == null)))
                errors += "${step.stepId}: target needs a stable resource ID/description or an exact click label; role alone cannot execute."
            if (action == RuntimeAction.INPUT_TEXT && s.role != null && s.role !in setOf("EditText", "AutoCompleteTextView", "MultiAutoCompleteTextView"))
                errors += "${step.stepId}: text input requires an editable target; reteach the input field."
            if (step.preconditions.requiredActivity != null || step.preconditions.customConditions.isNotEmpty())
                errors += "${step.stepId}: activity/custom preconditions cannot be observed by this runtime."
            TransitionVerifier(SemanticMatcher()).unsupported(step.expectedTransition)?.let { errors += "${step.stepId}: $it" }
        }
        // Probe binding with type-correct placeholders, never demonstration values for variables.
        val probe = workflow.slots.filter { it.required }.associate { it.name to when (it.type) {
            SlotType.INTEGER -> if (it.name == "quantity") "20" else "2"; SlotType.DECIMAL -> "2.0"; SlotType.BOOLEAN -> "true"; else -> "Replay value"
        } }
        SlotBinder.bind(workflow, probe).error?.let { errors += it }
        return errors.distinct()
    }

    fun validate(workflow: Workflow?): WorkflowValidationResult {
        val structural = WorkflowValidator.validate(workflow)
        if (workflow == null || !structural.isStoreable) return structural
        val issues = problems(workflow).map { WorkflowValidationIssue(ValidationSeverity.ERROR, ValidationCategory.SEMANTICS, it) }
        return if (issues.isEmpty()) structural else structural.copy(status = ValidationStatus.INVALID,
            issues = structural.issues + issues, isStoreable = false)
    }
}
