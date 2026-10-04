package com.chockXlate.teachablevoice.teach.bonus.clarification

/**
 * Encapsulates the user's response to a clarification request.
 */
data class ClarificationResponse(
    val requestId: String,
    val rawInput: String,
    val isCancelRequest: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
