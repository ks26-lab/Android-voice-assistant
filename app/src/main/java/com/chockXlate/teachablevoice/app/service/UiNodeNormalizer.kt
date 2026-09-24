package com.chockXlate.teachablevoice.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiElementBounds
import com.chockXlate.teachablevoice.contract.ui.UiState
import java.util.UUID

/**
 * Normalizes live Android AccessibilityNodeInfo objects into platform-agnostic,
 * serializable UiElement and UiState contracts.
 */
object UiNodeNormalizer {

    /**
     * Converts a root AccessibilityNodeInfo into a full UiState snapshot.
     */
    fun normalizeState(
        rootNode: AccessibilityNodeInfo?,
        appContext: String,
        windowId: Int = 0
    ): UiState {
        val allElements = mutableListOf<UiElement>()
        val rootElement = rootNode?.let { normalizeNode(it, null, null, allElements) }

        return UiState(
            schemaVersion = "1.0",
            stateId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            appContext = appContext,
            windowId = windowId,
            rootElement = rootElement,
            allElements = allElements
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
        val elementId = UUID.randomUUID().toString()
        val className = node.className?.toString() ?: "android.view.View"
        val simpleRole = className.substringAfterLast('.')

        val protected = TeachingPrivacyGuard.protectedField(node)
        val text = if (protected) null else node.text?.toString()
        val contentDescription = if (protected) null else node.contentDescription?.toString()
        val resourceId = node.viewIdResourceName

        val boundsRect = Rect()
        node.getBoundsInScreen(boundsRect)
        val bounds = UiElementBounds(
            left = boundsRect.left,
            top = boundsRect.top,
            right = boundsRect.right,
            bottom = boundsRect.bottom
        )

        val children = mutableListOf<UiElement>()
        val currentAncestorRole = parentRole ?: ancestorRole

        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            if (childNode != null) {
                try {
                val childElement = normalizeNode(
                    node = childNode,
                    parentRole = simpleRole,
                    ancestorRole = currentAncestorRole,
                    allElementsList = allElementsList
                )
                children.add(childElement)
                } finally { childNode.recycle() }
            }
        }

        val element = UiElement(
            schemaVersion = "1.0",
            elementId = elementId,
            role = simpleRole,
            text = text,
            textSlot = null, // Will be set during constant/variable inference
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
            children = children
        )

        allElementsList?.add(element)
        return element
    }
}
