package com.chockXlate.teachablevoice.contract.workflow

import kotlinx.serialization.Serializable

@Serializable
data class SafetyBoundary(
    val schemaVersion: String = "1.0",
    val requiresExplicitUserConfirmation: Boolean = false,
    val sensitiveKeywords: List<String> = emptyList(),
    val restrictedActions: List<String> = emptyList(),
    val maxAllowedValue: Double? = null
)
