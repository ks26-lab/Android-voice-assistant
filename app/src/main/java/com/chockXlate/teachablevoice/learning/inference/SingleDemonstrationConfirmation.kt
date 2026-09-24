package com.chockXlate.teachablevoice.learning.inference

/** One example cannot prove variability. Only explicit user choices resolve that uncertainty. */
object SingleDemonstrationConfirmation {
    fun confirm(result: InferenceResult, variableNames: Set<String>): InferenceResult {
        require(result.demonstrationsAnalyzedCount == 1) { "Expected one real demonstration." }
        require(variableNames.all { name -> result.slotInferences.any { it.slotName == name } })
        return result.copy(slotInferences = result.slotInferences.map { slot ->
            if (slot.status != SlotInferenceStatus.UNKNOWN) slot else slot.copy(
                status = if (slot.slotName in variableNames) SlotInferenceStatus.VARIABLE else SlotInferenceStatus.CONSTANT,
                reasoning = "Explicit user confirmation after one demonstration.",
                confidence = 1.0,
                confidenceLevel = "HIGH"
            )
        })
    }
}
