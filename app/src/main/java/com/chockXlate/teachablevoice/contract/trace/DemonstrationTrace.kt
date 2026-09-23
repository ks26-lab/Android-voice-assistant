package com.chockXlate.teachablevoice.contract.trace

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.ui.UiState
import kotlinx.serialization.Serializable

@Serializable
data class DemonstrationTrace(
    val schemaVersion: String = "1.0",
    val traceId: String,
    val timestamp: Long,
    val appContext: String,
    val windowContext: String? = null,
    val voiceEvents: List<VoiceEvent> = emptyList(),
    val uiStates: List<UiState> = emptyList(),
    val userActions: List<ActionEvent> = emptyList(),
    val stateEvents: List<StateEvent> = emptyList(),
    val traceEvents: List<TraceEvent> = emptyList()
)
