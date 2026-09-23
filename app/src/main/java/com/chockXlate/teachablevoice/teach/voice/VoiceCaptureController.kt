package com.chockXlate.teachablevoice.teach.voice

import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import java.util.UUID

/**
 * Controller/abstraction for capturing voice transcript events during a teaching session.
 */
object VoiceCaptureController {

    /**
     * Records a recognized voice utterance and dispatches a VoiceEvent to the active TeachingSession.
     */
    fun recordUtterance(
        transcript: String,
        confidence: Double = 1.0,
        rawAudioUri: String? = null
    ): VoiceEvent {
        val event = VoiceEvent(
            schemaVersion = "1.0",
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            rawAudioUri = rawAudioUri,
            transcript = transcript,
            confidence = confidence,
            isFinal = true
        )

        TeachingSessionManager.recordVoiceEvent(event)
        return event
    }
}
