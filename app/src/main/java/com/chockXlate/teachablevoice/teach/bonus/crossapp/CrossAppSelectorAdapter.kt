package com.chockXlate.teachablevoice.teach.bonus.crossapp

import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector

/**
 * Adapter utility for converting/adapting source selectors to target app expectations.
 */
class CrossAppSelectorAdapter {

    fun adaptSelector(
        sourceSelector: SemanticSelector,
        targetPackage: String,
        matchedElement: UiElement?
    ): SemanticSelector {
        if (matchedElement == null) {
            // Strip package-specific resourceId while retaining pure semantic attributes
            val cleanResId = sourceSelector.resourceId?.takeIf { !it.contains(":") }
            return sourceSelector.copy(resourceId = cleanResId)
        }

        return SemanticSelector(
            schemaVersion = "1.0",
            role = matchedElement.role.ifBlank { sourceSelector.role },
            text = matchedElement.text ?: sourceSelector.text,
            textSlot = sourceSelector.textSlot,
            contentDescription = matchedElement.contentDescription ?: sourceSelector.contentDescription,
            resourceId = matchedElement.resourceId, // Adapted target resource ID
            parentRole = matchedElement.parentRole ?: sourceSelector.parentRole,
            ancestorRole = matchedElement.ancestorRole ?: sourceSelector.ancestorRole,
            nearbyText = matchedElement.nearbyText ?: sourceSelector.nearbyText,
            relativePosition = matchedElement.relativePosition ?: sourceSelector.relativePosition
        )
    }
}
