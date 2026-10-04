package com.chockXlate.teachablevoice.runtime.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.chockXlate.teachablevoice.app.service.TeachableVoiceAccessibilityService
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SafetyBoundary
import com.chockXlate.teachablevoice.runtime.matching.MatchStatus
import com.chockXlate.teachablevoice.runtime.matching.SemanticMatcher
import com.chockXlate.teachablevoice.runtime.slots.BoundStep
import com.chockXlate.teachablevoice.runtime.verification.PreconditionEvaluator
import com.chockXlate.teachablevoice.runtime.verification.TransitionVerifier
import com.chockXlate.teachablevoice.safety.RuntimeSafetyPolicy
import com.chockXlate.teachablevoice.safety.SafetyGate
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Owns every acquired node only for the duration of one observation or dispatch. */
class AccessibilityUiDriver(
    private val serviceProvider: () -> TeachableVoiceAccessibilityService? = { TeachableVoiceAccessibilityService.instance }
) : UiDriver {
    private val handler = Handler(Looper.getMainLooper())
    private val policy = RuntimeSafetyPolicy()

    override fun isReady(): Boolean = serviceProvider()?.let {
        it.isRuntimeReady && !it.isTeachingModeActive && !TeachingSessionManager.isTeachingActive()
    } == true

    override fun getActivePackage(): String? {
        val service = serviceProvider()?.takeIf { it.isRuntimeReady } ?: return null
        return service.rootInActiveWindow?.packageName?.toString()
    }

    override suspend fun observe(): UiObservation? = onMain {
        if (!isReady()) return@onMain null
        val service = serviceProvider()?.takeIf { it.isRuntimeReady } ?: return@onMain null
        val frame = capture(service) ?: return@onMain null
        try { frame.observation } finally { frame.close() }
    }

    override suspend fun execute(
        step: BoundStep,
        expectedPackage: String,
        boundary: SafetyBoundary,
        matcher: SemanticMatcher,
        gate: SafetyGate
    ): ActionOutcome = onMain {
        gate.reason()?.let { return@onMain ActionOutcome(false, reason = it) }
        if (!isReady()) return@onMain ActionOutcome(false, reason = "Accessibility runtime is unavailable or teaching is active.")
        val service = serviceProvider()?.takeIf { it.isRuntimeReady }
            ?: return@onMain ActionOutcome(false, reason = "Accessibility service is unavailable.")
        val frame = capture(service)
            ?: return@onMain ActionOutcome(false, reason = "The active window is unavailable or too large to inspect safely.")
        try {
            val ui = frame.observation
            gate.check(boundary, ui, step)?.let { return@onMain ActionOutcome(false, reason = it, before = ui) }
            PreconditionEvaluator(matcher).evaluate(step.preconditions, ui, expectedPackage, step.stateEvidence)?.let {
                return@onMain ActionOutcome(false, reason = it, before = ui)
            }
            TransitionVerifier(matcher).startingStateError(step, ui)?.let {
                return@onMain ActionOutcome(false, reason = it, before = ui)
            }
            if (step.action == RuntimeAction.BACK) {
                val dispatched = gate.dispatch(boundary, ui, step) {
                    if (!isReady() || serviceProvider() !== service) false
                    else service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
                return@onMain ActionOutcome(dispatched.attempted, dispatched.accepted, dispatched.reason, ui)
            }
            if (step.action == RuntimeAction.HOME) {
                val dispatched = gate.dispatch(boundary, ui, step) {
                    if (!isReady() || serviceProvider() !== service) false
                    else service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                }
                return@onMain ActionOutcome(dispatched.attempted, dispatched.accepted, dispatched.reason, ui)
            }

            val match = matcher.match(step.selector, ui, step.action)
            if (match.status != MatchStatus.MATCHED) return@onMain ActionOutcome(false, reason = match.reason, before = ui)
            
            val nodeActions = frame.targets[match.best!!.element.elementId]
            val node = nodeActions?.get(step.action)
            val fallbackNode = node ?: nodeActions?.values?.firstOrNull()
            
            val actionId = when (step.action) {
                RuntimeAction.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
                RuntimeAction.INPUT_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
                RuntimeAction.LONG_PRESS -> AccessibilityNodeInfo.ACTION_LONG_CLICK
                RuntimeAction.SCROLL -> when (step.scrollDirection) {
                    "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                else -> -1
            }

            val canPerformNative = node != null && node.isEnabled && node.isVisibleToUser && node.actionList.any { it.id == actionId }
            
            if (canPerformNative) {
                val arguments = if (step.action == RuntimeAction.INPUT_TEXT) Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.inputText)
                } else null
                val dispatched = gate.dispatch(boundary, ui, step) {
                    if (!isReady() || serviceProvider() !== service) false
                    else node!!.performAction(actionId, arguments)
                }
                return@onMain ActionOutcome(dispatched.attempted, dispatched.accepted, dispatched.reason, ui)
            }
            
            // Gesture Fallback
            if (step.action == RuntimeAction.SWIPE || step.action == RuntimeAction.SCROLL || step.action == RuntimeAction.LONG_PRESS || step.action == RuntimeAction.CLICK) {
                val bounds = android.graphics.Rect()
                if (fallbackNode != null) {
                    fallbackNode.getBoundsInScreen(bounds)
                } else {
                    if (step.action == RuntimeAction.SWIPE || step.action == RuntimeAction.SCROLL) {
                        val displayMetrics = service.resources.displayMetrics
                        bounds.set(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
                    } else {
                        return@onMain ActionOutcome(false, reason = "Action requires a visible target.", before = ui)
                    }
                }
                
                val path = android.graphics.Path()
                val centerX = bounds.centerX().toFloat()
                val centerY = bounds.centerY().toFloat()
                
                var duration = 300L
                if (step.action == RuntimeAction.LONG_PRESS) {
                    path.moveTo(centerX, centerY)
                    duration = 600L
                } else if (step.action == RuntimeAction.CLICK) {
                    path.moveTo(centerX, centerY)
                    duration = 100L
                } else { // SWIPE or SCROLL
                    val height = bounds.height()
                    val width = bounds.width()
                    when (step.scrollDirection ?: "forward") {
                        "forward", "down" -> { // Swipe up
                            path.moveTo(centerX, centerY + height * 0.3f)
                            path.lineTo(centerX, centerY - height * 0.3f)
                        }
                        "backward", "up" -> { // Swipe down
                            path.moveTo(centerX, centerY - height * 0.3f)
                            path.lineTo(centerX, centerY + height * 0.3f)
                        }
                        "left" -> { // Swipe left
                            path.moveTo(centerX + width * 0.3f, centerY)
                            path.lineTo(centerX - width * 0.3f, centerY)
                        }
                        "right" -> { // Swipe right
                            path.moveTo(centerX - width * 0.3f, centerY)
                            path.lineTo(centerX + width * 0.3f, centerY)
                        }
                        else -> {
                            path.moveTo(centerX, centerY + height * 0.3f)
                            path.lineTo(centerX, centerY - height * 0.3f)
                        }
                    }
                }
                
                val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
                gestureBuilder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, duration))
                val dispatched = gate.dispatch(boundary, ui, step) {
                    if (!isReady() || serviceProvider() !== service) false
                    else service.dispatchGesture(gestureBuilder.build(), null, null)
                }
                return@onMain ActionOutcome(dispatched.attempted, dispatched.accepted, dispatched.reason, ui)
            }
            
            ActionOutcome(false, reason = "Live control does not support the action and no fallback applies.", before = ui)
        } finally {
            frame.close()
        }
    }

    override suspend fun awaitChange(delayMs: Long) = suspendCoroutine<Unit> { continuation ->
        handler.postDelayed({ continuation.resume(Unit) }, delayMs.coerceIn(1, 60_000))
    }

    @Suppress("DEPRECATION") // Required for node ownership on supported pre-API-33 devices.
    private class Frame(
        val observation: UiObservation,
        val targets: Map<String, Map<RuntimeAction, AccessibilityNodeInfo>>,
        private val owned: List<AccessibilityNodeInfo>
    ) {
        fun close() = owned.asReversed().forEach { it.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun capture(service: TeachableVoiceAccessibilityService): Frame? {
        val root = service.rootInActiveWindow ?: return null
        val owned = mutableListOf(root)
        try {
            val elements = mutableListOf<UiElement>()
            val targets = mutableMapOf<String, Map<RuntimeAction, AccessibilityNodeInfo>>()
            val editableNodes = mutableMapOf<String, AccessibilityNodeInfo>()
            var protectedField = false
            fun visit(node: AccessibilityNodeInfo, ancestors: List<AccessibilityNodeInfo>, depth: Int): UiElement? {
                check(depth <= 64 && owned.size <= 2000) // Refuse partial safety observations.
                if (!node.isVisibleToUser) return null
                val id = UUID.randomUUID().toString()
                val role = node.className?.toString()?.substringAfterLast('.') ?: "View"
                val resource = node.viewIdResourceName
                val description = node.contentDescription?.toString()
                val variation = node.inputType and InputType.TYPE_MASK_VARIATION
                val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
                val passwordType = (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
                    InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                )) || (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                val sensitive = node.isPassword || passwordType || (node.isEditable && policy.credentialText("$resource $description ${node.hintText}"))
                if (sensitive) protectedField = true
                val enabled = node.isEnabled && ancestors.all { it.isEnabled }
                val actions = mutableMapOf<RuntimeAction, AccessibilityNodeInfo>()
                fun supports(n: AccessibilityNodeInfo, action: Int) = n.isEnabled && n.isVisibleToUser && n.actionList.any { it.id == action }
                if (enabled) {
                    // At most two ancestors; never bubble text entry, scrolling, or long press.
                    (listOf(node) + ancestors.asReversed().take(2)).firstOrNull {
                        supports(it, AccessibilityNodeInfo.ACTION_CLICK) && !it.isEditable
                    }?.let { actions[RuntimeAction.CLICK] = it }
                    if (node.isEditable && !sensitive && supports(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) actions[RuntimeAction.INPUT_TEXT] = node
                    if (supports(node, AccessibilityNodeInfo.ACTION_LONG_CLICK)) actions[RuntimeAction.LONG_PRESS] = node
                    if (node.isScrollable && (supports(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) || supports(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))) actions[RuntimeAction.SCROLL] = node
                }
                targets[id] = actions
                if (node.isEditable && !sensitive) editableNodes[id] = node
                val children = mutableListOf<UiElement>()
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    owned.add(child)
                    visit(child, ancestors + node, depth + 1)?.let { children.add(it) }
                }
                val element = UiElement(
                    elementId = id, role = role,
                    // Defer ALL editable values until the complete screen's credential scan passes.
                    text = if (sensitive || node.isEditable) null else node.text?.toString(),
                    contentDescription = if (sensitive) null else description,
                    resourceId = resource,
                    parentRole = ancestors.lastOrNull()?.className?.toString()?.substringAfterLast('.'),
                    ancestorRole = ancestors.getOrNull(ancestors.size - 2)?.className?.toString()?.substringAfterLast('.'),
                    isClickable = node.isClickable, isEditable = node.isEditable,
                    isCheckable = node.isCheckable, isChecked = node.isChecked, isSelected = node.isSelected,
                    isScrollable = node.isScrollable, isEnabled = enabled, children = children
                )
                elements.add(element)
                return element
            }
            val tree = visit(root, emptyList(), 0) ?: error("Invisible root")
            protectedField = protectedField || elements.any {
                policy.credentialText(listOfNotNull(it.text, it.contentDescription, it.resourceId).joinToString(" "))
            }
            val safeElements = if (protectedField) elements else elements.map { element ->
                editableNodes[element.elementId]?.let { element.copy(text = it.text?.toString()) } ?: element
            }
            val byId = safeElements.associateBy { it.elementId }
            fun rebuild(element: UiElement): UiElement = byId.getValue(element.elementId).copy(children = element.children.map(::rebuild))
            val state = UiState(
                stateId = UUID.randomUUID().toString(), timestamp = System.currentTimeMillis(),
                appContext = root.packageName?.toString() ?: "unknown", windowId = root.windowId,
                rootElement = rebuild(tree), allElements = safeElements.map { it.copy(children = emptyList()) }
            )
            return Frame(UiObservation(state, targets.mapValues { it.value.keys.toSet() }, protectedField), targets, owned)
        } catch (_: Exception) {
            owned.asReversed().forEach { it.recycle() }
            return null
        }
    }

    private suspend fun <T> onMain(block: () -> T): T = suspendCoroutine { continuation ->
        val run = Runnable {
            try { continuation.resume(block()) } catch (e: Exception) { continuation.resumeWithException(e) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else handler.post(run)
    }
}
