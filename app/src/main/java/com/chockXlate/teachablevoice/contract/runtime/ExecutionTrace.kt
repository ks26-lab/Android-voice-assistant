package com.chockXlate.teachablevoice.contract.runtime

import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import kotlinx.serialization.Serializable

@Serializable
data class ExecutionTrace(
    val schemaVersion: String = "1.0",
    val executionId: String,
    val skillId: String,
    val startTime: Long,
    val endTime: Long? = null,
    val events: List<TraceEvent> = emptyList(),
    val result: ExecutionResult? = null,
    val progress: ExecutionProgress? = null
)
