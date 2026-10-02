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
    val stateEvidence: Map<String, String> = emptyMap(),
    val quantityBefore: SemanticSelector? = null
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
            require(slot.name != "quantity" || (slot.type == SlotType.INTEGER && value.toIntOrNull() in 1..20)) {
                "Quantity must be an integer from 1 to 20."
            }
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
        val bound = workflow.steps.flatMap { step ->
            val action = RuntimeAction.parse(step.semanticAction) ?: error("Unsupported semantic action.")
            val supportedKeys = when (action) {
                RuntimeAction.INPUT_TEXT -> setOf("input_parameter", "input_literal", "text")
                RuntimeAction.SCROLL -> setOf("direction")
                RuntimeAction.CLICK -> setOf("repeat_slot", "quantity_start", "quantity_selector")
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
                return if (inputTarget) bound.copy(text = null) else bound
            }
            val single = BoundStep(
                source = step,
                action = action,
                selector = inputIdentity(step.semanticSelector),
                preconditions = step.preconditions.copy(requiredElementPresent = step.preconditions.requiredElementPresent?.let {
                    if (inputTarget || it == step.semanticSelector) inputIdentity(it) else selector(it)
                }),
                transition = step.expectedTransition.copy(
                    expectedElementAppeared = step.expectedTransition.expectedElementAppeared?.let { selector(it) },
                    expectedElementDisappeared = step.expectedTransition.expectedElementDisappeared?.let { selector(it) },
                    expectedEvidence = step.expectedTransition.expectedEvidence.map { req ->
                        req.copy(
                            selector = req.selector?.let { selector(it) },
                            expectedValue = req.expectedValue?.let { v ->
                                if (v.startsWith("\${") || v.startsWith("{")) resolve(v) else v
                            },
                            previousValue = req.previousValue?.let { v ->
                                if (v.startsWith("\${") || v.startsWith("{")) resolve(v) else v
                            }
                        )
                    }
                ),
                inputText = textValues.firstOrNull(),
                scrollDirection = direction
            )
            val repeat = step.parameters["repeat_slot"]
            if (repeat == null) {
                require(step.parameters.keys.none { it in setOf("quantity_start", "quantity_selector") }) { "Incomplete quantity operation." }
                listOf(single)
            } else {
                require(action == RuntimeAction.CLICK) { "Quantity adjustment requires CLICK/TAP." }
                val name = reference(repeat) ?: error("Invalid quantity slot.")
                require(declared[name]?.type == SlotType.INTEGER) { "Quantity operation requires an INTEGER slot." }
                val desired = resolve(repeat).toIntOrNull() ?: error("Quantity must be an integer.")
                val start = step.parameters["quantity_start"]?.toIntOrNull() ?: error("Quantity baseline is missing.")
                require(desired in 1..20 && start in 0..19 && desired > start) {
                    "Quantity must be 1..20 and exceed the demonstrated starting count; restore the initial count or reteach."
                }
                val encodedCounter = step.parameters["quantity_selector"] ?: error("Quantity counter evidence is missing.")
                val counter = try {
                    kotlinx.serialization.json.Json.decodeFromString(SemanticSelector.serializer(), encodedCounter)
                } catch (_: IllegalArgumentException) {
                    error("Quantity counter selector is malformed; reteach the control.")
                }
                require(counter.schemaVersion == "1.0" && counter.textSlot == null &&
                    (!counter.resourceId.isNullOrBlank() || !counter.contentDescription.isNullOrBlank())) {
                    "Quantity counter requires stable semantic identity."
                }
                (start until desired).map { current ->
                    val index = current - start
                    val previous = if (index == 0) step.preconditions.fromState else "${step.stepId}_quantity_$current"
                    val next = if (current + 1 == desired) step.expectedTransition.toState else "${step.stepId}_quantity_${current + 1}"
                    val countAppeared = counter.copy(text = (current + 1).toString())
                    val countDisappeared = counter.copy(text = current.toString())
                    single.copy(
                        source = step.copy(stepId = "${step.stepId}_quantity_${current + 1}"),
                        preconditions = single.preconditions.copy(fromState = previous),
                        transition = single.transition.copy(
                            fromState = previous,
                            toState = next,
                            expectedElementAppeared = countAppeared,
                            expectedElementDisappeared = countDisappeared,
                            expectedEvidence = listOf(
                                StateEvidenceRequirement(
                                    type = EvidenceType.COUNTER_CHANGE,
                                    selector = counter,
                                    expectedValue = (current + 1).toString(),
                                    previousValue = current.toString(),
                                    counterDelta = 1,
                                    description = "Quantity counter incremented to ${current + 1}"
                                ),
                                StateEvidenceRequirement(
                                    type = EvidenceType.ELEMENT_APPEARED,
                                    selector = countAppeared,
                                    description = "Counter value ${current + 1} appeared"
                                )
                            ),
                            evidenceOperator = EvidenceOperator.ANY_SUFFICIENT
                        ),
                        quantityBefore = countDisappeared
                    )
                }
            }
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
