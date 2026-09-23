package com.chockXlate.teachablevoice.teach.capture

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.ui.UiState
import java.util.concurrent.atomic.AtomicReference

/**
 * Singleton controller managing the lifecycle of active Person 1 teaching sessions.
 */
object TeachingSessionManager {

    private val currentSession = AtomicReference<TeachingSessionImpl?>(null)

    fun startSession(skillName: String, intent: String): TeachingSession {
        val newSession = TeachingSessionImpl(skillName, intent)
        newSession.startTeaching(skillName, intent)
        currentSession.set(newSession)
        return newSession
    }

    fun isTeachingActive(): Boolean {
        return currentSession.get()?.isRecording == true
    }

    fun getActiveSession(): TeachingSession? {
        val session = currentSession.get()
        return if (session?.isRecording == true) session else null
    }

    fun recordVoiceEvent(event: VoiceEvent) {
        getActiveSession()?.recordVoiceEvent(event)
    }

    fun recordUiEvent(event: UiEvent) {
        getActiveSession()?.recordUiEvent(event)
    }

    fun recordUiState(state: UiState) {
        val session = currentSession.get() as? TeachingSessionImpl
        if (session?.isRecording == true) {
            session.recordUiState(state)
        }
    }

    fun recordActionEvent(action: ActionEvent) {
        getActiveSession()?.recordActionEvent(action)
    }

    fun recordStateEvent(event: StateEvent) {
        getActiveSession()?.recordStateEvent(event)
    }

    fun peekSessionTrace(): DemonstrationTrace? {
        val session = currentSession.get() as? TeachingSessionImpl
        return if (session?.isRecording == true) session.peekTrace() else null
    }

    fun stopSession(): DemonstrationTrace? {
        val session = currentSession.get() ?: return null
        if (!session.isRecording) return null
        val trace = session.stopTeaching()
        currentSession.set(null)
        return trace
    }

    fun clearSession() {
        currentSession.set(null)
    }
}
