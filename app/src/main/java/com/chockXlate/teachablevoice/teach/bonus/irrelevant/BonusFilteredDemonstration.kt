package com.chockXlate.teachablevoice.teach.bonus.irrelevant

import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import kotlinx.serialization.Serializable

/**
 * Isolated output contract representing a filtered demonstration trace.
 * Maintains schemaVersion = "1.0" without altering existing core DemonstrationTrace schema.
 */
@Serializable
data class BonusFilteredDemonstration(
    val schemaVersion: String = "1.0",
    val traceId: String,
    val sessionId: String,
    val originalEventCount: Int,
    val retainedEventCount: Int,
    val removedEventCount: Int,
    val uncertainEventCount: Int,
    val filteredTrace: DemonstrationTrace,
    val removedEvents: List<TraceEvent>,
    val relevanceDecisions: List<RelevanceEvidence>,
    val filterSummary: String
)
