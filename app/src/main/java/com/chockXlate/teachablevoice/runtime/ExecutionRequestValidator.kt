package com.chockXlate.teachablevoice.runtime

import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest

object ExecutionRequestValidator {
    fun validate(request: ExecutionRequest): String? = when {
        request.schemaVersion != "1.0" -> "Unsupported execution request schema."
        request.executionId.isBlank() -> "Execution ID is missing."
        request.skillId.isBlank() -> "Skill ID is missing."
        request.version < 1 -> "Workflow version must be positive."
        request.boundSlots.keys.any { it.isBlank() } -> "A slot name is blank."
        else -> null
    }
}
