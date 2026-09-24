package com.chockXlate.teachablevoice.runtime.slots

import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.runtime.ui.RuntimeAction

data class BoundStep(
    val source: WorkflowStep,
    val action: RuntimeAction,
    val selector: SemanticSelector,
    val preconditions: Preconditions,
    val transition: ExpectedTransition,
    val inputText: String? = null,
    val scrollDirection: String? = null,
    val stateEvidence: Map<String, String> = emptyMap()
)

data class BindingResult(val steps: List<BoundStep> = emptyList(), val error: String? = null)

/** The only compatibility parser for bare names, ${'$'}{name}, and {name}. */
object SlotBinder {
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    fun reference(value: String): String? {
        val trimmed = value.trim()
        val name = when {
            trimmed.startsWith("\${") && trimmed.endsWith("}") -> trimmed.substring(2, trimmed.length - 1)
            trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed.substring(1, trimmed.length - 1)
            else -> trimmed
        }
        return name.takeIf { identifier.matches(it) }
    }

    fun bind(workflow: Workflow, supplied: Map<String, String>): BindingResult = try {
        val declared = workflow.slots.associateBy { it.name }
        require(supplied.keys.all { it in declared }) { "Request contains undeclared slots." }
        val values = mutableMapOf<String, String>()
        for (slot in workflow.slots) {
            val constant = !slot.required || slot.provenance.equals("constant", true)
            val value = if (constant) {
                val literal = slot.exampleValue
                require(literal != null && !literal.startsWith("\${") && !literal.startsWith("{")) {
                    "A workflow constant has no literal value."
                }
                require(supplied[slot.name] == null || supplied[slot.name].equals(literal, true)) {
                    "A supplied value conflicts with a workflow constant."
                }
                literal
            } else supplied[slot.name]
            require(value != null && value.isNotBlank()) { "A required slot value is missing or blank." }
            require(validType(value, slot.type)) { "A slot value does not satisfy its declared type." }
            values[slot.name] = value
        }
        fun resolve(ref: String): String {
            val name = reference(ref) ?: error("Unsupported slot reference syntax.")
            return values[name] ?: error("A referenced slot has no binding.")
        }
        fun selector(sel: SemanticSelector, inputTarget: Boolean = false): SemanticSelector {
            require(sel.schemaVersion == "1.0") { "Unsupported selector schema." }
            if (sel.textSlot == null) return sel
            val value = resolve(sel.textSlot)
            return sel.copy(text = if (inputTarget) null else value, textSlot = null)
        }
        val bound = workflow.steps.map { step ->
            val action = RuntimeAction.parse(step.semanticAction) ?: error("Unsupported semantic action.")
            val supportedKeys = when (action) {
                RuntimeAction.INPUT_TEXT -> setOf("input_parameter", "input_literal", "text")
                RuntimeAction.SCROLL -> setOf("direction")
                else -> emptySet()
            }
            require(step.parameters.keys.all { it in supportedKeys }) { "Unsupported action parameters." }
            val textValues = mutableListOf<String>()
            if (action == RuntimeAction.INPUT_TEXT) {
                step.semanticSelector.textSlot?.let { textValues.add(resolve(it)) }
                step.parameters["input_parameter"]?.let { textValues.add(resolve(it)) }
                step.parameters["input_literal"]?.let { textValues.add(it) }
                step.parameters["text"]?.let {
                    textValues.add(if (it.startsWith("{") || it.startsWith("\${")) resolve(it) else it)
                }
                require(textValues.isNotEmpty()) { "INPUT_TEXT has no explicit input value." }
                require(textValues.distinct().size == 1) { "Conflicting text input bindings." }
            }
            val direction = step.parameters["direction"]?.lowercase()
            require(action != RuntimeAction.SCROLL || direction in setOf("forward", "backward")) {
                "SCROLL requires an explicit forward or backward direction."
            }
            val inputTarget = action == RuntimeAction.INPUT_TEXT
            fun inputIdentity(sel: SemanticSelector): SemanticSelector {
                val bound = selector(sel, inputTarget)
                // A learned input value is not the identity of an empty field on the next run.
                return if (inputTarget && bound.text == textValues.firstOrNull() &&
                    (!bound.resourceId.isNullOrBlank() || !bound.contentDescription.isNullOrBlank())) bound.copy(text = null)
                else bound
            }
            BoundStep(
                source = step,
                action = action,
                selector = inputIdentity(step.semanticSelector),
                preconditions = step.preconditions.copy(requiredElementPresent = step.preconditions.requiredElementPresent?.let {
                    if (it == step.semanticSelector) inputIdentity(it) else selector(it)
                }),
                transition = step.expectedTransition.copy(
                    expectedElementAppeared = step.expectedTransition.expectedElementAppeared?.let { selector(it) },
                    expectedElementDisappeared = step.expectedTransition.expectedElementDisappeared?.let { selector(it) }
                ),
                inputText = textValues.firstOrNull(),
                scrollDirection = direction
            )
        }
        BindingResult(steps = bound)
    } catch (e: IllegalArgumentException) {
        BindingResult(error = e.message ?: "Slot binding failed.")
    } catch (e: IllegalStateException) {
        BindingResult(error = e.message ?: "Slot binding failed.")
    }

    private fun validType(value: String, type: SlotType): Boolean = when (type) {
        SlotType.INTEGER -> value.toLongOrNull() != null
        SlotType.DECIMAL -> value.toDoubleOrNull()?.isFinite() == true
        SlotType.BOOLEAN -> value.lowercase() in setOf("true", "false")
        else -> value.isNotBlank() // ENUM has no allowed-values field in the frozen schema.
    }
}
