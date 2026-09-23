package com.chockXlate.teachablevoice.teach.normalization

import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace

/**
 * Diagnostic result container returned by DemonstrationTraceNormalizer.
 */
data class TraceNormalizationResult(
    val normalizedTrace: DemonstrationTrace,
    val rawEventCount: Int,
    val normalizedEventCount: Int,
    val removedDuplicateCount: Int,
    val warningCount: Int,
    val warnings: List<String> = emptyList(),
    val status: String = "NORMALIZED"
)
