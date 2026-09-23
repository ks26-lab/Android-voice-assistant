package com.chockXlate.teachablevoice.contract.ui

import kotlinx.serialization.Serializable

@Serializable
data class UiState(
    val schemaVersion: String = "1.0",
    val stateId: String,
    val timestamp: Long,
    val appContext: String, // Package name of the active app
    val windowId: Int = 0,
    val rootElement: UiElement? = null,
    val allElements: List<UiElement> = emptyList()
)
