package com.chockXlate.teachablevoice.command.matching

import com.chockXlate.teachablevoice.command.interpretation.CommandUnderstandingResult
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.skill.validation.ValidationStatus
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator

/**
 * Generic deterministic Skill Matcher for Phase 11.
 * Matches a [CommandUnderstandingResult] against learned workflows in [LocalSkillRepository].
 *
 * Produces [SkillMatchResult] with status:
 * - MATCHED: Exactly one compatible learned workflow found.
 * - UNKNOWN: No compatible learned workflow found.
 * - AMBIGUOUS: Multiple compatible learned workflows found.
 *
 * Does NOT construct ExecutionRequest or perform runtime execution (Phase 12).
 */
class SkillMatcher(
    private val repository: LocalSkillRepository
) {

    fun match(understandingResult: CommandUnderstandingResult): SkillMatchResult {
        val rawWorkflows = repository.getAllWorkflows()
        val diagnostics = mutableListOf<String>()

        if (rawWorkflows.isEmpty()) {
            diagnostics.add("Skill store is empty. No learned skills available.")
            return SkillMatchResult(
                status = SkillMatchStatus.UNKNOWN,
                commandText = understandingResult.rawCommand,
                intent = understandingResult.intent.canonicalName,
                candidates = emptyList(),
                selectedSkillId = null,
                selectedVersion = null,
                diagnostics = diagnostics,
                overallConfidence = 0.0
            )
        }

        val cmdIntentCanonical = understandingResult.intent.canonicalName.lowercase().trim()
        val cmdIntentName = understandingResult.intent.name.lowercase().trim()

        val candidateMatches = mutableListOf<SkillCandidateMatch>()

        for (workflow in rawWorkflows) {
            // Safety Boundary / Validation Check (Phase 8 & 17)
            val validation = WorkflowValidator.validate(workflow)
            if (validation.status != ValidationStatus.VALID || !validation.isStoreable) {
                diagnostics.add("Workflow '${workflow.skillId}' excluded: Validation status is ${validation.status}")
                continue
            }

            if (workflow.safetyBoundary.requiresExplicitUserConfirmation) {
                // If flagged as requiring confirmation or blocked, verify safety status
                if (validation.diagnostics.any { it.contains("BLOCKED", ignoreCase = true) }) {
                    diagnostics.add("Workflow '${workflow.skillId}' excluded: BLOCKED safety boundary")
                    continue
                }
            }

            val wfIntent = workflow.intent.lowercase().trim()
            val isIntentMatch = (wfIntent == cmdIntentCanonical || wfIntent == cmdIntentName)

            if (!isIntentMatch) {
                diagnostics.add("Workflow '${workflow.skillId}' excluded: Intent mismatch ('$wfIntent' vs '$cmdIntentCanonical')")
                candidateMatches.add(
                    SkillCandidateMatch(
                        skillId = workflow.skillId,
                        version = repository.getSkillVersion(workflow.skillId).coerceAtLeast(1),
                        intent = workflow.intent,
                        isIntentCompatible = false,
                        isConstantCompatible = false,
                        confidence = 0.0,
                        reasoning = listOf("Intent mismatch: stored '${workflow.intent}' vs command '$cmdIntentCanonical'"),
                        provenance = "local_skill_store"
                    )
                )
                continue
            }

            // Intent is compatible -> evaluate slots & constants
            val matchedSlots = mutableListOf<String>()
            val missingSlots = mutableListOf<String>()
            val incompatibleSlots = mutableListOf<String>()
            val reasoning = mutableListOf<String>()
            var isConstantCompatible = true

            reasoning.add("Intent matched: '${workflow.intent}'")

            for (wfSlot in workflow.slots) {
                val cmdSlot = understandingResult.slots.find { it.name.equals(wfSlot.name, ignoreCase = true) }

                // Distinguish CONSTANT vs VARIABLE slot
                val isConstantSlot = (!wfSlot.required) ||
                        wfSlot.provenance.equals("constant", ignoreCase = true) ||
                        (wfSlot.exampleValue != null && !wfSlot.exampleValue.startsWith("\${") && !wfSlot.required)

                if (cmdSlot != null) {
                    val cmdVal = cmdSlot.typedValue.ifBlank { cmdSlot.rawValue }

                    if (isConstantSlot) {
                        val expectedConst = wfSlot.exampleValue
                        if (expectedConst != null &&
                            !expectedConst.equals(cmdVal, ignoreCase = true) &&
                            !expectedConst.equals(cmdSlot.rawValue, ignoreCase = true)
                        ) {
                            isConstantCompatible = false
                            incompatibleSlots.add(wfSlot.name)
                            reasoning.add("Constant slot '${wfSlot.name}' mismatch: expected '$expectedConst', command provided '$cmdVal'")
                        } else {
                            matchedSlots.add(wfSlot.name)
                            reasoning.add("Constant slot '${wfSlot.name}' matched value '$cmdVal'")
                        }
                    } else {
                        // Variable slot
                        matchedSlots.add(wfSlot.name)
                        reasoning.add("Variable slot '${wfSlot.name}' matched command value '$cmdVal'")
                    }
                } else {
                    // Command did not provide a value for this slot
                    if (isConstantSlot) {
                        // Fixed constant workflow value is retained
                        reasoning.add("Constant slot '${wfSlot.name}' default '${wfSlot.exampleValue}' retained (command unspecified)")
                    } else {
                        // Missing variable slot
                        missingSlots.add(wfSlot.name)
                        reasoning.add("Missing command value for variable slot '${wfSlot.name}'")
                    }
                }
            }

            val confidence = if (isConstantCompatible) {
                if (missingSlots.isEmpty()) 1.0 else 0.85
            } else {
                0.0
            }

            candidateMatches.add(
                SkillCandidateMatch(
                    skillId = workflow.skillId,
                    version = repository.getSkillVersion(workflow.skillId).coerceAtLeast(1),
                    intent = workflow.intent,
                    isIntentCompatible = true,
                    isConstantCompatible = isConstantCompatible,
                    matchedSlots = matchedSlots,
                    missingSlots = missingSlots,
                    incompatibleSlots = incompatibleSlots,
                    confidence = confidence,
                    reasoning = reasoning,
                    provenance = "local_skill_store"
                )
            )
        }

        // Filter compatible candidates (deterministic selection)
        val compatibleCandidates = candidateMatches
            .filter { it.isIntentCompatible && it.isConstantCompatible }
            .sortedWith(compareBy<SkillCandidateMatch> { it.skillId }.thenBy { it.version })

        val status: SkillMatchStatus
        val selectedSkillId: String?
        val selectedVersion: Int?
        val overallConfidence: Double

        when {
            compatibleCandidates.isEmpty() -> {
                status = SkillMatchStatus.UNKNOWN
                selectedSkillId = null
                selectedVersion = null
                overallConfidence = 0.0
                diagnostics.add("No compatible learned skill found for command intent '${understandingResult.intent.canonicalName}'")
            }
            compatibleCandidates.size == 1 -> {
                status = SkillMatchStatus.MATCHED
                selectedSkillId = compatibleCandidates[0].skillId
                selectedVersion = compatibleCandidates[0].version
                overallConfidence = compatibleCandidates[0].confidence
                diagnostics.add("Single compatible skill matched: $selectedSkillId (v$selectedVersion)")
            }
            else -> {
                status = SkillMatchStatus.AMBIGUOUS
                selectedSkillId = null
                selectedVersion = null
                overallConfidence = 0.5
                diagnostics.add("Multiple compatible learned skills found (${compatibleCandidates.size} candidates). Ambiguity unresolved.")
            }
        }

        // Return candidate list sorted deterministically
        val sortedAllCandidates = candidateMatches.sortedWith(
            compareBy<SkillCandidateMatch> { it.skillId }.thenBy { it.version }
        )

        return SkillMatchResult(
            schemaVersion = "1.0",
            status = status,
            commandText = understandingResult.rawCommand,
            intent = understandingResult.intent.canonicalName,
            candidates = sortedAllCandidates,
            selectedSkillId = selectedSkillId,
            selectedVersion = selectedVersion,
            diagnostics = diagnostics,
            overallConfidence = overallConfidence
        )
    }
}
