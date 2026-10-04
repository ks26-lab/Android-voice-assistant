package com.chockXlate.teachablevoice.runtime.ui

import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.safety.SafetyGate

enum class RuntimeAction {
    CLICK, INPUT_TEXT, LONG_PRESS, SCROLL, SWIPE, BACK, HOME;

    companion object {
        fun parse(value: String): RuntimeAction? = when (value.trim().uppercase()) {
            "CLICK", "TAP", "SEARCH", "INCREMENT", "DECREMENT", "OPEN", "SELECT", "CONFIRM_NON_SENSITIVE" -> CLICK
            "INPUT_TEXT", "SET_TEXT", "TYPE" -> INPUT_TEXT
            "LONG_PRESS" -> LONG_PRESS
            "SCROLL" -> SCROLL
            "SWIPE" -> SWIPE
            "BACK", "NAVIGATE_BACK" -> BACK
            "HOME", "NAVIGATE_HOME" -> HOME
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
    val before: UiObservation? = null,
    val targetElementId: String? = null,
    val isStale: Boolean = false,
    val isSensitiveBlocked: Boolean = false,
    val executedAction: RuntimeAction? = null
) {
    val status: String get() = if (accepted) "SUCCESS" else if (!attempted) "BLOCKED" else "FAILED"
}

interface UiDriver {
    fun isReady(): Boolean
    fun getActivePackage(): String?
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
