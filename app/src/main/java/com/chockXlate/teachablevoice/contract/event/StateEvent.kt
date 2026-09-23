package com.chockXlate.teachablevoice.contract.event

import com.chockXlate.teachablevoice.contract.ui.UiState
import kotlinx.serialization.Serializable

@Serializable
data class StateEvent(
    val schemaVersion: String = "1.0",
    val stateEventId: String,
    val timestamp: Long,
    val beforeState: UiState,
    val afterState: UiState,
    val causeActionId: String? = null,
    val eventType: EventType = EventType.STATE_CHANGE
)
