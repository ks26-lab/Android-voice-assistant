package com.chockXlate.teachablevoice.contract.event

import com.chockXlate.teachablevoice.contract.ui.UiElement
import kotlinx.serialization.Serializable

@Serializable
data class UiEvent(
    val schemaVersion: String = "1.0",
    val eventId: String,
    val timestamp: Long,
    val accessibilityEventType: String,
    val packageName: String,
    val className: String? = null,
    val targetElement: UiElement? = null,
    val eventType: EventType = EventType.UI_INTERACTION
)
