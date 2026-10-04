package com.chockXlate.teachablevoice.teach.capture

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace

/**
 * Manages active teaching capture sessions for recording user demonstrations.
 */
interface TeachingSession {
    val isRecording: Boolean
    val sessionId: String
    val skillId: String
    val skillName: String
    val intent: String
    val description: String
    val startTimestamp: Long

    fun startTeaching(skillName: String, intent: String, skillId: String = "", description: String = "")
    fun recordVoiceEvent(event: VoiceEvent)
    fun recordUiEvent(event: UiEvent)
    fun recordActionEvent(action: ActionEvent)
    fun recordStateEvent(event: StateEvent) {}
    fun stopTeaching(): DemonstrationTrace
}


