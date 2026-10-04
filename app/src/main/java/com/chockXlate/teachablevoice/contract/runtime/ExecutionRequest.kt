package com.chockXlate.teachablevoice.contract.runtime

import kotlinx.serialization.Serializable

@Serializable
data class ExecutionRequest(
    val schemaVersion: String = "1.0",
    val executionId: String,
    val skillId: String,
    val boundSlots: Map<String, String> = emptyMap(),
    val version: Int = 1,
    val provenance: String = "person_1_matching",
    val isSensitive: Boolean = false,
    val originalCommand: String? = null
)

