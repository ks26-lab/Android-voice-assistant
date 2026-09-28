package com.chockXlate.teachablevoice.contract.filter

import kotlinx.serialization.Serializable

@Serializable
enum class DemonstrationFilterClassification {
    TASK_RELEVANT,
    NAVIGATION_CONTEXT,
    SYSTEM_NOISE,
    UNCERTAIN
}

@Serializable
data class FilteredTraceEvent(
    val schemaVersion: String = "1.0",
    val eventId: String,
    val classification: DemonstrationFilterClassification,
    val reason: String,
    val packageName: String? = null,
    val isSystemSurface: Boolean = false,
    val isActionable: Boolean = false
)

@Serializable
data class FilteredDemonstrationResult(
    val schemaVersion: String = "1.0",
    val traceId: String,
    val targetAppContext: String,
    val allEvents: List<FilteredTraceEvent> = emptyList(),
    val taskRelevantEventIds: List<String> = emptyList(),
    val navigationContextEventIds: List<String> = emptyList(),
    val systemNoiseEventIds: List<String> = emptyList(),
    val uncertainEventIds: List<String> = emptyList()
) {
    fun isTaskRelevant(eventId: String): Boolean = eventId in taskRelevantEventIds
    fun isFiltered(eventId: String): Boolean = eventId !in taskRelevantEventIds
}
