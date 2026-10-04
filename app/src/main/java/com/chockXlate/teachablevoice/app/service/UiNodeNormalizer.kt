package com.chockXlate.teachablevoice.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiElementBounds
import com.chockXlate.teachablevoice.contract.ui.UiState
import java.util.UUID

/**
 * Abstraction over Android AccessibilityNodeInfo to allow safe testing,
 * lifecycle handling, and decoupling from framework stubs.
 */
interface AccessibilityNodeSource {
    val className: CharSequence?
    val text: CharSequence?
    val contentDescription: CharSequence?
    val viewIdResourceName: String?
    val isClickable: Boolean
    val isEditable: Boolean
    val isCheckable: Boolean
    val isChecked: Boolean
    val isSelected: Boolean
    val isScrollable: Boolean
    val isEnabled: Boolean
    val isVisibleToUser: Boolean
    val isFocused: Boolean
    val isPassword: Boolean
    val inputType: Int
    val childCount: Int
    val bounds: UiElementBounds get() = UiElementBounds(0, 0, 0, 0)
    val packageName: CharSequence? get() = null
    fun getChild(index: Int): AccessibilityNodeSource?
    fun recycle() {}
}

/**
 * Adapter wrapping real Android framework AccessibilityNodeInfo.
 */
class RealAccessibilityNode(val node: AccessibilityNodeInfo) : AccessibilityNodeSource {
    override val className: CharSequence? get() = try { node.className } catch (_: Throwable) { null }
    override val text: CharSequence? get() = try { node.text } catch (_: Throwable) { null }
    override val contentDescription: CharSequence? get() = try { node.contentDescription } catch (_: Throwable) { null }
    override val viewIdResourceName: String? get() = try { node.viewIdResourceName } catch (_: Throwable) { null }
    override val packageName: CharSequence? get() = try { node.packageName } catch (_: Throwable) { null }
    override val isClickable: Boolean get() = try { node.isClickable } catch (_: Throwable) { false }
    override val isEditable: Boolean get() = try { node.isEditable } catch (_: Throwable) { false }
    override val isCheckable: Boolean get() = try { node.isCheckable } catch (_: Throwable) { false }
    override val isChecked: Boolean get() = try { node.isChecked } catch (_: Throwable) { false }
    override val isSelected: Boolean get() = try { node.isSelected } catch (_: Throwable) { false }
    override val isScrollable: Boolean get() = try { node.isScrollable } catch (_: Throwable) { false }
    override val isEnabled: Boolean get() = try { node.isEnabled } catch (_: Throwable) { true }
    override val isVisibleToUser: Boolean get() = try { node.isVisibleToUser } catch (_: Throwable) { true }
    override val isFocused: Boolean get() = try { node.isFocused } catch (_: Throwable) { false }
    override val isPassword: Boolean get() = try { node.isPassword } catch (_: Throwable) { false }
    override val inputType: Int get() = try { node.inputType } catch (_: Throwable) { 0 }
    override val childCount: Int get() = try { node.childCount } catch (_: Throwable) { 0 }

    override val bounds: UiElementBounds
        get() {
            return try {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                UiElementBounds(rect.left, rect.top, rect.right, rect.bottom)
            } catch (_: Throwable) {
                UiElementBounds(0, 0, 0, 0)
            }
        }

    override fun getChild(index: Int): AccessibilityNodeSource? = try {
        node.getChild(index)?.let { RealAccessibilityNode(it) }
    } catch (_: Throwable) {
        null
    }

    override fun recycle() {
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: Throwable) {}
    }
}

/**
 * In-memory test double representing an Accessibility node for unit testing.
 */
class MockAccessibilityNode(
    override val className: CharSequence? = "android.widget.Button",
    override val text: CharSequence? = null,
    override val contentDescription: CharSequence? = null,
    override val viewIdResourceName: String? = null,
    override val isClickable: Boolean = false,
    override val isEditable: Boolean = false,
    override val isCheckable: Boolean = false,
    override val isChecked: Boolean = false,
    override val isSelected: Boolean = false,
    override val isScrollable: Boolean = false,
    override val isEnabled: Boolean = true,
    override val isVisibleToUser: Boolean = true,
    override val isFocused: Boolean = false,
    override val isPassword: Boolean = false,
    override val inputType: Int = 0,
    override val bounds: UiElementBounds = UiElementBounds(0, 0, 100, 100),
    override val packageName: CharSequence? = null,
    val childrenList: List<AccessibilityNodeSource> = emptyList()
) : AccessibilityNodeSource {
    override val childCount: Int get() = childrenList.size
    override fun getChild(index: Int): AccessibilityNodeSource? = childrenList.getOrNull(index)
}

/**
 * Normalizes live Android AccessibilityNodeInfo objects into platform-agnostic,
 * serializable UiElement and UiState contracts.
 */
object UiNodeNormalizer {

    private const val MAX_DEPTH = 64
    private const val MAX_NODES = 2000

    /**
     * Converts a root AccessibilityNodeInfo into a full UiState snapshot.
     */
    fun normalizeState(
        rootNode: AccessibilityNodeInfo?,
        appContext: String,
        windowId: Int = 0,
        sequenceNumber: Long = 0L
    ): UiState {
        return normalizeState(rootNode?.let { RealAccessibilityNode(it) }, appContext, windowId, sequenceNumber)
    }

    /**
     * Converts a root AccessibilityNodeSource into a full UiState snapshot.
     */
    fun normalizeState(
        rootSource: AccessibilityNodeSource?,
        appContext: String,
        windowId: Int = 0,
        sequenceNumber: Long = 0L
    ): UiState {
        if (rootSource == null) {
            return UiState(
                schemaVersion = "1.0",
                stateId = "state_unavailable",
                timestamp = System.currentTimeMillis(),
                appContext = appContext,
                windowId = windowId,
                rootElement = null,
                allElements = emptyList(),
                sequenceNumber = sequenceNumber
            )
        }

        val allElements = mutableListOf<UiElement>()
        var hasSensitiveField = false
        val visitedNodesCount = intArrayOf(0)

        val rootElement = try {
            normalizeNodeInternal(
                node = rootSource,
                path = "0",
                parentRole = null,
                ancestorRole = null,
                depth = 0,
                visitedCount = visitedNodesCount,
                allElementsList = allElements,
                onSensitiveDetected = { hasSensitiveField = true }
            )
        } catch (_: Exception) {
            null
        }

        val timestamp = System.currentTimeMillis()
        val deterministicStateId = computeStateSignature(appContext, windowId, allElements)

        return UiState(
            schemaVersion = "1.0",
            stateId = deterministicStateId,
            timestamp = timestamp,
            appContext = appContext,
            windowId = windowId,
            rootElement = rootElement,
            allElements = allElements,
            sequenceNumber = sequenceNumber,
            isSensitiveContext = hasSensitiveField
        )
    }

    /**
     * Recursively normalizes an AccessibilityNodeInfo into a UiElement hierarchy.
     */
    fun normalizeNode(
        node: AccessibilityNodeInfo,
        parentRole: String? = null,
        ancestorRole: String? = null,
        allElementsList: MutableList<UiElement>? = null
    ): UiElement {
        return normalizeNode(RealAccessibilityNode(node), parentRole, ancestorRole, allElementsList)
    }

    /**
     * Recursively normalizes an AccessibilityNodeSource into a UiElement hierarchy.
     */
    fun normalizeNode(
        node: AccessibilityNodeSource,
        parentRole: String? = null,
        ancestorRole: String? = null,
        allElementsList: MutableList<UiElement>? = null
    ): UiElement {
        return normalizeNodeInternal(
            node = node,
            path = "0",
            parentRole = parentRole,
            ancestorRole = ancestorRole,
            depth = 0,
            visitedCount = intArrayOf(0),
            allElementsList = allElementsList,
            onSensitiveDetected = {}
        )
    }

    private fun normalizeNodeInternal(
        node: AccessibilityNodeSource,
        path: String,
        parentRole: String?,
        ancestorRole: String?,
        depth: Int,
        visitedCount: IntArray,
        allElementsList: MutableList<UiElement>?,
        onSensitiveDetected: () -> Unit
    ): UiElement {
        visitedCount[0]++
        val className = node.className?.toString() ?: "android.view.View"
        val simpleRole = className.substringAfterLast('.')
        val elementId = "elem_${path}_${simpleRole}_${node.viewIdResourceName?.substringAfterLast('/') ?: "node"}"

        val protected = TeachingPrivacyGuard.isSensitiveCredentialTarget(node)
        if (protected) {
            onSensitiveDetected()
        }

        val text = if (protected) null else node.text?.toString()
        val contentDescription = if (protected) null else node.contentDescription?.toString()
        val resourceId = node.viewIdResourceName

        val bounds = node.bounds

        val children = mutableListOf<UiElement>()
        val currentAncestorRole = parentRole ?: ancestorRole

        if (depth < MAX_DEPTH && visitedCount[0] < MAX_NODES) {
            for (i in 0 until node.childCount) {
                val childNode = node.getChild(i)
                if (childNode != null) {
                    try {
                        val childElement = normalizeNodeInternal(
                            node = childNode,
                            path = "${path}_$i",
                            parentRole = simpleRole,
                            ancestorRole = currentAncestorRole,
                            depth = depth + 1,
                            visitedCount = visitedCount,
                            allElementsList = allElementsList,
                            onSensitiveDetected = onSensitiveDetected
                        )
                        children.add(childElement)
                    } finally {
                        childNode.recycle()
                    }
                }
            }
        }

        val element = UiElement(
            schemaVersion = "1.0",
            elementId = elementId,
            role = simpleRole,
            text = text,
            textSlot = null,
            contentDescription = contentDescription,
            resourceId = resourceId,
            parentRole = parentRole,
            ancestorRole = ancestorRole,
            nearbyText = null,
            relativePosition = null,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isSelected = node.isSelected,
            isScrollable = node.isScrollable,
            isEnabled = node.isEnabled,
            bounds = bounds,
            children = children,
            isVisible = node.isVisibleToUser,
            isFocused = node.isFocused,
            isSensitive = protected,
            className = className,
            packageName = node.packageName?.toString()
        )

        allElementsList?.add(element)
        return element
    }

    /**
     * Deterministic, timestamp-independent state signature for equivalent UI states.
     */
    fun computeStateSignature(
        appContext: String,
        windowId: Int,
        elements: List<UiElement>
    ): String {
        val builder = java.lang.StringBuilder()
        builder.append(appContext).append(':').append(windowId).append(';')
        for (el in elements) {
            builder.append(el.role).append(',')
            builder.append(el.resourceId.orEmpty()).append(',')
            builder.append(el.text?.trim().orEmpty()).append(',')
            builder.append(el.contentDescription?.trim().orEmpty()).append(',')
            builder.append(el.isClickable).append(',')
            builder.append(el.isEditable).append(',')
            builder.append(el.isEnabled).append(',')
            builder.append(el.isChecked).append(',')
            builder.append(el.isSelected).append(',')
            builder.append(el.isScrollable).append(';')
        }
        val hash = builder.toString().hashCode()
        val posHash = if (hash < 0) -hash else hash
        return "state_${appContext.substringAfterLast('.').takeLast(10)}_${posHash.toString(16)}"
    }
}

