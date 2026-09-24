package com.chockXlate.teachablevoice.runtime.ui

import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.safety.SafetyGate

enum class RuntimeAction {
    CLICK, INPUT_TEXT, LONG_PRESS, SCROLL;

    companion object {
        fun parse(value: String): RuntimeAction? = when (value.trim().uppercase()) {
            "CLICK", "TAP" -> CLICK
            "INPUT_TEXT" -> INPUT_TEXT
            "LONG_PRESS" -> LONG_PRESS
            "SCROLL" -> SCROLL
            else -> null
        }
    }
}

/** Node handles never leave the Android adapter. Capabilities refer only to this snapshot. */
data class UiObservation(
    val state: UiState,
    val capabilities: Map<String, Set<RuntimeAction>> = emptyMap(),
    val credentialFieldPresent: Boolean = false
)

data class ActionOutcome(
    val attempted: Boolean,
    val accepted: Boolean = false,
    val reason: String,
    val before: UiObservation? = null
)

interface UiDriver {
    fun isReady(): Boolean
    suspend fun observe(): UiObservation?

    /** Must re-observe, re-match, and call gate.dispatch immediately around the side effect. */
    suspend fun execute(
        step: BoundStep,
        expectedPackage: String,
        boundary: SafetyBoundary,
        matcher: SemanticMatcher,
        gate: SafetyGate
    ): ActionOutcome

    /** Nonblocking on Android; fake drivers can advance a virtual clock. */
    suspend fun awaitChange(delayMs: Long)
}
