package com.chockXlate.teachablevoice.contract.trace

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import kotlinx.serialization.Serializable

@Serializable
sealed class TraceEvent {
    abstract val eventId: String
    abstract val timestamp: Long

    @Serializable
    data class Voice(
        override val eventId: String,
        override val timestamp: Long,
        val voiceEvent: VoiceEvent
    ) : TraceEvent()

    @Serializable
    data class Ui(
        override val eventId: String,
        override val timestamp: Long,
        val uiEvent: UiEvent
    ) : TraceEvent()

    @Serializable
    data class Action(
        override val eventId: String,
        override val timestamp: Long,
        val actionEvent: ActionEvent
    ) : TraceEvent()

    @Serializable
    data class State(
        override val eventId: String,
        override val timestamp: Long,
        val stateEvent: StateEvent
    ) : TraceEvent()
}
