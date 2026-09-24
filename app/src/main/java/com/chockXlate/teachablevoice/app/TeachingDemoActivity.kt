package com.chockXlate.teachablevoice.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chockXlate.teachablevoice.app.service.TeachableVoiceAccessibilityService
import com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter
import com.chockXlate.teachablevoice.command.matching.SkillMatchStatus
import com.chockXlate.teachablevoice.command.matching.SkillMatcher
import com.chockXlate.teachablevoice.command.request.ExecutionRequestBuildResult
import com.chockXlate.teachablevoice.command.request.ExecutionRequestBuilder
import com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus
import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import com.chockXlate.teachablevoice.contract.runtime.ExecutionState
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.runtime.ExecutionEngine
import com.chockXlate.teachablevoice.runtime.trace.RuntimeReport
import com.chockXlate.teachablevoice.runtime.ui.AccessibilityUiDriver
import com.chockXlate.teachablevoice.skill.repository.SkillRepositoryProvider
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import com.chockXlate.teachablevoice.teach.trace.TraceViewer
import com.chockXlate.teachablevoice.teach.voice.VoiceCaptureController
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Demonstrable UI Activity for Person 1 Phase 1: Teaching Capture.
 * Provides controls for starting/stopping teaching sessions, recording voice utterances,
 * triggering interactive UI actions, and viewing real DemonstrationTrace trees.
 */
class TeachingDemoActivity : Activity() {

    private lateinit var statusTextView: TextView
    private lateinit var traceInspectorTextView: TextView
    private lateinit var skillNameInput: EditText
    private lateinit var intentInput: EditText
    private lateinit var voiceInput: EditText
    private lateinit var dishSearchInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(32, 32, 32, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleText = TextView(this).apply {
            text = "Teachable Voice Automation\nPerson 1 — Phase 1 Teaching Capture Demo"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        }
        rootLayout.addView(titleText)

        statusTextView = TextView(this).apply {
            text = "STATUS: TEACHING INACTIVE"
            textSize = 14f
            setTextColor(Color.parseColor("#FF5252"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        }
        rootLayout.addView(statusTextView)

        // Session Setup Section
        val setupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 24, 24, 24)
        }

        skillNameInput = EditText(this).apply {
            hint = "Skill Name (e.g. order_food)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText("order_food")
        }
        setupCard.addView(skillNameInput)

        intentInput = EditText(this).apply {
            hint = "Intent (e.g. Order food from app)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText("Order food from app")
        }
        setupCard.addView(intentInput)

        val startBtn = Button(this).apply {
            text = "START TEACHING"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener { startTeachingSession() }
        }

        val stopBtn = Button(this).apply {
            text = "STOP TEACHING"
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            setOnClickListener { stopTeachingSession() }
        }

        val normalizeBtn = Button(this).apply {
            text = "NORMALIZE TRACE (PHASE 2)"
            setBackgroundColor(Color.parseColor("#7B1FA2"))
            setTextColor(Color.WHITE)
            setOnClickListener { normalizeCurrentTrace() }
        }

        val semanticBtn = Button(this).apply {
            text = "EXTRACT SEMANTIC ACTIONS (PHASE 3)"
            setBackgroundColor(Color.parseColor("#00838F"))
            setTextColor(Color.WHITE)
            setOnClickListener { extractSemanticActionsPhase3() }
        }

        val intentBtn = Button(this).apply {
            text = "EXTRACT INTENT (PHASE 4)"
            setBackgroundColor(Color.parseColor("#E65100"))
            setTextColor(Color.WHITE)
            setOnClickListener { extractIntentPhase4() }
        }

        val slotsBtn = Button(this).apply {
            text = "EXTRACT SLOTS (PHASE 5)"
            setBackgroundColor(Color.parseColor("#1B5E20"))
            setTextColor(Color.WHITE)
            setOnClickListener { extractSlotsPhase5() }
        }

        val alignInferBtn = Button(this).apply {
            text = "ALIGN & INFER VARIABLES (PHASE 6)"
            setBackgroundColor(Color.parseColor("#FF6F00"))
            setTextColor(Color.WHITE)
            setOnClickListener { alignAndInferVariablesPhase6() }
        }

        val synthesizeBtn = Button(this).apply {
            text = "SYNTHESIZE WORKFLOW (PHASE 7)"
            setBackgroundColor(Color.parseColor("#D81B60"))
            setTextColor(Color.WHITE)
            setOnClickListener { synthesizeWorkflowPhase7() }
        }

        val validateStoreBtn = Button(this).apply {
            text = "VALIDATE & STORE SKILL (PHASE 8)"
            setBackgroundColor(Color.parseColor("#311B92"))
            setTextColor(Color.WHITE)
            setOnClickListener { validateAndStoreSkillPhase8() }
        }

        val inspectBtn = Button(this).apply {
            text = "INSPECT STORED SKILL (PHASE 9)"
            setBackgroundColor(Color.parseColor("#00695C"))
            setTextColor(Color.WHITE)
            setOnClickListener { inspectStoredSkillPhase9() }
        }

        val understandCmdBtn = Button(this).apply {
            text = "UNDERSTAND NEW COMMAND (PHASE 10)"
            setBackgroundColor(Color.parseColor("#E65100"))
            setTextColor(Color.WHITE)
            setOnClickListener { understandNewCommandPhase10() }
        }

        val matchSkillBtn = Button(this).apply {
            text = "MATCH COMMAND TO SKILL (PHASE 11)"
            setBackgroundColor(Color.parseColor("#4A148C"))
            setTextColor(Color.WHITE)
            setOnClickListener { matchCommandToSkillPhase11() }
        }

        val createRequestBtn = Button(this).apply {
            text = "CREATE EXECUTION REQUEST (PHASE 12)"
            setBackgroundColor(Color.parseColor("#004D40"))
            setTextColor(Color.WHITE)
            setOnClickListener { createExecutionRequestPhase12() }
        }

        val executeRuntimeBtn = Button(this).apply {
            text = "EXECUTE RUNTIME (PERSON 2)"
            setBackgroundColor(Color.parseColor("#00897B"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setOnClickListener { executeWithPerson2Runtime() }
        }

        val ackHandoffBtn = Button(this).apply {
            text = "RESET / ACKNOWLEDGE HANDOFF"
            setBackgroundColor(Color.parseColor("#FB8C00"))
            setTextColor(Color.WHITE)
            setOnClickListener { acknowledgeHandoffAndReset() }
        }

        val cancelRuntimeBtn = Button(this).apply {
            text = "CANCEL RUNTIME"
            setBackgroundColor(Color.parseColor("#E53935"))
            setTextColor(Color.WHITE)
            setOnClickListener { cancelRuntimeExecution() }
        }

        val accessibilitySettingsBtn = Button(this).apply {
            text = "ACCESSIBILITY SETTINGS"
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            setOnClickListener { openAccessibilitySettings() }
        }

        fun createButtonRow(vararg buttons: Button): View {
            val hScroll = HorizontalScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (btn in buttons) {
                btn.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 8, 0) }
                row.addView(btn)
            }
            hScroll.addView(row)
            return hScroll
        }

        setupCard.addView(createButtonRow(startBtn, stopBtn))
        setupCard.addView(createButtonRow(normalizeBtn, semanticBtn, intentBtn, slotsBtn, alignInferBtn, synthesizeBtn))
        setupCard.addView(createButtonRow(validateStoreBtn, inspectBtn, understandCmdBtn, matchSkillBtn, createRequestBtn))
        setupCard.addView(createButtonRow(executeRuntimeBtn, ackHandoffBtn, cancelRuntimeBtn, accessibilitySettingsBtn))
        rootLayout.addView(setupCard)

        // Interactive Voice Capture Section
        val voiceSection = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 24)
        }

        voiceInput = EditText(this).apply {
            hint = "Voice Command (e.g. Order a Margherita pizza)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText("Order a Margherita pizza")
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        voiceSection.addView(voiceInput)

        val recordVoiceBtn = Button(this).apply {
            text = "SPEAK"
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setOnClickListener { recordVoiceUtterance() }
        }
        voiceSection.addView(recordVoiceBtn)
        rootLayout.addView(voiceSection)

        // Interactive Target UI Section
        val targetUiSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#263238"))
            setPadding(24, 24, 24, 24)
        }

        val targetUiTitle = TextView(this).apply {
            text = "Interactive Target App Area (Perform Teaching Actions):"
            setTextColor(Color.parseColor("#80D8FF"))
            setTypeface(null, Typeface.BOLD)
        }
        targetUiSection.addView(targetUiTitle)

        dishSearchInput = EditText(this).apply {
            hint = "Search dishes..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText("Pizza Palace")
        }
        targetUiSection.addView(dishSearchInput)

        val searchDishBtn = Button(this).apply {
            text = "Search Dish"
            setBackgroundColor(Color.parseColor("#37474F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                recordAction(
                    actionType = "INPUT_TEXT",
                    role = "EditText",
                    text = "Search dishes...",
                    resourceId = "com.example.foodapp:id/search_input",
                    inputData = dishSearchInput.text.toString()
                )
            }
        }
        targetUiSection.addView(searchDishBtn)

        val addPizzaBtn = Button(this).apply {
            text = "ADD Pizza to Cart"
            setBackgroundColor(Color.parseColor("#37474F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                recordAction(
                    actionType = "CLICK",
                    role = "Button",
                    text = "ADD",
                    resourceId = "com.example.foodapp:id/btn_add"
                )
            }
        }
        targetUiSection.addView(addPizzaBtn)

        val checkoutBtn = Button(this).apply {
            text = "View Cart & Checkout"
            setBackgroundColor(Color.parseColor("#37474F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                recordAction(
                    actionType = "CLICK",
                    role = "Button",
                    text = "View Cart",
                    resourceId = "com.example.foodapp:id/btn_checkout"
                )
            }
        }
        targetUiSection.addView(checkoutBtn)
        rootLayout.addView(targetUiSection)

        // Trace Inspector Output ScrollView
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
            setPadding(0, 24, 0, 0)
        }

        traceInspectorTextView = TextView(this).apply {
            text = "--- DEMONSTRATION TRACE INSPECTOR ---\n(Start teaching and perform actions to view live trace output)"
            setTextColor(Color.parseColor("#A7FFEB"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#000000"))
            setPadding(16, 16, 16, 16)
        }
        scrollView.addView(traceInspectorTextView)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
    }

    private fun startTeachingSession() {
        val skill = skillNameInput.text.toString().ifBlank { "order_food" }
        val intent = intentInput.text.toString().ifBlank { "Order food from app" }

        TeachingSessionManager.startSession(skill, intent)
        statusTextView.text = "STATUS: TEACHING ACTIVE (Skill: '$skill')"
        statusTextView.setTextColor(Color.parseColor("#00E676"))
        traceInspectorTextView.text = "=== TEACHING SESSION STARTED ===\nSkill: $skill\nIntent: $intent\n\nReady to record events..."
    }

    private fun recordVoiceUtterance() {
        if (!TeachingSessionManager.isTeachingActive()) {
            traceInspectorTextView.text = "WARNING: Click 'START TEACHING' before recording voice commands!"
            return
        }
        val text = voiceInput.text.toString().ifBlank { "Order a pizza" }
        val voiceEvent = VoiceCaptureController.recordUtterance(text)
        updateLiveTraceDisplay()
    }

    private fun recordAction(
        actionType: String,
        role: String,
        text: String,
        resourceId: String,
        inputData: String? = null
    ) {
        if (!TeachingSessionManager.isTeachingActive()) {
            traceInspectorTextView.text = "WARNING: Click 'START TEACHING' before performing actions!"
            return
        }

        val selector = SemanticSelector(
            schemaVersion = "1.0",
            role = role,
            text = text,
            resourceId = resourceId
        )

        val actionEvent = ActionEvent(
            schemaVersion = "1.0",
            actionId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            actionType = actionType,
            semanticSelector = selector,
            inputData = inputData
        )

        TeachingSessionManager.recordActionEvent(actionEvent)
        updateLiveTraceDisplay()
    }

    private fun stopTeachingSession() {
        val trace = TeachingSessionManager.stopSession()
        if (trace == null) {
            statusTextView.text = "STATUS: TEACHING INACTIVE"
            statusTextView.setTextColor(Color.parseColor("#FF5252"))
            traceInspectorTextView.text = "No active teaching session to stop."
            return
        }

        statusTextView.text = "STATUS: DEMONSTRATION CAPTURED"
        statusTextView.setTextColor(Color.parseColor("#448AFF"))

        val formattedTrace = TraceViewer.formatTrace(trace)
        traceInspectorTextView.text = formattedTrace
        lastCapturedTrace = trace
    }

    private var lastCapturedTrace: DemonstrationTrace? = null

    private fun normalizeCurrentTrace() {
        val targetTrace = TeachingSessionManager.peekSessionTrace() 
            ?: lastCapturedTrace 
            ?: run {
                traceInspectorTextView.text = "No trace available to normalize. Start teaching or record events first."
                return
            }

        val result = com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer.normalize(targetTrace)
        statusTextView.text = "STATUS: TRACE NORMALIZED (Raw: ${result.rawEventCount} -> Norm: ${result.normalizedEventCount})"
        statusTextView.setTextColor(Color.parseColor("#E040FB"))

        val summary = TraceViewer.formatNormalizationSummary(result)
        traceInspectorTextView.text = summary
    }

    private fun extractSemanticActionsPhase3() {
        val targetTrace = TeachingSessionManager.peekSessionTrace() 
            ?: lastCapturedTrace 
            ?: run {
                traceInspectorTextView.text = "No trace available for semantic extraction. Start teaching or record actions first."
                return
            }

        val normResult = com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer.normalize(targetTrace)
        val actions = com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor.extract(normResult.normalizedTrace)

        statusTextView.text = "STATUS: PHASE 3 SEMANTIC ACTIONS EXTRACTED (${actions.size} Actions)"
        statusTextView.setTextColor(Color.parseColor("#00E5FF"))

        val formatted = TraceViewer.formatSemanticActions(actions)
        traceInspectorTextView.text = formatted
    }

    private fun extractIntentPhase4() {
        val targetTrace = TeachingSessionManager.peekSessionTrace() 
            ?: lastCapturedTrace 
            ?: run {
                traceInspectorTextView.text = "No trace available for intent extraction. Start teaching or record actions/voice first."
                return
            }

        val normResult = com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer.normalize(targetTrace)
        val semanticActions = com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor.extract(normResult.normalizedTrace)
        val intentResult = com.chockXlate.teachablevoice.learning.intent.IntentExtractor.extract(normResult.normalizedTrace, semanticActions)

        statusTextView.text = "STATUS: PHASE 4 INTENT EXTRACTED ('${intentResult.intent.canonicalName}', ${intentResult.intent.confidenceLevel})"
        statusTextView.setTextColor(Color.parseColor("#FF9100"))

        val formatted = TraceViewer.formatIntentResult(intentResult)
        traceInspectorTextView.text = formatted
    }

    private fun extractSlotsPhase5() {
        val targetTrace = TeachingSessionManager.peekSessionTrace() 
            ?: lastCapturedTrace 
            ?: run {
                traceInspectorTextView.text = "No trace available for slot extraction. Start teaching or record actions/voice first."
                return
            }

        val normResult = com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer.normalize(targetTrace)
        val semanticActions = com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor.extract(normResult.normalizedTrace)
        val intentResult = com.chockXlate.teachablevoice.learning.intent.IntentExtractor.extract(normResult.normalizedTrace, semanticActions)
        val slotResult = com.chockXlate.teachablevoice.learning.slots.SlotExtractor.extract(normResult.normalizedTrace, semanticActions, intentResult)

        statusTextView.text = "STATUS: PHASE 5 SLOTS EXTRACTED (${slotResult.extractedSlots.size} Slots, ${slotResult.conflicts.size} Conflicts)"
        statusTextView.setTextColor(Color.parseColor("#76FF03"))

        val formatted = TraceViewer.formatSlotResult(slotResult)
        traceInspectorTextView.text = formatted
    }

    private fun alignAndInferVariablesPhase6() {
        val targetTrace1 = TeachingSessionManager.peekSessionTrace() 
            ?: lastCapturedTrace 
            ?: run {
                traceInspectorTextView.text = "No trace available for Phase 6. Start teaching or record actions/voice first."
                return
            }

        // Demo 1 processing
        val norm1 = com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer.normalize(targetTrace1)
        val actions1 = com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor.extract(norm1.normalizedTrace)
        val intent1 = com.chockXlate.teachablevoice.learning.intent.IntentExtractor.extract(norm1.normalizedTrace, actions1)
        val slot1 = com.chockXlate.teachablevoice.learning.slots.SlotExtractor.extract(norm1.normalizedTrace, actions1, intent1)
        val demoDataset1 = com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset(
            demonstrationId = "demo_01",
            traceId = norm1.normalizedTrace.traceId,
            intentResult = intent1,
            slotResult = slot1
        )

        // Demo 2 processing (Simulated 2nd demonstration with different item & quantity)
        val demo2Voice = com.chockXlate.teachablevoice.contract.event.VoiceEvent(
            eventId = "v_demo2",
            timestamp = System.currentTimeMillis(),
            transcript = "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )
        val trace2 = com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace(
            traceId = "tr_demo2",
            timestamp = System.currentTimeMillis(),
            appContext = norm1.normalizedTrace.appContext,
            voiceEvents = listOf(demo2Voice),
            traceEvents = listOf(com.chockXlate.teachablevoice.contract.trace.TraceEvent.Voice("v_demo2", System.currentTimeMillis(), demo2Voice))
        )
        val norm2 = com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer.normalize(trace2)
        val actions2 = com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor.extract(norm2.normalizedTrace)
        val intent2 = com.chockXlate.teachablevoice.learning.intent.IntentExtractor.extract(norm2.normalizedTrace, actions2)
        val slot2 = com.chockXlate.teachablevoice.learning.slots.SlotExtractor.extract(norm2.normalizedTrace, actions2, intent2)
        val demoDataset2 = com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset(
            demonstrationId = "demo_02",
            traceId = norm2.normalizedTrace.traceId,
            intentResult = intent2,
            slotResult = slot2
        )

        val datasets = listOf(demoDataset1, demoDataset2)

        val alignmentResult = com.chockXlate.teachablevoice.learning.alignment.DemonstrationAlignment.align(datasets)
        val inferenceResult = com.chockXlate.teachablevoice.learning.inference.ConstantVariableInference.infer(alignmentResult)

        statusTextView.text = "STATUS: PHASE 6 ALIGNED & INFERRED (${alignmentResult.alignedDemonstrationIds.size} Demos, ${inferenceResult.slotInferences.size} Inferences)"
        statusTextView.setTextColor(Color.parseColor("#FFD600"))

        val formatted = TraceViewer.formatInferenceResult(alignmentResult, inferenceResult)
        traceInspectorTextView.text = formatted
    }

    private fun synthesizeWorkflowPhase7() {
        val targetTrace1 = TeachingSessionManager.peekSessionTrace() 
            ?: lastCapturedTrace 
            ?: run {
                traceInspectorTextView.text = "No trace available for Phase 7. Start teaching or record actions/voice first."
                return
            }

        // Demo 1
        val norm1 = com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer.normalize(targetTrace1)
        val actions1 = com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor.extract(norm1.normalizedTrace)
        val intent1 = com.chockXlate.teachablevoice.learning.intent.IntentExtractor.extract(norm1.normalizedTrace, actions1)
        val slot1 = com.chockXlate.teachablevoice.learning.slots.SlotExtractor.extract(norm1.normalizedTrace, actions1, intent1)
        val demoDataset1 = com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset(
            demonstrationId = "demo_01",
            traceId = norm1.normalizedTrace.traceId,
            intentResult = intent1,
            slotResult = slot1
        )

        // Demo 2
        val demo2Voice = com.chockXlate.teachablevoice.contract.event.VoiceEvent(
            eventId = "v_demo2",
            timestamp = System.currentTimeMillis(),
            transcript = "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )
        val trace2 = com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace(
            traceId = "tr_demo2",
            timestamp = System.currentTimeMillis(),
            appContext = norm1.normalizedTrace.appContext,
            voiceEvents = listOf(demo2Voice),
            traceEvents = listOf(com.chockXlate.teachablevoice.contract.trace.TraceEvent.Voice("v_demo2", System.currentTimeMillis(), demo2Voice))
        )
        val norm2 = com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer.normalize(trace2)
        val actions2 = com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor.extract(norm2.normalizedTrace)
        val intent2 = com.chockXlate.teachablevoice.learning.intent.IntentExtractor.extract(norm2.normalizedTrace, actions2)
        val slot2 = com.chockXlate.teachablevoice.learning.slots.SlotExtractor.extract(norm2.normalizedTrace, actions2, intent2)
        val demoDataset2 = com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset(
            demonstrationId = "demo_02",
            traceId = norm2.normalizedTrace.traceId,
            intentResult = intent2,
            slotResult = slot2
        )

        val datasets = listOf(demoDataset1, demoDataset2)

        val alignmentResult = com.chockXlate.teachablevoice.learning.alignment.DemonstrationAlignment.align(datasets)
        val inferenceResult = com.chockXlate.teachablevoice.learning.inference.ConstantVariableInference.infer(alignmentResult)

        val synthesisResult = com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer.synthesize(
            intentResult = intent1,
            semanticActions = actions1,
            slotResult = slot1,
            alignmentResult = alignmentResult,
            inferenceResult = inferenceResult,
            trace = norm1.normalizedTrace
        )

        statusTextView.text = "STATUS: PHASE 7 WORKFLOW SYNTHESIZED (Status: ${synthesisResult.status}, Steps: ${synthesisResult.workflow?.steps?.size ?: 0})"
        statusTextView.setTextColor(Color.parseColor("#FF4081"))

        val formatted = TraceViewer.formatWorkflowSynthesis(synthesisResult)
        traceInspectorTextView.text = formatted
    }

    private val skillRepo get() = SkillRepositoryProvider.getRepository()
    private val runtimeEngine by lazy {
        ExecutionEngine(
            repository = skillRepo,
            driver = AccessibilityUiDriver()
        )
    }

    private var lastBuildResult: ExecutionRequestBuildResult? = null
    private var lastExecutionRequest: ExecutionRequest? = null
    private val isExecuting = AtomicBoolean(false)

    private fun validateAndStoreSkillPhase8() {
        val targetTrace1 = TeachingSessionManager.peekSessionTrace() 
            ?: lastCapturedTrace 
            ?: run {
                traceInspectorTextView.text = "No trace available for Phase 8. Start teaching or record actions/voice first."
                return
            }

        // Run full Phase 1-7 pipeline
        val norm1 = com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer.normalize(targetTrace1)
        val actions1 = com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor.extract(norm1.normalizedTrace)
        val intent1 = com.chockXlate.teachablevoice.learning.intent.IntentExtractor.extract(norm1.normalizedTrace, actions1)
        val slot1 = com.chockXlate.teachablevoice.learning.slots.SlotExtractor.extract(norm1.normalizedTrace, actions1, intent1)
        val demoDataset1 = com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset("demo_01", norm1.normalizedTrace.traceId, intent1, slot1)

        val demo2Voice = com.chockXlate.teachablevoice.contract.event.VoiceEvent(
            eventId = "v_demo2",
            timestamp = System.currentTimeMillis(),
            transcript = "Order 1 Farmhouse pizza from Pizza Palace to Home"
        )
        val trace2 = com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace(
            traceId = "tr_demo2",
            timestamp = System.currentTimeMillis(),
            appContext = norm1.normalizedTrace.appContext,
            voiceEvents = listOf(demo2Voice),
            traceEvents = listOf(com.chockXlate.teachablevoice.contract.trace.TraceEvent.Voice("v_demo2", System.currentTimeMillis(), demo2Voice))
        )
        val norm2 = com.chockXlate.teachablevoice.teach.normalization.DemonstrationTraceNormalizer.normalize(trace2)
        val actions2 = com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor.extract(norm2.normalizedTrace)
        val intent2 = com.chockXlate.teachablevoice.learning.intent.IntentExtractor.extract(norm2.normalizedTrace, actions2)
        val slot2 = com.chockXlate.teachablevoice.learning.slots.SlotExtractor.extract(norm2.normalizedTrace, actions2, intent2)
        val demoDataset2 = com.chockXlate.teachablevoice.learning.alignment.DemonstrationDataset("demo_02", norm2.normalizedTrace.traceId, intent2, slot2)

        val alignmentResult = com.chockXlate.teachablevoice.learning.alignment.DemonstrationAlignment.align(listOf(demoDataset1, demoDataset2))
        val inferenceResult = com.chockXlate.teachablevoice.learning.inference.ConstantVariableInference.infer(alignmentResult)

        val synthesisResult = com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer.synthesize(
            intentResult = intent1,
            semanticActions = actions1,
            slotResult = slot1,
            alignmentResult = alignmentResult,
            inferenceResult = inferenceResult,
            trace = norm1.normalizedTrace
        )

        val workflow = synthesisResult.workflow
        val validation = com.chockXlate.teachablevoice.skill.validation.WorkflowValidator.validate(workflow)

        var saved = false
        var retrieved: com.chockXlate.teachablevoice.contract.workflow.Workflow? = null
        var version = 0

        if (workflow != null && validation.isStoreable) {
            saved = skillRepo.saveWorkflow(workflow)
            if (saved) {
                retrieved = skillRepo.getWorkflowById(workflow.skillId)
                version = skillRepo.getSkillVersion(workflow.skillId)
            }
        }

        statusTextView.text = "STATUS: PHASE 8 VALIDATED & STORED (Validation: ${validation.status}, Stored: $saved)"
        statusTextView.setTextColor(if (saved) Color.parseColor("#B388FF") else Color.parseColor("#FF5252"))

        val formatted = TraceViewer.formatValidationAndStoreResult(validation, saved, retrieved, version)
        traceInspectorTextView.text = formatted
    }

    private fun inspectStoredSkillPhase9() {
        val storedSkills = skillRepo.getAllWorkflows()
        val targetWorkflow = storedSkills.firstOrNull()

        if (targetWorkflow == null) {
            traceInspectorTextView.text = "No stored skill available in SkillRepository. Run 'VALIDATE & STORE SKILL (PHASE 8)' first!"
            statusTextView.text = "STATUS: INSPECTION FAILED (No stored skill)"
            statusTextView.setTextColor(Color.parseColor("#FF5252"))
            return
        }

        val inspection = com.chockXlate.teachablevoice.skill.inspector.WorkflowInspectorImpl.inspect(targetWorkflow, skillRepo)

        statusTextView.text = "STATUS: PHASE 9 INSPECTED ('${targetWorkflow.skillId}', Status: ${inspection.validationStatus})"
        statusTextView.setTextColor(Color.parseColor("#80CBC4"))

        val formatted = TraceViewer.formatInspectionResult(inspection)
        traceInspectorTextView.text = formatted
    }

    private fun understandNewCommandPhase10() {
        val rawCmd = voiceInput.text.toString().ifBlank { "Order 2 Farmhouse pizzas from Pizza Palace" }
        val result = com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter.understandCommand(rawCmd)

        statusTextView.text = "STATUS: PHASE 10 COMMAND UNDERSTOOD ('${result.intent.canonicalName}', ${result.slots.size} Slots)"
        statusTextView.setTextColor(Color.parseColor("#FF6D00"))

        val formatted = TraceViewer.formatCommandUnderstandingResult(result)
        traceInspectorTextView.text = formatted
    }

    private fun matchCommandToSkillPhase11() {
        // Ensure skill store has at least one valid workflow for demo if empty
        if (skillRepo.getWorkflowCount() == 0) {
            val sampleWorkflow = com.chockXlate.teachablevoice.contract.workflow.Workflow(
                skillId = "skill_order_food_s5_p4",
                name = "Order Food Skill",
                intent = "order_food",
                appContext = "com.example.foodapp",
                slots = listOf(
                    com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot(
                        name = "restaurant",
                        type = com.chockXlate.teachablevoice.contract.workflow.SlotType.TEXT,
                        required = false,
                        exampleValue = "Pizza Palace",
                        provenance = "constant"
                    ),
                    com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot(
                        name = "item",
                        type = com.chockXlate.teachablevoice.contract.workflow.SlotType.TEXT,
                        required = true,
                        exampleValue = "\${item}",
                        provenance = "variable"
                    ),
                    com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot(
                        name = "quantity",
                        type = com.chockXlate.teachablevoice.contract.workflow.SlotType.INTEGER,
                        required = true,
                        exampleValue = "\${quantity}",
                        provenance = "variable"
                    ),
                    com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot(
                        name = "address",
                        type = com.chockXlate.teachablevoice.contract.workflow.SlotType.ADDRESS,
                        required = false,
                        exampleValue = "Home",
                        provenance = "constant"
                    )
                ),
                steps = listOf(
                    com.chockXlate.teachablevoice.contract.workflow.WorkflowStep(
                        stepId = "step_1",
                        semanticAction = "INPUT_TEXT",
                        semanticSelector = com.chockXlate.teachablevoice.contract.workflow.SemanticSelector(
                            role = "EditText",
                            textSlot = "item",
                            resourceId = "com.example.foodapp:id/search"
                        )
                    )
                )
            )
            skillRepo.saveWorkflow(sampleWorkflow)
        }

        val rawCmd = voiceInput.text.toString().ifBlank { "Order 2 Farmhouse pizzas from Pizza Palace" }
        val understanding = com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter.understandCommand(rawCmd)
        val matcher = com.chockXlate.teachablevoice.command.matching.SkillMatcher(skillRepo)
        val matchResult = matcher.match(understanding)

        statusTextView.text = when (matchResult.status) {
            com.chockXlate.teachablevoice.command.matching.SkillMatchStatus.MATCHED -> "STATUS: PHASE 11 SKILL MATCHED ('${matchResult.selectedSkillId}')"
            com.chockXlate.teachablevoice.command.matching.SkillMatchStatus.AMBIGUOUS -> "STATUS: PHASE 11 SKILL MATCH AMBIGUOUS (${matchResult.candidates.size} candidates). Clarification needed."
            com.chockXlate.teachablevoice.command.matching.SkillMatchStatus.UNKNOWN -> "STATUS: PHASE 11 SKILL MATCH UNKNOWN. Teaching required."
        }
        statusTextView.setTextColor(
            when (matchResult.status) {
                com.chockXlate.teachablevoice.command.matching.SkillMatchStatus.MATCHED -> Color.parseColor("#00E676")
                com.chockXlate.teachablevoice.command.matching.SkillMatchStatus.AMBIGUOUS -> Color.parseColor("#FFD600")
                com.chockXlate.teachablevoice.command.matching.SkillMatchStatus.UNKNOWN -> Color.parseColor("#FF5252")
            }
        )

        val formatted = TraceViewer.formatSkillMatchResult(matchResult)
        traceInspectorTextView.text = formatted
    }

    private fun createExecutionRequestPhase12() {
        // Ensure skill store has at least one valid workflow for demo if empty
        if (skillRepo.getWorkflowCount() == 0) {
            val sampleWorkflow = com.chockXlate.teachablevoice.contract.workflow.Workflow(
                skillId = "skill_order_food_s5_p4",
                name = "Order Food Skill",
                intent = "order_food",
                appContext = "com.example.foodapp",
                slots = listOf(
                    com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot(
                        name = "restaurant",
                        type = com.chockXlate.teachablevoice.contract.workflow.SlotType.TEXT,
                        required = false,
                        exampleValue = "Pizza Palace",
                        provenance = "constant"
                    ),
                    com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot(
                        name = "item",
                        type = com.chockXlate.teachablevoice.contract.workflow.SlotType.TEXT,
                        required = true,
                        exampleValue = "\${item}",
                        provenance = "variable"
                    ),
                    com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot(
                        name = "quantity",
                        type = com.chockXlate.teachablevoice.contract.workflow.SlotType.INTEGER,
                        required = true,
                        exampleValue = "\${quantity}",
                        provenance = "variable"
                    ),
                    com.chockXlate.teachablevoice.contract.workflow.WorkflowSlot(
                        name = "address",
                        type = com.chockXlate.teachablevoice.contract.workflow.SlotType.ADDRESS,
                        required = false,
                        exampleValue = "Home",
                        provenance = "constant"
                    )
                ),
                steps = listOf(
                    com.chockXlate.teachablevoice.contract.workflow.WorkflowStep(
                        stepId = "step_1",
                        semanticAction = "INPUT_TEXT",
                        semanticSelector = com.chockXlate.teachablevoice.contract.workflow.SemanticSelector(
                            role = "EditText",
                            textSlot = "item",
                            resourceId = "com.example.foodapp:id/search"
                        )
                    )
                )
            )
            skillRepo.saveWorkflow(sampleWorkflow)
        }

        val rawCmd = voiceInput.text.toString().ifBlank { "Order 2 Farmhouse pizzas from Pizza Palace" }
        val understanding = com.chockXlate.teachablevoice.command.interpretation.CommandInterpreter.understandCommand(rawCmd)
        val matcher = com.chockXlate.teachablevoice.command.matching.SkillMatcher(skillRepo)
        val matchResult = matcher.match(understanding)

        val buildResult = com.chockXlate.teachablevoice.command.request.ExecutionRequestBuilder.build(
            understandingResult = understanding,
            matchResult = matchResult,
            repository = skillRepo
        )
        lastBuildResult = buildResult
        lastExecutionRequest = buildResult.executionRequest

        statusTextView.text = when (buildResult.status) {
            com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus.READY_FOR_PERSON_2 -> "STATUS: PHASE 12 REQUEST READY (ReqID: ${buildResult.executionRequest?.executionId})"
            com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus.REJECTED_MISSING_REQUIRED_SLOTS -> "STATUS: PHASE 12 REJECTED - Missing slots: ${buildResult.missingSlots.joinToString()}. Clarification needed."
            com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus.REJECTED_UNKNOWN_MATCH -> "STATUS: PHASE 12 REJECTED - Unknown skill. Teaching required."
            com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus.REJECTED_AMBIGUOUS_MATCH -> "STATUS: PHASE 12 REJECTED - Ambiguous match. Clarification needed."
            com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus.REJECTED_SAFETY_BLOCKED -> "STATUS: PHASE 12 REJECTED - Safety boundary blocked."
            else -> "STATUS: PHASE 12 REJECTED (${buildResult.rejectionReason ?: buildResult.status.name})"
        }
        statusTextView.setTextColor(
            if (buildResult.status == com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus.READY_FOR_PERSON_2)
                Color.parseColor("#00E676")
            else
                Color.parseColor("#FF5252")
        )

        val formatted = TraceViewer.formatExecutionRequestResult(buildResult)
        traceInspectorTextView.text = formatted
    }

    private fun executeWithPerson2Runtime() {
        if (!isExecuting.compareAndSet(false, true)) {
            statusTextView.text = "STATUS: RUNTIME ALREADY EXECUTING"
            statusTextView.setTextColor(Color.parseColor("#FFD600"))
            return
        }

        // Accessibility service readiness check
        val service = TeachableVoiceAccessibilityService.instance
        val serviceConnected = service != null && service.isRuntimeReady
        val teachingActive = service?.isTeachingModeActive == true || TeachingSessionManager.isTeachingActive()

        if (!serviceConnected || teachingActive) {
            val warningReason = when {
                !serviceConnected -> "Accessibility service is not connected. Enable TeachableVoice in Settings."
                else -> "Teaching mode is active. Stop teaching before executing runtime."
            }
            statusTextView.text = "STATUS: ACCESSIBILITY NOT READY\n$warningReason"
            statusTextView.setTextColor(Color.parseColor("#FF5252"))
            traceInspectorTextView.text = "WARNING: $warningReason\n\nClick 'ACCESSIBILITY SETTINGS' to enable the service."
            isExecuting.set(false)
            return
        }

        // Ensure we have an ExecutionRequest
        var request = lastExecutionRequest
        if (request == null || lastBuildResult?.status != com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus.READY_FOR_PERSON_2) {
            createExecutionRequestPhase12()
            request = lastExecutionRequest
        }

        if (request == null || lastBuildResult?.status != com.chockXlate.teachablevoice.command.request.ExecutionRequestStatus.READY_FOR_PERSON_2) {
            val reason = lastBuildResult?.rejectionReason ?: "No valid ExecutionRequest ready for Person 2."
            statusTextView.text = "STATUS: REQUEST NOT READY\n$reason"
            statusTextView.setTextColor(Color.parseColor("#FF5252"))
            isExecuting.set(false)
            return
        }

        statusTextView.text = "STATUS: RUNTIME EXECUTING (${request.executionId})..."
        statusTextView.setTextColor(Color.parseColor("#00E5FF"))
        traceInspectorTextView.text = "=== LAUNCHING PERSON 2 EXECUTION ENGINE ===\nExecution ID: ${request.executionId}\nSkill ID: ${request.skillId}\nBound Slots: ${request.boundSlots}\n\nRunning execution off main thread..."

        val targetRequest = request
        Thread {
            val suspendBlock: suspend () -> RuntimeReport = {
                runtimeEngine.execute(targetRequest)
            }
            suspendBlock.startCoroutine(object : Continuation<RuntimeReport> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<RuntimeReport>) {
                    runOnUiThread {
                        isExecuting.set(false)
                        result.onSuccess { report ->
                            handleRuntimeReport(report)
                        }.onFailure { error ->
                            statusTextView.text = "STATUS: RUNTIME UNCAUGHT ERROR (${error.javaClass.simpleName})"
                            statusTextView.setTextColor(Color.parseColor("#FF5252"))
                            traceInspectorTextView.text = "RUNTIME ERROR: ${error.message}\n${error.stackTraceToString()}"
                        }
                    }
                }
            })
        }.start()
    }

    private fun handleRuntimeReport(report: RuntimeReport) {
        val result = report.result
        when (result.finalState) {
            com.chockXlate.teachablevoice.contract.runtime.ExecutionState.COMPLETED -> {
                statusTextView.text = "STATUS: RUNTIME COMPLETED (${result.stepsCompleted}/${result.totalSteps} steps)"
                statusTextView.setTextColor(Color.parseColor("#00E676"))
            }
            com.chockXlate.teachablevoice.contract.runtime.ExecutionState.PAUSED_FOR_HANDOFF -> {
                statusTextView.text = "STATUS: RUNTIME PAUSED FOR HANDOFF\n${result.errorMessage ?: "User handoff required"}"
                statusTextView.setTextColor(Color.parseColor("#FFD600"))
            }
            com.chockXlate.teachablevoice.contract.runtime.ExecutionState.ABORTED -> {
                statusTextView.text = "STATUS: RUNTIME ABORTED\n${result.errorMessage ?: "Execution aborted"}"
                statusTextView.setTextColor(Color.parseColor("#FF9100"))
            }
            com.chockXlate.teachablevoice.contract.runtime.ExecutionState.FAILED -> {
                statusTextView.text = "STATUS: RUNTIME FAILED\n${result.errorMessage ?: "Execution failed"}"
                statusTextView.setTextColor(Color.parseColor("#FF5252"))
            }
            else -> {
                statusTextView.text = "STATUS: RUNTIME STATE: ${result.finalState}"
                statusTextView.setTextColor(Color.parseColor("#00E5FF"))
            }
        }
        traceInspectorTextView.text = TraceViewer.formatRuntimeReport(report)
    }

    private fun acknowledgeHandoffAndReset() {
        val acknowledged = runtimeEngine.acknowledgeHandoffForNewExecution()
        if (acknowledged) {
            statusTextView.text = "STATUS: HANDOFF ACKNOWLEDGED & RESET"
            statusTextView.setTextColor(Color.parseColor("#00E676"))
            traceInspectorTextView.text = "Handoff acknowledged. Safety gate reset for new execution."
        } else {
            statusTextView.text = "STATUS: CANNOT RESET (Execution currently active)"
            statusTextView.setTextColor(Color.parseColor("#FF5252"))
        }
    }

    private fun cancelRuntimeExecution() {
        runtimeEngine.cancel()
        statusTextView.text = "STATUS: RUNTIME CANCELLATION REQUESTED"
        statusTextView.setTextColor(Color.parseColor("#FF9100"))
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            traceInspectorTextView.text = "Unable to open Accessibility Settings: ${e.message}"
        }
    }

    private fun updateLiveTraceDisplay() {
        val trace = TeachingSessionManager.peekSessionTrace() ?: return
        statusTextView.text = "STATUS: TEACHING ACTIVE (Events: ${trace.traceEvents.size})"
        statusTextView.setTextColor(Color.parseColor("#00E676"))
        
        val liveSummary = TraceViewer.formatTrace(trace)
        traceInspectorTextView.text = liveSummary
    }
}

