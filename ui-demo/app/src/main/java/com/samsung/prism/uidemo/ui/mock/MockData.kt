package com.samsung.prism.uidemo.ui.mock

enum class UiState {
    READY,
    TEACHING,
    TRACE_CAPTURED,
    NORMALIZING,
    NORMALIZED,
    EXTRACTING,
    VALIDATING,
    SKILL_STORED
}

enum class RuntimeState {
    NO_WORKFLOW,
    READY,
    STARTING,
    RUNNING,
    PAUSED,
    USER_HANDOFF,
    CLARIFICATION_NEEDED,
    RECOVERING,
    TARGET_NOT_RESOLVED,
    COMPLETED
}

enum class RuntimeScenario(val label: String) {
    SUCCESS("Normal"),
    SAFETY_HANDOFF("Safety Stop"),
    CLARIFICATION("Clarify"),
    UNRESOLVED_TARGET("Unresolved")
}

data class PipelineStep(
    val id: String,
    val label: String,
    val status: String // "inactive", "active", "done"
)

data class SemanticAction(
    val step: Int,
    val type: String,
    val target: String,
    val value: String? = null
)

data class ValidationItem(
    val id: String,
    val label: String,
    val passed: Boolean
)

data class SlotItem(
    val name: String,
    val example: String
)

data class LearnedSkillData(
    val state: String = "EMPTY", // "EMPTY", "BUILDING", "VALIDATING", "STORED"
    val hasSkill: Boolean = false,
    val name: String = "Order Food",
    val technicalId: String = "skill.order_food.v1",
    val status: String = "VALIDATED",
    val intent: String = "order_food",
    val slots: List<SlotItem> = listOf(
        SlotItem("restaurant", "Pizza Palace"),
        SlotItem("item", "Margherita Pizza"),
        SlotItem("quantity", "1"),
        SlotItem("address", "Home")
    ),
    val semanticActionCount: Int = 5,
    val semanticActions: List<SemanticAction> = listOf(
        SemanticAction(1, "SEARCH", "Search Bar", "Pizza Palace"),
        SemanticAction(2, "SELECT", "First Match Item", "Pizza Palace"),
        SemanticAction(3, "SET_VALUE", "Quantity Field", "1"),
        SemanticAction(4, "SELECT", "Add to Cart Button", null),
        SemanticAction(5, "CONFIRM", "Place Order Button", null)
    ),
    val emptyTitle: String = "No learned skill yet",
    val emptyDescription: String = "Teach a workflow once to create a reusable skill.",
    val buildingTitle: String = "Building skill...",
    val buildingDescription: String = "Semantic structure being prepared."
)

data class RuntimeStep(
    val stepNumber: Int,
    val action: String,
    val target: String,
    val description: String,
    val log: String
)

data class LogEvent(
    val time: String,
    val category: String,
    val event: String,
    val detail: String,
    val type: String = "normal" // "normal", "active", "success", "warning", "safety", "error"
)

data class LastRunData(
    val hasHistory: Boolean = false,
    val outcome: String? = null,
    val status: String? = null,
    val skillName: String = "Order Food",
    val stepsCount: Int = 5,
    val failuresCount: Int = 0,
    val duration: String = "12.4 s",
    val stoppedAt: String? = null,
    val totalSteps: Int = 5,
    val completedSteps: Int = 3,
    val target: String? = null,
    val emptyTitle: String = "No previous run",
    val emptyDescription: String = "Run history will appear here."
)

object MockData {
    val defaultPipelineSteps = listOf(
        PipelineStep("capture", "Capture", "inactive"),
        PipelineStep("trace", "Trace", "inactive"),
        PipelineStep("normalize", "Normalize", "inactive"),
        PipelineStep("extract", "Extract", "inactive"),
        PipelineStep("validate", "Validate", "inactive"),
        PipelineStep("store", "Store", "inactive")
    )

    val runtimeMockSteps = listOf(
        RuntimeStep(1, "SEARCH", "Search Bar", "Searching for restaurant", "STEP 1/5: SEARCH -> 'Pizza Palace'"),
        RuntimeStep(2, "SELECT", "Restaurant Result", "Selecting restaurant", "STEP 2/5: SELECT -> Restaurant item"),
        RuntimeStep(3, "SET_VALUE", "Quantity Field", "Setting item / quantity", "STEP 3/5: SET_VALUE -> Item: Margherita, Qty: 2"),
        RuntimeStep(4, "SELECT", "Delivery Destination", "Selecting destination", "STEP 4/5: SELECT -> Address: Home"),
        RuntimeStep(5, "CONFIRM", "Place Order Button", "Confirming order", "STEP 5/5: CONFIRM -> Order Placed")
    )

    val semanticActionsDetected = listOf(
        SemanticAction(1, "SEARCH", "Search Bar", "Pizza Palace"),
        SemanticAction(2, "SELECT", "First Match Item", "Pizza Palace"),
        SemanticAction(3, "SET_VALUE", "Quantity Field", "1"),
        SemanticAction(4, "SELECT", "Add to Cart Button", null),
        SemanticAction(5, "CONFIRM", "Place Order Button", null)
    )

    val validationItems = listOf(
        ValidationItem("v1", "Trace complete", true),
        ValidationItem("v2", "Actions detected", true),
        ValidationItem("v3", "Required values identified", true),
        ValidationItem("v4", "Workflow structure valid", true)
    )

    val initialSystemLogs = listOf(
        LogEvent("12:28:41", "SYSTEM", "SYSTEM_READY", "Core services initialized in local mode", "normal"),
        LogEvent("12:28:42", "STATE", "UI_INITIALIZED", "Dashboard rendered (READY state)", "normal"),
        LogEvent("12:28:43", "WAITING", "WAITING_FOR_USER", "No active workflow pipeline", "normal")
    )

    val teachingStartLogs = listOf(
        LogEvent("12:28:45", "TEACH", "TEACHING_STARTED", "Demonstration session initialized", "active"),
        LogEvent("12:28:46", "TEACH", "CAPTURING_DEMONSTRATION", "Listening for user gesture events", "active")
    )

    val traceCapturedLogs = listOf(
        LogEvent("12:28:48", "TRACE", "TRACE_CAPTURED", "14 raw accessibility events buffered", "normal")
    )

    val normalizeStartLogs = listOf(
        LogEvent("12:28:50", "NORMALIZE", "NORMALIZATION_STARTED", "Filtering noise & canonicalizing view tree", "active")
    )

    val normalizeDoneLogs = listOf(
        LogEvent("12:28:51", "NORMALIZE", "NORMALIZATION_COMPLETE", "5 canonical step candidates identified", "success")
    )

    val extractLogs = listOf(
        LogEvent("12:28:52", "LEARN", "SEMANTIC_EXTRACTION_STARTED", "Analyzing input parameters and intent slots", "active"),
        LogEvent("12:28:53", "LEARN", "SEMANTIC_ACTIONS_DETECTED", "Bound 4 slots: restaurant, item, quantity, address", "success")
    )

    val validateLogs = listOf(
        LogEvent("12:28:54", "VALIDATE", "VALIDATION_STARTED", "Running safety and invariant rule checks", "active"),
        LogEvent("12:28:55", "VALIDATE", "VALIDATION_PASSED", "4 / 4 invariant & safety checks passed", "success")
    )

    val storeLogs = listOf(
        LogEvent("12:28:56", "SKILL", "SKILL_STORED", "Order Food (order_food) ready for runtime", "success")
    )

    val runtimeStepLogs = listOf(
        LogEvent("12:29:01", "ACTION", "STEP_1_SEARCH", "Target: Search Bar -> 'Pizza Palace'", "active"),
        LogEvent("12:29:02", "VERIFY", "STEP_1_COMPLETE", "Search results displayed", "success"),
        LogEvent("12:29:03", "ACTION", "STEP_2_SELECT", "Target: Restaurant Result item", "active"),
        LogEvent("12:29:04", "VERIFY", "STEP_2_COMPLETE", "Menu loaded", "success"),
        LogEvent("12:29:05", "ACTION", "STEP_3_SET_VALUE", "Target: Quantity Field -> Margherita (Qty: 2)", "active"),
        LogEvent("12:29:06", "VERIFY", "STEP_3_COMPLETE", "Items added to cart", "success"),
        LogEvent("12:29:07", "ACTION", "STEP_4_SELECT", "Target: Delivery Destination -> Home", "active"),
        LogEvent("12:29:08", "VERIFY", "STEP_4_COMPLETE", "Address confirmed", "success"),
        LogEvent("12:29:09", "ACTION", "STEP_5_CONFIRM", "Target: Place Order Button", "active"),
        LogEvent("12:29:10", "VERIFY", "STEP_5_COMPLETE", "Order confirmed successfully", "success"),
        LogEvent("12:29:10", "RUNTIME", "RUNTIME_COMPLETE", "5 / 5 steps executed in 12.4s (0 failures)", "success")
    )

    val handoffScenarioLogs = listOf(
        LogEvent("12:29:05", "SAFETY", "SENSITIVE_STATE_DETECTED", "Payment confirmation screen encountered", "safety"),
        LogEvent("12:29:05", "SAFETY", "USER_HANDOFF_REQUIRED", "Payment requires user control", "safety"),
        LogEvent("12:29:06", "RUNTIME", "AUTOMATION_STOPPED", "Execution suspended at Step 3 / 5", "warning")
    )

    val clarificationAmbiguousLogs = listOf(
        LogEvent("12:29:03", "ACTION", "TARGET_RESOLUTION", "Querying target: 'Restaurant'", "active"),
        LogEvent("12:29:03", "WAITING", "TARGET_NOT_UNIQUE", "2 candidates found (Pizza Palace vs Domino's)", "warning"),
        LogEvent("12:29:04", "WAITING", "CLARIFICATION_REQUIRED", "Waiting for user selection", "warning"),
        LogEvent("12:29:04", "WAITING", "WAITING_FOR_USER", "User prompt rendered in UI", "warning")
    )

    val clarificationRecoveryLogs = listOf(
        LogEvent("12:29:06", "RECOVERY", "CLARIFICATION_RECEIVED", "User selected 'Pizza Palace'", "active"),
        LogEvent("12:29:07", "RECOVERY", "TARGET_RESOLVED", "Target aligned successfully", "success"),
        LogEvent("12:29:07", "RUNTIME", "RUNTIME_RESUMED", "Continuing execution from Step 2", "success")
    )

    val unresolvedScenarioLogs = listOf(
        LogEvent("12:29:03", "ACTION", "TARGET_RESOLUTION", "Querying target: 'Restaurant'", "active"),
        LogEvent("12:29:03", "WAITING", "TARGET_NOT_UNIQUE", "Target matching ambiguity detected", "warning"),
        LogEvent("12:29:04", "SAFETY", "TARGET_RESOLUTION_FAILED", "No matching element found in view hierarchy", "error"),
        LogEvent("12:29:04", "RUNTIME", "AUTOMATION_PAUSED", "Automation paused due to unresolved target", "warning"),
        LogEvent("12:29:05", "WAITING", "USER_INPUT_REQUIRED", "Manual user input needed to continue", "warning")
    )
}
