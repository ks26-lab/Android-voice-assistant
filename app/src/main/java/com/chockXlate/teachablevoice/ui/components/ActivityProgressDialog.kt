package com.chockXlate.teachablevoice.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.chockXlate.teachablevoice.contract.runtime.ExecutionTrace
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.runtime.trace.RuntimeReport
import com.chockXlate.teachablevoice.runtime.trace.StepExecutionStatus
import com.chockXlate.teachablevoice.ui.theme.*

/**
 * Generic event status for activity visualization.
 * Expresses generic progression states without architecture-specific knowledge.
 */
enum class ActivityStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

/**
 * Generic activity event data model.
 * Consumed by the UI to render real-time progression facts dynamically.
 */
data class ActivityEvent(
    val id: String,
    val label: String,
    val status: ActivityStatus = ActivityStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: String? = null
)

/**
 * Maps raw [TraceEvent] records from teaching or execution traces into UI-renderable [ActivityEvent]s.
 */
fun mapTraceEventsToActivityEvents(traceEvents: List<TraceEvent>): List<ActivityEvent> {
    return traceEvents.map { event ->
        when (event) {
            is TraceEvent.Voice -> ActivityEvent(
                id = event.eventId,
                label = "Voice: \"${event.voiceEvent.transcript}\"",
                status = ActivityStatus.COMPLETED,
                timestamp = event.timestamp,
                metadata = "Confidence: ${event.voiceEvent.confidence}"
            )
            is TraceEvent.Ui -> ActivityEvent(
                id = event.eventId,
                label = "UI: ${event.uiEvent.accessibilityEventType}",
                status = ActivityStatus.COMPLETED,
                timestamp = event.timestamp,
                metadata = event.uiEvent.packageName
            )
            is TraceEvent.Action -> ActivityEvent(
                id = event.eventId,
                label = "Action: ${event.actionEvent.actionType}",
                status = ActivityStatus.COMPLETED,
                timestamp = event.timestamp,
                metadata = event.actionEvent.semanticSelector.text ?: event.actionEvent.semanticSelector.role
            )
            is TraceEvent.State -> ActivityEvent(
                id = event.eventId,
                label = "State Transition",
                status = ActivityStatus.COMPLETED,
                timestamp = event.timestamp,
                metadata = event.stateEvent.afterState.appContext
            )
        }
    }
}

/**
 * Maps a captured [DemonstrationTrace] into an ordered stream of [ActivityEvent]s.
 */
fun mapDemonstrationTraceToActivityEvents(trace: DemonstrationTrace): List<ActivityEvent> {
    return mapTraceEventsToActivityEvents(trace.traceEvents)
}

/**
 * Maps an [ExecutionTrace] into [ActivityEvent]s.
 */
fun mapExecutionTraceToActivityEvents(trace: ExecutionTrace): List<ActivityEvent> {
    return mapTraceEventsToActivityEvents(trace.events)
}

/**
 * Maps a completed [RuntimeReport] into [ActivityEvent]s reflecting actual executed steps and safety events.
 */
fun mapRuntimeReportToActivityEvents(report: RuntimeReport): List<ActivityEvent> {
    val events = mutableListOf<ActivityEvent>()

    // Step reports
    report.stepReports.forEach { step ->
        val status = when (step.finalStepStatus) {
            StepExecutionStatus.COMPLETED -> ActivityStatus.COMPLETED
            StepExecutionStatus.FAILED,
            StepExecutionStatus.SAFETY_BLOCKED -> ActivityStatus.FAILED
            StepExecutionStatus.WAITING_FOR_USER,
            StepExecutionStatus.HANDED_OFF -> ActivityStatus.PENDING
            else -> ActivityStatus.IN_PROGRESS
        }
        events.add(
            ActivityEvent(
                id = "step_${step.stepId}",
                label = "Step ${step.stepIndex + 1}: ${step.actionType}",
                status = status,
                metadata = step.targetDescription ?: step.reason
            )
        )
    }

    // Safety events
    report.safetyEvents.forEach { safety ->
        events.add(
            ActivityEvent(
                id = "safety_${safety.stepId ?: java.util.UUID.randomUUID()}",
                label = "Safety Gate: ${safety.decision}",
                status = if (safety.decision == "BLOCKED") ActivityStatus.FAILED else ActivityStatus.COMPLETED,
                metadata = safety.reason
            )
        )
    }

    return events
}

/**
 * Thread-safe real-time architecture activity event stream.
 * Direct bridge between real components (Demonstration capture, Normalizer, Synthesizer,
 * Validator, Repository, ExecutionEngine, SafetyGate) and the ActivityProgressDialog.
 */
object ActivityEventStream {
    private val listeners = java.util.Collections.synchronizedList(mutableListOf<(ActivityEvent) -> Unit>())
    private val history = java.util.Collections.synchronizedList(mutableListOf<ActivityEvent>())

    fun emit(event: ActivityEvent) {
        synchronized(history) {
            val idx = history.indexOfFirst { it.id == event.id }
            if (idx >= 0) {
                history[idx] = event
            } else {
                history.add(event)
            }
        }
        val currentListeners = synchronized(listeners) { listeners.toList() }
        currentListeners.forEach { it(event) }
    }

    fun emit(
        id: String,
        label: String,
        status: ActivityStatus = ActivityStatus.PENDING,
        metadata: String? = null
    ) {
        emit(ActivityEvent(id = id, label = label, status = status, timestamp = System.currentTimeMillis(), metadata = metadata))
    }

    fun getEvents(): List<ActivityEvent> = synchronized(history) { history.toList() }

    val size: Int get() = synchronized(history) { history.size }

    fun clear() {
        synchronized(history) { history.clear() }
        val currentListeners = synchronized(listeners) { listeners.toList() }
        currentListeners.forEach { it(ActivityEvent("clear", "", ActivityStatus.PENDING)) }
    }

    fun subscribe(listener: (ActivityEvent) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (ActivityEvent) -> Unit) {
        listeners.remove(listener)
    }
}

/**
 * Standalone presentation state model for the Activity Dialog.
 */
data class ActivityUiState(
    val isOpen: Boolean = false,
    val title: String = "Activity",
    val events: List<ActivityEvent> = emptyList()
)

/**
 * Standalone Activity Progress Dialog.
 * Purely data-driven: renders supplied events dynamically with zero hardcoded pipeline or fake progress.
 */
@Composable
fun ActivityProgressDialog(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    title: String = "Activity",
    events: List<ActivityEvent> = emptyList(),
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = 380.dp)
                .clip(RadiusMd)
                .background(ColorBgSurfaceElevated)
                .border(1.dp, ColorBorderMedium, RadiusMd)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header: Title and Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = ColorTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.01).sp
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(ColorBgSurfaceSubtle)
                        .clickable { onDismissRequest() }
                        .semantics { contentDescription = "Close activity" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✕",
                        color = ColorTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Events List or Truthful Neutral Empty State
            if (events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No active activity",
                        color = ColorTextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(events, key = { it.id }) { event ->
                        ActivityEventRow(event = event)
                    }
                }
            }
        }
    }
}

/**
 * Visual renderer for an individual generic activity event.
 * Displays label exactly as supplied by the event source with zero hardcoded step branches.
 */
@Composable
fun ActivityEventRow(
    event: ActivityEvent,
    modifier: Modifier = Modifier
) {
    // Subtle rotation animation for active in-progress state (no fake progress increments)
    val infiniteTransition = rememberInfiniteTransition()
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadiusSm)
            .background(ColorBgSurfaceSubtle)
            .border(1.dp, ColorBorderSubtle, RadiusSm)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status symbol indicator
        when (event.status) {
            ActivityStatus.PENDING -> {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(ColorStatusInactiveBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "○",
                        color = ColorTextDisabled,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            ActivityStatus.IN_PROGRESS -> {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(ColorAccentSubtle)
                        .border(1.dp, ColorAccentGlow, CircleShape)
                        .rotate(rotationAngle),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "◉",
                        color = ColorAccentHover,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            ActivityStatus.COMPLETED -> {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(ColorStatusReadyBg)
                        .border(1.dp, ColorStatusReadyBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓",
                        color = ColorStatusReady,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            ActivityStatus.FAILED -> {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(ColorStatusErrorBg)
                        .border(1.dp, ColorStatusErrorBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✕",
                        color = ColorStatusError,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Generic Event Label and Optional Metadata (Truthful rendering)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.label,
                color = ColorTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            if (!event.metadata.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = event.metadata,
                    color = ColorTextMuted,
                    fontSize = 10.5.sp
                )
            }
        }
    }
}
