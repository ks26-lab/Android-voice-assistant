package com.chockXlate.teachablevoice.command.matching

import kotlinx.serialization.Serializable

@Serializable
enum class SkillMatchStatus {
    MATCHED,
    UNKNOWN,
    AMBIGUOUS
}

/**
 * Individual skill candidate evaluated during Phase 11 Skill Matching.
 */
@Serializable
data class SkillCandidateMatch(
    val skillId: String,
    val version: Int = 1,
    val intent: String,
    val isIntentCompatible: Boolean,
    val isConstantCompatible: Boolean = true,
    val matchedSlots: List<String> = emptyList(),
    val missingSlots: List<String> = emptyList(),
    val incompatibleSlots: List<String> = emptyList(),
    val confidence: Double = 1.0,
    val reasoning: List<String> = emptyList(),
    val provenance: String = "local_skill_store"
)

/**
 * Deterministic result of matching a Phase 10 CommandUnderstandingResult
 * against learned skills in LocalSkillRepository.
 *
 * Does NOT construct an ExecutionRequest or invoke runtime execution (Phase 12).
 */
@Serializable
data class SkillMatchResult(
    val schemaVersion: String = "1.0",
    val status: SkillMatchStatus,
    val commandText: String,
    val intent: String,
    val candidates: List<SkillCandidateMatch> = emptyList(),
    val selectedSkillId: String? = null,
    val selectedVersion: Int? = null,
    val diagnostics: List<String> = emptyList(),
    val overallConfidence: Double = 1.0
)
