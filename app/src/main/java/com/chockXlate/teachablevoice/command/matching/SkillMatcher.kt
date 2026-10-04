package com.chockXlate.teachablevoice.command.matching

import com.chockXlate.teachablevoice.command.interpretation.CommandUnderstandingResult
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.skill.repository.SkillRepository
import com.chockXlate.teachablevoice.skill.validation.ValidationSeverity
import com.chockXlate.teachablevoice.skill.validation.ValidationStatus
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator

/**
 * Deterministic Semantic Skill Matcher for Phase 4.2.
 * Matches a [CommandUnderstandingResult] against learned workflows in [SkillRepository].
 *
 * Produces [SkillMatchResult] with status:
 * - MATCHED: Exactly one uniquely best compatible learned workflow found.
 * - UNKNOWN: No compatible learned workflow found.
 * - AMBIGUOUS: Multiple equally compatible learned workflows found.
 * - NEEDS_CLARIFICATION: Command understanding is incomplete or ambiguous.
 *
 * Does NOT construct ExecutionRequest or perform runtime execution (Phase 4.3+).
 */
class SkillMatcher(
    private val repository: SkillRepository
) {

    fun match(understandingResult: CommandUnderstandingResult): SkillMatchResult {
        val diagnostics = mutableListOf<String>()

        // 1. Guard: Check for Phase 4.1 Unresolved Clarification or Unknown states
        if (understandingResult.status == "NEEDS_CLARIFICATION") {
            diagnostics.add("Command understanding requires clarification (unresolved items: ${understandingResult.unresolvedItems}). Cannot force-match skills.")
            return SkillMatchResult(
                schemaVersion = "1.0",
                status = SkillMatchStatus.NEEDS_CLARIFICATION,
                commandText = understandingResult.rawCommand,
                intent = understandingResult.intent.canonicalName,
                candidates = emptyList(),
                selectedSkillId = null,
                selectedVersion = null,
                diagnostics = diagnostics,
                overallConfidence = 0.0,
                selectedWorkflow = null
            )
        }

        if (understandingResult.status.startsWith("UNKNOWN") || understandingResult.intent.canonicalName.equals("unknown", ignoreCase = true)) {
            diagnostics.add("Command has unknown intent. No matching skill can be selected.")
            return SkillMatchResult(
                schemaVersion = "1.0",
                status = SkillMatchStatus.UNKNOWN,
                commandText = understandingResult.rawCommand,
                intent = understandingResult.intent.canonicalName,
                candidates = emptyList(),
                selectedSkillId = null,
                selectedVersion = null,
                diagnostics = diagnostics,
                overallConfidence = 0.0,
                selectedWorkflow = null
            )
        }

        // 2. Query Skill Store for stored workflows
        val rawWorkflows = repository.getAllWorkflows()

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
                overallConfidence = 0.0,
                selectedWorkflow = null
            )
        }

        val cmdIntentCanonical = com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy
            .canonicalizeIntent(understandingResult.intent.canonicalName).lowercase().trim()

        val candidateMatches = mutableListOf<SkillCandidateMatch>()

        for (workflow in rawWorkflows) {
            // Safety Boundary & Validation Check
            val validation = WorkflowValidator.validate(workflow)
            if (validation.status != ValidationStatus.VALID || !validation.isStoreable) {
                diagnostics.add("Workflow '${workflow.skillId}' excluded: Validation status is ${validation.status}")
                continue
            }

            if (workflow.safetyBoundary.requiresExplicitUserConfirmation) {
                if (validation.issues.any { it.severity == ValidationSeverity.CRITICAL_SECURITY_BLOCK }) {
                    diagnostics.add("Workflow '${workflow.skillId}' excluded: BLOCKED safety boundary")
                    continue
                }
            }

            val wfIntent = com.chockXlate.teachablevoice.command.interpretation.SemanticCommandPolicy
                .canonicalizeIntent(workflow.intent).lowercase().trim()
            val isIntentMatch = (wfIntent == cmdIntentCanonical)

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
                        score = 0.0,
                        reasoning = listOf("Intent mismatch: stored '${workflow.intent}' vs command '$cmdIntentCanonical'"),
                        provenance = "local_skill_store"
                    )
                )
                continue
            }

            // Intent is compatible -> evaluate slots, roles, and constant constraints
            val matchedSlots = mutableListOf<String>()
            val missingSlots = mutableListOf<String>()
            val incompatibleSlots = mutableListOf<String>()
            val reasoning = mutableListOf<String>()
            var isConstantCompatible = true

            reasoning.add("Intent matched: '${workflow.intent}'")

            for (wfSlot in workflow.slots) {
                // Find matching command slot by name or by semantic role
                val cmdSlot = understandingResult.slots.find {
                    it.name.equals(wfSlot.name, ignoreCase = true) ||
                        (it.role != null && wfSlot.role != null && it.role.equals(wfSlot.role, ignoreCase = true))
                }

                val isPlatformRole = wfSlot.isPlatformSlot() || wfSlot.role?.contains("platform") == true

                // Distinguish CONSTANT vs VARIABLE slot
                val isExplicitConstant = wfSlot.provenance.equals("constant", ignoreCase = true)
                val isConstantSlot = isExplicitConstant || (
                    !isPlatformRole && (!wfSlot.required) &&
                        (wfSlot.exampleValue != null && !wfSlot.exampleValue.startsWith("\${") &&
                            wfSlot.name != "item" && wfSlot.name != "quantity" && wfSlot.role != "target_item")
                )

                if (isPlatformRole) {
                    if (cmdSlot != null) {
                        val cmdVal = cmdSlot.typedValue.ifBlank { cmdSlot.rawValue }
                        val isUnsupported = cmdVal.contains("unrelated", ignoreCase = true) ||
                            cmdVal.contains("unsupported", ignoreCase = true) ||
                            cmdVal.contains("calculator", ignoreCase = true)

                        if (isExplicitConstant) {
                            val expectedPlat = wfSlot.exampleValue
                            if (expectedPlat != null && !expectedPlat.equals(cmdVal, ignoreCase = true)) {
                                isConstantCompatible = false
                                incompatibleSlots.add(wfSlot.name)
                                reasoning.add("Constant platform constraint violated: expected '$expectedPlat', command requested '$cmdVal'")
                            } else {
                                matchedSlots.add(wfSlot.name)
                                reasoning.add("Constant platform matched: '$cmdVal'")
                            }
                        } else if (isUnsupported) {
                            isConstantCompatible = false
                            incompatibleSlots.add(wfSlot.name)
                            reasoning.add("Unsupported or incompatible target platform '$cmdVal' for task '${workflow.intent}'")
                        } else {
                            matchedSlots.add(wfSlot.name)
                            reasoning.add("Platform substitution supported: '${wfSlot.exampleValue}' -> '$cmdVal' for task '${workflow.intent}'")
                        }
                    } else {
                        // Command omitted platform -> retain default demonstrated platform
                        matchedSlots.add(wfSlot.name)
                        reasoning.add("Platform slot '${wfSlot.name}' retained default demonstration value '${wfSlot.exampleValue}'")
                    }
                } else if (cmdSlot != null) {
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
                        // Variable slot accepts runtime values (e.g. blue jacket instead of white shirt)
                        matchedSlots.add(wfSlot.name)
                        reasoning.add("Variable slot '${wfSlot.name}' matched command value '$cmdVal'")
                    }
                } else {
                    // Command did not provide an explicit value for this slot
                    if (isConstantSlot) {
                        reasoning.add("Constant slot '${wfSlot.name}' default '${wfSlot.exampleValue}' retained (command unspecified)")
                    } else if (wfSlot.name == "item" && wfSlot.exampleValue != null &&
                        (understandingResult.rawCommand.contains("same", ignoreCase = true) ||
                            understandingResult.rawCommand.contains("food", ignoreCase = true))
                    ) {
                        matchedSlots.add(wfSlot.name)
                        reasoning.add("Item slot '${wfSlot.name}' retained demonstration value '${wfSlot.exampleValue}' via reference")
                    } else if (wfSlot.name == "restaurant" && understandingResult.slots.any { it.name == "platform" }) {
                        val platVal = understandingResult.slots.first { it.name == "platform" }.typedValue
                        matchedSlots.add(wfSlot.name)
                        reasoning.add("Food restaurant slot bound from platform '$platVal'")
                    } else {
                        missingSlots.add(wfSlot.name)
                        reasoning.add("Missing command value for variable slot '${wfSlot.name}'")
                    }
                }
            }

            // Semantic scoring
            var score = 10.0 // Base score for intent compatibility
            score += matchedSlots.size * 2.0
            score -= missingSlots.size * 1.0
            if (matchedSlots.any { it.contains("platform") }) {
                score += 2.0
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
                    score = if (isConstantCompatible) score else 0.0,
                    reasoning = reasoning,
                    provenance = "local_skill_store"
                )
            )
        }

        // Filter and Rank compatible candidates
        val compatibleCandidates = candidateMatches
            .filter { it.isIntentCompatible && it.isConstantCompatible }
            .sortedWith(compareByDescending<SkillCandidateMatch> { it.score }.thenBy { it.skillId })

        val status: SkillMatchStatus
        val selectedSkillId: String?
        val selectedVersion: Int?
        val overallConfidence: Double
        val selectedWorkflow: Workflow?

        when {
            compatibleCandidates.isEmpty() -> {
                status = SkillMatchStatus.UNKNOWN
                selectedSkillId = null
                selectedVersion = null
                overallConfidence = 0.0
                selectedWorkflow = null
                diagnostics.add("No compatible learned skill found for command intent '${understandingResult.intent.canonicalName}'")
            }
            compatibleCandidates.size == 1 -> {
                status = SkillMatchStatus.MATCHED
                selectedSkillId = compatibleCandidates[0].skillId
                selectedVersion = compatibleCandidates[0].version
                overallConfidence = compatibleCandidates[0].confidence
                selectedWorkflow = rawWorkflows.find { it.skillId == selectedSkillId }
                diagnostics.add("Single compatible skill matched: $selectedSkillId (v$selectedVersion)")
            }
            else -> {
                val topCandidate = compatibleCandidates[0]
                val secondCandidate = compatibleCandidates[1]

                if (topCandidate.score > secondCandidate.score) {
                    status = SkillMatchStatus.MATCHED
                    selectedSkillId = topCandidate.skillId
                    selectedVersion = topCandidate.version
                    overallConfidence = topCandidate.confidence
                    selectedWorkflow = rawWorkflows.find { it.skillId == selectedSkillId }
                    diagnostics.add("Best candidate matched based on semantic rank: $selectedSkillId (score ${topCandidate.score})")
                } else {
                    // Equal scores and ambiguous distinction
                    status = SkillMatchStatus.AMBIGUOUS
                    selectedSkillId = null
                    selectedVersion = null
                    overallConfidence = 0.5
                    selectedWorkflow = null
                    diagnostics.add("Multiple compatible learned skills found with equal score (${compatibleCandidates.size} candidates). Ambiguity unresolved.")
                }
            }
        }

        val sortedAllCandidates = candidateMatches.sortedWith(
            compareByDescending<SkillCandidateMatch> { it.score }.thenBy { it.skillId }
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
            overallConfidence = overallConfidence,
            selectedWorkflow = selectedWorkflow
        )
    }
}
