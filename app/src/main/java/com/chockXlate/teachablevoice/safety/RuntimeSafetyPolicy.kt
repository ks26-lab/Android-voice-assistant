package com.chockXlate.teachablevoice.safety

import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.ui.UiObservation

/** Deliberately conservative: uncertain credential/payment boundaries require human control. */
class RuntimeSafetyPolicy {
    private val credential = Regex(
        "(?iu)(\\b(password|passcode|pin|otp|cvv2?|cvc|username|user[ _-]*name|credential|one[ _-]*time[ _-]*(password|code)|verification[ _-]*code|security[ _-]*code|card[ _-]*(number|code)|credit[ _-]*card|debit[ _-]*card|auth[ _-]*token|login|log[ _-]*in|sign[ _-]*in|authenticate|authentication)\\b|पासवर्ड|ओटीपी|पिन|लॉगिन)"
    )
    private val payment = Regex(
        "(?iu)(\\b(pay|payment|checkout|place[ _-]*order|confirm[ _-]*(purchase|order|payment)|buy[ _-]*now|complete[ _-]*purchase|banking|transfer[ _-]*funds|confirm[ _-]*(transfer|transaction)|security[ _-]*confirmation)\\b|भुगतान|खरीदें)"
    )

    private fun words(value: String): String = value.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ')
    fun credentialText(value: String): Boolean = credential.containsMatchIn(words(value))

    fun admission(workflow: Workflow): String? = when {
        workflow.slots.any { credentialText(it.name) } -> "Credential input requires manual user control."
        workflow.safetyBoundary.schemaVersion != "1.0" -> "Unsupported safety boundary schema."
        workflow.safetyBoundary.requiresExplicitUserConfirmation -> "This workflow requires explicit user handoff before automation."
        workflow.safetyBoundary.maxAllowedValue != null -> "A monetary limit is declared, but the contract provides no reliable amount/currency evidence."
        workflow.steps.any { it.semanticAction.equals("EXECUTE_INTENT", ignoreCase = true) } ->
            "Voice-only demonstration has no recorded UI actions. Clarification and interactive teaching required."
        else -> null
    }

    fun evaluate(boundary: SafetyBoundary, ui: UiObservation, step: BoundStep): String? {
        if (boundary.requiresExplicitUserConfirmation) return "This workflow requires explicit user handoff before automation."
        if (boundary.maxAllowedValue != null) return "The declared value limit cannot be verified reliably."
        if (ui.credentialFieldPresent) return "A protected credential field is visible. Complete authentication manually."
        val liveText = ui.state.allElements.flatMap {
            listOfNotNull(it.text, it.contentDescription, it.resourceId, it.nearbyText)
        }
        val targetText = listOfNotNull(step.selector.text, step.selector.contentDescription, step.selector.resourceId)
        // Never inspect/store/log the supplied input value as diagnostic evidence.
        val evidence = liveText + targetText
        if (evidence.any(::credentialText)) return "Login or credential entry is required. Continue manually."
        if (evidence.any { payment.containsMatchIn(words(it)) }) {
            return "A payment, checkout, or order-confirmation boundary requires manual user control."
        }
        if (boundary.sensitiveKeywords.any { keyword ->
                keyword.isNotBlank() && evidence.any { it.contains(keyword.trim(), ignoreCase = true) }
            }) return "The current screen matches a declared sensitive boundary."
        if (boundary.restrictedActions.isNotEmpty()) {
            val names = setOf(step.action.name, step.source.semanticAction.uppercase())
            if (boundary.restrictedActions.any { it.uppercase().removeSuffix("_SENSITIVE") in names }) {
                return "The requested action is restricted by this workflow."
            }
            // No shared vocabulary exists for arbitrary labels such as FINAL_PAYMENT.
            return "The workflow declares restricted actions whose scope requires user review."
        }
        return null
    }
}
