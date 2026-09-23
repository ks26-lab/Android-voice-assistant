package com.chockXlate.teachablevoice.contract.ui

import kotlinx.serialization.Serializable

@Serializable
data class UiElementBounds(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

@Serializable
data class UiElement(
    val schemaVersion: String = "1.0",
    val elementId: String,
    val role: String,
    val text: String? = null,
    val textSlot: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val parentRole: String? = null,
    val ancestorRole: String? = null,
    val nearbyText: String? = null,
    val relativePosition: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isSelected: Boolean = false,
    val isScrollable: Boolean = false,
    val isEnabled: Boolean = true,
    val bounds: UiElementBounds? = null, // Execution-only bounds; NOT used as workflow identity
    val children: List<UiElement> = emptyList()
)
