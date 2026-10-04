package com.chockXlate.teachablevoice.teach.bonus.integration

/**
 * Feature flags for enabling/disabling bonus modules independently.
 */
data class BonusFeaturePolicy(
    val irrelevantFilteringEnabled: Boolean = true,
    val crossAppGeneralizationEnabled: Boolean = true,
    val midFlowClarificationEnabled: Boolean = true
)
