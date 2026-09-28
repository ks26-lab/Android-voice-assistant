package com.chockXlate.teachablevoice.teach.filter

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.filter.DemonstrationFilterClassification
import com.chockXlate.teachablevoice.contract.filter.FilteredDemonstrationResult
import com.chockXlate.teachablevoice.contract.filter.FilteredTraceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent

/**
 * Deterministic DemonstrationFilter.
 * Classifies raw/normalized trace events into:
 * - TASK_RELEVANT
 * - NAVIGATION_CONTEXT
 * - SYSTEM_NOISE
 * - UNCERTAIN
 *
 * Uses generic Android OS infrastructure identification, package continuity,
 * actionability, and semantic evidence. Never deletes evidence or uses app-specific rules.
 */
object DemonstrationFilter {

    private val SYSTEM_OR_LAUNCHER_PACKAGE_PATTERNS = listOf(
        "com.android.systemui",
        "android",
        "com.android.settings.intelligence",
        "com.android.permissioncontroller"
    )

    fun isSystemSurface(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        if (SYSTEM_OR_LAUNCHER_PACKAGE_PATTERNS.any { pkg == it || pkg.startsWith("$it.") }) return true
        if (pkg.contains("launcher") || pkg.contains("home") || pkg.endsWith(".launcher3")) return true
        if (pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains("honeyboard") ||
            pkg.contains("swiftkey") || pkg.contains("latin")) return true
        return false
    }

    fun isLauncherSurface(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        return pkg.contains("launcher") || pkg.contains("home") || pkg.endsWith(".launcher3")
    }

    fun isKeyboardSurface(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        return pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains("honeyboard") ||
            pkg.contains("swiftkey") || pkg.contains("latin")
    }

    fun filter(trace: DemonstrationTrace): FilteredDemonstrationResult {
        val allFiltered = mutableListOf<FilteredTraceEvent>()

        // 1. Resolve primary target application package(s)
        val candidatePackages = mutableListOf<String>()
        if (trace.appContext.isNotBlank() && trace.appContext != "unknown" && !isSystemSurface(trace.appContext)) {
            candidatePackages.add(trace.appContext)
        }

        // Collect packages from action events
        val actions = trace.traceEvents
            .filterIsInstance<TraceEvent.Action>()
            .map { it.actionEvent }
            .ifEmpty { trace.userActions }

        for (action in actions) {
            val pkg = if (action.semanticSelector.resourceId?.contains(":id/") == true) {
                action.semanticSelector.resourceId!!.substringBefore(":id/")
            } else null
            // Also check state events if matching
            val stateEv = trace.stateEvents.find { it.causeActionId == action.actionId }
            val statePkg = stateEv?.afterState?.appContext ?: stateEv?.beforeState?.appContext
            val resolvedPkg = statePkg ?: pkg
            if (!resolvedPkg.isNullOrBlank() && !isSystemSurface(resolvedPkg)) {
                candidatePackages.add(resolvedPkg)
            }
        }

        val primaryTargetPackage = candidatePackages.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: trace.appContext.ifBlank { "unknown" }

        // Find the timestamp when the target application was first engaged
        val firstTargetEngagementTimestamp = actions.firstOrNull { action ->
            val stateEv = trace.stateEvents.find { it.causeActionId == action.actionId }
            val pkg = stateEv?.beforeState?.appContext ?: stateEv?.afterState?.appContext ?: primaryTargetPackage
            !isSystemSurface(pkg)
        }?.timestamp ?: Long.MAX_VALUE

        // Track seen packages to evaluate cross-package continuity
        val actionPackages = actions.mapNotNull { a ->
            val stateEv = trace.stateEvents.find { it.causeActionId == a.actionId }
            stateEv?.afterState?.appContext ?: stateEv?.beforeState?.appContext
        }.filter { it.isNotBlank() && !isSystemSurface(it) }
        val packageCounts = actionPackages.groupingBy { it }.eachCount()

        // 2. Classify each event
        val eventsToProcess = trace.traceEvents.ifEmpty {
            // Reconstruct pseudo-trace events if traceEvents was empty
            val list = mutableListOf<TraceEvent>()
            trace.voiceEvents.forEach { list.add(TraceEvent.Voice(it.eventId, it.timestamp, it)) }
            trace.userActions.forEach { list.add(TraceEvent.Action(it.actionId, it.timestamp, it)) }
            trace.stateEvents.forEach { list.add(TraceEvent.State(it.stateEventId, it.timestamp, it)) }
            list.sortedBy { it.timestamp }
        }

        for (event in eventsToProcess) {
            val filteredEvent = classifyEvent(
                event = event,
                primaryPackage = primaryTargetPackage,
                firstTargetEngagementTimestamp = firstTargetEngagementTimestamp,
                packageContinuity = packageCounts,
                trace = trace
            )
            allFiltered.add(filteredEvent)
        }

        val taskRelevant = allFiltered.filter { it.classification == DemonstrationFilterClassification.TASK_RELEVANT }.map { it.eventId }
        val navContext = allFiltered.filter { it.classification == DemonstrationFilterClassification.NAVIGATION_CONTEXT }.map { it.eventId }
        val systemNoise = allFiltered.filter { it.classification == DemonstrationFilterClassification.SYSTEM_NOISE }.map { it.eventId }
        val uncertain = allFiltered.filter { it.classification == DemonstrationFilterClassification.UNCERTAIN }.map { it.eventId }

        return FilteredDemonstrationResult(
            schemaVersion = "1.0",
            traceId = trace.traceId,
            targetAppContext = primaryTargetPackage,
            allEvents = allFiltered,
            taskRelevantEventIds = taskRelevant,
            navigationContextEventIds = navContext,
            systemNoiseEventIds = systemNoise,
            uncertainEventIds = uncertain
        )
    }

    private fun classifyEvent(
        event: TraceEvent,
        primaryPackage: String,
        firstTargetEngagementTimestamp: Long,
        packageContinuity: Map<String, Int>,
        trace: DemonstrationTrace
    ): FilteredTraceEvent {
        return when (event) {
            is TraceEvent.Voice -> {
                FilteredTraceEvent(
                    eventId = event.eventId,
                    classification = DemonstrationFilterClassification.TASK_RELEVANT,
                    reason = "Voice guidance provided during demonstration for intent grounding.",
                    isActionable = false
                )
            }
            is TraceEvent.Action -> {
                classifyAction(
                    action = event.actionEvent,
                    primaryPackage = primaryPackage,
                    firstTargetEngagementTimestamp = firstTargetEngagementTimestamp,
                    packageContinuity = packageContinuity,
                    trace = trace
                )
            }
            is TraceEvent.State -> {
                classifyState(event.stateEvent)
            }
            is TraceEvent.Ui -> {
                classifyUi(event, firstTargetEngagementTimestamp)
            }
        }
    }

    private fun classifyAction(
        action: ActionEvent,
        primaryPackage: String,
        firstTargetEngagementTimestamp: Long,
        packageContinuity: Map<String, Int>,
        trace: DemonstrationTrace
    ): FilteredTraceEvent {
        val stateEv = trace.stateEvents.find { it.causeActionId == action.actionId }
        val resIdPkg = if (action.semanticSelector.resourceId?.contains(":id/") == true) {
            action.semanticSelector.resourceId!!.substringBefore(":id/")
        } else null
        val pkg = stateEv?.afterState?.appContext
            ?: stateEv?.beforeState?.appContext
            ?: resIdPkg
            ?: primaryPackage

        val isSystem = isSystemSurface(pkg)
        val isLauncher = isLauncherSurface(pkg)
        val isKeyboard = isKeyboardSurface(pkg)

        // Rule 1: Launcher navigation before target task
        if (isLauncher || (action.timestamp < firstTargetEngagementTimestamp && isSystem)) {
            return FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.NAVIGATION_CONTEXT,
                reason = "Launcher or home navigation prior to target application engagement.",
                packageName = pkg,
                isSystemSurface = true,
                isActionable = true
            )
        }

        // Rule 2: Keyboard window or IME events without inputData
        if (isKeyboard && action.inputData.isNullOrBlank() && action.actionType != "INPUT_TEXT") {
            return FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.NAVIGATION_CONTEXT,
                reason = "Keyboard surface transition without input text payload.",
                packageName = pkg,
                isSystemSurface = true,
                isActionable = true
            )
        }

        // Rule 3: System UI (status bar, volume dialog, notification shade)
        if (isSystem && !isKeyboard) {
            return FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.SYSTEM_NOISE,
                reason = "Transient system UI interaction not contributing to target task.",
                packageName = pkg,
                isSystemSurface = true,
                isActionable = false
            )
        }

        // Rule 4: Action lacks identifiable semantic target
        val selector = action.semanticSelector
        val hasIdentity = !selector.role.isNullOrBlank() ||
            !selector.resourceId.isNullOrBlank() ||
            !selector.text.isNullOrBlank() ||
            !selector.contentDescription.isNullOrBlank() ||
            !selector.textSlot.isNullOrBlank()

        if (!hasIdentity) {
            return FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.UNCERTAIN,
                reason = "Action contains no identifiable semantic selector attributes.",
                packageName = pkg,
                isSystemSurface = isSystem,
                isActionable = false
            )
        }

        // Rule 5: Legitimate cross-package transition vs unrelated foreign package
        if (pkg != primaryPackage && !isSystem) {
            val count = packageContinuity[pkg] ?: 0
            if (count >= 1) {
                return FilteredTraceEvent(
                    eventId = action.actionId,
                    classification = DemonstrationFilterClassification.TASK_RELEVANT,
                    reason = "Legitimate cross-package interaction in '$pkg' with task continuity.",
                    packageName = pkg,
                    isSystemSurface = false,
                    isActionable = true
                )
            } else {
                return FilteredTraceEvent(
                    eventId = action.actionId,
                    classification = DemonstrationFilterClassification.SYSTEM_NOISE,
                    reason = "Incidental action in unrelated package '$pkg' without task continuity.",
                    packageName = pkg,
                    isSystemSurface = false,
                    isActionable = false
                )
            }
        }

        // Rule 6: Target app action with identifiable target
        return FilteredTraceEvent(
            eventId = action.actionId,
            classification = DemonstrationFilterClassification.TASK_RELEVANT,
            reason = "Task-relevant action in application '$pkg'.",
            packageName = pkg,
            isSystemSurface = false,
            isActionable = true
        )
    }

    private fun classifyState(
        state: com.chockXlate.teachablevoice.contract.event.StateEvent
    ): FilteredTraceEvent {
        val beforePkg = state.beforeState.appContext
        val afterPkg = state.afterState.appContext

        if (state.beforeState.stateId.isBlank() && state.afterState.stateId.isBlank()) {
            return FilteredTraceEvent(
                eventId = state.stateEventId,
                classification = DemonstrationFilterClassification.UNCERTAIN,
                reason = "State event contains blank before and after state identity.",
                packageName = beforePkg.ifBlank { afterPkg },
                isSystemSurface = false,
                isActionable = false
            )
        }

        if (isSystemSurface(beforePkg) && isSystemSurface(afterPkg)) {
            return FilteredTraceEvent(
                eventId = state.stateEventId,
                classification = DemonstrationFilterClassification.SYSTEM_NOISE,
                reason = "State transition completely enclosed within Android system/launcher surface.",
                packageName = beforePkg,
                isSystemSurface = true,
                isActionable = false
            )
        }

        return FilteredTraceEvent(
            eventId = state.stateEventId,
            classification = DemonstrationFilterClassification.TASK_RELEVANT,
            reason = "Task state transition between states '${state.beforeState.stateId}' and '${state.afterState.stateId}'.",
            packageName = afterPkg.ifBlank { beforePkg },
            isSystemSurface = false,
            isActionable = false
        )
    }

    private fun classifyUi(
        uiEvent: TraceEvent.Ui,
        firstTargetEngagementTimestamp: Long
    ): FilteredTraceEvent {
        val event = uiEvent.uiEvent
        val pkg = event.packageName

        if (isLauncherSurface(pkg) || (uiEvent.timestamp < firstTargetEngagementTimestamp && isSystemSurface(pkg))) {
            return FilteredTraceEvent(
                eventId = uiEvent.eventId,
                classification = DemonstrationFilterClassification.NAVIGATION_CONTEXT,
                reason = "Launcher surface UI event prior to target application engagement.",
                packageName = pkg,
                isSystemSurface = true,
                isActionable = false
            )
        }

        if (isSystemSurface(pkg)) {
            return FilteredTraceEvent(
                eventId = uiEvent.eventId,
                classification = DemonstrationFilterClassification.SYSTEM_NOISE,
                reason = "Android system surface UI notification or transition.",
                packageName = pkg,
                isSystemSurface = true,
                isActionable = false
            )
        }

        // Unrelated content changes without target element or action association
        if (event.targetElement == null && event.accessibilityEventType.contains("CONTENT_CHANGE")) {
            return FilteredTraceEvent(
                eventId = uiEvent.eventId,
                classification = DemonstrationFilterClassification.SYSTEM_NOISE,
                reason = "Unrelated background UI content change without semantic target.",
                packageName = pkg,
                isSystemSurface = false,
                isActionable = false
            )
        }

        return FilteredTraceEvent(
            eventId = uiEvent.eventId,
            classification = DemonstrationFilterClassification.TASK_RELEVANT,
            reason = "Target UI observation for application '$pkg'.",
            packageName = pkg,
            isSystemSurface = false,
            isActionable = false
        )
    }
}
