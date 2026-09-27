package com.chockXlate.teachablevoice.learning.synthesis

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.learning.actions.SemanticAction
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.learning.actions.SemanticActionType
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationAlignment
import com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset
import com.chockXlate.teachablevoice.learning.inference.AlignedSlotInference
import com.chockXlate.teachablevoice.learning.inference.ConstantVariableInference
import com.chockXlate.teachablevoice.learning.inference.InferenceResult
import com.chockXlate.teachablevoice.learning.inference.SlotInferenceStatus
import com.chockXlate.teachablevoice.learning.intent.Intent
import com.chockXlate.teachablevoice.learning.intent.IntentExtractionResult
import com.chockXlate.teachablevoice.learning.intent.IntentExtractor
import com.chockXlate.teachablevoice.learning.slots.ExtractedSlot
import com.chockXlate.teachablevoice.learning.slots.SlotExtractionResult
import com.chockXlate.teachablevoice.learning.slots.SlotExtractor
import com.chockXlate.teachablevoice.learning.targets.SemanticTarget
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionImpl
import com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowSynthesizerTest {

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun createPipelineInputs(
        transcript1: String,
        transcript2: String
    ): Triple<DemonstrationTrace, IntentExtractionResult, InferenceResult> {
        val voice1 = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = transcript1)
        val trace1 = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice1), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice1)))
        val norm1 = DemonstrationTraceNormalizer.normalize(trace1)
        val act1 = SemanticActionExtractor.extract(norm1.normalizedTrace)
        val int1 = IntentExtractor.extract(norm1.normalizedTrace, act1)
        val slot1 = SlotExtractor.extract(norm1.normalizedTrace, act1, int1)
        val ds1 = DemonstrationDataset("d1", norm1.normalizedTrace.traceId, int1, slot1)

        val voice2 = VoiceEvent(eventId = "v2", timestamp = 1000L, transcript = transcript2)
        val trace2 = DemonstrationTrace(traceId = "t2", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice2), traceEvents = listOf(TraceEvent.Voice("v2", 1000L, voice2)))
        val norm2 = DemonstrationTraceNormalizer.normalize(trace2)
        val act2 = SemanticActionExtractor.extract(norm2.normalizedTrace)
        val int2 = IntentExtractor.extract(norm2.normalizedTrace, act2)
        val slot2 = SlotExtractor.extract(norm2.normalizedTrace, act2, int2)
        val ds2 = DemonstrationDataset("d2", norm2.normalizedTrace.traceId, int2, slot2)

        val alignRes = DemonstrationAlignment.align(listOf(ds1, ds2))
        val infRes = ConstantVariableInference.infer(alignRes)

        return Triple(norm1.normalizedTrace, int1, infRes)
    }

    @Test
    fun test1_intentTransferredFromPhase4() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Order 2 Margherita pizzas from Pizza Palace to Home",
            "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )

        val result = WorkflowSynthesizer.synthesize(
            intentResult = intResult,
            semanticActions = emptyList(),
            slotResult = SlotExtractionResult(intentName = intResult.intent.canonicalName),
            alignmentResult = DemonstrationAlignment.align(emptyList()),
            inferenceResult = infResult,
            trace = trace
        )

        assertNotNull(result.workflow)
        assertEquals("order_food", result.workflow?.intent)
    }

    @Test
    fun test2_variableSlotsComeFromPhase6VariableResults() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Order 2 Margherita pizzas from Pizza Palace to Home",
            "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )

        val result = WorkflowSynthesizer.synthesize(
            intentResult = intResult,
            semanticActions = emptyList(),
            slotResult = SlotExtractionResult(intentName = intResult.intent.canonicalName),
            alignmentResult = DemonstrationAlignment.align(emptyList()),
            inferenceResult = infResult,
            trace = trace
        )

        val vars = result.workflow?.slots?.filter { it.required } ?: emptyList()
        assertTrue(vars.any { it.name == "item" })
        assertTrue(vars.any { it.name == "quantity" })
    }

    @Test
    fun test3_constantsRemainConstants() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Order 2 Margherita pizzas from Pizza Palace to Home",
            "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )

        val result = WorkflowSynthesizer.synthesize(
            intentResult = intResult,
            semanticActions = emptyList(),
            slotResult = SlotExtractionResult(intentName = intResult.intent.canonicalName),
            alignmentResult = DemonstrationAlignment.align(emptyList()),
            inferenceResult = infResult,
            trace = trace
        )

        val consts = result.workflow?.slots?.filter { !it.required } ?: emptyList()
        assertTrue(consts.any { it.name == "restaurant" })
        assertTrue(consts.any { it.name == "address" })
    }

    @Test
    fun test4_unknownSlotsNotSilentlyConvertedToVariables() {
        val inf = AlignedSlotInference(
            slotName = "special_instructions",
            slotType = SlotType.TEXT,
            status = SlotInferenceStatus.UNKNOWN,
            confidence = 0.5,
            confidenceLevel = "LOW",
            reasoning = "Single demonstration evidence"
        )
        val infRes = InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 1, slotInferences = listOf(inf))

        val intent = Intent(intentId = "i1", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH")
        val intRes = IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK")
        val trace = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.app")

        val result = WorkflowSynthesizer.synthesize(intRes, emptyList(), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infRes, trace)

        assertTrue(result.workflow?.slots?.none { it.name == "special_instructions" } == true)
        assertFalse(result.isExecutable)
        assertTrue(result.warnings.any { it.contains("special_instructions") })
    }

    @Test
    fun test5_conflictingSlotsNotSilentlyResolved() {
        val inf = AlignedSlotInference(
            slotName = "restaurant",
            slotType = SlotType.TEXT,
            status = SlotInferenceStatus.CONFLICTING,
            confidence = 0.5,
            confidenceLevel = "LOW",
            reasoning = "Phase 5 Conflict"
        )
        val infRes = InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 2, slotInferences = listOf(inf))

        val intent = Intent(intentId = "i1", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH")
        val intRes = IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK")
        val trace = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.app")

        val result = WorkflowSynthesizer.synthesize(intRes, emptyList(), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infRes, trace)

        // A conflicting draft must not become an executable literal workflow.
        assertNull(result.workflow)
        assertEquals(SynthesisStatus.BLOCKED, result.status)
        assertFalse(result.isExecutable)
    }

    @Test
    fun test6_variableStepReferencesUseCanonicalSlotIdentity() {
        val sa = SemanticAction(
            actionId = "sa1",
            timestamp = 1000L,
            actionType = SemanticActionType.INPUT_TEXT,
            target = SemanticTarget(role = "EditText", resourceId = "com.app:id/search_dish"),
            inputValue = "Margherita Pizza"
        )
        val inf = AlignedSlotInference(
            slotName = "item",
            slotType = SlotType.TEXT,
            status = SlotInferenceStatus.VARIABLE,
            rawValues = listOf("Margherita Pizza", "Farmhouse Pizza"),
            confidence = 1.0,
            confidenceLevel = "HIGH",
            reasoning = "Variable"
        )
        val infRes = InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 2, slotInferences = listOf(inf))
        val intent = Intent(intentId = "i1", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH")
        val intRes = IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK")
        val trace = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.app")

        val result = WorkflowSynthesizer.synthesize(intRes, listOf(sa), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infRes, trace)

        val step = result.workflow?.steps?.firstOrNull()
        assertNotNull(step)
        assertEquals("\${item}", step?.semanticSelector?.textSlot)
        assertNull(step?.semanticSelector?.text)
    }

    @Test
    fun test7_constantValuesRemainDistinguishable() {
        val sa = SemanticAction(
            actionId = "sa1",
            timestamp = 1000L,
            actionType = SemanticActionType.INPUT_TEXT,
            target = SemanticTarget(role = "EditText", resourceId = "com.app:id/restaurant_input"),
            inputValue = "Pizza Palace"
        )
        val inf = AlignedSlotInference(
            slotName = "restaurant",
            slotType = SlotType.TEXT,
            status = SlotInferenceStatus.CONSTANT,
            rawValues = listOf("Pizza Palace"),
            confidence = 1.0,
            confidenceLevel = "HIGH",
            reasoning = "Constant"
        )
        val infRes = InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 2, slotInferences = listOf(inf))
        val intent = Intent(intentId = "i1", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH")
        val intRes = IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK")
        val trace = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.app")

        val result = WorkflowSynthesizer.synthesize(intRes, listOf(sa), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infRes, trace)

        val step = result.workflow?.steps?.firstOrNull()
        assertNotNull(step)
        assertNull(step?.semanticSelector?.textSlot) // Not parameterized
        assertEquals("Pizza Palace", step?.semanticSelector?.text)
    }

    @Test
    fun test8_semanticActionsBecomeWorkflowSteps() {
        val sa1 = SemanticAction(actionId = "sa1", timestamp = 1000L, actionType = SemanticActionType.INPUT_TEXT, target = SemanticTarget(role = "EditText", resourceId = "id/input"), inputValue = "Subway")
        val sa2 = SemanticAction(actionId = "sa2", timestamp = 1002L, actionType = SemanticActionType.TAP, target = SemanticTarget(role = "Button", text = "Submit"), inputValue = null)

        val intent = Intent(intentId = "i1", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH")
        val intRes = IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK")
        val trace = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.app")
        val infRes = InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 1)

        val result = WorkflowSynthesizer.synthesize(intRes, listOf(sa1, sa2), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infRes, trace)

        assertEquals(2, result.workflow?.steps?.size)
        assertEquals("INPUT_TEXT", result.workflow?.steps?.get(0)?.semanticAction)
        assertEquals("TAP", result.workflow?.steps?.get(1)?.semanticAction)
    }

    @Test
    fun test9_workflowStepsDoNotContainCoordinates() {
        val sa = SemanticAction(actionId = "sa1", timestamp = 1000L, actionType = SemanticActionType.TAP, target = SemanticTarget(role = "Button", resourceId = "id/btn"), inputValue = null)
        val intent = Intent(intentId = "i1", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH")
        val intRes = IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK")
        val trace = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.app")

        val result = WorkflowSynthesizer.synthesize(intRes, listOf(sa), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 1), trace)

        val json = jsonFormatter.encodeToString(Workflow.serializer(), result.workflow!!)
        assertFalse(json.contains("\"x\":"))
        assertFalse(json.contains("\"y\":"))
        assertFalse(json.contains("coordinate"))
    }

    @Test
    fun test10_resourceIdsRolesTextUsedAsSemanticSelectors() {
        val sa = SemanticAction(actionId = "sa1", timestamp = 1000L, actionType = SemanticActionType.TAP, target = SemanticTarget(role = "Button", resourceId = "com.app:id/checkout_btn", text = "Checkout"), inputValue = null)
        val intent = Intent(intentId = "i1", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH")
        val trace = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.app")

        val result = WorkflowSynthesizer.synthesize(IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK"), listOf(sa), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 1), trace)

        val sel = result.workflow?.steps?.first()?.semanticSelector
        assertEquals("Button", sel?.role)
        assertEquals("com.app:id/checkout_btn", sel?.resourceId)
        assertEquals("Checkout", sel?.text)
    }

    @Test
    fun test11_stateEvidenceBecomesExpectedTransitions() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Order 2 Margherita pizzas from Pizza Palace to Home",
            "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )
        val sa = SemanticAction(actionId = "sa1", timestamp = 1000L, actionType = SemanticActionType.TAP, target = SemanticTarget(role = "Button", text = "Add"), inputValue = null)

        val result = WorkflowSynthesizer.synthesize(intResult, listOf(sa), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infResult, trace)

        val step = result.workflow?.steps?.first()
        assertNotNull(step?.expectedTransition)
        assertNotNull(step?.expectedTransition?.transitionType)
    }

    @Test
    fun test12_missingStateEvidenceDoesNotFabricateTransitions() {
        val sa = SemanticAction(actionId = "sa1", timestamp = 1000L, actionType = SemanticActionType.TAP, target = SemanticTarget(role = "Button", text = "Add"), inputValue = null)
        val intent = Intent(intentId = "i1", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH")
        val trace = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.app") // No state events

        val result = WorkflowSynthesizer.synthesize(IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK"), listOf(sa), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 1), trace)

        val step = result.workflow?.steps?.first()
        assertNull(step?.expectedTransition?.expectedElementAppeared)
        assertNull(step?.expectedTransition?.expectedElementDisappeared)
    }

    @Test
    fun test13_supportedPreconditionsPreserved() {
        val sa = SemanticAction(actionId = "sa1", timestamp = 1000L, actionType = SemanticActionType.TAP, target = SemanticTarget(role = "Button", resourceId = "id/btn", packageName = "com.food.app"), inputValue = null)
        val intent = Intent(intentId = "i1", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH")
        val trace = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.food.app")

        val result = WorkflowSynthesizer.synthesize(IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK"), listOf(sa), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 1), trace)

        val prec = result.workflow?.steps?.first()?.preconditions
        assertEquals("com.food.app", prec?.requiredPackage)
        assertNotNull(prec?.requiredElementPresent)
    }

    @Test
    fun test14_unsupportedPreconditionsNotInvented() {
        val sa = SemanticAction(actionId = "sa1", timestamp = 1000L, actionType = SemanticActionType.TAP, target = SemanticTarget(role = "Button"), inputValue = null)
        val intent = Intent(intentId = "i1", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH")
        val trace = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "")

        val result = WorkflowSynthesizer.synthesize(IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK"), listOf(sa), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 1), trace)

        val prec = result.workflow?.steps?.first()?.preconditions
        assertTrue(prec?.customConditions?.isEmpty() == true)
    }

    @Test
    fun test15_safetyBoundaryExists() {
        val intent = Intent(intentId = "i1", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH")
        val trace = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.app")

        val result = WorkflowSynthesizer.synthesize(IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK"), emptyList(), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 1), trace)

        assertNotNull(result.workflow?.safetyBoundary)
    }

    @Test
    fun test16_credentialPaymentActionsCannotBecomeExecutableSteps() {
        val sa = SemanticAction(
            actionId = "sa_otp",
            timestamp = 1000L,
            actionType = SemanticActionType.INPUT_TEXT,
            target = SemanticTarget(role = "EditText", resourceId = "com.bank.app:id/enter_otp_field"),
            inputValue = "123456"
        )
        val intent = Intent(intentId = "i1", canonicalName = "pay_money", confidence = 1.0, confidenceLevel = "HIGH")
        val trace = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.bank.app")

        val result = WorkflowSynthesizer.synthesize(IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK"), listOf(sa), SlotExtractionResult(intentName = "pay_money"), DemonstrationAlignment.align(emptyList()), InferenceResult(intentName = "pay_money", demonstrationsAnalyzedCount = 1), trace)

        val sb = result.workflow?.safetyBoundary
        assertTrue(sb?.requiresExplicitUserConfirmation == true)
        assertTrue(sb?.sensitiveKeywords?.contains("otp") == true)
        
        val step = result.workflow?.steps?.first()
        assertEquals(com.chockXlate.teachablevoice.contract.workflow.RecoveryStrategy.HANDOFF_TO_USER, step?.recoveryPolicy?.strategy)
        assertEquals(0, step?.recoveryPolicy?.maxRetries)
    }

    @Test
    fun test17_sensitiveValuesNotStoredAsWorkflowVariables() {
        val sa = SemanticAction(
            actionId = "sa_pass",
            timestamp = 1000L,
            actionType = SemanticActionType.INPUT_TEXT,
            target = SemanticTarget(role = "EditText", resourceId = "id/password_input"),
            inputValue = "SecretPassword123"
        )
        val inf = AlignedSlotInference(
            slotName = "password",
            slotType = SlotType.TEXT,
            status = SlotInferenceStatus.VARIABLE,
            rawValues = listOf("SecretPassword123"),
            confidence = 1.0,
            confidenceLevel = "HIGH",
            reasoning = "Test"
        )
        val infRes = InferenceResult(intentName = "login", demonstrationsAnalyzedCount = 2, slotInferences = listOf(inf))
        val intent = Intent(intentId = "i1", canonicalName = "login", confidence = 1.0, confidenceLevel = "HIGH")
        val trace = DemonstrationTrace(traceId = "t1", timestamp = 1000L, appContext = "com.app")

        val result = WorkflowSynthesizer.synthesize(IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK"), listOf(sa), SlotExtractionResult(intentName = "login"), DemonstrationAlignment.align(emptyList()), infRes, trace)

        val json = jsonFormatter.encodeToString(Workflow.serializer(), result.workflow!!)
        assertFalse(json.contains("SecretPassword123"))
    }

    @Test
    fun test18_provenanceIsPreserved() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Order 2 Margherita pizzas from Pizza Palace to Home",
            "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )

        val result = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infResult, trace)

        assertTrue(result.provenanceDemonstrationIds.isNotEmpty())
        assertTrue(result.evidenceSummary.isNotBlank())
    }

    @Test
    fun test19_synthesisIsDeterministic() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Order 2 Margherita pizzas from Pizza Palace to Home",
            "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )

        val res1 = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infResult, trace)
        val res2 = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infResult, trace)

        assertEquals(res1.status, res2.status)
        assertEquals(res1.workflow?.intent, res2.workflow?.intent)
        assertEquals(res1.workflow?.slots?.size, res2.workflow?.slots?.size)
    }

    @Test
    fun test20_sameInputProducesIdenticalWorkflowOutput() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Order 2 Margherita pizzas from Pizza Palace to Home",
            "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )

        val res1 = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infResult, trace)
        val res2 = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infResult, trace)

        val json1 = jsonFormatter.encodeToString(WorkflowSynthesisResult.serializer(), res1)
        val json2 = jsonFormatter.encodeToString(WorkflowSynthesisResult.serializer(), res2)

        assertEquals(json1, json2)
    }

    @Test
    fun test21_serializationCompatibility() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Order 2 Margherita pizzas from Pizza Palace to Home",
            "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )

        val result = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infResult, trace)

        val json = jsonFormatter.encodeToString(WorkflowSynthesisResult.serializer(), result)
        val decoded = jsonFormatter.decodeFromString(WorkflowSynthesisResult.serializer(), json)

        assertEquals("1.0", decoded.schemaVersion)
        assertEquals(SynthesisStatus.DEGRADED, decoded.status)
        assertFalse(decoded.isExecutable)
        assertEquals("order_food", decoded.workflow?.intent)
    }

    @Test
    fun test22_phase2InputCompatibility() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val rawTrace = DemonstrationTrace(traceId = "t22", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))
        val normResult = DemonstrationTraceNormalizer.normalize(rawTrace)

        val intResult = IntentExtractor.extract(normResult.normalizedTrace, emptyList())
        val infResult = InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 1)

        val synthRes = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infResult, normResult.normalizedTrace)

        assertNotNull(synthRes.workflow)
    }

    @Test
    fun test23_phase3InputCompatibility() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/dish"), inputData = "Noodles")
        val trace = DemonstrationTrace(traceId = "t23", timestamp = 1000L, appContext = "com.app", userActions = listOf(action), traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        val intResult = IntentExtractor.extract(trace, semanticActions)
        val infResult = InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 1)

        val synthRes = WorkflowSynthesizer.synthesize(intResult, semanticActions, SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infResult, trace)

        assertEquals(1, synthRes.workflow?.steps?.size)
    }

    @Test
    fun test24_phase4InputCompatibility() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order food from Subway")
        val trace = DemonstrationTrace(traceId = "t24", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val intResult = IntentExtractor.extract(trace, emptyList())
        val infResult = InferenceResult(intentName = intResult.intent.canonicalName, demonstrationsAnalyzedCount = 1)

        val synthRes = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = intResult.intent.canonicalName), DemonstrationAlignment.align(emptyList()), infResult, trace)

        assertEquals("order_food", synthRes.workflow?.intent)
    }

    @Test
    fun test25_phase5InputCompatibility() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val trace = DemonstrationTrace(traceId = "t25", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val intResult = IntentExtractor.extract(trace, emptyList())
        val slotResult = SlotExtractor.extract(trace, emptyList(), intResult)
        val infResult = InferenceResult(intentName = intResult.intent.canonicalName, demonstrationsAnalyzedCount = 1)

        val synthRes = WorkflowSynthesizer.synthesize(intResult, emptyList(), slotResult, DemonstrationAlignment.align(emptyList()), infResult, trace)

        assertNotNull(synthRes.workflow)
    }

    @Test
    fun test26_phase6InputCompatibility() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Order 2 Margherita pizzas from Pizza Palace to Home",
            "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )

        val synthRes = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = intResult.intent.canonicalName), DemonstrationAlignment.align(emptyList()), infResult, trace)

        assertTrue(synthRes.workflow?.slots?.isNotEmpty() == true)
    }

    @Test
    fun test27_noHardcodedPizzaPalaceLogic() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Book haircut at Salon Deluxe for 4 PM",
            "Book haircut at Salon Deluxe for 5 PM"
        )

        val synthRes = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = "book_appointment"), DemonstrationAlignment.align(emptyList()), infResult, trace)

        assertNotNull(synthRes.workflow)
    }

    @Test
    fun test28_noHardcodedJudgeWorkflow() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Send message Hello to Bob",
            "Send message Bye to Bob"
        )

        val synthRes = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = "send_message"), DemonstrationAlignment.align(emptyList()), infResult, trace)

        assertNotNull(synthRes.workflow)
    }

    @Test
    fun test29_noExternalNetworkOrAIDependency() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Order 2 pizzas",
            "Order 3 pizzas"
        )

        val startMs = System.currentTimeMillis()
        val synthRes = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infResult, trace)
        val durationMs = System.currentTimeMillis() - startMs

        assertFalse(synthRes.isExecutable) // Voice-only IR is inspectable, not executable.
        assertTrue(durationMs < 1000L) // Instant offline local processing
    }

    @Test
    fun test30_regressionPhase1TeachingCapture() {
        val session = TeachingSessionImpl("order_food", "Order food")
        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas"))
        val trace = session.stopTeaching()

        val norm = DemonstrationTraceNormalizer.normalize(trace)
        val act = SemanticActionExtractor.extract(norm.normalizedTrace)
        val intResult = IntentExtractor.extract(norm.normalizedTrace, act)
        val slotResult = SlotExtractor.extract(norm.normalizedTrace, act, intResult)
        val ds = DemonstrationDataset("d1", trace.traceId, intResult, slotResult)
        val infResult = ConstantVariableInference.infer(listOf(ds))

        val synthRes = WorkflowSynthesizer.synthesize(intResult, act, slotResult, DemonstrationAlignment.align(listOf(ds)), infResult, norm.normalizedTrace)
        assertNotNull(synthRes.workflow)
    }

    @Test
    fun test31_regressionPhase2TraceNormalization() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas")
        val rawTrace = DemonstrationTrace(traceId = "tr31", timestamp = 1000L, appContext = "unknown", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val normResult = DemonstrationTraceNormalizer.normalize(rawTrace)
        val act = SemanticActionExtractor.extract(normResult.normalizedTrace)
        val intResult = IntentExtractor.extract(normResult.normalizedTrace, act)
        val slotResult = SlotExtractor.extract(normResult.normalizedTrace, act, intResult)
        val ds = DemonstrationDataset("d1", rawTrace.traceId, intResult, slotResult)
        val infResult = ConstantVariableInference.infer(listOf(ds))

        val synthRes = WorkflowSynthesizer.synthesize(intResult, act, slotResult, DemonstrationAlignment.align(listOf(ds)), infResult, normResult.normalizedTrace)
        assertNotNull(synthRes.workflow)
    }

    @Test
    fun test32_regressionPhase3SemanticActionExtraction() {
        val action = ActionEvent(actionId = "a1", timestamp = 1000L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText", resourceId = "id/dish"), inputData = "Noodles")
        val trace = DemonstrationTrace(traceId = "tr32", timestamp = 1000L, appContext = "com.app", userActions = listOf(action), traceEvents = listOf(TraceEvent.Action("a1", 1000L, action)))

        val semanticActions = SemanticActionExtractor.extract(trace)
        val intResult = IntentExtractor.extract(trace, semanticActions)
        val slotResult = SlotExtractor.extract(trace, semanticActions, intResult)
        val ds = DemonstrationDataset("d1", trace.traceId, intResult, slotResult)
        val infResult = ConstantVariableInference.infer(listOf(ds))

        val synthRes = WorkflowSynthesizer.synthesize(intResult, semanticActions, slotResult, DemonstrationAlignment.align(listOf(ds)), infResult, trace)
        assertEquals(1, synthRes.workflow?.steps?.size)
    }

    @Test
    fun test33_regressionPhase4IntentExtraction() {
        val voice = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas from Domino's")
        val trace = DemonstrationTrace(traceId = "tr33", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice)))

        val intResult = IntentExtractor.extract(trace, emptyList())
        val slotResult = SlotExtractor.extract(trace, emptyList(), intResult)
        val ds = DemonstrationDataset("d1", trace.traceId, intResult, slotResult)
        val infResult = ConstantVariableInference.infer(listOf(ds))

        val synthRes = WorkflowSynthesizer.synthesize(intResult, emptyList(), slotResult, DemonstrationAlignment.align(listOf(ds)), infResult, trace)
        assertEquals("order_food", synthRes.workflow?.intent)
    }

    @Test
    fun test34_regressionPhase5SlotExtraction() {
        val voice1 = VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order 2 pizzas from Domino's to Home")
        val trace1 = DemonstrationTrace(traceId = "tr34_1", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice1), traceEvents = listOf(TraceEvent.Voice("v1", 1000L, voice1)))
        val int1 = IntentExtractor.extract(trace1, emptyList())
        val slot1 = SlotExtractor.extract(trace1, emptyList(), int1)
        val ds1 = DemonstrationDataset("d1", trace1.traceId, int1, slot1)

        val voice2 = VoiceEvent(eventId = "v2", timestamp = 1000L, transcript = "Order 1 burger from Domino's to Home")
        val trace2 = DemonstrationTrace(traceId = "tr34_2", timestamp = 1000L, appContext = "com.app", voiceEvents = listOf(voice2), traceEvents = listOf(TraceEvent.Voice("v2", 1000L, voice2)))
        val int2 = IntentExtractor.extract(trace2, emptyList())
        val slot2 = SlotExtractor.extract(trace2, emptyList(), int2)
        val ds2 = DemonstrationDataset("d2", trace2.traceId, int2, slot2)

        val alignRes = DemonstrationAlignment.align(listOf(ds1, ds2))
        val infResult = ConstantVariableInference.infer(alignRes)

        val synthRes = WorkflowSynthesizer.synthesize(int1, emptyList(), slot1, alignRes, infResult, trace1)
        assertTrue(synthRes.workflow?.slots?.any { it.name == "item" && it.required } == true)
        assertTrue(synthRes.workflow?.slots?.any { it.name == "restaurant" && !it.required } == true)
    }

    @Test
    fun test35_regressionPhase6ConstantVariableInference() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Order 2 Margherita pizzas from Pizza Palace to Home",
            "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )

        val synthRes = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = intResult.intent.canonicalName), DemonstrationAlignment.align(emptyList()), infResult, trace)

        assertEquals(SynthesisStatus.DEGRADED, synthRes.status)
        assertFalse(synthRes.isExecutable)
        assertTrue(synthRes.workflow?.slots?.size == 4)
    }

    @Test
    fun testSensitiveWorkflowForcesUserHandoff() {
        val sa = SemanticAction(
            actionId = "sa_pin",
            timestamp = 1000L,
            actionType = SemanticActionType.INPUT_TEXT,
            target = SemanticTarget(role = "EditText", resourceId = "id/enter_pin_field"),
            inputValue = "9988"
        )
        val intent = Intent(intentId = "i1", canonicalName = "transfer_funds", confidence = 1.0, confidenceLevel = "HIGH")
        val trace = DemonstrationTrace(traceId = "t_sens", timestamp = 1000L, appContext = "com.bank.app")

        val result = WorkflowSynthesizer.synthesize(IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK"), listOf(sa), SlotExtractionResult(intentName = "transfer_funds"), DemonstrationAlignment.align(emptyList()), InferenceResult(intentName = "transfer_funds", demonstrationsAnalyzedCount = 1), trace)

        assertTrue(result.workflow?.safetyBoundary?.requiresExplicitUserConfirmation == true)
        assertTrue(result.workflow?.safetyBoundary?.sensitiveKeywords?.contains("pin") == true)
        assertEquals(com.chockXlate.teachablevoice.contract.workflow.RecoveryStrategy.HANDOFF_TO_USER, result.workflow?.steps?.first()?.recoveryPolicy?.strategy)
        assertEquals(0, result.workflow?.steps?.first()?.recoveryPolicy?.maxRetries)
    }

    @Test
    fun testSensitiveValuesNeverBecomeExecutableParameters() {
        val sa = SemanticAction(
            actionId = "sa_cvv",
            timestamp = 1000L,
            actionType = SemanticActionType.INPUT_TEXT,
            target = SemanticTarget(role = "EditText", resourceId = "id/card_cvv_input"),
            inputValue = "789"
        )
        val inf = AlignedSlotInference(
            slotName = "cvv",
            slotType = SlotType.INTEGER,
            status = SlotInferenceStatus.VARIABLE,
            rawValues = listOf("789"),
            confidence = 1.0,
            confidenceLevel = "HIGH",
            reasoning = "CVV"
        )
        val infRes = InferenceResult(intentName = "pay", demonstrationsAnalyzedCount = 1, slotInferences = listOf(inf))
        val intent = Intent(intentId = "i1", canonicalName = "pay", confidence = 1.0, confidenceLevel = "HIGH")
        val trace = DemonstrationTrace(traceId = "t_cvv", timestamp = 1000L, appContext = "com.bank.app")

        val result = WorkflowSynthesizer.synthesize(IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK"), listOf(sa), SlotExtractionResult(intentName = "pay"), DemonstrationAlignment.align(emptyList()), infRes, trace)

        val json = jsonFormatter.encodeToString(Workflow.serializer(), result.workflow!!)
        assertFalse(json.contains("\"789\""))
        val slot = result.workflow?.slots?.find { it.name == "cvv" }
        assertEquals("[REDACTED_SENSITIVE]", slot?.exampleValue)
    }

    @Test
    fun testVariableSlotsRemainReferences() {
        val sa = SemanticAction(
            actionId = "sa_dish",
            timestamp = 1000L,
            actionType = SemanticActionType.INPUT_TEXT,
            target = SemanticTarget(role = "EditText", resourceId = "id/dish_input"),
            inputValue = "Tacos"
        )
        val inf = AlignedSlotInference(
            slotName = "item",
            slotType = SlotType.TEXT,
            status = SlotInferenceStatus.VARIABLE,
            rawValues = listOf("Tacos", "Burritos"),
            confidence = 1.0,
            confidenceLevel = "HIGH",
            reasoning = "Variable"
        )
        val infRes = InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 2, slotInferences = listOf(inf))
        val intent = Intent(intentId = "i1", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH")
        val trace = DemonstrationTrace(traceId = "t_var", timestamp = 1000L, appContext = "com.app")

        val result = WorkflowSynthesizer.synthesize(IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK"), listOf(sa), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infRes, trace)

        val step = result.workflow?.steps?.first()
        assertEquals("\${item}", step?.semanticSelector?.textSlot)
        assertNull(step?.semanticSelector?.text)
        assertEquals("\${item}", step?.parameters?.get("input_parameter"))
    }

    @Test
    fun testConstantSlotsRemainFixedValues() {
        val sa = SemanticAction(
            actionId = "sa_store",
            timestamp = 1000L,
            actionType = SemanticActionType.INPUT_TEXT,
            target = SemanticTarget(role = "EditText", resourceId = "id/store_input"),
            inputValue = "Taco Bell"
        )
        val inf = AlignedSlotInference(
            slotName = "restaurant",
            slotType = SlotType.TEXT,
            status = SlotInferenceStatus.CONSTANT,
            rawValues = listOf("Taco Bell"),
            confidence = 1.0,
            confidenceLevel = "HIGH",
            reasoning = "Constant"
        )
        val infRes = InferenceResult(intentName = "order_food", demonstrationsAnalyzedCount = 2, slotInferences = listOf(inf))
        val intent = Intent(intentId = "i1", canonicalName = "order_food", confidence = 1.0, confidenceLevel = "HIGH")
        val trace = DemonstrationTrace(traceId = "t_const", timestamp = 1000L, appContext = "com.app")

        val result = WorkflowSynthesizer.synthesize(IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "OK"), listOf(sa), SlotExtractionResult(intentName = "order_food"), DemonstrationAlignment.align(emptyList()), infRes, trace)

        val step = result.workflow?.steps?.first()
        assertNull(step?.semanticSelector?.textSlot)
        assertEquals("Taco Bell", step?.semanticSelector?.text)
        assertEquals("Taco Bell", step?.parameters?.get("input_literal"))
    }

    @Test
    fun testSerializedWorkflowContainsNoCoordinateExecutionTruth() {
        val (trace, intResult, infResult) = createPipelineInputs(
            "Order 2 Margherita pizzas from Pizza Palace to Home",
            "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )

        val result = WorkflowSynthesizer.synthesize(intResult, emptyList(), SlotExtractionResult(intentName = intResult.intent.canonicalName), DemonstrationAlignment.align(emptyList()), infResult, trace)

        val json = jsonFormatter.encodeToString(Workflow.serializer(), result.workflow!!)
        assertFalse(json.contains("\"x\":"))
        assertFalse(json.contains("\"y\":"))
        assertFalse(json.contains("boundsInScreen"))
        assertFalse(json.contains("tapPosition"))
    }
}

