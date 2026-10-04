package com.chockXlate.teachablevoice.command.matching

import com.chockXlate.teachablevoice.contract.workflow.Workflow
import kotlinx.serialization.Serializable

@Serializable
enum class SkillMatchStatus {
    MATCHED,
    UNKNOWN,
    AMBIGUOUS,
    NEEDS_CLARIFICATION
}

/**
 * Individual skill candidate evaluated during Skill Matching.
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
    val score: Double = 0.0,
    val reasoning: List<String> = emptyList(),
    val provenance: String = "local_skill_store"
)

/**
 * Deterministic result of matching a CommandUnderstandingResult
 * against learned skills in the SkillRepository.
 *
 * Does NOT construct an ExecutionRequest or invoke runtime execution (Phase 4.3+).
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
    val overallConfidence: Double = 1.0,
    val selectedWorkflow: Workflow? = null
)

fun SkillMatchResult.toClarificationRequest(
    executionId: String = java.util.UUID.randomUUID().toString()
): com.chockXlate.teachablevoice.contract.runtime.ClarificationRequest? {
    if (status != SkillMatchStatus.AMBIGUOUS) return null
    val candidateDescs = candidates.map { "${it.intent} (Skill: ${it.skillId})" }
    val q = if (candidates.size == 2) {
        "I found two matching workflows: ${candidates[0].intent} and ${candidates[1].intent}. Which one do you want?"
    } else {
        "Multiple matching workflows were found. Which one do you want?"
    }
    return com.chockXlate.teachablevoice.contract.runtime.ClarificationRequest(
        executionId = executionId,
        reason = "Ambiguous workflow match among ${candidates.size} learned skills.",
        question = q,
        candidateDescriptions = candidateDescs,
        candidates = candidates.mapIndexed { idx, c ->
            com.chockXlate.teachablevoice.contract.runtime.ClarificationCandidate(
                index = idx,
                description = "${c.intent} (Skill: ${c.skillId})"
            )
        },
        uncertaintyType = com.chockXlate.teachablevoice.contract.runtime.UncertaintyType.AMBIGUOUS_WORKFLOW
    )
}

