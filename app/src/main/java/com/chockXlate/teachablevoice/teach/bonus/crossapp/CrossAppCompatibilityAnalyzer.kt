package com.chockXlate.teachablevoice.teach.bonus.crossapp

import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.workflow.Workflow

/**
 * Analyzes target UI hierarchy for sensitive screens (payments, credentials, OTP).
 */
class CrossAppCompatibilityAnalyzer {

    private val SENSITIVE_KEYWORDS = setOf(
        "payment", "card number", "cvv", "expiry", "upi pin", "password",
        "passcode", "otp", "one time password", "credit card", "debit card",
        "social security", "ssn", "login credentials"
    )

    fun isSensitiveScreen(elements: List<UiElement>): Boolean {
        for (e in elements) {
            val text = (e.text ?: "").lowercase()
            val desc = (e.contentDescription ?: "").lowercase()
            val resId = (e.resourceId ?: "").lowercase()

            if (SENSITIVE_KEYWORDS.any { text.contains(it) || desc.contains(it) || resId.contains(it) }) {
                return true
            }
            if (e.children.isNotEmpty() && isSensitiveScreen(e.children)) {
                return true
            }
        }
        return false
    }

    fun isTaskSequenceCompatible(workflow: Workflow, targetElements: List<UiElement>): Boolean {
        // Simple structural check: ensure target UI has at least minimum elements to support workflow step count
        val actionableTargetCount = countActionableElements(targetElements)
        return actionableTargetCount >= workflow.steps.size
    }

    private fun countActionableElements(elements: List<UiElement>): Int {
        var count = 0
        for (e in elements) {
            if (e.isClickable || e.isEditable || !e.text.isNullOrBlank() || !e.contentDescription.isNullOrBlank()) {
                count++
            }
            count += countActionableElements(e.children)
        }
        return count
    }
}
