package com.chockXlate.teachablevoice.contract.event

import kotlinx.serialization.Serializable

@Serializable
data class VoiceEvent(
    val schemaVersion: String = "1.0",
    val eventId: String,
    val timestamp: Long,
    val rawAudioUri: String? = null,
    val transcript: String,
    val confidence: Double = 1.0,
    val isFinal: Boolean = true,
    val eventType: EventType = EventType.VOICE
)
