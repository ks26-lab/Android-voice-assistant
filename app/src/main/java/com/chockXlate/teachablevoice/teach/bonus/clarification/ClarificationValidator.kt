package com.chockXlate.teachablevoice.teach.bonus.clarification

import com.chockXlate.teachablevoice.contract.workflow.SlotType

data class ValidationResult(
    val isValid: Boolean,
    val validatedValue: String? = null,
    val isCancel: Boolean = false,
    val isSafetyBlocked: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Generic response validator with safety boundary checks (credentials, OTP, PIN).
 */
class ClarificationValidator {

    private val CANCEL_KEYWORDS = setOf("cancel", "stop", "don't continue", "abort", "nevermind")

    private val CREDENTIAL_PATTERNS = listOf(
        Regex("(?i)\\b(password|passcode|pin|cvv|otp|ssn|social security)\\b"),
        Regex("^\\d{4,6}\$"), // 4-6 digit numeric PIN or OTP
        Regex("(?i)\\b(\\d{16}|\\d{4}[-\\s]\\d{4}[-\\s]\\d{4}[-\\s]\\d{4})\\b") // Credit card number
    )

    fun validate(input: String, expectedType: SlotType, slotName: String): ValidationResult {
        val trimmed = input.trim()

        if (trimmed.isBlank()) {
            return ValidationResult(isValid = false, errorMessage = "User input is empty.")
        }

        val lower = trimmed.lowercase()
        if (CANCEL_KEYWORDS.any { lower == it || lower.startsWith("$it ") }) {
            return ValidationResult(isValid = false, isCancel = true, errorMessage = "User cancelled execution.")
        }

        // SAFETY BOUNDARY CHECK: Never accept credentials/OTP/PIN as clarification slot values
        if (isSensitiveCredential(trimmed, slotName)) {
            return ValidationResult(
                isValid = false,
                isSafetyBlocked = true,
                errorMessage = "Safety Gate Blocked: User response contains sensitive credential/OTP/PIN data. Handoff triggered."
            )
        }

        return when (expectedType) {
            SlotType.INTEGER -> {
                val num = trimmed.toIntOrNull()
                if (num != null) {
                    if (slotName.equals("quantity", ignoreCase = true) && (num < 1 || num > 20)) {
                        ValidationResult(isValid = false, errorMessage = "Quantity must be between 1 and 20.")
                    } else {
                        ValidationResult(isValid = true, validatedValue = num.toString())
                    }
                } else {
                    ValidationResult(isValid = false, errorMessage = "Expected integer value.")
                }
            }
            SlotType.DECIMAL -> {
                val dbl = trimmed.toDoubleOrNull()
                if (dbl != null && dbl.isFinite()) {
                    ValidationResult(isValid = true, validatedValue = dbl.toString())
                } else {
                    ValidationResult(isValid = false, errorMessage = "Expected decimal number.")
                }
            }
            SlotType.BOOLEAN -> {
                if (lower in setOf("true", "yes", "y", "enable", "1")) {
                    ValidationResult(isValid = true, validatedValue = "true")
                } else if (lower in setOf("false", "no", "n", "disable", "0")) {
                    ValidationResult(isValid = true, validatedValue = "false")
                } else {
                    ValidationResult(isValid = false, errorMessage = "Expected boolean true/false.")
                }
            }
            SlotType.TEXT, SlotType.ENUM, SlotType.ADDRESS, SlotType.PLATFORM -> {
                ValidationResult(isValid = true, validatedValue = trimmed)
            }
        }
    }

    private fun isSensitiveCredential(input: String, slotName: String): Boolean {
        val lowerSlot = slotName.lowercase()
        if (lowerSlot.contains("password") || lowerSlot.contains("pin") || lowerSlot.contains("otp") || lowerSlot.contains("cvv")) {
            return true
        }
        return CREDENTIAL_PATTERNS.any { it.containsMatchIn(input) }
    }
}
