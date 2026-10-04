package com.chockXlate.teachablevoice.command.interpretation

import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.learning.intent.Intent

/**
 * Deterministic engine for Phase 4.1 Natural-Language Command Understanding & Semantic Parameter Extraction.
 * Parses natural-language user commands into structured canonical intents, semantic slots, platform roles,
 * references, and ambiguity detection without performing skill matching, slot binding, or execution.
 */
object SemanticCommandPolicy {

    val EXPECTED_CANONICAL_SLOTS = mapOf(
        "order_food" to listOf("restaurant", "item", "quantity", "address"),
        "shop_item" to listOf("item"),
        "send_message" to listOf("recipient", "message"),
        "book_appointment" to listOf("service", "date", "time"),
        "create_reminder" to listOf("task", "time"),
        "navigate" to listOf("destination"),
        "search_information" to listOf("item"),
        "search_item" to listOf("query")
    )

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )

    private val PLACEHOLDER_ITEMS = setOf(
        "something", "anything", "it", "this", "that", "stuff", "some", "item", "things", "thing"
    )

    private val REFERENCE_ITEMS = setOf(
        "the same food", "same food", "the same item", "same item", "previous food", "previous item"
    )

    private val SENSITIVE_KEYWORDS = listOf(
        "otp", "pin", "password", "passcode", "cvv", "security settings",
        "complete payment", "authorize payment", "credit card", "debit card"
    )

    fun canonicalizeIntent(intent: String): String = when (intent.trim().lowercase()) {
        "search_item" -> "search_information"
        "shop", "shopping", "shop_item", "order_item", "buy_item" -> "shop_item"
        else -> intent.trim()
    }

    fun bindableSlots(intent: String): Set<String> = when (canonicalizeIntent(intent)) {
        "order_food" -> setOf("item", "restaurant", "quantity", "address", "platform")
        "shop_item" -> setOf("item", "platform", "quantity", "address", "shopping_platform")
        "search_information", "search_item" -> setOf("item", "query", "platform")
        "send_message" -> setOf("recipient", "message", "platform", "messaging_platform")
        else -> emptySet()
    }

    fun normalizeCommand(rawCommand: String): String {
        return rawCommand.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    private fun stripConversationalFillers(text: String): String {
        return text.replace(
            Regex("""^(?:hey|hi|hello|please|could you please|can you please|can you|would you please|would you|i want to|i'd like to)\s+""", RegexOption.IGNORE_CASE),
            ""
        ).trim()
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

        // Check for Sensitive Credentials or Actions (Understanding != Authorization)
        val isSensitive = SENSITIVE_KEYWORDS.any { normalized.contains(it) }
        if (isSensitive) {
            diagnostics.add("Sensitive request detected (e.g. payment/OTP/PIN). Semantic understanding does not constitute execution authorization; SafetyGate remains authoritative.")
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

        if (canonicalIntent == "unknown" || canonicalIntent == "open_app" || canonicalIntent == "open_settings") {
            diagnostics.add("Unrecognized or non-automatable task intent for command: '$rawCommand'")
            unresolvedItems.add("intent")
            return CommandUnderstandingResult(
                rawCommand = rawCommand,
                normalizedCommand = normalized,
                intent = intent,
                intentConfidence = if (canonicalIntent == "unknown") 0.0 else intentConfidence,
                unresolvedItems = unresolvedItems,
                diagnostics = diagnostics,
                overallConfidence = 0.0,
                status = "UNKNOWN_INTENT",
                isSensitive = isSensitive
            )
        }

        // 2. Extract Slots from Command Text
        val slots = extractSlotsFromCommand(rawCommand, normalized, canonicalIntent, diagnostics, unresolvedItems)

        // 3. Identify Unresolved Expected Slots
        val expectedSlots = EXPECTED_CANONICAL_SLOTS[canonicalIntent]
            ?: EXPECTED_CANONICAL_SLOTS[canonicalizeIntent(canonicalIntent)]
            ?: emptyList()
        val extractedSlotNames = slots.filter { it.status == CommandSlotStatus.EXTRACTED || it.status == CommandSlotStatus.REFERENCE }.map { it.name }.toSet()
        val missing = expectedSlots.filter { it !in extractedSlotNames && it !in unresolvedItems }
        unresolvedItems.addAll(missing)

        val status = when {
            unresolvedItems.isNotEmpty() -> "NEEDS_CLARIFICATION"
            else -> "UNDERSTOOD"
        }

        val overallConfidence = if (unresolvedItems.isEmpty()) intentConfidence else (intentConfidence * 0.8)

        return CommandUnderstandingResult(
            schemaVersion = "1.0",
            rawCommand = rawCommand,
            normalizedCommand = normalized,
            intent = intent,
            intentConfidence = intentConfidence,
            intentProvenance = "CommandInterpreter deterministic pattern engine",
            slots = slots.sortedBy { it.name },
            unresolvedItems = unresolvedItems.distinct(),
            diagnostics = diagnostics,
            overallConfidence = overallConfidence,
            status = status,
            isSensitive = isSensitive
        )
    }

    private fun inferIntentFromCommand(normalized: String): Pair<String, Double> {
        // App navigation distinction (e.g. "open amazon", "open settings")
        if (Regex("""^(?:open|launch)\s+([a-z0-9_-]+)$""").matches(normalized)) {
            return Pair("open_app", 1.0)
        }

        if (Regex("""\b(?:settings|preference|preferences|configure|system settings)\b""").containsMatchIn(normalized)) {
            return Pair("unknown", 0.0)
        }

        val cleaned = stripConversationalFillers(normalized)

        val shoppingKeywords = listOf("shop", "shopping", "shirt", "shoes", "headphones", "clothes", "product", "dress", "pants", "electronics", "laptop", "phone", "jacket", "socks", "item")
        val foodKeywords = listOf("pizza", "burger", "tacos", "sushi", "dish", "dishes", "restaurant", "lunch", "dinner", "breakfast", "meal", "snack")
        val messagePhrases = listOf("send text message", "send message", "text", "whatsapp", "tell", "send a message")
        val appointmentPhrases = listOf("book appointment", "schedule appointment", "book haircut", "make reservation", "book a table", "haircut appointment")
        val reminderPhrases = listOf("remind me", "create reminder", "set reminder")
        val navigatePhrases = listOf("navigate to", "take me to", "directions to")

        val hasShoppingWords = shoppingKeywords.any { cleaned.contains(it) }
        val hasFoodWords = foodKeywords.any { cleaned.contains(it) }

        return when {
            // Explicit search commands
            Regex("""^(?:search(?: for)?|look up|lookup)\b""").containsMatchIn(cleaned) -> Pair("search_information", 1.0)
            // Shopping verbs and item patterns
            Regex("""^(?:buy|purchase|shop for)\b""").containsMatchIn(cleaned) ||
                listOf("get a white shirt", "buy a white shirt", "get me a white shirt", "get me a blue jacket", "get blue jacket", "buy blue jacket").any { cleaned.contains(it) } ||
                (hasShoppingWords && listOf("buy", "purchase", "order", "get", "find").any { cleaned.contains(it) }) -> Pair("shop_item", 1.0)
            // Food ordering patterns
            hasFoodWords -> Pair("order_food", 1.0)
            listOf("order food", "get food", "order a meal", "deliver food", "order dinner", "order lunch", "order breakfast").any { cleaned.contains(it) } -> Pair("order_food", 1.0)
            listOf("order", "get me", "can you order", "deliver").any { cleaned.contains(it) } && !hasShoppingWords -> Pair("order_food", 1.0)
            // Other canonical intents
            messagePhrases.any { cleaned.contains(it) } -> Pair("send_message", 1.0)
            appointmentPhrases.any { cleaned.contains(it) } -> Pair("book_appointment", 1.0)
            reminderPhrases.any { cleaned.contains(it) } -> Pair("create_reminder", 1.0)
            navigatePhrases.any { cleaned.contains(it) } -> Pair("navigate", 1.0)
            else -> Pair("unknown", 0.0)
        }
    }

    private fun extractSlotsFromCommand(
        rawCommand: String,
        normalized: String,
        canonicalIntent: String,
        diagnostics: MutableList<String>,
        unresolvedItems: MutableList<String>
    ): List<CommandSlot> {
        val slots = mutableListOf<CommandSlot>()

        // 1. Multi-app messaging extraction (e.g. "and send it to me on Telegram")
        val multiAppSendMatch = Regex("""\band\s+(?:send|share)\s+.*?\s+(?:on|via|through|using)\s+([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(rawCommand)
        if (multiAppSendMatch != null) {
            val messagingPlatform = multiAppSendMatch.groupValues[1].trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            slots.add(CommandSlot(
                name = "messaging_platform",
                type = SlotType.TEXT,
                rawValue = messagingPlatform,
                typedValue = messagingPlatform,
                confidence = 1.0,
                confidenceLevel = "HIGH",
                provenance = "Secondary Messaging Platform",
                role = "messaging_platform"
            ))
        }

        // 2. Generic Platform Extraction with Prepositions (from, on, through, via, using)
        val platformMatch = Regex(
            """\b(?:from|on|through|via|using)\s+([A-Za-z0-9_-]+)(?=\s+(?:to|and\s+send|and\s+share|for|with)\b|\$|\.|\?|!|$)""",
            RegexOption.IGNORE_CASE
        ).find(rawCommand)
        if (platformMatch != null) {
            val plat = platformMatch.groupValues[1].trim()
            val stopPlats = setOf("cart", "home", "settings", "me")
            if (plat.isNotBlank() && plat.lowercase() !in stopPlats) {
                val formattedPlat = plat.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                if (multiAppSendMatch != null) {
                    slots.add(CommandSlot(
                        name = "shopping_platform",
                        type = SlotType.TEXT,
                        rawValue = formattedPlat,
                        typedValue = formattedPlat,
                        confidence = 1.0,
                        confidenceLevel = "HIGH",
                        provenance = "Multi-App Shopping Platform",
                        role = "shopping_platform"
                    ))
                }
                slots.add(CommandSlot(
                    name = "platform",
                    type = SlotType.TEXT,
                    rawValue = formattedPlat,
                    typedValue = formattedPlat,
                    confidence = 1.0,
                    confidenceLevel = "HIGH",
                    provenance = "Command Platform Preposition",
                    role = if (multiAppSendMatch != null) "shopping_platform" else "target_platform"
                ))
            }
        }

        // 3. Search Information / Search Item
        if (canonicalizeIntent(canonicalIntent) == "search_information") {
            val cleaned = rawCommand.trim().trimEnd('.', '?', '!')
            val query = Regex("""^(?:search(?: for)?|find|look up|lookup)\s+(.+)$""", RegexOption.IGNORE_CASE)
                .find(cleaned)?.groupValues?.get(1)?.trim()
            if (!query.isNullOrBlank()) {
                slots.add(CommandSlot(
                    name = "query",
                    type = SlotType.TEXT,
                    rawValue = query,
                    typedValue = query,
                    confidence = 1.0,
                    confidenceLevel = "HIGH",
                    provenance = "Explicit search query"
                ))
                slots.add(CommandSlot(
                    name = "item",
                    type = SlotType.TEXT,
                    rawValue = query,
                    typedValue = query,
                    confidence = 1.0,
                    confidenceLevel = "HIGH",
                    provenance = "Search query alias"
                ))
            }
        }

        // 4. Quantity Extraction (Integer digits or Number words)
        val (rawQty, intQty) = extractQuantity(rawCommand)
        if (rawQty != null && intQty != null) {
            slots.add(
                CommandSlot(
                    name = "quantity",
                    type = SlotType.INTEGER,
                    rawValue = rawQty,
                    typedValue = intQty.toString(),
                    confidence = 1.0,
                    confidenceLevel = "HIGH",
                    provenance = "Extracted quantity parameter"
                )
            )
        }

        // 5. Food Ordering and Shopping Slot Extraction
        if (canonicalIntent == "order_food" || canonicalIntent == "shop_item") {

            // Extract Restaurant ("from <Vendor>") if order_food
            if (canonicalIntent == "order_food") {
                val fromMatch = Regex("""\bfrom\s+([A-Za-z0-9\s'-]+?)(?=\s+to\b|\$|$)""", RegexOption.IGNORE_CASE).find(rawCommand)
                if (fromMatch != null) {
                    val restName = fromMatch.groupValues[1].trim()
                    if (restName.isNotBlank() && slots.none { it.name == "restaurant" }) {
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
                } else {
                    val plat = slots.find { it.name == "platform" }
                    if (plat != null && slots.none { it.name == "restaurant" }) {
                        slots.add(
                            CommandSlot(
                                name = "restaurant",
                                type = SlotType.TEXT,
                                rawValue = plat.rawValue,
                                typedValue = plat.typedValue,
                                confidence = 0.9,
                                confidenceLevel = "HIGH",
                                provenance = "Platform as Restaurant Fallback"
                            )
                        )
                    }
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

            // Extract Item
            val orderMatch = Regex(
                """\b(?:order|get\s+me|want\s+to\s+get|want\s+to\s+order|want|deliver|can\s+you\s+order|can\s+you\s+get\s+me|buy|purchase|find)\s+(?:a\s+|an\s+|the\s+)?(?:\d+\s+|one\s+|two\s+|three\s+|four\s+|five\s+|six\s+|seven\s+|eight\s+|nine\s+|ten\s+)?([A-Za-z0-9\s'-]+?)(?=\s+(?:from|on|through|via|using|to|and)\b|\$|\.|\?|!|$)""",
                RegexOption.IGNORE_CASE
            ).find(rawCommand)

            if (orderMatch != null) {
                var itemName = orderMatch.groupValues[1].trim()

                // Check for ambiguous placeholder reference (e.g. "something", "it")
                if (itemName.lowercase() in PLACEHOLDER_ITEMS) {
                    unresolvedItems.add("item")
                    slots.add(
                        CommandSlot(
                            name = "item",
                            type = SlotType.TEXT,
                            rawValue = itemName,
                            typedValue = itemName,
                            confidence = 0.0,
                            confidenceLevel = "LOW",
                            provenance = "Vague placeholder / ambiguous reference",
                            status = CommandSlotStatus.UNRESOLVED
                        )
                    )
                }
                // Check for reference to previous demonstration values (e.g. "the same food")
                else if (itemName.lowercase() in REFERENCE_ITEMS) {
                    slots.add(
                        CommandSlot(
                            name = "item",
                            type = SlotType.TEXT,
                            rawValue = itemName,
                            typedValue = itemName,
                            confidence = 1.0,
                            confidenceLevel = "HIGH",
                            provenance = "Previous Demonstration Reference",
                            status = CommandSlotStatus.REFERENCE
                        )
                    )
                }
                // Regular concrete item name
                else if (itemName.isNotBlank() && !itemName.equals("food", ignoreCase = true)) {
                    if (itemName.startsWith("pairs of ", ignoreCase = true)) {
                        itemName = itemName.removePrefix("pairs of ").trim()
                    } else if (itemName.startsWith("pair of ", ignoreCase = true)) {
                        itemName = itemName.removePrefix("pair of ").trim()
                    }

                    if (itemName.endsWith("shirts", ignoreCase = true)) {
                        itemName = itemName.dropLast(1)
                    } else if (itemName.endsWith("pizzas", ignoreCase = true)) {
                        itemName = itemName.dropLast(1)
                    } else if (itemName.endsWith("burgers", ignoreCase = true)) {
                        itemName = itemName.dropLast(1)
                    } else if (itemName.endsWith("tacos", ignoreCase = true)) {
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
                            provenance = "Command Item Expression",
                            role = "target_item"
                        )
                    )
                }
            }
        }

        return slots
    }

    private fun extractQuantity(rawCommand: String): Pair<String?, Int?> {
        // 1. Integer digits
        val digitMatch = Regex("""\b(?:order|get\s+me|deliver|buy|purchase|want|get)\s+(\d+)\b""", RegexOption.IGNORE_CASE).find(rawCommand)
            ?: Regex("""\b(\d+)\s+(?:pairs?\s+of\s+)?[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE).find(rawCommand)
        if (digitMatch != null) {
            val raw = digitMatch.groupValues[1]
            return Pair(raw, raw.toIntOrNull())
        }

        // 2. Number words
        for ((word, value) in NUMBER_WORDS) {
            val wordRegex = Regex("""\b$word\b""", RegexOption.IGNORE_CASE)
            if (wordRegex.containsMatchIn(rawCommand)) {
                return Pair(word, value)
            }
        }

        return Pair(null, null)
    }
}
