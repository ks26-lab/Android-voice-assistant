package com.chockXlate.teachablevoice.teach.bonus.irrelevant

import kotlinx.serialization.Serializable

/**
 * Categorical classification decision for a captured demonstration event.
 */
@Serializable
enum class RelevanceDecision {
    /** Event contributes to the task intent, state transition, or input workflow. */
    RELEVANT,

    /** Event is established with high confidence to be noise, accidental tap, or transient focus. */
    IRRELEVANT,

    /** Evidence is ambiguous or weak; preserved conservatively to protect workflow integrity. */
    UNCERTAIN
}
