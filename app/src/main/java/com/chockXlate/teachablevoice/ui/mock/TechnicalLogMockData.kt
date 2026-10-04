package com.chockXlate.teachablevoice.ui.mock

enum class LogCategory(val displayName: String) {
    STATE("STATE"),
    SYSTEM("SYSTEM"),
    TEACH("TEACH"),
    TRACE("TRACE"),
    NORMALIZE("NORMALIZE"),
    LEARN("LEARN"),
    VALIDATE("VALIDATE"),
    SKILL("SKILL"),
    RUNTIME("RUNTIME"),
    WAITING("WAITING"),
    SAFETY("SAFETY"),
    ERROR("ERROR"),
    RECOVERY("RECOVERY"),
    ACTION("ACTION"),
    VERIFY("VERIFY")
}

data class LogEntry(
    val time: String,
    val category: LogCategory,
    val event: String,
    val detail: String? = null,
    val type: String = "normal" // normal, active, success, warning, error
)

val initialSystemLogs = listOf(
    LogEntry(
        time = "12:28:40",
        category = LogCategory.SYSTEM,
        event = "SYSTEM_INITIALIZED",
        detail = "Accessibility bridge connected on port 8080",
        type = "normal"
    ),
    LogEntry(
        time = "12:28:41",
        category = LogCategory.STATE,
        event = "SYSTEM_READY",
        detail = "Ready for teaching or runtime execution",
        type = "normal"
    ),
    LogEntry(
        time = "12:28:42",
        category = LogCategory.WAITING,
        event = "WAITING_FOR_USER_ACTION",
        detail = "Listening for voice trigger or tap event",
        type = "normal"
    )
)

val teachingLogStart = listOf(
    LogEntry("12:28:45", LogCategory.STATE, "STATE_CHANGED", "READY -> TEACHING", "active"),
    LogEntry("12:28:45", LogCategory.TEACH, "TEACHING_STARTED", "Recording interaction stream from target app", "active"),
    LogEntry("12:28:46", LogCategory.TRACE, "DEMONSTRATION_BUFFER_ACTIVE", "Window hierarchy capture enabled", "normal")
)

val teachingLogCapture = listOf(
    LogEntry("12:28:50", LogCategory.TEACH, "TEACHING_STOPPED", "Captured 5 raw user interactions", "normal"),
    LogEntry("12:28:50", LogCategory.STATE, "STATE_CHANGED", "TEACHING -> TRACE_CAPTURED", "normal"),
    LogEntry("12:28:50", LogCategory.TRACE, "TRACE_BUFFER_SEALED", "Raw event stream stored in local buffer (size: 48KB)", "normal")
)

val teachingLogNormalizeStart = listOf(
    LogEntry("12:28:52", LogCategory.NORMALIZE, "TRACE_NORMALIZATION_STARTED", "Filtering redundant gestures and temporal delays", "active"),
    LogEntry("12:28:52", LogCategory.STATE, "STATE_CHANGED", "TRACE_CAPTURED -> NORMALIZING", "active")
)

val teachingLogNormalizeDone = listOf(
    LogEntry("12:28:53", LogCategory.NORMALIZE, "TRACE_NORMALIZATION_COMPLETE", "5 canonical UI events preserved; 3 noise events dropped", "success"),
    LogEntry("12:28:53", LogCategory.STATE, "STATE_CHANGED", "NORMALIZING -> NORMALIZED", "normal")
)

val teachingLogExtract = listOf(
    LogEntry("12:28:55", LogCategory.STATE, "STATE_CHANGED", "NORMALIZED -> EXTRACTING", "active"),
    LogEntry("12:28:55", LogCategory.LEARN, "SEMANTIC_ACTIONS_EXTRACTED", "Discovered 5 semantic steps: SEARCH, SELECT, SET_VALUE, SELECT, CONFIRM", "success"),
    LogEntry("12:28:56", LogCategory.LEARN, "PARAMETER_SLOTS_BOUND", "Bound slots: [restaurant, item, quantity, address]", "normal")
)

val teachingLogValidate = listOf(
    LogEntry("12:28:58", LogCategory.STATE, "STATE_CHANGED", "EXTRACTING -> VALIDATING", "active"),
    LogEntry("12:28:58", LogCategory.VALIDATE, "WORKFLOW_VALIDATION_PASSED", "All 4 consistency & determinism checks satisfied", "success"),
    LogEntry("12:28:59", LogCategory.VERIFY, "POST_CONDITION_VERIFIED", "Deterministic completion path verified", "normal")
)

val teachingLogStore = listOf(
    LogEntry("12:29:00", LogCategory.SKILL, "SKILL_STORED", "Skill 'Order Food' (order_food_v1) saved to local store", "success"),
    LogEntry("12:29:00", LogCategory.STATE, "STATE_CHANGED", "VALIDATING -> SKILL_STORED", "success"),
    LogEntry("12:29:00", LogCategory.STATE, "SYSTEM_READY", "Runtime execution engine armed for 'order_food'", "normal")
)

val runtimeStepLogs = listOf(
    LogEntry("12:29:01", LogCategory.ACTION, "STEP_1_SEARCH", "Executing: search_input.type('Pizza Palace')", "active"),
    LogEntry("12:29:02", LogCategory.VERIFY, "STEP_1_COMPLETE", "Search results rendered successfully", "success"),
    LogEntry("12:29:03", LogCategory.ACTION, "STEP_2_SELECT", "Executing: card_item[0].click()", "active"),
    LogEntry("12:29:04", LogCategory.VERIFY, "STEP_2_COMPLETE", "Menu page loaded successfully", "success"),
    LogEntry("12:29:05", LogCategory.ACTION, "STEP_3_SET_VALUE", "Executing: quantity_stepper.set(2)", "active"),
    LogEntry("12:29:06", LogCategory.VERIFY, "STEP_3_COMPLETE", "Quantity set to 2", "success"),
    LogEntry("12:29:07", LogCategory.ACTION, "STEP_4_SELECT", "Executing: address_selector.choose('Home')", "active"),
    LogEntry("12:29:08", LogCategory.VERIFY, "STEP_4_COMPLETE", "Delivery address verified", "success"),
    LogEntry("12:29:09", LogCategory.ACTION, "STEP_5_CONFIRM", "Executing: place_order_button.click()", "active"),
    LogEntry("12:29:10", LogCategory.VERIFY, "STEP_5_COMPLETE", "Order confirmation receipt acknowledged", "success"),
    LogEntry("12:29:10", LogCategory.RUNTIME, "RUNTIME_COMPLETE", "All 5 semantic steps executed in 12.4s", "success")
)

val handoffScenarioLogs = listOf(
    LogEntry("12:29:05", LogCategory.SAFETY, "SENSITIVE_STATE_DETECTED", "Payment authorization step encountered", "warning"),
    LogEntry("12:29:05", LogCategory.SAFETY, "USER_HANDOFF_REQUIRED", "Security policy requires direct biometric/user action", "warning"),
    LogEntry("12:29:06", LogCategory.STATE, "AUTOMATION_STOPPED", "Control returned to user at Step 3 (Payment)", "warning")
)

val clarificationAmbiguousLogs = listOf(
    LogEntry("12:29:03", LogCategory.WAITING, "TARGET_NOT_UNIQUE", "Multiple matching restaurant results found", "warning"),
    LogEntry("12:29:03", LogCategory.WAITING, "CLARIFICATION_REQUIRED", "Awaiting user selection: [Pizza Palace | Domino's]", "warning")
)

val clarificationRecoveryLogs = listOf(
    LogEntry("12:29:07", LogCategory.RECOVERY, "CLARIFICATION_RECEIVED", "User selected: 'Pizza Palace'", "success"),
    LogEntry("12:29:07", LogCategory.RECOVERY, "TARGET_RESOLVED", "Selector rebound to target element", "success"),
    LogEntry("12:29:08", LogCategory.RUNTIME, "RUNTIME_RESUMED", "Continuing execution from Step 2", "active")
)

val unresolvedScenarioLogs = listOf(
    LogEntry("12:29:03", LogCategory.ERROR, "TARGET_NOT_RESOLVED", "Selector failed: no element matching 'Restaurant Result'", "error"),
    LogEntry("12:29:04", LogCategory.SAFETY, "AUTOMATION_PAUSED", "Execution halted; manual target resolution required", "warning")
)
