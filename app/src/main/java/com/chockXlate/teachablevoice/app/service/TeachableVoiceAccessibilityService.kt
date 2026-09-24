package com.chockXlate.teachablevoice.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
    @Volatile var isRuntimeReady: Boolean = false
        private set
    private var previousUiState: UiState? = null
    private var captureSessionId: String? = null
    private var capturedPackage: String? = null
    var teachingWarning: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRuntimeReady = true
        Log.i(TAG, "TeachableVoiceAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val isTeaching = isTeachingModeActive || TeachingSessionManager.isTeachingActive()
        if (!isTeaching) return

        val sessionId = TeachingSessionManager.getActiveSession()?.sessionId ?: return
        if (captureSessionId != sessionId) {
            captureSessionId = sessionId
            previousUiState = null
            capturedPackage = null
            teachingWarning = null
        }
        if (teachingWarning != null) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return
        val actionable = event.eventType in setOf(AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        if (capturedPackage == null && !actionable) return
        if (capturedPackage != null && packageName != capturedPackage) return
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

        return try {
            if (TeachingPrivacyGuard.blocks(rootNode)) null
            else UiNodeNormalizer.normalizeState(rootNode, packageName, windowId)
        } finally { rootNode.recycle() }
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

    }

    private fun handleTeachingEvent(
        event: AccessibilityEvent,
        packageName: String,
        className: String?,
        eventTypeString: String
    ) {
        val timestamp = System.currentTimeMillis()
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != packageName) return
            if (event.isPassword || TeachingPrivacyGuard.blocks(root)) {
                teachingWarning = "Teaching capture paused at a credential boundary. Stop teaching; continue manually."
                return
            }
        } finally { root.recycle() }
        val sourceNode = event.source ?: return
        val targetElement = try {
            if (TeachingPrivacyGuard.blocks(sourceNode)) {
                teachingWarning = "Teaching capture paused at a credential boundary."
                return
            }
            UiNodeNormalizer.normalizeNode(sourceNode)
        } finally { sourceNode.recycle() }
        capturedPackage = packageName

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
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (targetElement.isEditable) null else "CLICK"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "LONG_PRESS"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "INPUT_TEXT"
            else -> null
        }

        if (actionType != null) {
            val inputData = if (actionType == "INPUT_TEXT") {
                targetElement.text
            } else {
                null
            }

            val selector = SemanticSelector(
                schemaVersion = "1.0",
                role = targetElement.role,
                text = targetElement.text,
                contentDescription = targetElement.contentDescription,
                resourceId = targetElement.resourceId,
                parentRole = targetElement.parentRole,
                ancestorRole = targetElement.ancestorRole
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
        isRuntimeReady = false
        Log.w(TAG, "TeachableVoiceAccessibilityService interrupted.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRuntimeReady = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isRuntimeReady = false
        super.onDestroy()
        instance = null
        Log.i(TAG, "TeachableVoiceAccessibilityService destroyed.")
    }
}
