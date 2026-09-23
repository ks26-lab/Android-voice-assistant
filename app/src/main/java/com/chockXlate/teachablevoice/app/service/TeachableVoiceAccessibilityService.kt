package com.chockXlate.teachablevoice.app.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import java.util.UUID

/**
 * Native Android AccessibilityService that captures live UI hierarchies,
 * normalizes active window nodes into UiElement/UiState contracts,
 * logs UI structures for development, and captures teaching interaction events.
 */
class TeachableVoiceAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TeachableVoiceService"
        var instance: TeachableVoiceAccessibilityService? = null
            private set
    }

    var isTeachingModeActive: Boolean = false
    private var previousUiState: UiState? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "TeachableVoiceAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val isTeaching = isTeachingModeActive || TeachingSessionManager.isTeachingActive()
        if (!isTeaching) return

        val packageName = event.packageName?.toString() ?: "unknown"
        val className = event.className?.toString()
        val eventTypeString = AccessibilityEvent.eventTypeToString(event.eventType)

        Log.d(TAG, "Accessibility Event [$eventTypeString] App Context: $packageName ($className)")
        handleTeachingEvent(event, packageName, className, eventTypeString)
    }

    /**
     * Reads and normalizes the current active window UI hierarchy.
     */
    fun captureCurrentUiState(): UiState? {
        val rootNode = rootInActiveWindow ?: return null
        val packageName = rootNode.packageName?.toString() ?: "unknown"
        val windowId = rootNode.windowId

        val state = UiNodeNormalizer.normalizeState(rootNode, packageName, windowId)
        rootNode.recycle()

        Log.d(TAG, "Captured UI State for $packageName: ${state.allElements.size} elements extracted.")
        return state
    }

    /**
     * Logs the current active UI hierarchy for development & debugging.
     */
    fun logCurrentUiHierarchy() {
        val uiState = captureCurrentUiState()
        if (uiState == null) {
            Log.w(TAG, "Unable to capture active UI hierarchy (rootInActiveWindow was null).")
            return
        }
        Log.i(TAG, "=== UI Hierarchy Dump [App: ${uiState.appContext}, Elements: ${uiState.allElements.size}] ===")
        uiState.allElements.forEach { element ->
            Log.i(TAG, "  Element [Role: ${element.role}, Text: '${element.text}', ResId: ${element.resourceId}, Clickable: ${element.isClickable}]")
        }
    }

    private fun handleTeachingEvent(
        event: AccessibilityEvent,
        packageName: String,
        className: String?,
        eventTypeString: String
    ) {
        val timestamp = System.currentTimeMillis()
        val sourceNode = event.source
        val targetElement = sourceNode?.let { UiNodeNormalizer.normalizeNode(it) }
        sourceNode?.recycle()

        val uiEvent = UiEvent(
            schemaVersion = "1.0",
            eventId = UUID.randomUUID().toString(),
            timestamp = timestamp,
            accessibilityEventType = eventTypeString,
            packageName = packageName,
            className = className,
            targetElement = targetElement
        )

        TeachingSessionManager.recordUiEvent(uiEvent)

        val currentUiState = captureCurrentUiState()
        if (currentUiState != null) {
            TeachingSessionManager.recordUiState(currentUiState)
        }

        // Action Detection based on Accessibility Event Type
        val actionType = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "LONG_PRESS"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "INPUT_TEXT"
            AccessibilityEvent.TYPE_VIEW_SELECTED -> "SELECT"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "FOCUS"
            else -> null
        }

        if (actionType != null) {
            val inputData = if (actionType == "INPUT_TEXT") {
                event.text?.joinToString("") ?: targetElement?.text
            } else {
                null
            }

            val selector = SemanticSelector(
                schemaVersion = "1.0",
                role = targetElement?.role,
                text = targetElement?.text,
                contentDescription = targetElement?.contentDescription,
                resourceId = targetElement?.resourceId,
                parentRole = targetElement?.parentRole,
                ancestorRole = targetElement?.ancestorRole
            )

            val actionEvent = ActionEvent(
                schemaVersion = "1.0",
                actionId = UUID.randomUUID().toString(),
                timestamp = timestamp,
                actionType = actionType,
                semanticSelector = selector,
                inputData = inputData
            )

            TeachingSessionManager.recordActionEvent(actionEvent)

            // Before / After State capture
            val before = previousUiState
            val after = currentUiState
            if (before != null && after != null) {
                val stateEvent = StateEvent(
                    schemaVersion = "1.0",
                    stateEventId = UUID.randomUUID().toString(),
                    timestamp = timestamp,
                    beforeState = before,
                    afterState = after,
                    causeActionId = actionEvent.actionId
                )
                TeachingSessionManager.recordStateEvent(stateEvent)
            }
        }

        previousUiState = currentUiState
    }

    override fun onInterrupt() {
        Log.w(TAG, "TeachableVoiceAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "TeachableVoiceAccessibilityService destroyed.")
    }
}

