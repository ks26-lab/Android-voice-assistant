package com.chockXlate.teachablevoice.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import java.util.UUID

/**
 * Native Android AccessibilityService that captures live UI hierarchies,
 * normalizes active window nodes into UiElement/UiState contracts,
 * logs UI structures for development, and captures teaching interaction events.
 */
open class TeachableVoiceAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TeachableVoiceService"
        const val CAPTURE_TAG = "TVA_CAPTURE"
        val RELEVANT_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )
        val USER_INTERACTION_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED
        )
        var instance: TeachableVoiceAccessibilityService? = null
            private set

        internal fun safeLogD(tag: String, msg: String) {
            try { Log.d(tag, msg) } catch (t: Throwable) {}
        }
        internal fun safeLogI(tag: String, msg: String) {
            try { Log.i(tag, msg) } catch (t: Throwable) {}
        }
        internal fun safeLogW(tag: String, msg: String) {
            try { Log.w(tag, msg) } catch (t: Throwable) {}
        }
    }

    var isTeachingModeActive: Boolean = false
    @Volatile var isRuntimeReady: Boolean = false
        private set
    private var previousUiState: UiState? = null
    private var captureSessionId: String? = null
    var teachingWarning: String? = null
        internal set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRuntimeReady = true
        safeLogI(TAG, "TeachableVoiceAccessibilityService connected.")
    }

    internal open fun getServicePackageName(): String {
        return try {
            packageName ?: "com.chockXlate.teachablevoice"
        } catch (t: Throwable) {
            "com.chockXlate.teachablevoice"
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val isTeaching = isTeachingModeActive || TeachingSessionManager.isTeachingActive()
        if (!isTeaching) return

        val session = TeachingSessionManager.getActiveSession() ?: return
        val sessionId = session.sessionId
        if (captureSessionId != sessionId) {
            captureSessionId = sessionId
            previousUiState = null
            teachingWarning = null
        }
        if (teachingWarning != null) {
            safeLogD(CAPTURE_TAG, "Rejected: type=${AccessibilityEvent.eventTypeToString(event.eventType)}, reason=paused_at_credential_boundary")
            return
        }

        val packageName = event.packageName?.toString() ?: run {
            safeLogD(CAPTURE_TAG, "Rejected: type=${AccessibilityEvent.eventTypeToString(event.eventType)}, pkg=null, reason=null_package")
            return
        }

        val ownPackage = getServicePackageName()
        if (packageName == ownPackage) {
            safeLogD(CAPTURE_TAG, "Rejected: type=${AccessibilityEvent.eventTypeToString(event.eventType)}, pkg=$packageName, reason=own_package")
            return
        }

        if (event.eventType !in RELEVANT_EVENT_TYPES) {
            return
        }

        val className = event.className?.toString()
        val eventTypeString = AccessibilityEvent.eventTypeToString(event.eventType)

        safeLogD(TAG, "Accessibility Event [$eventTypeString] App Context: $packageName ($className)")
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
            UiNodeNormalizer.normalizeState(rootNode, packageName, windowId)
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    /**
     * Logs the current active UI hierarchy for development & debugging.
     */
    fun logCurrentUiHierarchy() {
        val uiState = captureCurrentUiState()
        if (uiState == null) {
            safeLogW(TAG, "Unable to capture active UI hierarchy (rootInActiveWindow was null).")
            return
        }
        safeLogI(TAG, "=== UI Hierarchy Dump [App: ${uiState.appContext}, Elements: ${uiState.allElements.size}] ===")

    }

    private fun handleTeachingEvent(
        event: AccessibilityEvent,
        packageName: String,
        className: String?,
        eventTypeString: String
    ) {
        val isUserInteraction = event.eventType in USER_INTERACTION_EVENT_TYPES
        val sourceNode = try { event.source } catch (e: Exception) { null }
        val isTargetProtected = if (sourceNode != null && isUserInteraction) {
            TeachingPrivacyGuard.isSensitiveCredentialTarget(sourceNode)
        } else false

        val targetElement = try {
            if (!isTargetProtected) {
                sourceNode?.let { UiNodeNormalizer.normalizeNode(it) }
            } else null
        } catch (e: Exception) {
            null
        } finally {
            try {
                @Suppress("DEPRECATION")
                sourceNode?.recycle()
            } catch (e: Exception) {}
        }

        val eventText = try {
            event.text.firstOrNull()?.toString()
        } catch (e: Exception) {
            null
        }

        val eventContentDesc = try {
            event.contentDescription?.toString()
        } catch (e: Exception) {
            null
        }

        val currentUiState = captureCurrentUiState()

        recordNormalizedTeachingEvent(
            eventType = event.eventType,
            eventTypeString = eventTypeString,
            packageName = packageName,
            className = className,
            targetElement = targetElement,
            isPassword = event.isPassword,
            isTargetProtected = isTargetProtected,
            eventText = eventText,
            eventContentDescription = eventContentDesc,
            capturedUiState = currentUiState
        )
    }

    internal fun recordNormalizedTeachingEvent(
        eventType: Int,
        eventTypeString: String,
        packageName: String,
        className: String?,
        targetElement: UiElement?,
        isPassword: Boolean = false,
        isTargetProtected: Boolean = false,
        eventText: String? = null,
        eventContentDescription: String? = null,
        capturedUiState: UiState? = null
    ): Boolean {
        val ownPackage = getServicePackageName()
        if (packageName == ownPackage) {
            safeLogD(CAPTURE_TAG, "Rejected: type=$eventTypeString, pkg=$packageName, reason=own_package")
            return false
        }

        if (!TeachingSessionManager.isTeachingActive()) {
            safeLogD(CAPTURE_TAG, "Rejected: type=$eventTypeString, pkg=$packageName, reason=teaching_inactive")
            return false
        }

        if (teachingWarning != null) {
            safeLogD(CAPTURE_TAG, "Rejected: type=$eventTypeString, pkg=$packageName, reason=paused_at_credential_boundary")
            return false
        }

        if (isPassword) {
            teachingWarning = "Teaching capture paused at a credential boundary. Stop teaching; continue manually."
            safeLogD(CAPTURE_TAG, "Rejected: type=$eventTypeString, pkg=$packageName, reason=password_event")
            return false
        }

        val isUserInteraction = eventType in USER_INTERACTION_EVENT_TYPES
        val isTargetElementSensitive = isUserInteraction && TeachingPrivacyGuard.isSensitiveTargetElement(targetElement)

        if (isUserInteraction && (isTargetProtected || isTargetElementSensitive)) {
            teachingWarning = "Teaching capture paused at a credential boundary."
            safeLogD(CAPTURE_TAG, "Rejected: type=$eventTypeString, pkg=$packageName, reason=credential_target_element")
            return false
        }

        val timestamp = System.currentTimeMillis()

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

        if (capturedUiState != null) {
            TeachingSessionManager.recordUiState(capturedUiState)
        }

        val actionType = when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (targetElement?.isEditable == true) null else "CLICK"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "LONG_PRESS"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "INPUT_TEXT"
            else -> null
        }

        if (actionType != null) {
            val inputData = if (actionType == "INPUT_TEXT") {
                targetElement?.text ?: eventText
            } else {
                null
            }

            val selector = SemanticSelector(
                schemaVersion = "1.0",
                role = targetElement?.role ?: className?.substringAfterLast('.'),
                text = targetElement?.text ?: eventText,
                contentDescription = targetElement?.contentDescription ?: eventContentDescription,
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
                inputData = inputData,
                packageName = packageName
            )

            TeachingSessionManager.recordActionEvent(actionEvent)

            val before = previousUiState
            val after = capturedUiState
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

        if (capturedUiState != null) {
            previousUiState = capturedUiState
        }

        val totalTraceEvents = TeachingSessionManager.peekSessionTrace()?.traceEvents?.size ?: 0
        safeLogD(CAPTURE_TAG, "Accepted: type=$eventTypeString, pkg=$packageName, traceCount=$totalTraceEvents")
        return true
    }

    override fun onInterrupt() {
        isRuntimeReady = false
        safeLogW(TAG, "TeachableVoiceAccessibilityService interrupted.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRuntimeReady = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isRuntimeReady = false
        super.onDestroy()
        instance = null
        safeLogI(TAG, "TeachableVoiceAccessibilityService destroyed.")
    }
}
