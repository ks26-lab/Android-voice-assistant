package com.chockXlate.teachablevoice.command.interpretation

import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationAlignment
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset
import com.chockXlate.teachablevoice.learning.inference.ConstantVariableInference
import com.chockXlate.teachablevoice.learning.intent.IntentExtractor
import com.chockXlate.teachablevoice.learning.slots.SlotExtractor
import com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer
import com.chockXlate.teachablevoice.skill.inspector.WorkflowInspectorImpl
import com.chockXlate.teachablevoice.skill.repository.LocalSkillRepository
import com.chockXlate.teachablevoice.skill.validation.WorkflowValidator
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionImpl
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandInterpreterTest {

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    // --- Intent Tests (1-5) ---

    @Test
    fun test1_exactFoodOrderCommand() {
        val result = CommandInterpreter.understandCommand("Order 2 Farmhouse pizzas from Pizza Palace")
        assertEquals("order_food", result.intent.canonicalName)
        assertEquals(1.0, result.intentConfidence, 0.01)
    }

    @Test
    fun test2_foodOrderParaphrase1() {
        val result = CommandInterpreter.understandCommand("Get me 2 Farmhouse pizzas from Pizza Palace")
        assertEquals("order_food", result.intent.canonicalName)
    }

    @Test
    fun test3_foodOrderParaphrase2() {
        val result = CommandInterpreter.understandCommand("Can you order 2 Farmhouse pizzas from Pizza Palace?")
        assertEquals("order_food", result.intent.canonicalName)
    }

    @Test
    fun test4_unknownIntent() {
        val result = CommandInterpreter.understandCommand("I want to book a flight to Tokyo")
        assertEquals("unknown", result.intent.canonicalName)
        assertEquals(0.0, result.intentConfidence, 0.01)
        assertTrue(result.unresolvedItems.contains("intent"))
    }

    @Test
    fun test5_emptyOrWhitespaceCommand() {
        val result = CommandInterpreter.understandCommand("   ")
        assertEquals("unknown", result.intent.canonicalName)
        assertEquals("UNKNOWN_COMMAND", result.status)
    }

    // --- Slot Tests (6-14) ---

    @Test
    fun test6_restaurantExtraction() {
        val result = CommandInterpreter.understandCommand("Order food from Subway")
        val rest = result.slots.find { it.name == "restaurant" }
        assertNotNull(rest)
        assertEquals("Subway", rest?.rawValue)
        assertEquals(SlotType.TEXT, rest?.type)
    }

    @Test
    fun test7_itemExtraction() {
        val result = CommandInterpreter.understandCommand("Order Margherita Pizza")
        val item = result.slots.find { it.name == "item" }
        assertNotNull(item)
        assertEquals("Margherita Pizza", item?.rawValue)
    }

    @Test
    fun test8_quantityExtraction() {
        val result = CommandInterpreter.understandCommand("Order 3 pizzas")
        val qty = result.slots.find { it.name == "quantity" }
        assertNotNull(qty)
        assertEquals("3", qty?.rawValue)
        assertEquals(SlotType.INTEGER, qty?.type)
    }

    @Test
    fun test9_addressExtraction() {
        val result = CommandInterpreter.understandCommand("Deliver pizza to 100 Tech Park")
        val addr = result.slots.find { it.name == "address" }
        assertNotNull(addr)
        assertEquals("100 Tech Park", addr?.rawValue)
        assertEquals(SlotType.ADDRESS, addr?.type)
    }

    @Test
    fun test10_multipleSlotsInOneCommand() {
        val result = CommandInterpreter.understandCommand("Order 5 Tacos from Taco Town to Suite 10")
        assertEquals(4, result.slots.size)
        assertTrue(result.slots.any { it.name == "restaurant" })
        assertTrue(result.slots.any { it.name == "item" })
        assertTrue(result.slots.any { it.name == "quantity" })
        assertTrue(result.slots.any { it.name == "address" })
    }

    @Test
    fun test11_missingSlotUnresolved() {
        val result = CommandInterpreter.understandCommand("Order a pizza")
        assertTrue(result.unresolvedItems.contains("restaurant"))
        assertTrue(result.unresolvedItems.contains("address"))
        assertNull(result.slots.find { it.name == "address" })
    }

    @Test
    fun test12_typedIntegerQuantity() {
        val result = CommandInterpreter.understandCommand("Order 4 burgers")
        val qty = result.slots.find { it.name == "quantity" }
        assertEquals("4", qty?.typedValue)
        assertEquals(SlotType.INTEGER, qty?.type)
    }

    @Test
    fun test13_textSlot() {
        val result = CommandInterpreter.understandCommand("Order Cheese Burger")
        val item = result.slots.find { it.name == "item" }
        assertEquals(SlotType.TEXT, item?.type)
    }

    @Test
    fun test14_addressSlot() {
        val result = CommandInterpreter.understandCommand("Deliver food to Home")
        val addr = result.slots.find { it.name == "address" }
        assertEquals(SlotType.ADDRESS, addr?.type)
    }

    // --- Normalization Tests (15-18) ---

    @Test
    fun test15_leadingTrailingWhitespace() {
        val norm = CommandInterpreter.normalizeCommand("   Order pizza   ")
        assertEquals("order pizza", norm)
    }

    @Test
    fun test16_repeatedWhitespace() {
        val norm = CommandInterpreter.normalizeCommand("Order    2   pizzas")
        assertEquals("order 2 pizzas", norm)
    }

    @Test
    fun test17_caseNormalization() {
        val norm = CommandInterpreter.normalizeCommand("Order PIZZA")
        assertEquals("order pizza", norm)
    }

    @Test
    fun test18_deterministicNormalization() {
        val cmd = "  Order 2 Farmhouse Pizzas  "
        val n1 = CommandInterpreter.normalizeCommand(cmd)
        val n2 = CommandInterpreter.normalizeCommand(cmd)
        assertEquals(n1, n2)
    }

    // --- Safety / Correctness Tests (19-23) ---

    @Test
    fun test19_noWorkflowExampleValuesLeakIntoCommandResults() {
        // Command extracts what was spoken in the command, not stored workflow defaults
        val result = CommandInterpreter.understandCommand("Order 1 Salad from Green Bowl")
        val item = result.slots.find { it.name == "item" }
        val rest = result.slots.find { it.name == "restaurant" }

        assertEquals("Salad", item?.rawValue)
        assertEquals("Green Bowl", rest?.rawValue)
    }

    @Test
    fun test20_noCommandValueBecomesWorkflowConstant() {
        val result = CommandInterpreter.understandCommand("Order 2 pizzas")
        val qty = result.slots.find { it.name == "quantity" }
        assertEquals("Command Slot", qty?.status?.name, "EXTRACTED")
    }

    @Test
    fun test21_noFabricatedMissingValues() {
        val result = CommandInterpreter.understandCommand("Order 2 pizzas")
        assertNull(result.slots.find { it.name == "restaurant" })
        assertNull(result.slots.find { it.name == "address" })
        assertTrue(result.unresolvedItems.contains("restaurant"))
        assertTrue(result.unresolvedItems.contains("address"))
    }

    @Test
    fun test22_noCoordinateBasedData() {
        val result = CommandInterpreter.understandCommand("Order 2 pizzas")
        val json = jsonFormatter.encodeToString(CommandUnderstandingResult.serializer(), result)
        assertFalse(json.contains("\"x\":"))
        assertFalse(json.contains("\"y\":"))
    }

    @Test
    fun test23_noNetworkOrExternalAIDependency() {
        val startMs = System.currentTimeMillis()
        val result = CommandInterpreter.understandCommand("Order 2 Farmhouse pizzas from Pizza Palace")
        val durationMs = System.currentTimeMillis() - startMs

        assertEquals("order_food", result.intent.canonicalName)
        assertTrue(durationMs < 1000L) // Instant local deterministic parsing
    }

    // --- Determinism Tests (24-26) ---

    @Test
    fun test24_sameCommandProducesIdenticalResult() {
        val cmd = "Order 2 Farmhouse pizzas from Pizza Palace"
        val res1 = CommandInterpreter.understandCommand(cmd)
        val res2 = CommandInterpreter.understandCommand(cmd)

        assertEquals(res1.intent.canonicalName, res2.intent.canonicalName)
        assertEquals(res1.slots.size, res2.slots.size)
    }

    @Test
    fun test25_sameCommandRepeatedIdenticalSlotOrdering() {
        val cmd = "Order 2 Farmhouse pizzas from Pizza Palace to Home"
        val res1 = CommandInterpreter.understandCommand(cmd)
        val res2 = CommandInterpreter.understandCommand(cmd)

        assertEquals(res1.slots.map { it.name }, res2.slots.map { it.name })
    }

    @Test
    fun test26_serializationRoundTrip() {
        val result = CommandInterpreter.understandCommand("Order 2 Farmhouse pizzas from Pizza Palace")
        val json = jsonFormatter.encodeToString(CommandUnderstandingResult.serializer(), result)
        val decoded = jsonFormatter.decodeFromString(CommandUnderstandingResult.serializer(), json)

        assertEquals("1.0", decoded.schemaVersion)
        assertEquals(result.rawCommand, decoded.rawCommand)
        assertEquals(result.intent.canonicalName, decoded.intent.canonicalName)
        assertEquals(result.slots.size, decoded.slots.size)
    }

    // --- Regressions (27-36) ---

    @Test
    fun test27_phase1Regression() {
        val session = TeachingSessionImpl("order_food", "Order food")
        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas"))
        val trace = session.stopTeaching()
        assertNotNull(trace)
    }

    @Test
    fun test28_phase2Regression() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace = DemonstrationTrace(traceId = "t28", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))
        val norm = DemonstrationTraceNormalizer.normalize(trace)
        assertNotNull(norm.normalizedTrace)
    }

    @Test
    fun test29_phase3Regression() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace = DemonstrationTrace(traceId = "t29", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))
        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val actions = SemanticActionExtractor.extract(norm.normalizedTrace)
        assertNotNull(actions)
    }

    @Test
    fun test30_phase4Regression() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace = DemonstrationTrace(traceId = "t30", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))
        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val intentRes = IntentExtractor.extract(norm.normalizedTrace, emptyList())
        assertEquals("order_food", intentRes.intent.canonicalName)
    }

    @Test
    fun test31_phase5Regression() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace = DemonstrationTrace(traceId = "t31", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))
        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val intRes = IntentExtractor.extract(norm.normalizedTrace, emptyList())
        val slotRes = SlotExtractor.extract(norm.normalizedTrace, emptyList(), intRes)
        assertTrue(slotRes.extractedSlots.isNotEmpty())
    }

    @Test
    fun test32_phase6Regression() {
        val voice1 = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace1 = DemonstrationTrace(traceId = "t32_1", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice1), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice1)))
        val int1 = IntentExtractor.extract(trace1, emptyList())
        val slot1 = SlotExtractor.extract(trace1, emptyList(), int1)
        val ds1 = DemonstrationDataset("d1", trace1.traceId, int1, slot1)

        val voice2 = VoiceEvent(eventId = "v2", timestamp = 1000L, transcript = "Order 3 pizzas")
        val trace2 = DemonstrationTrace(traceId = "t32_2", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice2), traceEvents = listOf(TraceEvent.Voice("v2", 1000L, voice2)))
        val int2 = IntentExtractor.extract(trace2, emptyList())
        val slot2 = SlotExtractor.extract(trace2, emptyList(), int2)
        val ds2 = DemonstrationDataset("d2", trace2.traceId, int2, slot2)

        val inf = ConstantVariableInference.infer(listOf(ds1, ds2))
        assertTrue(inf.slotInferences.isNotEmpty())
    }

    @Test
    fun test33_phase7Regression() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace = DemonstrationTrace(traceId = "t33", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))
        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val act = SemanticActionExtractor.extract(norm.normalizedTrace)
        val intRes = IntentExtractor.extract(norm.normalizedTrace, act)
        val slotRes = SlotExtractor.extract(norm.normalizedTrace, act, intRes)
        val ds = DemonstrationDataset("d1", trace.traceId, intRes, slotRes)
        val infRes = ConstantVariableInference.infer(listOf(ds))

        val synthRes = WorkflowSynthesizer.synthesize(intRes, act, slotRes, DemonstrationAlignment.align(listOf(ds)), infRes, norm.normalizedTrace)
        assertNotNull(synthRes.workflow)
    }

    @Test
    fun test34_phase8Regression() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace = DemonstrationTrace(traceId = "t34", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))
        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val act = SemanticActionExtractor.extract(norm.normalizedTrace)
        val intRes = IntentExtractor.extract(norm.normalizedTrace, act)
        val slotRes = SlotExtractor.extract(norm.normalizedTrace, act, intRes)
        val ds = DemonstrationDataset("d1", trace.traceId, intRes, slotRes)
        val infRes = ConstantVariableInference.infer(listOf(ds))
        val synthRes = WorkflowSynthesizer.synthesize(intRes, act, slotRes, DemonstrationAlignment.align(listOf(ds)), infRes, norm.normalizedTrace)
        val wf = synthRes.workflow!!

        val valRes = WorkflowValidator.validate(wf)
        val repo = LocalSkillRepository()
        repo.saveWorkflow(wf)

        assertTrue(valRes.isStoreable)
        assertNotNull(repo.getWorkflowById(wf.skillId))
    }

    @Test
    fun test35_phase9Regression() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace = DemonstrationTrace(traceId = "t35", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))
        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val act = SemanticActionExtractor.extract(norm.normalizedTrace)
        val intRes = IntentExtractor.extract(norm.normalizedTrace, act)
        val slotRes = SlotExtractor.extract(norm.normalizedTrace, act, intRes)
        val ds = DemonstrationDataset("d1", trace.traceId, intRes, slotRes)
        val infRes = ConstantVariableInference.infer(listOf(ds))
        val synthRes = WorkflowSynthesizer.synthesize(intRes, act, slotRes, DemonstrationAlignment.align(listOf(ds)), infRes, norm.normalizedTrace)
        val wf = synthRes.workflow!!

        val repo = LocalSkillRepository()
        repo.saveWorkflow(wf)

        val insp = WorkflowInspectorImpl.inspect(wf, repo)
        assertEquals("STORED", insp.storeStatus)
    }

    @Test
    fun test36_sharedContractSerializationRegression() {
        val result = CommandInterpreter.understandCommand("Order 2 pizzas")
        val json = jsonFormatter.encodeToString(CommandUnderstandingResult.serializer(), result)
        val decoded = jsonFormatter.decodeFromString(CommandUnderstandingResult.serializer(), json)

        assertEquals("1.0", decoded.schemaVersion)
    }
}
