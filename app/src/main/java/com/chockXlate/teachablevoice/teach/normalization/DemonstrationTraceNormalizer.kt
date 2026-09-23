package com.chockXlate.teachablevoice.teach.normalization

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiState
import kotlin.math.abs

/**
 * Normalizes raw DemonstrationTrace objects into canonical, deterministic,
 * ordered, and conservatively deduplicated inputs for learning.
 */
object DemonstrationTraceNormalizer {

    fun normalize(rawTrace: DemonstrationTrace): TraceNormalizationResult {
        val warnings = mutableListOf<String>()
        val rawEventCount = rawTrace.traceEvents.size

        // 1. Deterministic Chronological Sorting (Stable sort preserves tie-breaker insertion order)
        val sortedTraceEvents = rawTrace.traceEvents.sortedBy { it.timestamp }

        // 2. Conservative Deduplication & Validation
        val normalizedEvents = mutableListOf<TraceEvent>()
        var removedDuplicateCount = 0

        var lastUiEvent: UiEvent? = null

        for (event in sortedTraceEvents) {
            when (event) {
                is TraceEvent.Voice -> {
                    normalizedEvents.add(event)
                    lastUiEvent = null
                }
                is TraceEvent.Action -> {
                    normalizedEvents.add(event)
                    lastUiEvent = null
                }
                is TraceEvent.State -> {
                    val se = event.stateEvent
                    if (se.beforeState.stateId.isBlank() || se.afterState.stateId.isBlank()) {
                        warnings.add("StateEvent ${se.stateEventId} contains blank before/after state identity.")
                    }
                    normalizedEvents.add(event)
                    lastUiEvent = null
                }
                is TraceEvent.Ui -> {
                    val currentUi = event.uiEvent
                    if (isTrueDuplicateUiEvent(lastUiEvent, currentUi)) {
                        removedDuplicateCount++
                    } else {
                        normalizedEvents.add(event)
                        lastUiEvent = currentUi
                    }
                }
            }
        }

        // 3. App & Window Context Resolution
        var resolvedAppContext = rawTrace.appContext
        if (resolvedAppContext.isBlank() || resolvedAppContext == "unknown") {
            val discoveredApp = normalizedEvents.asSequence()
                .mapNotNull { ev ->
                    when (ev) {
                        is TraceEvent.Ui -> ev.uiEvent.packageName
                        is TraceEvent.State -> ev.stateEvent.beforeState.appContext
                        else -> null
                    }
                }
                .firstOrNull { it.isNotBlank() && it != "unknown" }

            if (discoveredApp != null) {
                resolvedAppContext = discoveredApp
            }
        }

        // 4. Reconstruct Typed Event Collections from Normalized Stream
        val voiceEvents = normalizedEvents.filterIsInstance<TraceEvent.Voice>().map { it.voiceEvent }
        val actionEvents = normalizedEvents.filterIsInstance<TraceEvent.Action>().map { it.actionEvent }
        val stateEvents = normalizedEvents.filterIsInstance<TraceEvent.State>().map { it.stateEvent }

        // Deduplicate UiStates while preserving order
        val uiStates = mutableListOf<UiState>()
        val seenStateIds = mutableSetOf<String>()
        rawTrace.uiStates.forEach { state ->
            if (state.stateId.isNotBlank() && seenStateIds.add(state.stateId)) {
                uiStates.add(state)
            }
        }

        val canonicalTrace = rawTrace.copy(
            appContext = resolvedAppContext,
            voiceEvents = voiceEvents,
            uiStates = uiStates,
            userActions = actionEvents,
            stateEvents = stateEvents,
            traceEvents = normalizedEvents
        )

        val status = if (warnings.isEmpty()) "NORMALIZED ✓" else "NORMALIZED WITH WARNINGS"

        return TraceNormalizationResult(
            normalizedTrace = canonicalTrace,
            rawEventCount = rawEventCount,
            normalizedEventCount = normalizedEvents.size,
            removedDuplicateCount = removedDuplicateCount,
            warningCount = warnings.size,
            warnings = warnings,
            status = status
        )
    }

    private fun isTrueDuplicateUiEvent(e1: UiEvent?, e2: UiEvent): Boolean {
        if (e1 == null) return false
        if (e1.accessibilityEventType != e2.accessibilityEventType) return false
        if (e1.packageName != e2.packageName) return false

        val t1 = e1.targetElement
        val t2 = e2.targetElement
        if (t1 == null || t2 == null) return false

        val sameRole = t1.role == t2.role
        val sameText = t1.text == t2.text
        val sameResId = t1.resourceId == t2.resourceId
        val withinTimeWindow = abs(e2.timestamp - e1.timestamp) <= 300L

        return sameRole && sameText && sameResId && withinTimeWindow
    }
}
