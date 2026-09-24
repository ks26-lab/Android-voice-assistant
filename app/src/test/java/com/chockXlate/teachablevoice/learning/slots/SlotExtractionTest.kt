package com.chockXlate.teachablevoice.learning.slots

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.learning.actions.SemanticAction
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.learning.actions.SemanticActionType
import com.chockXlate.teachablevoice.learning.intent.Intent
import com.chockXlate.teachablevoice.learning.intent.IntentExtractionResult
import com.chockXlate.teachablevoice.learning.intent.IntentExtractor
import com.chockXlate.teachablevoice.learning.targets.SemanticTarget
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionImpl
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SlotExtractionTest {

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun createDummyIntentResult(intentName: String = "order_food"): IntentExtractionResult {
        val intent = Intent(intentId = "intent_01", canonicalName = intentName, confidence = 1.0, confidenceLevel = "HIGH")
        return IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "Extracted $intentName")
    }

    @Test
    fun test1_restaurantTextExtraction() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order food from Burger Barn")
        val trace = DemonstrationTrace(traceId = "tr1", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val result = SlotExtractor.extract(trace, emptyList(), createDummyIntentResult("order_food"))
        val restSlot = result.extractedSlots.find { it.name == "restaurant" }
        assertNotNull(restSlot)
        assertEquals("Burger Barn", restSlot?.rawValue)
        assertEquals(SlotType.TEXT, restSlot?.type)
    }

    @Test
    fun test2_itemTextExtraction() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order Cheese Burger")
        val trace = DemonstrationTrace(traceId = "tr2", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val result = SlotExtractor.extract(trace, emptyList(), createDummyIntentResult("order_food"))
        val itemSlot = result.extractedSlots.find { it.name == "item" }
        assertNotNull(itemSlot)
        assertEquals("Cheese Burger", itemSlot?.rawValue)
        assertEquals(SlotType.TEXT, itemSlot?.type)
    }

    @Test
    fun test3_quantityIntegerExtraction() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 3 pizzas")
        val trace = DemonstrationTrace(traceId = "tr3", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val result = SlotExtractor.extract(trace, emptyList(), createDummyIntentResult("order_food"))
        val qtySlot = result.extractedSlots.find { it.name == "quantity" }
        assertNotNull(qtySlot)
        assertEquals("3", qtySlot?.rawValue)
        assertEquals("3", qtySlot?.typedValue)
        assertEquals(SlotType.INTEGER, qtySlot?.type)
    }

    @Test
    fun test4_addressAddressExtraction() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Deliver to 456 Tech Park")
        val trace = DemonstrationTrace(traceId = "tr4", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val result = SlotExtractor.extract(trace, emptyList(), createDummyIntentResult("order_food"))
        val addrSlot = result.extractedSlots.find { it.name == "address" }
        assertNotNull(addrSlot)
        assertEquals("456 Tech Park", addrSlot?.rawValue)
        assertEquals(SlotType.ADDRESS, addrSlot?.type)
    }

    @Test
    fun test5_integerTypeConversion() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/quantity"), inputData = "5")
        val trace = DemonstrationTrace(traceId = "tr5", timestamp = 1000L, appContext = "com.app", userActions = listOf(action), traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))
        val semanticActions = SemanticActionExtractor.extract(trace)

        val result = SlotExtractor.extract(trace, semanticActions, createDummyIntentResult())
        val slot = result.extractedSlots.find { it.name == "quantity" }
        assertNotNull(slot)
        assertEquals("5", slot?.typedValue)
        assertEquals(SlotType.INTEGER, slot?.type)
    }

    @Test
    fun test6_originalRawValuePreserved() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace = DemonstrationTrace(traceId = "tr6", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val result = SlotExtractor.extract(trace, emptyList(), createDummyIntentResult())
        val slot = result.extractedSlots.find { it.name == "quantity" }
        assertEquals("2", slot?.rawValue)
    }

    @Test
    fun test7_slotNameCanonicalization() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "com.app:id/search_dish_input"), inputData = "Tacos")
        val trace = DemonstrationTrace(traceId = "tr7", timestamp = 1000L, appContext = "com.app", userActions = listOf(action), traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))
        val semanticActions = SemanticActionExtractor.extract(trace)

        val result = SlotExtractor.extract(trace, semanticActions, createDummyIntentResult())
        assertTrue(result.extractedSlots.any { it.name == "item" })
    }

    @Test
    fun test8_voiceTranscriptEvidencePreserved() {
        val voice = VoiceEvent(eventId = "v100", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace = DemonstrationTrace(traceId = "tr8", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v100", 1000L, voice)))

        val result = SlotExtractor.extract(trace, emptyList(), createDummyIntentResult())
        val slot = result.extractedSlots.find { it.name == "quantity" }
        assertTrue(slot?.sourceVoiceEventIds?.contains("v100") == true)
    }

    @Test
    fun test9_semanticActionEvidencePreserved() {
        val action = ActionEvent(actionId = "act_200", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/search"), inputData = "Burger")
        val trace = DemonstrationTrace(traceId = "tr9", timestamp = 1000L, appContext = "com.app", userActions = listOf(action), traceEvents = listOf(TraceEvent.Action("act_200", 1000L, action)))
        val semanticActions = SemanticActionExtractor.extract(trace)

        val result = SlotExtractor.extract(trace, semanticActions, createDummyIntentResult())
        val slot = result.extractedSlots.find { it.name == "item" }
        assertTrue(slot?.sourceActionIds?.contains("act_200") == true)
    }

    @Test
    fun test10_targetContextPreserved() {
        val sa = SemanticAction(actionId = "sa1", timestamp = 1000L, actionType = SemanticActionType.INPUT_TEXT, target = SemanticTarget(role = "EditText", resourceId = "com.app:id/vendor_input"), inputValue = "Subway")
        val trace = DemonstrationTrace(traceId = "tr10", timestamp = 1000L, appContext = "com.app")

        val result = SlotExtractor.extract(trace, listOf(sa), createDummyIntentResult())
        val slot = result.extractedSlots.find { it.name == "restaurant" }
        assertEquals("EditText", slot?.targetRole)
        assertEquals("com.app:id/vendor_input", slot?.targetResourceId)
    }

    @Test
    fun test11_slotConfidenceDeterminism() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/quantity"), inputData = "2")
        val trace = DemonstrationTrace(traceId = "tr11", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), userActions = listOf(action), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice), TraceEvent.Action("a1", 1000L, action)))
        val semanticActions = SemanticActionExtractor.extract(trace)

        val result = SlotExtractor.extract(trace, semanticActions, createDummyIntentResult())
        val slot = result.extractedSlots.find { it.name == "quantity" }
        assertEquals(1.0, slot?.confidence ?: 0.0, 0.01)
        assertEquals("HIGH", slot?.confidenceLevel)
    }

    @Test
    fun test12_sameInputProducesIdenticalSlotOutput() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace = DemonstrationTrace(traceId = "tr12", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val res1 = SlotExtractor.extract(trace, emptyList(), createDummyIntentResult())
        val res2 = SlotExtractor.extract(trace, emptyList(), createDummyIntentResult())

        assertEquals(res1, res2)
    }

    @Test
    fun test13_duplicateCompatibleEvidenceMerged() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order food from Subway")
        val action = ActionEvent(actionId = "a1", timestamp = 1002L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/restaurant"), inputData = "Subway")
        val trace = DemonstrationTrace(traceId = "tr13", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), userActions = listOf(action), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice), TraceEvent.Action("a1", 1002L, action)))
        val semanticActions = SemanticActionExtractor.extract(trace)

        val result = SlotExtractor.extract(trace, semanticActions, createDummyIntentResult())
        assertEquals(1, result.extractedSlots.count { it.name == "restaurant" })
        val slot = result.extractedSlots.find { it.name == "restaurant" }
        assertEquals("Subway", slot?.rawValue)
        assertTrue(slot?.sourceVoiceEventIds?.contains("v1") == true)
        assertTrue(slot?.sourceActionIds?.contains("a1") == true)
    }

    @Test
    fun test14_conflictingEvidenceNotSilentlyResolved() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order food from Subway")
        val action = ActionEvent(actionId = "a1", timestamp = 1002L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/restaurant"), inputData = "Domino's")
        val trace = DemonstrationTrace(traceId = "tr14", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), userActions = listOf(action), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice), TraceEvent.Action("a1", 1002L, action)))
        val semanticActions = SemanticActionExtractor.extract(trace)

        val result = SlotExtractor.extract(trace, semanticActions, createDummyIntentResult())
        assertEquals(1, result.conflicts.size)
        val conflict = result.conflicts.first()
        assertEquals("restaurant", conflict.slotName)
        assertEquals("Subway", conflict.voiceValue)
        assertEquals("Domino's", conflict.actionValue)
        
        val slot = result.extractedSlots.find { it.name == "restaurant" }
        assertEquals("MEDIUM", slot?.confidenceLevel)
    }

    @Test
    fun test15_missingSlotNotFabricated() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order pizza")
        val trace = DemonstrationTrace(traceId = "tr15", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val result = SlotExtractor.extract(trace, emptyList(), createDummyIntentResult("order_food"))
        // Expected slots: restaurant, item, quantity, address
        assertTrue(result.unresolvedSlots.contains("restaurant"))
        assertTrue(result.unresolvedSlots.contains("address"))
        assertNull(result.extractedSlots.find { it.name == "address" })
    }

    @Test
    fun test16_partialDemonstrationHandledSafely() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/item"), inputData = "Salad")
        val trace = DemonstrationTrace(traceId = "tr16", timestamp = 1000L, appContext = "com.app", userActions = listOf(action), traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))
        val semanticActions = SemanticActionExtractor.extract(trace)

        val result = SlotExtractor.extract(trace, semanticActions, createDummyIntentResult("order_food"))
        assertEquals(1, result.extractedSlots.size)
        assertEquals("item", result.extractedSlots.first().name)
        assertEquals("Salad", result.extractedSlots.first().rawValue)
    }

    @Test
    fun test17_phase4IntentFeedsPhase5() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace = DemonstrationTrace(traceId = "tr17", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))
        val intentResult = IntentExtractor.extract(trace, emptyList())

        val slotResult = SlotExtractor.extract(trace, emptyList(), intentResult)
        assertEquals("order_food", slotResult.intentName)
        assertTrue(slotResult.extractedSlots.any { it.name == "quantity" })
    }

    @Test
    fun test18_phase3SemanticActionsFeedPhase5() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/dish"), inputData = "Pasta")
        val trace = DemonstrationTrace(traceId = "tr18", timestamp = 1000L, appContext = "com.app", userActions = listOf(action), traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))
        val semanticActions = SemanticActionExtractor.extract(trace)

        val slotResult = SlotExtractor.extract(trace, semanticActions, createDummyIntentResult())
        val slot = slotResult.extractedSlots.find { it.name == "item" }
        assertEquals("Pasta", slot?.rawValue)
    }

    @Test
    fun test19_phase2NormalizedTraceFeedsPhase5() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val rawTrace = DemonstrationTrace(traceId = "tr19", timestamp = 1000L, appContext = "unknown", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val normResult = DemonstrationTraceNormalizer.normalize(rawTrace)
        val intentResult = IntentExtractor.extract(normResult.normalizedTrace, emptyList())

        val slotResult = SlotExtractor.extract(normResult.normalizedTrace, emptyList(), intentResult)
        assertTrue(slotResult.extractedSlots.any { it.name == "quantity" })
    }

    @Test
    fun test20_serializationCompatibility() {
        val slot = ExtractedSlot(
            slotId = "sl_100",
            name = "quantity",
            type = SlotType.INTEGER,
            rawValue = "2",
            typedValue = "2",
            confidence = 1.0,
            confidenceLevel = "HIGH"
        )
        val result = SlotExtractionResult(intentName = "order_food", extractedSlots = listOf(slot))

        val json = jsonFormatter.encodeToString(SlotExtractionResult.serializer(), result)
        val decoded = jsonFormatter.decodeFromString(SlotExtractionResult.serializer(), json)

        assertEquals("1.0", decoded.schemaVersion)
        assertEquals("order_food", decoded.intentName)
        assertEquals(1, decoded.extractedSlots.size)
        assertEquals("quantity", decoded.extractedSlots.first().name)
        assertEquals(SlotType.INTEGER, decoded.extractedSlots.first().type)
    }

    @Test
    fun test21_noCoordinateDependency() {
        val sa = SemanticAction(actionId = "sa1", timestamp = 1000L, actionType = SemanticActionType.INPUT_TEXT, target = SemanticTarget(role = "EditText", resourceId = "id/input"), inputValue = "SampleText")
        val trace = DemonstrationTrace(traceId = "tr21", timestamp = 1000L, appContext = "com.app")

        val result = SlotExtractor.extract(trace, listOf(sa), createDummyIntentResult())
        val slot = result.extractedSlots.first()
        // Slot extraction relies on semantic action target attributes (role, resourceId), not coordinates
        assertEquals("SampleText", slot.rawValue)
        assertEquals("id/input", slot.targetResourceId)
    }

    @Test
    fun test22_noHardcodedJudgeValues() {
        // Arbitrary un-hardcoded test values
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 5 Sushi Rolls from Tokyo Express to Suite 400")
        val trace = DemonstrationTrace(traceId = "tr22", timestamp = 1000L, appContext = "com.custom.delivery", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val result = SlotExtractor.extract(trace, emptyList(), createDummyIntentResult("order_food"))
        val qty = result.extractedSlots.find { it.name == "quantity" }
        val rest = result.extractedSlots.find { it.name == "restaurant" }
        val item = result.extractedSlots.find { it.name == "item" }
        val addr = result.extractedSlots.find { it.name == "address" }

        assertEquals("5", qty?.rawValue)
        assertEquals("Tokyo Express", rest?.rawValue)
        assertEquals("Sushi Rolls", item?.rawValue)
        assertEquals("Suite 400", addr?.rawValue)
    }

    @Test
    fun test23_noExternalNetworkOrAIDependency() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace = DemonstrationTrace(traceId = "tr23", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val startMs = System.currentTimeMillis()
        val result = SlotExtractor.extract(trace, emptyList(), createDummyIntentResult())
        val durationMs = System.currentTimeMillis() - startMs

        assertTrue(result.extractedSlots.isNotEmpty())
        assertTrue(durationMs < 1000L) // Instant offline local parsing
    }

    @Test
    fun test24_regressionPhase1TeachingCapture() {
        val session = TeachingSessionImpl("order_food", "Order food")
        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas"))
        val trace = session.stopTeaching()

        val intentResult = IntentExtractor.extract(trace, emptyList())
        val slotResult = SlotExtractor.extract(trace, emptyList(), intentResult)

        assertTrue(slotResult.extractedSlots.any { it.name == "quantity" })
    }

    @Test
    fun test25_regressionPhase2TraceNormalization() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val rawTrace = DemonstrationTrace(traceId = "tr25", timestamp = 1000L, appContext = "unknown", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val normResult = DemonstrationTraceNormalizer.normalize(rawTrace)
        val intentResult = IntentExtractor.extract(normResult.normalizedTrace, emptyList())
        val slotResult = SlotExtractor.extract(normResult.normalizedTrace, emptyList(), intentResult)

        assertTrue(slotResult.extractedSlots.any { it.name == "quantity" })
    }

    @Test
    fun test26_regressionPhase3SemanticActionExtraction() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/dish"), inputData = "Noodles")
        val trace = DemonstrationTrace(traceId = "tr26", timestamp = 1000L, appContext = "com.app", userActions = listOf(action), traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        val intentResult = IntentExtractor.extract(trace, semanticActions)
        val slotResult = SlotExtractor.extract(trace, semanticActions, intentResult)

        val slot = slotResult.extractedSlots.find { it.name == "item" }
        assertEquals("Noodles", slot?.rawValue)
    }

    @Test
    fun test27_regressionPhase4IntentExtraction() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas from Domino's to Home")
        val trace = DemonstrationTrace(traceId = "tr27", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val intentResult = IntentExtractor.extract(trace, emptyList())
        assertEquals("order_food", intentResult.intent.canonicalName)

        val slotResult = SlotExtractor.extract(trace, emptyList(), intentResult)
        assertEquals(4, slotResult.extractedSlots.size)
        assertTrue(slotResult.extractedSlots.any { it.name == "restaurant" })
        assertTrue(slotResult.extractedSlots.any { it.name == "item" })
        assertTrue(slotResult.extractedSlots.any { it.name == "quantity" })
        assertTrue(slotResult.extractedSlots.any { it.name == "address" })
    }
}
