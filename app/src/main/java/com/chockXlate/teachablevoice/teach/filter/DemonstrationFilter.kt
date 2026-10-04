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

    fun isOwnApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val own = try {
            com.chockXlate.teachablevoice.app.service.TeachableVoiceAccessibilityService.instance?.getServicePackageName()
                ?: "com.chockXlate.teachablevoice"
        } catch (t: Throwable) {
            "com.chockXlate.teachablevoice"
        }
        val pkg = packageName.lowercase()
        val ownLower = own.lowercase()
        return pkg == ownLower || pkg.startsWith("$ownLower.")
    }

    fun isSystemSurface(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        if (SYSTEM_OR_LAUNCHER_PACKAGE_PATTERNS.any { pkg == it || pkg.startsWith("$it.") }) return true
        if (isLauncherSurface(pkg)) return true
        if (pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains("honeyboard") ||
            pkg.contains("swiftkey") || pkg.contains("latin")) return true
        return false
    }

    fun isLauncherSurface(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        if (pkg.contains("search") || pkg.contains("browser") || pkg.contains("chrome")) return false
        return pkg.contains("launcher") || pkg.endsWith(".home") || pkg.contains(".home.") ||
            pkg.endsWith(".launcher3") || pkg.contains("nexuslauncher") || pkg.contains("quickstep") ||
            pkg.contains("recents") || pkg.contains("overview")
    }

    fun isKeyboardSurface(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        return pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains("honeyboard") ||
            pkg.contains("swiftkey") || pkg.contains("latin")
    }

    private val GENERIC_CONTAINER_ROLES = setOf(
        "framelayout", "linearlayout", "relativelayout", "viewgroup",
        "view", "viewstub", "scrollview", "horizontalscrollview",
        "nestedscrollview", "coordinatorlayout", "constraintlayout",
        "drawerlayout", "viewpager", "viewpager2", "recyclerview",
        "listview", "gridview", "cardview"
    )

    fun isAnonymousContainer(selector: com.chockXlate.teachablevoice.contract.workflow.SemanticSelector): Boolean {
        val role = selector.role?.substringAfterLast('.')?.lowercase() ?: return true
        val isGenericRole = role.isBlank() || role in GENERIC_CONTAINER_ROLES
        val hasEvidence = !selector.text.isNullOrBlank() ||
            !selector.contentDescription.isNullOrBlank() ||
            !selector.resourceId.isNullOrBlank() ||
            !selector.nearbyText.isNullOrBlank() ||
            !selector.textSlot.isNullOrBlank()
        return isGenericRole && !hasEvidence
    }

    fun isSettingsSurface(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        return pkg == "com.android.settings" || pkg.startsWith("com.android.settings.") ||
            pkg.contains(".settings") || pkg.endsWith(".settings")
    }

    fun isBackAction(action: ActionEvent): Boolean {
        if (action.actionType.equals("BACK", ignoreCase = true)) return true
        val text = action.semanticSelector.text?.lowercase() ?: ""
        val desc = action.semanticSelector.contentDescription?.lowercase() ?: ""
        val res = action.semanticSelector.resourceId?.lowercase() ?: ""
        return desc.contains("navigate up") || desc.contains("back") || text == "back" ||
            res.contains("back") || res.contains("up_button") || res.contains("home_as_up")
    }

    fun extractIntentTokens(
        skillName: String?,
        skillDescription: String?,
        voiceEvents: List<com.chockXlate.teachablevoice.contract.event.VoiceEvent> = emptyList()
    ): Set<String> {
        val combined = buildString {
            if (!skillName.isNullOrBlank()) append(skillName).append(" ")
            if (!skillDescription.isNullOrBlank()) append(skillDescription).append(" ")
            voiceEvents.forEach { append(it.transcript).append(" ") }
        }.lowercase()

        val stopWords = setOf(
            "a", "an", "the", "and", "or", "to", "for", "in", "on", "at", "by", "of", "from", "with",
            "search", "find", "order", "buy", "open", "launch", "navigate", "go", "click", "tap",
            "using", "use", "app", "application", "task", "skill", "query", "any", "please", "can",
            "you", "me", "show", "get", "do", "run", "start", "stop", "test", "my", "item", "something"
        )

        return combined.split(Regex("[^a-zA-Z0-9_]+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()
    }

    fun packageMatchesIntent(packageName: String, intentTokens: Set<String>): Boolean {
        if (intentTokens.isEmpty()) return false
        val pkgLower = packageName.lowercase()
        val segments = pkgLower.split('.', '_', '-')
        return intentTokens.any { token ->
            segments.any { seg -> seg == token || seg.contains(token) } || pkgLower.contains(token)
        }
    }

    fun filter(
        trace: DemonstrationTrace,
        skillName: String? = null,
        skillDescription: String? = null
    ): FilteredDemonstrationResult {
        val allFiltered = mutableListOf<FilteredTraceEvent>()

        val intentTokens = extractIntentTokens(skillName, skillDescription, trace.voiceEvents)

        // 1. Resolve candidate application package(s)
        val candidatePackages = mutableListOf<String>()
        if (trace.appContext.isNotBlank() && trace.appContext != "unknown" && !isSystemSurface(trace.appContext) && !isOwnApp(trace.appContext)) {
            candidatePackages.add(trace.appContext)
        }

        // Collect packages from action events
        val actions = trace.traceEvents
            .filterIsInstance<TraceEvent.Action>()
            .map { it.actionEvent }
            .ifEmpty { trace.userActions }

        for (action in actions) {
            val resPkg = action.semanticSelector.resourceId?.takeIf { it.contains(":id/") }?.substringBefore(":id/")
            val stateEv = trace.stateEvents.find { it.causeActionId == action.actionId }
            val statePkg = stateEv?.beforeState?.appContext?.takeIf { it.isNotBlank() && it != "unknown" }
                ?: stateEv?.afterState?.appContext?.takeIf { it.isNotBlank() && it != "unknown" }

            val resolvedPkg = action.packageName?.takeIf { it.isNotBlank() && it != "unknown" }
                ?: statePkg
                ?: resPkg

            if (!resolvedPkg.isNullOrBlank() && !isSystemSurface(resolvedPkg) && !isOwnApp(resolvedPkg)) {
                candidatePackages.add(resolvedPkg)
            }
        }

        if (candidatePackages.isEmpty()) {
            val uiPkgs = trace.traceEvents.filterIsInstance<TraceEvent.Ui>()
                .map { it.uiEvent.packageName }
                .filter { it.isNotBlank() && it != "unknown" && !isSystemSurface(it) && !isOwnApp(it) }
            candidatePackages.addAll(uiPkgs)
            if (candidatePackages.isEmpty()) {
                val statePkgs = trace.uiStates.map { it.appContext }
                    .filter { it.isNotBlank() && it != "unknown" && !isSystemSurface(it) && !isOwnApp(it) }
                candidatePackages.addAll(statePkgs)
            }
        }

        // Identify Intent-Target Packages (supports multi-app workflows natively)
        val intentTargetPackages = candidatePackages.filter { packageMatchesIntent(it, intentTokens) && !isSettingsSurface(it) }.toSet()

        val primaryTargetPackage = when {
            intentTargetPackages.isNotEmpty() -> {
                candidatePackages.filter { it in intentTargetPackages }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    ?: intentTargetPackages.first()
            }
            else -> {
                candidatePackages.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    ?: trace.appContext.takeIf { !isSystemSurface(it) && !isOwnApp(it) && it.isNotBlank() && it != "unknown" }
                    ?: actions.mapNotNull { it.packageName }.firstOrNull { !isSystemSurface(it) && !isOwnApp(it) && it.isNotBlank() && it != "unknown" }
                    ?: "unknown"
            }
        }

        // Pre-pass: Detect Mistake Sequences (Backtracking, Accidental App Detours, Superseded Inputs)
        val sortedActions = actions.sortedBy { it.timestamp }
        val undoneActionIds = mutableSetOf<String>()
        val recoveryActionIds = mutableSetOf<String>()
        val supersededActionIds = mutableSetOf<String>()
        val detourActionIds = mutableSetOf<String>()

        val allowsSettings = intentTokens.contains("settings")

        for (i in sortedActions.indices) {
            val current = sortedActions[i]
            val curPkg = current.packageName ?: ""

            // Accidental Settings Detection
            if (isSettingsSurface(curPkg) && !allowsSettings) {
                detourActionIds.add(current.actionId)
            }

            // Accidental Foreign App Detour Detection
            if (intentTargetPackages.isNotEmpty() && curPkg.isNotBlank() && curPkg !in intentTargetPackages && !isSystemSurface(curPkg)) {
                detourActionIds.add(current.actionId)
            }

            // Backtracking & Correction Detection
            if (isBackAction(current)) {
                recoveryActionIds.add(current.actionId)
                if (i > 0) {
                    val prev = sortedActions[i - 1]
                    // If previous action was in a detour or was an accidental click in same app, mark as undone
                    undoneActionIds.add(prev.actionId)
                }
            }

            // Text input correction detection (e.g. typing "cars", then replacing with "headphones")
            if (current.actionType == "INPUT_TEXT" || !current.inputData.isNullOrBlank()) {
                val currentText = current.inputData ?: current.semanticSelector.text ?: ""
                for (j in i + 1 until sortedActions.size) {
                    val next = sortedActions[j]
                    if (next.actionType == "INPUT_TEXT" || !next.inputData.isNullOrBlank()) {
                        val nextText = next.inputData ?: next.semanticSelector.text ?: ""
                        if (currentText.isNotBlank() && nextText.isNotBlank() && currentText != nextText) {
                            val nextMatchesIntent = intentTokens.any { nextText.lowercase().contains(it) }
                            val currentMatchesIntent = intentTokens.any { currentText.lowercase().contains(it) }
                            if (nextMatchesIntent && !currentMatchesIntent) {
                                supersededActionIds.add(current.actionId)
                            }
                        }
                    }
                }
            }
        }

        // Find the timestamp when the target application was first engaged
        val firstTargetEngagementTimestamp = actions.firstOrNull { action ->
            val resPkg = action.semanticSelector.resourceId?.takeIf { it.contains(":id/") }?.substringBefore(":id/")
            val stateEv = trace.stateEvents.find { it.causeActionId == action.actionId }
            val statePkg = stateEv?.afterState?.appContext?.takeIf { it.isNotBlank() && it != "unknown" && !isSystemSurface(it) }
                ?: stateEv?.beforeState?.appContext?.takeIf { it.isNotBlank() && it != "unknown" && !isSystemSurface(it) }
            val resolvedPkg = action.packageName?.takeIf { it.isNotBlank() && it != "unknown" }
                ?: resPkg
                ?: statePkg
                ?: if (primaryTargetPackage != "unknown") primaryTargetPackage else null

            val isTextInput = action.actionType == "INPUT_TEXT" || !action.inputData.isNullOrBlank()

            (resolvedPkg != null && !isSystemSurface(resolvedPkg) && !isOwnApp(resolvedPkg)) || (isTextInput && primaryTargetPackage != "unknown")
        }?.timestamp ?: Long.MAX_VALUE

        // Track seen packages to evaluate cross-package continuity
        val actionPackages = actions.mapNotNull { a ->
            val stateEv = trace.stateEvents.find { it.causeActionId == a.actionId }
            stateEv?.afterState?.appContext ?: stateEv?.beforeState?.appContext
        }.filter { it.isNotBlank() && !isSystemSurface(it) }
        val packageCounts = actionPackages.groupingBy { it }.eachCount()

        // 2. Classify each event
        val eventsToProcess = trace.traceEvents.ifEmpty {
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
                intentTargetPackages = intentTargetPackages,
                undoneActionIds = undoneActionIds,
                recoveryActionIds = recoveryActionIds,
                supersededActionIds = supersededActionIds,
                detourActionIds = detourActionIds,
                allowsSettings = allowsSettings,
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
        intentTargetPackages: Set<String> = emptySet(),
        undoneActionIds: Set<String> = emptySet(),
        recoveryActionIds: Set<String> = emptySet(),
        supersededActionIds: Set<String> = emptySet(),
        detourActionIds: Set<String> = emptySet(),
        allowsSettings: Boolean = false,
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
                    intentTargetPackages = intentTargetPackages,
                    undoneActionIds = undoneActionIds,
                    recoveryActionIds = recoveryActionIds,
                    supersededActionIds = supersededActionIds,
                    detourActionIds = detourActionIds,
                    allowsSettings = allowsSettings,
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
        intentTargetPackages: Set<String> = emptySet(),
        undoneActionIds: Set<String> = emptySet(),
        recoveryActionIds: Set<String> = emptySet(),
        supersededActionIds: Set<String> = emptySet(),
        detourActionIds: Set<String> = emptySet(),
        allowsSettings: Boolean = false,
        firstTargetEngagementTimestamp: Long,
        packageContinuity: Map<String, Int>,
        trace: DemonstrationTrace
    ): FilteredTraceEvent {
        val stateEv = trace.stateEvents.find { it.causeActionId == action.actionId }
        val resIdPkg = action.semanticSelector.resourceId?.takeIf { it.contains(":id/") }?.substringBefore(":id/")
        val matchingUi = trace.traceEvents.asSequence()
            .filterIsInstance<TraceEvent.Ui>()
            .map { it.uiEvent }
            .find { it.timestamp == action.timestamp || (kotlin.math.abs(it.timestamp - action.timestamp) <= 100L && it.targetElement?.text == action.semanticSelector.text) }

        val isTextInput = action.actionType == "INPUT_TEXT" || !action.inputData.isNullOrBlank()

        val rawOriginPkg = action.packageName?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: matchingUi?.packageName?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: stateEv?.beforeState?.appContext?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: resIdPkg?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: stateEv?.afterState?.appContext?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: primaryPackage

        // Re-attribute text input from keyboard window to genuine target application
        val originPkg = if (isTextInput && (isKeyboardSurface(rawOriginPkg) || rawOriginPkg == "unknown" || isSystemSurface(rawOriginPkg))) {
            val targetApp = primaryPackage.takeIf { it != "unknown" && !isSystemSurface(it) && !isOwnApp(it) }
                ?: trace.appContext.takeIf { it.isNotBlank() && it != "unknown" && !isSystemSurface(it) && !isOwnApp(it) }
                ?: rawOriginPkg
            targetApp
        } else {
            rawOriginPkg
        }

        val destPkg = stateEv?.afterState?.appContext ?: originPkg

        val isOwn = isOwnApp(originPkg)
        val isSystem = isSystemSurface(originPkg)
        val isLauncher = isLauncherSurface(originPkg)
        val isKeyboard = isKeyboardSurface(originPkg)

        // Rule 0: Own-package interaction must NEVER become learned workflow action
        if (isOwn) {
            val res = FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.SYSTEM_NOISE,
                reason = "Interaction belongs to assistant's own application package.",
                packageName = originPkg,
                isSystemSurface = true,
                isActionable = false
            )
            safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
            return res
        }

        // Rule -3: Undone accidental action (reverted by subsequent Back)
        if (action.actionId in undoneActionIds) {
            val res = FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.SYSTEM_NOISE,
                reason = "Undone accidental target interaction (reverted by user back navigation).",
                packageName = originPkg,
                isSystemSurface = isSystem,
                isActionable = false
            )
            safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
            return res
        }

        // Rule -2: Mistake recovery action (Back button returning to task context)
        if (action.actionId in recoveryActionIds) {
            val res = FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.NAVIGATION_CONTEXT,
                reason = "Mistake recovery navigation returning to primary task context.",
                packageName = originPkg,
                isSystemSurface = isSystem,
                isActionable = false
            )
            safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
            return res
        }

        // Rule -1: Superseded input value (replaced by corrected value)
        if (action.actionId in supersededActionIds) {
            val res = FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.SYSTEM_NOISE,
                reason = "Superseded input value replaced by user correction.",
                packageName = originPkg,
                isSystemSurface = isSystem,
                isActionable = false
            )
            safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
            return res
        }

        // Rule 0.5: Settings surface interaction outside declared task intent
        if (isSettingsSurface(originPkg) && !allowsSettings) {
            val res = FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.SYSTEM_NOISE,
                reason = "Settings surface interaction is not part of declared task intent.",
                packageName = originPkg,
                isSystemSurface = true,
                isActionable = false
            )
            safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
            return res
        }

        // Rule 0.8: Accidental foreign app detour outside declared intent targets
        if (action.actionId in detourActionIds || (intentTargetPackages.isNotEmpty() && originPkg !in intentTargetPackages && !isSystem)) {
            val res = FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.SYSTEM_NOISE,
                reason = "Incidental action in unrelated package '$originPkg' outside declared task context.",
                packageName = originPkg,
                isSystemSurface = false,
                isActionable = false
            )
            safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
            return res
        }

        // Rule 1: Launcher or Recents transition navigation
        if (!isTextInput && (isLauncher || (action.timestamp < firstTargetEngagementTimestamp && isSystem))) {
            val res = FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.NAVIGATION_CONTEXT,
                reason = "Launcher or Recents transition navigation.",
                packageName = originPkg,
                isSystemSurface = true,
                isActionable = true
            )
            safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
            return res
        }

        // Rule 2: Keyboard window or IME events without inputData
        if (isKeyboard && action.inputData.isNullOrBlank() && action.actionType != "INPUT_TEXT") {
            val res = FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.NAVIGATION_CONTEXT,
                reason = "Keyboard surface transition without input text payload.",
                packageName = originPkg,
                isSystemSurface = true,
                isActionable = true
            )
            safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
            return res
        }

        // Rule 3: System UI (status bar, volume dialog, notification shade, nav bar)
        if (isSystem && !isKeyboard && !isTextInput) {
            val res = FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.SYSTEM_NOISE,
                reason = "Transient system UI interaction not contributing to target task.",
                packageName = originPkg,
                isSystemSurface = true,
                isActionable = false
            )
            safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
            return res
        }

        // Rule 3.5: Transition back to own app
        if (isOwnApp(destPkg) && originPkg != destPkg) {
            val res = FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.NAVIGATION_CONTEXT,
                reason = "Navigation transition returning to assistant application.",
                packageName = originPkg,
                isSystemSurface = true,
                isActionable = false
            )
            safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
            return res
        }

        // Rule 4: Anonymous container lacking meaningful semantic target evidence
        val selector = action.semanticSelector
        if (isAnonymousContainer(selector) && !isTextInput) {
            val res = FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.UNCERTAIN,
                reason = "Action on anonymous container '${selector.role ?: "View"}' lacks text, contentDescription, resourceId, and relational evidence.",
                packageName = originPkg,
                isSystemSurface = isSystem,
                isActionable = false
            )
            safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
            return res
        }

        val hasIdentity = !selector.role.isNullOrBlank() ||
            !selector.resourceId.isNullOrBlank() ||
            !selector.text.isNullOrBlank() ||
            !selector.contentDescription.isNullOrBlank() ||
            !selector.textSlot.isNullOrBlank() ||
            isTextInput

        if (!hasIdentity) {
            val res = FilteredTraceEvent(
                eventId = action.actionId,
                classification = DemonstrationFilterClassification.UNCERTAIN,
                reason = "Action contains no identifiable semantic selector attributes.",
                packageName = originPkg,
                isSystemSurface = isSystem,
                isActionable = false
            )
            safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
            return res
        }

        // Rule 5: Legitimate cross-package transition vs unrelated foreign package
        if (originPkg != primaryPackage && !isSystem) {
            val isIntentTarget = originPkg in intentTargetPackages
            val count = packageContinuity[originPkg] ?: 0
            if (isIntentTarget || count >= 1 || (action.packageName == originPkg && !isSystemSurface(originPkg))) {
                val res = FilteredTraceEvent(
                    eventId = action.actionId,
                    classification = DemonstrationFilterClassification.TASK_RELEVANT,
                    reason = "Legitimate cross-package interaction in '$originPkg' with task continuity.",
                    packageName = originPkg,
                    isSystemSurface = false,
                    isActionable = true
                )
                safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
                return res
            } else {
                val res = FilteredTraceEvent(
                    eventId = action.actionId,
                    classification = DemonstrationFilterClassification.SYSTEM_NOISE,
                    reason = "Incidental action in unrelated package '$originPkg' without task continuity.",
                    packageName = originPkg,
                    isSystemSurface = false,
                    isActionable = false
                )
                safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
                return res
            }
        }

        // Rule 6: Target app action with identifiable target
        val res = FilteredTraceEvent(
            eventId = action.actionId,
            classification = DemonstrationFilterClassification.TASK_RELEVANT,
            reason = "Task-relevant action in application '$originPkg'.",
            packageName = originPkg,
            isSystemSurface = false,
            isActionable = true
        )
        safeLogFilter(action.actionType, originPkg, res.classification.name, res.isActionable, res.reason)
        return res
    }

    private fun safeLogFilter(action: String, pkg: String, classification: String, actionable: Boolean, reason: String) {
        try {
            android.util.Log.d("TVA_FILTER", "action=$action pkg=$pkg classification=$classification actionable=$actionable reason=$reason")
        } catch (t: Throwable) {}
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
