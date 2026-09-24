package com.chockXlate.teachablevoice.command.interpretation

import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.learning.intent.Intent
import java.util.UUID

/**
 * Deterministic engine for Phase 10 Command Understanding.
 * Parses natural-language user commands into structured intent and parameters.
 * Does NOT perform Skill Matching (Phase 11) or ExecutionRequest construction.
 */
object CommandInterpreter {

    private val EXPECTED_CANONICAL_SLOTS = mapOf(
        "order_food" to listOf("restaurant", "item", "quantity", "address"),
        "send_message" to listOf("recipient", "message"),
        "book_appointment" to listOf("service", "date", "time"),
        "create_reminder" to listOf("task", "time"),
        "navigate" to listOf("destination"),
        "search_information" to listOf("item")
    )

    fun normalizeCommand(rawCommand: String): String {
        return rawCommand.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    fun understandCommand(rawCommand: String): CommandUnderstandingResult {
        val normalized = normalizeCommand(rawCommand)
        val diagnostics = mutableListOf<String>()
        val unresolvedItems = mutableListOf<String>()

        if (normalized.isBlank()) {
            diagnostics.add("Empty or whitespace command provided.")
            return CommandUnderstandingResult(
                rawCommand = rawCommand,
                normalizedCommand = "",
                intent = Intent(intentId = "unknown", canonicalName = "unknown", confidence = 0.0, confidenceLevel = "LOW"),
                intentConfidence = 0.0,
                unresolvedItems = listOf("intent"),
                diagnostics = diagnostics,
                overallConfidence = 0.0,
                status = "UNKNOWN_COMMAND"
            )
        }

        // 1. Infer Canonical Intent
        val (canonicalIntent, intentConfidence) = inferIntentFromCommand(normalized)
        val intentLevel = if (intentConfidence >= 0.8) "HIGH" else if (intentConfidence >= 0.5) "MEDIUM" else "LOW"
        val intentHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${canonicalIntent}_${normalized}".toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }
        val intent = Intent(
            intentId = "cmd_intent_$intentHash",
            canonicalName = canonicalIntent,
            confidence = intentConfidence,
            confidenceLevel = intentLevel
        )

        if (canonicalIntent == "unknown") {
            diagnostics.add("Unknown intent for command: '$rawCommand'")
            unresolvedItems.add("intent")
            return CommandUnderstandingResult(
                rawCommand = rawCommand,
                normalizedCommand = normalized,
                intent = intent,
                intentConfidence = 0.0,
                unresolvedItems = unresolvedItems,
                diagnostics = diagnostics,
                overallConfidence = 0.0,
                status = "UNKNOWN_INTENT"
            )
        }

        // 2. Extract Slots from Command Text
        val slots = extractSlotsFromCommand(rawCommand, normalized, canonicalIntent, diagnostics)

        // 3. Identify Unresolved Expected Slots
        val expectedSlots = EXPECTED_CANONICAL_SLOTS[canonicalIntent] ?: emptyList()
        val extractedSlotNames = slots.map { it.name }.toSet()
        val missing = expectedSlots.filter { it !in extractedSlotNames }
        unresolvedItems.addAll(missing)

        val overallConfidence = if (unresolvedItems.isEmpty()) intentConfidence else (intentConfidence * 0.8)

        return CommandUnderstandingResult(
            schemaVersion = "1.0",
            rawCommand = rawCommand,
            normalizedCommand = normalized,
            intent = intent,
            intentConfidence = intentConfidence,
            intentProvenance = "CommandInterpreter deterministic pattern engine",
            slots = slots.sortedBy { it.name },
            unresolvedItems = unresolvedItems,
            diagnostics = diagnostics,
            overallConfidence = overallConfidence,
            status = "UNDERSTOOD"
        )
    }

    private fun inferIntentFromCommand(normalized: String): Pair<String, Double> {
        val foodPhrases = listOf("order", "get me", "can you order", "deliver", "buy", "dish", "pizza", "burger", "food", "tacos", "sushi")
        val messagePhrases = listOf("send message", "text", "whatsapp", "tell", "send a message")
        val appointmentPhrases = listOf("book appointment", "schedule appointment", "book haircut", "make reservation", "book a table", "haircut appointment")
        val reminderPhrases = listOf("remind me", "create reminder", "set reminder")
        val navigatePhrases = listOf("navigate to", "take me to", "directions to")

        return when {
            Regex("""^(?:search(?: for)?|find|look up|lookup)\b""").containsMatchIn(normalized) -> Pair("search_information", 1.0)
            foodPhrases.any { normalized.contains(it) } -> Pair("order_food", 1.0)
            messagePhrases.any { normalized.contains(it) } -> Pair("send_message", 1.0)
            appointmentPhrases.any { normalized.contains(it) } -> Pair("book_appointment", 1.0)
            reminderPhrases.any { normalized.contains(it) } -> Pair("create_reminder", 1.0)
            navigatePhrases.any { normalized.contains(it) } -> Pair("navigate", 1.0)
            else -> Pair("unknown", 0.0)
        }
    }

    private fun extractSlotsFromCommand(
        rawCommand: String,
        normalized: String,
        canonicalIntent: String,
        diagnostics: MutableList<String>
    ): List<CommandSlot> {
        val slots = mutableListOf<CommandSlot>()

        if (canonicalIntent == "search_information") {
            val query = Regex("""^(?:search(?: for)?|find|look up|lookup)\s+(.+)$""", RegexOption.IGNORE_CASE)
                .find(rawCommand.trim())?.groupValues?.get(1)?.trim()
            if (!query.isNullOrBlank()) slots.add(CommandSlot(name = "item", type = SlotType.TEXT,
                rawValue = query, typedValue = query, confidence = 1.0, confidenceLevel = "HIGH",
                provenance = "Explicit search query"))
        }
        if (canonicalIntent == "order_food") {
            // Extract Quantity (Integer)
            val numMatch = Regex("""\b(\d+)\b""").find(rawCommand)
            if (numMatch != null) {
                val rawQty = numMatch.groupValues[1]
                slots.add(
                    CommandSlot(
                        name = "quantity",
                        type = SlotType.INTEGER,
                        rawValue = rawQty,
                        typedValue = rawQty,
                        confidence = 1.0,
                        confidenceLevel = "HIGH",
                        provenance = "Command Regex '\\b(\\d+)\\b'"
                    )
                )
            }

            // Extract Restaurant ("from <Vendor>")
            val fromMatch = Regex("""\bfrom\s+([A-Za-z0-9\s'-]+?)(?=\s+to\b|\$|$)""", RegexOption.IGNORE_CASE).find(rawCommand)
            if (fromMatch != null) {
                val restName = fromMatch.groupValues[1].trim()
                if (restName.isNotBlank()) {
                    slots.add(
                        CommandSlot(
                            name = "restaurant",
                            type = SlotType.TEXT,
                            rawValue = restName,
                            typedValue = restName,
                            confidence = 1.0,
                            confidenceLevel = "HIGH",
                            provenance = "Command Phrase 'from <Vendor>'"
                        )
                    )
                }
            }

            // Extract Address ("to <Destination>")
            val toMatch = Regex("""\bto\s+([A-Za-z0-9\s'-]+?)$""", RegexOption.IGNORE_CASE).find(rawCommand)
            if (toMatch != null) {
                val addr = toMatch.groupValues[1].trim()
                if (addr.isNotBlank() && !addr.equals("cart", ignoreCase = true)) {
                    slots.add(
                        CommandSlot(
                            name = "address",
                            type = SlotType.ADDRESS,
                            rawValue = addr,
                            typedValue = addr,
                            confidence = 1.0,
                            confidenceLevel = "HIGH",
                            provenance = "Command Phrase 'to <Destination>'"
                        )
                    )
                }
            }

            // Extract Item ("order/get me/want to get/deliver <Item>")
            val orderMatch = Regex("""\b(?:order|get\s+me|want\s+to\s+get|want\s+to\s+order|want|deliver|can\s+you\s+order|buy)\s+(?:a\s+|an\s+|the\s+)?(?:\d+\s+)?([A-Za-z0-9\s'-]+?)(?=\s+from\b|\s+to\b|\$|$)""", RegexOption.IGNORE_CASE).find(rawCommand)
            if (orderMatch != null) {
                var itemName = orderMatch.groupValues[1].trim()
                if (itemName.isNotBlank() && !itemName.equals("food", ignoreCase = true)) {
                    if (itemName.endsWith("pizzas", ignoreCase = true)) {
                        itemName = itemName.dropLast(1)
                    } else if (itemName.endsWith("burgers", ignoreCase = true)) {
                        itemName = itemName.dropLast(1)
                    }
                    val formatted = itemName.split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
                        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
                    slots.add(
                        CommandSlot(
                            name = "item",
                            type = SlotType.TEXT,
                            rawValue = formatted,
                            typedValue = formatted,
                            confidence = 1.0,
                            confidenceLevel = "HIGH",
                            provenance = "Command Item Expression"
                        )
                    )
                }
            }
        }

        return slots
    }
}
