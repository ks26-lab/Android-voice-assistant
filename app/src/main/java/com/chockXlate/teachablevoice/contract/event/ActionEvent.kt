package com.chockXlate.teachablevoice.contract.event

import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import kotlinx.serialization.Serializable

@Serializable
data class ActionEvent(
    val schemaVersion: String = "1.0",
    val actionId: String,
    val timestamp: Long,
    val actionType: String, // e.g. CLICK, TYPE, SCROLL, LONG_PRESS
    val semanticSelector: SemanticSelector,
    val inputData: String? = null,
    val eventType: EventType = EventType.ACTION
)
