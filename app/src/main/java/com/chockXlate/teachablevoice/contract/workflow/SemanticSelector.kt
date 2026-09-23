package com.chockXlate.teachablevoice.contract.workflow

import kotlinx.serialization.Serializable

@Serializable
data class SemanticSelector(
    val schemaVersion: String = "1.0",
    val role: String? = null,
    val text: String? = null,
    val textSlot: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val parentRole: String? = null,
    val ancestorRole: String? = null,
    val nearbyText: String? = null,
    val relativePosition: String? = null
)
