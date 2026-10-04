package com.chockXlate.teachablevoice.teach.bonus.crossapp

import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import java.util.Locale

/**
 * Cross-app semantic matcher capable of reasoning across different package names,
 * resource IDs, wording variations, and UI hierarchies.
 */
class CrossAppSemanticMatcher(
    private val config: CrossAppConfiguration = CrossAppConfiguration()
) {

    private val SYNONYMS = mapOf(
        "search" to setOf("search", "find", "query", "lookup", "explore", "type to search"),
        "find" to setOf("search", "find", "query", "lookup", "explore"),
        "enter" to setOf("enter", "type", "input", "write", "search"),
        "submit" to setOf("submit", "go", "search", "ok", "confirm", "done"),
        "item" to setOf("item", "result", "product", "card", "title", "entry"),
        "select" to setOf("select", "choose", "pick", "open", "view")
    )

    fun scoreElement(
        selector: SemanticSelector,
        element: UiElement,
        targetPackage: String
    ): CrossAppEvidence {
        var roleScore = 0.0
        var textScore = 0.0
        var contentDescScore = 0.0
        var structuralScore = 0.0
        var actionScore = 1.0 // Defaults to compatible if element can perform operation

        // 1. Role Matching
        val sRole = normalize(selector.role ?: "")
        val eRole = normalize(element.role)
        if (sRole.isNotBlank() && eRole.isNotBlank()) {
            if (sRole == eRole || sRole.endsWith(eRole) || eRole.endsWith(sRole)) {
                roleScore = 1.0
            } else if (isCompatibleRoleCategory(sRole, eRole)) {
                roleScore = 0.7
            }
        } else {
            roleScore = 0.5 // Neutral if role absent
        }

        // 2. Semantic Text Matching (Synonym & Token overlap)
        val sText = selector.text ?: selector.textSlot ?: ""
        val eText = element.text ?: ""
        textScore = calculateSemanticSimilarity(sText, eText)

        // 3. Content Description Matching
        val sDesc = selector.contentDescription ?: ""
        val eDesc = element.contentDescription ?: ""
        contentDescScore = calculateSemanticSimilarity(sDesc, eDesc)

        // If selector had text but target has contentDesc instead (or vice versa), cross-match them
        if (sText.isNotBlank() && eDesc.isNotBlank()) {
            val crossScore = calculateSemanticSimilarity(sText, eDesc)
            if (crossScore > textScore) textScore = crossScore
        }
        if (sDesc.isNotBlank() && eText.isNotBlank()) {
            val crossScore = calculateSemanticSimilarity(sDesc, eText)
            if (crossScore > contentDescScore) contentDescScore = crossScore
        }

        // 4. Structural Context Matching (Parent, Ancestor, Nearby)
        var parentScore = 0.5
        if (!selector.parentRole.isNullOrBlank() && !element.parentRole.isNullOrBlank()) {
            parentScore = if (normalize(selector.parentRole) == normalize(element.parentRole)) 1.0 else 0.4
        }
        var nearbyScore = 0.5
        if (!selector.nearbyText.isNullOrBlank() && !element.nearbyText.isNullOrBlank()) {
            nearbyScore = calculateSemanticSimilarity(selector.nearbyText!!, element.nearbyText!!)
        }
        structuralScore = (parentScore * 0.5) + (nearbyScore * 0.5)

        // Weighted Overall Score Calculation
        val totalConfidence = (roleScore * config.roleMatchWeight) +
            (textScore * config.semanticTextWeight) +
            (contentDescScore * config.contentDescWeight) +
            (structuralScore * config.structuralContextWeight)

        val reason = "Semantic match score: $totalConfidence (Role: $roleScore, Text: $textScore, ContentDesc: $contentDescScore, Struct: $structuralScore)"

        return CrossAppEvidence(
            stepIndex = 0,
            stepId = "",
            sourceRole = selector.role,
            matchedTargetElementId = element.elementId,
            roleMatchScore = roleScore,
            semanticTextScore = textScore,
            contentDescScore = contentDescScore,
            structuralContextScore = structuralScore,
            actionCompatibilityScore = actionScore,
            overallStepConfidence = totalConfidence.coerceIn(0.0, 1.0),
            reason = reason
        )
    }

    fun calculateSemanticSimilarity(s1: String, s2: String): Double {
        if (s1.isBlank() && s2.isBlank()) return 0.5
        if (s1.isBlank() || s2.isBlank()) return 0.0

        val n1 = normalize(s1)
        val n2 = normalize(s2)

        if (n1 == n2) return 1.0
        if (n1.contains(n2) || n2.contains(n1)) return 0.85

        val tokens1 = n1.split("\\s+".toRegex()).filter { it.length > 1 }
        val tokens2 = n2.split("\\s+".toRegex()).filter { it.length > 1 }

        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0

        var matchCount = 0
        for (t1 in tokens1) {
            for (t2 in tokens2) {
                if (t1 == t2 || isSynonym(t1, t2)) {
                    matchCount++
                    break
                }
            }
        }

        val overlap = matchCount.toDouble() / maxOf(tokens1.size, tokens2.size).toDouble()
        return overlap.coerceIn(0.0, 0.9)
    }

    private fun isSynonym(w1: String, w2: String): Boolean {
        for ((_, set) in SYNONYMS) {
            if (set.contains(w1) && set.contains(w2)) return true
        }
        return false
    }

    private fun isCompatibleRoleCategory(r1: String, r2: String): Boolean {
        val inputRoles = setOf("edittext", "textinputedittext", "searchbox", "autocomplete")
        val clickRoles = setOf("button", "imagebutton", "textview", "view", "cardview")

        val cat1 = inputRoles.any { r1.contains(it) }
        val cat2 = inputRoles.any { r2.contains(it) }
        if (cat1 && cat2) return true

        val btn1 = clickRoles.any { r1.contains(it) }
        val btn2 = clickRoles.any { r2.contains(it) }
        return btn1 && btn2
    }

    companion object {
        fun normalize(str: String): String = str.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    }
}
