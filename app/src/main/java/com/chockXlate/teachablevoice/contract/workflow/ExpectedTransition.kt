package com.chockXlate.teachablevoice.contract.workflow

import kotlinx.serialization.Serializable

@Serializable
data class ExpectedTransition(
    val schemaVersion: String = "1.0",
    val fromState: String? = null,
    val toState: String? = null,
    val transitionType: String = "STATE_CHANGE",
    val verification: String? = null,
    val expectedPackage: String? = null,
    val expectedElementAppeared: SemanticSelector? = null,
    val expectedElementDisappeared: SemanticSelector? = null,
    val timeoutMs: Long = 5000L
)
