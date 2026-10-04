package com.chockXlate.teachablevoice.ui.mock

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

enum class StepStatus {
    INACTIVE,
    ACTIVE,
    DONE
}

data class PipelineStepData(
    val id: String,
    val label: String,
    val status: StepStatus
)

data class SemanticActionData(
    val step: Int,
    val type: String,
    val target: String,
    val value: String? = null
)

data class ValidationItemData(
    val label: String,
    val passed: Boolean = true
)

data class SlotData(
    val name: String,
    val example: String? = null
)

data class LearnedSkillData(
    val state: String = "EMPTY", // EMPTY, BUILDING, VALIDATED
    val hasSkill: Boolean = false,
    val name: String = "Learned Skill",
    val technicalId: String = "skill_1",
    val intent: String = "search_information",
    val status: String = "VALIDATED",
    val slots: List<SlotData> = emptyList(),
    val semanticActionCount: Int = 0,
    val actionsLabel: String = "0 semantic actions",
    val emptyTitle: String = "No learned skill yet",
    val emptyDescription: String = "Teach a workflow once to create a reusable skill.",
    val buildingTitle: String = "Building skill...",
    val buildingDescription: String = "Semantic structure being prepared from stream.",
    val appContext: String? = null,
    val inspectionFormattedText: String? = null,
    val safetyBoundaryText: String? = null
)

data class RuntimeStepData(
    val stepNumber: Int,
    val action: String,
    val target: String,
    val description: String,
    val log: String
)

data class LastRunData(
    val hasHistory: Boolean = false,
    val outcome: String = "SUCCESS", // SUCCESS, HANDOFF, TARGET_NOT_RESOLVED
    val status: String = "SUCCESS",
    val skillName: String = "Order Food",
    val stepsCount: Int = 5,
    val completedSteps: Int = 3,
    val totalSteps: Int = 5,
    val failuresCount: Int = 0,
    val duration: String = "12.4 s",
    val stoppedAt: String = "Payment",
    val target: String = "Restaurant",
    val reason: String = "User control required",
    val emptyTitle: String = "No previous run",
    val emptyDescription: String = "Run history will appear here."
)

val defaultRuntimeSteps = listOf(
    RuntimeStepData(
        stepNumber = 1,
        action = "SEARCH",
        target = "Search Bar",
        description = "Searching for restaurant",
        log = "STEP 1/5: SEARCH -> 'Pizza Palace'"
    ),
    RuntimeStepData(
        stepNumber = 2,
        action = "SELECT",
        target = "Restaurant Result",
        description = "Selecting restaurant",
        log = "STEP 2/5: SELECT -> Restaurant item"
    ),
    RuntimeStepData(
        stepNumber = 3,
        action = "SET_VALUE",
        target = "Quantity Field",
        description = "Setting item / quantity",
        log = "STEP 3/5: SET_VALUE -> Item: Margherita, Qty: 2"
    ),
    RuntimeStepData(
        stepNumber = 4,
        action = "SELECT",
        target = "Delivery Destination",
        description = "Selecting destination",
        log = "STEP 4/5: SELECT -> Address: Home"
    ),
    RuntimeStepData(
        stepNumber = 5,
        action = "CONFIRM",
        target = "Place Order Button",
        description = "Confirming order",
        log = "STEP 5/5: CONFIRM -> Order Placed"
    )
)

val defaultSemanticActions = listOf(
    SemanticActionData(1, "SEARCH", "restaurant_search_input", "\"pizza\""),
    SemanticActionData(2, "SELECT", "first_restaurant_card"),
    SemanticActionData(3, "SET_VALUE", "item_quantity_stepper", "\"2\""),
    SemanticActionData(4, "SELECT", "delivery_address_home"),
    SemanticActionData(5, "CONFIRM", "place_order_button")
)

val defaultValidationItems = listOf(
    ValidationItemData("Deterministic control flow verified"),
    ValidationItemData("Slot parameter bindings resolved (4 slots)"),
    ValidationItemData("Target element selectors uniquely mapped"),
    ValidationItemData("Post-condition check verified (Order Complete)")
)

val populatedLearnedSkill = LearnedSkillData(
    state = "VALIDATED",
    hasSkill = true,
    name = "Order Food",
    technicalId = "skill_order_food_v1",
    intent = "order_food",
    status = "VALIDATED",
    slots = listOf(
        SlotData("restaurant", "Pizza Palace"),
        SlotData("item", "Margherita"),
        SlotData("quantity", "2"),
        SlotData("address", "Home")
    ),
    semanticActionCount = 5,
    actionsLabel = "5 semantic actions"
)
