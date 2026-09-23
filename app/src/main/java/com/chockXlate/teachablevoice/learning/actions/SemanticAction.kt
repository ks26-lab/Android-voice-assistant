package com.chockXlate.teachablevoice.learning.actions

import com.chockXlate.teachablevoice.learning.targets.SemanticTarget
import kotlinx.serialization.Serializable

@Serializable
enum class SemanticActionType {
    TAP,
    LONG_PRESS,
    INPUT_TEXT,
    SELECT,
    FOCUS,
    TOGGLE,
    SUBMIT,
    SCROLL,
    UNKNOWN
}

/**
 * Generic semantic representation of a user action performed on a UI target.
 */
@Serializable
data class SemanticAction(
    val schemaVersion: String = "1.0",
    val actionId: String,
    val timestamp: Long,
    val actionType: SemanticActionType,
    val target: SemanticTarget? = null,
    val inputValue: String? = null,
    val confidence: Double = 1.0,
    val confidenceLevel: String = "HIGH", // HIGH, MEDIUM, LOW
    val rawEventId: String? = null,
    val rawActionType: String? = null
)
