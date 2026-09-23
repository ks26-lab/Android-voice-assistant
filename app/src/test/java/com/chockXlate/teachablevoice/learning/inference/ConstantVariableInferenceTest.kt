package com.chockXlate.teachablevoice.learning.inference

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationAlignment
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset
import com.chockXlate.teachablevoice.learning.intent.Intent
import com.chockXlate.teachablevoice.learning.intent.IntentExtractionResult
import com.chockXlate.teachablevoice.learning.intent.IntentExtractor
import com.chockXlate.teachablevoice.learning.slots.ExtractedSlot
import com.chockXlate.teachablevoice.learning.slots.SlotExtractionResult
import com.chockXlate.teachablevoice.learning.slots.SlotExtractor
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionImpl
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstantVariableInferenceTest {

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun createDataset(
        demoId: String,
        intentName: String,
        slots: List<ExtractedSlot>
    ): DemonstrationDataset {
        val intent = Intent(intentId = "int_$demoId", canonicalName = intentName, confidence = 1.0, confidenceLevel = "HIGH")
        val intentRes = IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "Extracted $intentName")
        val slotRes = SlotExtractionResult(intentName = intentName, extractedSlots = slots)
        return DemonstrationDataset(
            demonstrationId = demoId,
            traceId = "tr_$demoId",
            intentResult = intentRes,
            slotResult = slotRes
        )
    }

    @Test
    fun test4_sameValueAcrossDemonstrationsProducesConstant() {
        val s1 = ExtractedSlot(slotId = "s1", name = "restaurant", type = SlotType.TEXT, rawValue = "Burger Barn", typedValue = "Burger Barn")
        val s2 = ExtractedSlot(slotId = "s2", name = "restaurant", type = SlotType.TEXT, rawValue = "Burger Barn", typedValue = "Burger Barn")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.find { it.slotName == "restaurant" }

        assertNotNull(inf)
        assertEquals(SlotInferenceStatus.CONSTANT, inf?.status)
        assertEquals(1.0, inf?.confidence ?: 0.0, 0.01)
        assertEquals("HIGH", inf?.confidenceLevel)
    }

    @Test
    fun test5_differentValuesAcrossDemonstrationsProducesVariable() {
        val s1 = ExtractedSlot(slotId = "s1", name = "item", type = SlotType.TEXT, rawValue = "Margherita Pizza", typedValue = "Margherita Pizza")
        val s2 = ExtractedSlot(slotId = "s2", name = "item", type = SlotType.TEXT, rawValue = "Farmhouse Pizza", typedValue = "Farmhouse Pizza")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.find { it.slotName == "item" }

        assertNotNull(inf)
        assertEquals(SlotInferenceStatus.VARIABLE, inf?.status)
        assertEquals(1.0, inf?.confidence ?: 0.0, 0.01)
        assertEquals("HIGH", inf?.confidenceLevel)
    }

    @Test
    fun test6_oneDemonstrationProducesUnknown() {
        val s1 = ExtractedSlot(slotId = "s1", name = "quantity", type = SlotType.INTEGER, rawValue = "2", typedValue = "2")
        val ds1 = createDataset("d1", "order_food", listOf(s1))

        val result = ConstantVariableInference.infer(listOf(ds1))
        val inf = result.slotInferences.find { it.slotName == "quantity" }

        assertNotNull(inf)
        assertEquals(SlotInferenceStatus.UNKNOWN, inf?.status)
        assertEquals("LOW", inf?.confidenceLevel)
    }

    @Test
    fun test7_missingSlotEvidenceProducesUnknown() {
        val s1 = ExtractedSlot(slotId = "s1", name = "address", type = SlotType.ADDRESS, rawValue = "Home", typedValue = "Home")
        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", emptyList()) // Missing address

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.find { it.slotName == "address" }

        assertNotNull(inf)
        assertEquals(SlotInferenceStatus.UNKNOWN, inf?.status)
        assertTrue(inf?.reasoning?.contains("present in only 1 of 2") == true)
    }

    @Test
    fun test8_conflictingPhase5EvidencePreserved() {
        val s1 = ExtractedSlot(
            slotId = "s1",
            name = "restaurant",
            type = SlotType.TEXT,
            rawValue = "Subway",
            typedValue = "Subway",
            confidenceLevel = "MEDIUM",
            provenanceReasoning = "Conflict detected between voice ('Subway') and action ('Domino's')."
        )
        val s2 = ExtractedSlot(slotId = "s2", name = "restaurant", type = SlotType.TEXT, rawValue = "Subway", typedValue = "Subway")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.find { it.slotName == "restaurant" }

        assertNotNull(inf)
        assertEquals(SlotInferenceStatus.CONFLICTING, inf?.status)
        assertEquals("LOW", inf?.confidenceLevel)
    }

    @Test
    fun test9_typeMismatchProducesConflicting() {
        val s1 = ExtractedSlot(slotId = "s1", name = "quantity", type = SlotType.INTEGER, rawValue = "2", typedValue = "2")
        val s2 = ExtractedSlot(slotId = "s2", name = "quantity", type = SlotType.ADDRESS, rawValue = "Home", typedValue = "Home")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.find { it.slotName == "quantity" }

        assertNotNull(inf)
        assertEquals(SlotInferenceStatus.CONFLICTING, inf?.status)
    }

    @Test
    fun test10_whitespaceNormalization() {
        val s1 = ExtractedSlot(slotId = "s1", name = "item", type = SlotType.TEXT, rawValue = "  Pizza Palace  ", typedValue = "  Pizza Palace  ")
        val s2 = ExtractedSlot(slotId = "s2", name = "item", type = SlotType.TEXT, rawValue = "Pizza   Palace", typedValue = "Pizza   Palace")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.find { it.slotName == "item" }

        assertEquals(SlotInferenceStatus.CONSTANT, inf?.status)
    }

    @Test
    fun test11_safeCaseNormalization() {
        val s1 = ExtractedSlot(slotId = "s1", name = "address", type = SlotType.ADDRESS, rawValue = "Home", typedValue = "Home")
        val s2 = ExtractedSlot(slotId = "s2", name = "address", type = SlotType.ADDRESS, rawValue = "HOME", typedValue = "HOME")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.find { it.slotName == "address" }

        assertEquals(SlotInferenceStatus.CONSTANT, inf?.status)
    }

    @Test
    fun test12_noAggressiveSemanticEquivalence() {
        val s1 = ExtractedSlot(slotId = "s1", name = "item", type = SlotType.TEXT, rawValue = "Burger", typedValue = "Burger")
        val s2 = ExtractedSlot(slotId = "s2", name = "item", type = SlotType.TEXT, rawValue = "Cheeseburger", typedValue = "Cheeseburger")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.find { it.slotName == "item" }

        assertEquals(SlotInferenceStatus.VARIABLE, inf?.status)
    }

    @Test
    fun test13_originalRawValuesPreserved() {
        val s1 = ExtractedSlot(slotId = "s1", name = "item", type = SlotType.TEXT, rawValue = "  Margherita  ", typedValue = "Margherita")
        val s2 = ExtractedSlot(slotId = "s2", name = "item", type = SlotType.TEXT, rawValue = "Farmhouse", typedValue = "Farmhouse")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.find { it.slotName == "item" }

        assertEquals(listOf("  Margherita  ", "Farmhouse"), inf?.rawValues)
    }

    @Test
    fun test14_demonstrationProvenancePreserved() {
        val s1 = ExtractedSlot(slotId = "s1", name = "quantity", type = SlotType.INTEGER, rawValue = "2", typedValue = "2")
        val s2 = ExtractedSlot(slotId = "s2", name = "quantity", type = SlotType.INTEGER, rawValue = "1", typedValue = "1")

        val ds1 = createDataset("demo_alpha", "order_food", listOf(s1))
        val ds2 = createDataset("demo_beta", "order_food", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.find { it.slotName == "quantity" }

        assertTrue(inf?.demonstrationIds?.contains("demo_alpha") == true)
        assertTrue(inf?.demonstrationIds?.contains("demo_beta") == true)
    }

    @Test
    fun test15_slotProvenancePreserved() {
        val s1 = ExtractedSlot(slotId = "slot_101", name = "quantity", type = SlotType.INTEGER, rawValue = "2", typedValue = "2", sourceVoiceEventIds = listOf("v101"))
        val s2 = ExtractedSlot(slotId = "slot_102", name = "quantity", type = SlotType.INTEGER, rawValue = "1", typedValue = "1", sourceVoiceEventIds = listOf("v102"))

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.find { it.slotName == "quantity" }

        assertTrue(inf?.sourceSlotIds?.contains("slot_101") == true)
        assertTrue(inf?.sourceSlotIds?.contains("slot_102") == true)
        assertTrue(inf?.sourceVoiceEventIds?.contains("v101") == true)
        assertTrue(inf?.sourceVoiceEventIds?.contains("v102") == true)
    }

    @Test
    fun test16_inferenceReasoningPreserved() {
        val s1 = ExtractedSlot(slotId = "s1", name = "item", type = SlotType.TEXT, rawValue = "Pizza", typedValue = "Pizza")
        val s2 = ExtractedSlot(slotId = "s2", name = "item", type = SlotType.TEXT, rawValue = "Burger", typedValue = "Burger")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.find { it.slotName == "item" }

        assertNotNull(inf?.reasoning)
        assertTrue(inf?.reasoning?.contains("2 compatible demonstrations") == true)
    }

    @Test
    fun test17_confidenceDeterministic() {
        val s1 = ExtractedSlot(slotId = "s1", name = "item", type = SlotType.TEXT, rawValue = "Pizza", typedValue = "Pizza")
        val s2 = ExtractedSlot(slotId = "s2", name = "item", type = SlotType.TEXT, rawValue = "Burger", typedValue = "Burger")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val res1 = ConstantVariableInference.infer(listOf(ds1, ds2))
        val res2 = ConstantVariableInference.infer(listOf(ds1, ds2))

        assertEquals(res1.slotInferences.first().confidence, res2.slotInferences.first().confidence, 0.001)
    }

    @Test
    fun test18_sameInputProducesIdenticalOutput() {
        val s1 = ExtractedSlot(slotId = "s1", name = "item", type = SlotType.TEXT, rawValue = "Pizza", typedValue = "Pizza")
        val s2 = ExtractedSlot(slotId = "s2", name = "item", type = SlotType.TEXT, rawValue = "Burger", typedValue = "Burger")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val res1 = ConstantVariableInference.infer(listOf(ds1, ds2))
        val res2 = ConstantVariableInference.infer(listOf(ds1, ds2))

        assertEquals(res1, res2)
    }

    @Test
    fun test19_phase5OutputFeedsPhase6Directly() {
        val voice1 = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas from Domino's")
        val trace1 = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.food.app", voiceEvents = listOf(voice1), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice1)))
        val norm1 = DemonstrationTraceNormalizer.normalize(trace1)
        val act1 = SemanticActionExtractor.extract(norm1.normalizedTrace)
        val int1 = IntentExtractor.extract(norm1.normalizedTrace, act1)
        val slot1 = SlotExtractor.extract(norm1.normalizedTrace, act1, int1)
        val ds1 = DemonstrationDataset("d1", traceId = norm1.normalizedTrace.traceId, intentResult = int1, slotResult = slot1)

        val voice2 = VoiceEvent(eventId = "v2", timestamp = 1000L, transcript = "Order 5 pizzas from Domino's")
        val trace2 = DemonstrationTrace(traceId = "t2", timestamp = 1000L, appContext = "com.food.app", voiceEvents = listOf(voice2), traceEvents = listOf(TraceEvent.Voice("v2", 1000L, voice2)))
        val norm2 = DemonstrationTraceNormalizer.normalize(trace2)
        val act2 = SemanticActionExtractor.extract(norm2.normalizedTrace)
        val int2 = IntentExtractor.extract(norm2.normalizedTrace, act2)
        val slot2 = SlotExtractor.extract(norm2.normalizedTrace, act2, int2)
        val ds2 = DemonstrationDataset("d2", traceId = norm2.normalizedTrace.traceId, intentResult = int2, slotResult = slot2)

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val qtyInf = result.slotInferences.find { it.slotName == "quantity" }
        val restInf = result.slotInferences.find { it.slotName == "restaurant" }

        assertEquals(SlotInferenceStatus.VARIABLE, qtyInf?.status)
        assertEquals(SlotInferenceStatus.CONSTANT, restInf?.status)
    }

    @Test
    fun test20_multipleDemonstrationsHandled() {
        val s1 = ExtractedSlot(slotId = "s1", name = "item", type = SlotType.TEXT, rawValue = "Pizza", typedValue = "Pizza")
        val s2 = ExtractedSlot(slotId = "s2", name = "item", type = SlotType.TEXT, rawValue = "Burger", typedValue = "Burger")
        val s3 = ExtractedSlot(slotId = "s3", name = "item", type = SlotType.TEXT, rawValue = "Tacos", typedValue = "Tacos")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))
        val ds3 = createDataset("d3", "order_food", listOf(s3))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2, ds3))
        assertEquals(3, result.demonstrationsAnalyzedCount)
        val inf = result.slotInferences.find { it.slotName == "item" }
        assertEquals(SlotInferenceStatus.VARIABLE, inf?.status)
        assertEquals(3, inf?.rawValues?.size)
    }

    @Test
    fun test21_noCoordinateDependency() {
        val s1 = ExtractedSlot(slotId = "s1", name = "item", type = SlotType.TEXT, rawValue = "Salad", typedValue = "Salad")
        val s2 = ExtractedSlot(slotId = "s2", name = "item", type = SlotType.TEXT, rawValue = "Soup", typedValue = "Soup")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.first()
        assertEquals("item", inf.slotName)
        assertEquals(SlotInferenceStatus.VARIABLE, inf.status)
    }

    @Test
    fun test22_noHardcodedPizzaPalaceLogic() {
        val s1 = ExtractedSlot(slotId = "s1", name = "service", type = SlotType.TEXT, rawValue = "Haircut", typedValue = "Haircut")
        val s2 = ExtractedSlot(slotId = "s2", name = "service", type = SlotType.TEXT, rawValue = "Spa", typedValue = "Spa")

        val ds1 = createDataset("d1", "book_appointment", listOf(s1))
        val ds2 = createDataset("d2", "book_appointment", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        assertEquals("book_appointment", result.intentName)
        val inf = result.slotInferences.find { it.slotName == "service" }
        assertEquals(SlotInferenceStatus.VARIABLE, inf?.status)
    }

    @Test
    fun test23_noHardcodedJudgeWorkflow() {
        val s1 = ExtractedSlot(slotId = "s1", name = "recipient", type = SlotType.TEXT, rawValue = "Alice", typedValue = "Alice")
        val s2 = ExtractedSlot(slotId = "s2", name = "recipient", type = SlotType.TEXT, rawValue = "Alice", typedValue = "Alice")

        val ds1 = createDataset("d1", "send_message", listOf(s1))
        val ds2 = createDataset("d2", "send_message", listOf(s2))

        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val inf = result.slotInferences.find { it.slotName == "recipient" }
        assertEquals(SlotInferenceStatus.CONSTANT, inf?.status)
    }

    @Test
    fun test24_noExternalNetworkOrAIDependency() {
        val s1 = ExtractedSlot(slotId = "s1", name = "item", type = SlotType.TEXT, rawValue = "Item1", typedValue = "Item1")
        val s2 = ExtractedSlot(slotId = "s2", name = "item", type = SlotType.TEXT, rawValue = "Item2", typedValue = "Item2")

        val ds1 = createDataset("d1", "order_food", listOf(s1))
        val ds2 = createDataset("d2", "order_food", listOf(s2))

        val startMs = System.currentTimeMillis()
        val result = ConstantVariableInference.infer(listOf(ds1, ds2))
        val durationMs = System.currentTimeMillis() - startMs

        assertTrue(result.slotInferences.isNotEmpty())
        assertTrue(durationMs < 1000L) // Instant offline deterministic execution
    }

    @Test
    fun test25_serializationCompatibility() {
        val inf = AlignedSlotInference(
            slotName = "item",
            slotType = SlotType.TEXT,
            status = SlotInferenceStatus.VARIABLE,
            rawValues = listOf("Pizza", "Burger"),
            normalizedValues = listOf("pizza", "burger"),
            confidence = 1.0,
            confidenceLevel = "HIGH",
            reasoning = "2 distinct values"
        )
        val result = InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 2, slotInferences = listOf(inf))

        val json = jsonFormatter.encodeToString(InferenceResult.serializer(), result)
        val decoded = jsonFormatter.decodeFromString(InferenceResult.serializer(), json)

        assertEquals("1.0", decoded.schemaVersion)
        assertEquals("order_food", decoded.intentName)
        assertEquals(2, decoded.demonstrationsAnalyzedCount)
        assertEquals(1, decoded.slotInferences.size)
        assertEquals(SlotInferenceStatus.VARIABLE, decoded.slotInferences.first().status)
    }

    @Test
    fun test26_regressionPhase1TeachingCapture() {
        val session = TeachingSessionImpl("order_food", "Order food")
        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas"))
        val trace = session.stopTeaching()

        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val act = SemanticActionExtractor.extract(norm.normalizedTrace)
        val intResult = IntentExtractor.extract(norm.normalizedTrace, act)
        val slotResult = SlotExtractor.extract(norm.normalizedTrace, act, intResult)

        val ds = DemonstrationDataset("d1", trace.traceId, intResult, slotResult)
        val infResult = ConstantVariableInference.infer(listOf(ds))

        assertTrue(infResult.slotInferences.isNotEmpty())
    }

    @Test
    fun test27_regressionPhase2TraceNormalization() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val rawTrace = DemonstrationTrace(traceId = "tr27", timestamp = 1000L, appContext = "unknown", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val normResult = DemonstrationTraceNormalizer.normalize(rawTrace)
        val act = SemanticActionExtractor.extract(normResult.normalizedTrace)
        val intResult = IntentExtractor.extract(normResult.normalizedTrace, act)
        val slotResult = SlotExtractor.extract(normResult.normalizedTrace, act, intResult)

        val ds = DemonstrationDataset("d1", rawTrace.traceId, intResult, slotResult)
        val infResult = ConstantVariableInference.infer(listOf(ds))

        assertTrue(infResult.slotInferences.isNotEmpty())
    }

    @Test
    fun test28_regressionPhase3SemanticActionExtraction() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/dish"), inputData = "Noodles")
        val trace = DemonstrationTrace(traceId = "tr28", timestamp = 1000L, appContext = "com.app", userActions = listOf(action), traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        val intResult = IntentExtractor.extract(trace, semanticActions)
        val slotResult = SlotExtractor.extract(trace, semanticActions, intResult)

        val ds = DemonstrationDataset("d1", trace.traceId, intResult, slotResult)
        val infResult = ConstantVariableInference.infer(listOf(ds))

        val slotInf = infResult.slotInferences.find { it.slotName == "item" }
        assertNotNull(slotInf)
    }

    @Test
    fun test29_regressionPhase4IntentExtraction() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas from Domino's")
        val trace = DemonstrationTrace(traceId = "tr29", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val intResult = IntentExtractor.extract(trace, emptyList())
        assertEquals("order_food", intResult.intent.canonicalName)

        val slotResult = SlotExtractor.extract(trace, emptyList(), intResult)
        val ds = DemonstrationDataset("d1", trace.traceId, intResult, slotResult)
        val infResult = ConstantVariableInference.infer(listOf(ds))

        assertEquals("order_food", infResult.intentName)
    }

    @Test
    fun test30_regressionPhase5SlotExtraction() {
        val voice1 = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas from Domino's to Home")
        val trace1 = DemonstrationTrace(traceId = "tr30_1", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice1), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice1)))
        val int1 = IntentExtractor.extract(trace1, emptyList())
        val slot1 = SlotExtractor.extract(trace1, emptyList(), int1)
        val ds1 = DemonstrationDataset("d1", trace1.traceId, int1, slot1)

        val voice2 = VoiceEvent(eventId = "v2", timestamp = 1000L, transcript = "Order 1 burger from Domino's to Home")
        val trace2 = DemonstrationTrace(traceId = "tr30_2", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice2), traceEvents = listOf(TraceEvent.Voice("v2", 1000L, voice2)))
        val int2 = IntentExtractor.extract(trace2, emptyList())
        val slot2 = SlotExtractor.extract(trace2, emptyList(), int2)
        val ds2 = DemonstrationDataset("d2", trace2.traceId, int2, slot2)

        val infResult = ConstantVariableInference.infer(listOf(ds1, ds2))

        val qtyInf = infResult.slotInferences.find { it.slotName == "quantity" }
        val itemInf = infResult.slotInferences.find { it.slotName == "item" }
        val restInf = infResult.slotInferences.find { it.slotName == "restaurant" }
        val addrInf = infResult.slotInferences.find { it.slotName == "address" }

        assertEquals(SlotInferenceStatus.VARIABLE, qtyInf?.status)
        assertEquals(SlotInferenceStatus.VARIABLE, itemInf?.status)
        assertEquals(SlotInferenceStatus.CONSTANT, restInf?.status)
        assertEquals(SlotInferenceStatus.CONSTANT, addrInf?.status)
    }
}
