package com.chockXlate.teachablevoice.learning.targets

import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import kotlinx.serialization.Serializable

/**
 * Platform-agnostic, non-coordinate based semantic representation of a UI target.
 */
@Serializable
data class SemanticTarget(
    val schemaVersion: String = "1.0",
    val role: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val packageName: String? = null,
    val parentRole: String? = null,
    val ancestorRole: String? = null,
    val isClickable: Boolean? = null,
    val isEditable: Boolean? = null,
    val isCheckable: Boolean? = null,
    val isChecked: Boolean? = null,
    val isSelected: Boolean? = null,
    val isEnabled: Boolean? = null,
    val targetConfidence: Double = 1.0
)

/**
 * Normalizer utility for constructing clean, canonical SemanticTargets from UI evidence.
 */
object SemanticTargetNormalizer {

    fun fromSelector(selector: SemanticSelector?, packageName: String? = null): SemanticTarget? {
        if (selector == null) return null

        val role = selector.role?.trim()?.ifBlank { null }
        val text = selector.text?.trim()?.ifBlank { null }
        val contentDesc = selector.contentDescription?.trim()?.ifBlank { null }
        val resId = selector.resourceId?.trim()?.ifBlank { null }
        val pkg = packageName?.trim()?.ifBlank { null }
        val parentRole = selector.parentRole?.trim()?.ifBlank { null }
        val ancestorRole = selector.ancestorRole?.trim()?.ifBlank { null }

        if (role == null && text == null && contentDesc == null && resId == null) {
            return null
        }

        // Deterministic target confidence evaluation
        val evidenceCount = listOfNotNull(role, text, contentDesc, resId).size
        val confidence = when {
            resId != null || (text != null && role != null) -> 1.0
            evidenceCount >= 2 -> 0.85
            evidenceCount == 1 -> 0.60
            else -> 0.40
        }

        return SemanticTarget(
            schemaVersion = "1.0",
            role = role,
            text = text,
            contentDescription = contentDesc,
            resourceId = resId,
            packageName = pkg,
            parentRole = parentRole,
            ancestorRole = ancestorRole,
            targetConfidence = confidence
        )
    }

    fun fromUiElement(element: UiElement?, packageName: String? = null): SemanticTarget? {
        if (element == null) return null

        val role = element.role.trim().ifBlank { null }
        val text = element.text?.trim()?.ifBlank { null }
        val contentDesc = element.contentDescription?.trim()?.ifBlank { null }
        val resId = element.resourceId?.trim()?.ifBlank { null }
        val pkg = packageName?.trim()?.ifBlank { null }

        val confidence = when {
            resId != null || (text != null && role != null) -> 1.0
            else -> 0.80
        }

        return SemanticTarget(
            schemaVersion = "1.0",
            role = role,
            text = text,
            contentDescription = contentDesc,
            resourceId = resId,
            packageName = pkg,
            parentRole = element.parentRole?.trim()?.ifBlank { null },
            ancestorRole = element.ancestorRole?.trim()?.ifBlank { null },
            isClickable = element.isClickable,
            isEditable = element.isEditable,
            isCheckable = element.isCheckable,
            isChecked = element.isChecked,
            isSelected = element.isSelected,
            isEnabled = element.isEnabled,
            targetConfidence = confidence
        )
    }
}
