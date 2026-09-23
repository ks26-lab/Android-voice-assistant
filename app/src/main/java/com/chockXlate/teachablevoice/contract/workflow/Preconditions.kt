package com.chockXlate.teachablevoice.contract.workflow

import kotlinx.serialization.Serializable

@Serializable
data class Preconditions(
    val schemaVersion: String = "1.0",
    val fromState: String? = null,
    val requiredPackage: String? = null,
    val requiredActivity: String? = null,
    val requiredElementPresent: SemanticSelector? = null,
    val customConditions: Map<String, String> = emptyMap()
)
