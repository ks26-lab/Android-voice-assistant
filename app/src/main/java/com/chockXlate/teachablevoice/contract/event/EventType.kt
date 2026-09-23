package com.chockXlate.teachablevoice.contract.event

import kotlinx.serialization.Serializable

@Serializable
enum class EventType {
    VOICE,
    UI_INTERACTION,
    ACTION,
    STATE_CHANGE
}

