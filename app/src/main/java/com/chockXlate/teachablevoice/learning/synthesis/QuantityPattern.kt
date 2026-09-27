package com.chockXlate.teachablevoice.learning.synthesis

import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.learning.actions.SemanticAction
import com.chockXlate.teachablevoice.learning.actions.SemanticActionType
import kotlinx.serialization.json.Json

/** A bounded quantity operation requires observed +1 counter transitions, never a '+' guess. */
internal object QuantityPattern {
    fun parameterize(steps: List<WorkflowStep>, actions: List<SemanticAction>, trace: DemonstrationTrace,
                     slots: List<WorkflowSlot>): List<WorkflowStep> {
        val quantity = slots.singleOrNull { it.name == "quantity" && it.required && it.type == SlotType.INTEGER } ?: return steps
        if (steps.any { it.semanticAction == "INPUT_TEXT" && it.semanticSelector.textSlot == "\${quantity}" }) return steps
        data class Evidence(val start: Int, val end: Int, val counter: SemanticSelector)
        fun evidence(action: SemanticAction): Evidence? {
            if (action.actionType != SemanticActionType.TAP) return null
            val labels = listOfNotNull(action.target?.text, action.target?.contentDescription)
                .map { it.trim().lowercase() }
            if (labels.none { it in setOf("+", "increase quantity", "increment quantity", "add one", "increase amount") }) return null
            val event = trace.stateEvents.singleOrNull { it.causeActionId == action.actionId } ?: return null
            if (event.beforeState.appContext != trace.appContext || event.afterState.appContext != trace.appContext) return null
            val pairs = event.beforeState.allElements.mapNotNull { before ->
                val start = before.text?.toIntOrNull() ?: return@mapNotNull null
                if (before.resourceId.isNullOrBlank() && before.contentDescription.isNullOrBlank()) return@mapNotNull null
                fun same(e: com.chockXlate.teachablevoice.contract.ui.UiElement) =
                    e.role == before.role && e.resourceId == before.resourceId && e.contentDescription == before.contentDescription
                if (event.beforeState.allElements.count(::same) != 1) return@mapNotNull null
                val after = event.afterState.allElements.singleOrNull(::same) ?: return@mapNotNull null
                val end = after.text?.toIntOrNull() ?: return@mapNotNull null
                if (start !in 0..19 || end != start + 1) return@mapNotNull null
                Evidence(start, end, SemanticSelector(role = before.role, resourceId = before.resourceId,
                    contentDescription = before.contentDescription))
            }
            return pairs.singleOrNull()
        }
        val evidence = actions.map(::evidence)
        val indices = evidence.indices.filter { evidence[it] != null }
        if (indices.isEmpty()) return steps
        // Multiple separated counters/operations need clarification rather than an invented loop.
        if (indices != (indices.first()..indices.last()).toList()) return steps
        val first = indices.first(); val last = indices.last()
        val initial = evidence[first]!!
        if (indices.any { steps[it].semanticSelector != steps[first].semanticSelector || evidence[it]!!.counter != initial.counter }) return steps
        if (indices.zipWithNext().any { (a, b) -> evidence[a]!!.end != evidence[b]!!.start }) return steps
        if (quantity.exampleValue?.toIntOrNull() != evidence[last]!!.end) return steps
        val operation = steps[first].copy(parameters = mapOf(
            "repeat_slot" to quantity.name,
            "quantity_start" to initial.start.toString(),
            "quantity_selector" to Json.encodeToString(SemanticSelector.serializer(), initial.counter)
        ), expectedTransition = steps[last].expectedTransition.copy(fromState = steps[first].preconditions.fromState))
        return steps.take(first) + operation + steps.drop(last + 1)
    }
}
