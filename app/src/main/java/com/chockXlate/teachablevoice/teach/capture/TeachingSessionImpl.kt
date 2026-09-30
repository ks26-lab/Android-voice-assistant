package com.chockXlate.teachablevoice.teach.capture

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiState
import java.util.Collections
import java.util.UUID

/**
 * Concrete thread-safe implementation of TeachingSession.
 * Records ordered events during a live user teaching demonstration.
 */
class TeachingSessionImpl(
    initialSkillName: String = "",
    initialIntent: String = ""
) : TeachingSession {

    override var isRecording: Boolean = false
        private set

    override var sessionId: String = UUID.randomUUID().toString()
        private set

    override var skillName: String = initialSkillName
        private set

    override var intent: String = initialIntent
        private set

    override var startTimestamp: Long = System.currentTimeMillis()
        private set

    var appContext: String = "unknown"

    private val _voiceEvents = Collections.synchronizedList(mutableListOf<VoiceEvent>())
    private val _uiStates = Collections.synchronizedList(mutableListOf<UiState>())
    private val _userActions = Collections.synchronizedList(mutableListOf<ActionEvent>())
    private val _stateEvents = Collections.synchronizedList(mutableListOf<StateEvent>())
    private val _traceEvents = Collections.synchronizedList(mutableListOf<TraceEvent>())

    init {
        if (initialSkillName.isNotBlank() || initialIntent.isNotBlank()) {
            isRecording = true
        }
    }

    override fun startTeaching(skillName: String, intent: String) {
        synchronized(this) {
            this.sessionId = UUID.randomUUID().toString()
            this.startTimestamp = System.currentTimeMillis()
            this.skillName = skillName
            this.intent = intent
            this._voiceEvents.clear()
            this._uiStates.clear()
            this._userActions.clear()
            this._stateEvents.clear()
            this._traceEvents.clear()
            this.appContext = "unknown"
            this.isRecording = true
        }
    }

    private fun isSystemOrOwnPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("launcher") || lower.contains("home") || lower.contains("quickstep") ||
            lower.contains("systemui") || lower.startsWith("android") ||
            lower == "com.chockXlate.teachablevoice".lowercase() || lower.startsWith("com.chockxlate.teachablevoice.")
    }

    private fun updateSessionAppContext(incoming: String?) {
        if (incoming.isNullOrBlank() || incoming == "unknown") return
        val isSystemOrOwn = isSystemOrOwnPackage(incoming)
        if (appContext == "unknown" || appContext.isBlank()) {
            appContext = incoming
        } else if (isSystemOrOwnPackage(appContext) && !isSystemOrOwn) {
            appContext = incoming
        } else if (!isSystemOrOwn) {
            appContext = incoming
        }
    }

    override fun recordVoiceEvent(event: VoiceEvent) {
        if (!isRecording) return
        _voiceEvents.add(event)
        _traceEvents.add(TraceEvent.Voice(event.eventId, event.timestamp, event))
    }

    override fun recordUiEvent(event: UiEvent) {
        if (!isRecording) return
        updateSessionAppContext(event.packageName)
        _traceEvents.add(TraceEvent.Ui(event.eventId, event.timestamp, event))
    }

    fun recordUiState(state: UiState) {
        if (!isRecording) return
        updateSessionAppContext(state.appContext)
        _uiStates.add(state)
    }

    override fun recordActionEvent(action: ActionEvent) {
        if (!isRecording) return
        updateSessionAppContext(action.packageName)
        _userActions.add(action)
        _traceEvents.add(TraceEvent.Action(action.actionId, action.timestamp, action))
    }

    override fun recordStateEvent(event: StateEvent) {
        if (!isRecording) return
        _stateEvents.add(event)
        _traceEvents.add(TraceEvent.State(event.stateEventId, event.timestamp, event))
    }

    fun peekTrace(): DemonstrationTrace {
        synchronized(this) {
            val voiceCopy = ArrayList(_voiceEvents)
            val uiStatesCopy = ArrayList(_uiStates)
            val actionsCopy = ArrayList(_userActions)
            val stateEventsCopy = ArrayList(_stateEvents)
            val sortedTraceEvents = ArrayList(_traceEvents).sortedBy { it.timestamp }

            return DemonstrationTrace(
                schemaVersion = "1.0",
                traceId = sessionId,
                timestamp = startTimestamp,
                appContext = appContext,
                windowContext = null,
                voiceEvents = voiceCopy,
                uiStates = uiStatesCopy,
                userActions = actionsCopy,
                stateEvents = stateEventsCopy,
                traceEvents = sortedTraceEvents
            )
        }
    }

    override fun stopTeaching(): DemonstrationTrace {
        synchronized(this) {
            val trace = peekTrace()
            isRecording = false
            return trace
        }
    }
}
