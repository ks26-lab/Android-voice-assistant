package com.chockXlate.teachablevoice.contract.ui

import kotlinx.serialization.Serializable

@Serializable
enum class UiObservationStatus {
    SUCCESS,
    UNAVAILABLE,
    STALE,
    UNSUPPORTED
}

@Serializable
data class UiObservationResult(
    val status: UiObservationStatus,
    val state: UiState? = null,
    val sequenceNumber: Long = 0L,
    val errorMessage: String? = null,
    val isSensitive: Boolean = false
)

@Serializable
data class UiState(
    val schemaVersion: String = "1.0",
    val stateId: String,
    val timestamp: Long,
    val appContext: String, // Package name of the active app
    val windowId: Int = 0,
    val rootElement: UiElement? = null,
    val allElements: List<UiElement> = emptyList(),
    val sequenceNumber: Long = 0L,
    val isSensitiveContext: Boolean = false
) {
    val foregroundPackage: String get() = appContext
    val visibleElements: List<UiElement> get() = allElements.filter { it.isVisible }
    val actionableElements: List<UiElement> get() = allElements.filter {
        it.isVisible && (it.isClickable || it.isEditable || it.isCheckable || it.isScrollable)
    }
}

