/**
 * Technical Log Mock Events & Scenarios (Phase 6 Standalone Presentation Data)
 * Categories: STATE, TEACH, TRACE, NORMALIZE, LEARN, VALIDATE, SKILL, RUNTIME, ACTION, VERIFY, WAITING, SAFETY, RECOVERY, SYSTEM
 * Types: 'normal' | 'active' | 'success' | 'warning' | 'safety' | 'error'
 */

export const initialSystemLogs = [
  {
    time: "12:28:41",
    category: "SYSTEM",
    event: "SYSTEM_READY",
    detail: "Core services initialized in local mode",
    type: "normal"
  },
  {
    time: "12:28:42",
    category: "STATE",
    event: "UI_INITIALIZED",
    detail: "Dashboard rendered (READY state)",
    type: "normal"
  },
  {
    time: "12:28:43",
    category: "WAITING",
    event: "WAITING_FOR_USER",
    detail: "No active workflow pipeline",
    type: "normal"
  }
];

// Scenario A: Teaching Lifecycle
export const teachingLogSequence = {
  START: [
    {
      time: "12:28:45",
      category: "TEACH",
      event: "TEACHING_STARTED",
      detail: "Demonstration session initialized",
      type: "active"
    },
    {
      time: "12:28:46",
      category: "TEACH",
      event: "CAPTURING_DEMONSTRATION",
      detail: "Listening for user gesture events",
      type: "active"
    }
  ],
  CAPTURE: [
    {
      time: "12:28:48",
      category: "TRACE",
      event: "TRACE_CAPTURED",
      detail: "14 raw accessibility events buffered",
      type: "normal"
    }
  ],
  NORMALIZE_START: [
    {
      time: "12:28:50",
      category: "NORMALIZE",
      event: "NORMALIZATION_STARTED",
      detail: "Filtering noise & canonicalizing view tree",
      type: "active"
    }
  ],
  NORMALIZE_DONE: [
    {
      time: "12:28:51",
      category: "NORMALIZE",
      event: "NORMALIZATION_COMPLETE",
      detail: "5 canonical step candidates identified",
      type: "success"
    }
  ],
  EXTRACT: [
    {
      time: "12:28:52",
      category: "LEARN",
      event: "SEMANTIC_EXTRACTION_STARTED",
      detail: "Analyzing input parameters and intent slots",
      type: "active"
    },
    {
      time: "12:28:53",
      category: "LEARN",
      event: "SEMANTIC_ACTIONS_DETECTED",
      detail: "Bound 4 slots: restaurant, item, quantity, address",
      type: "success"
    }
  ],
  VALIDATE: [
    {
      time: "12:28:54",
      category: "VALIDATE",
      event: "VALIDATION_STARTED",
      detail: "Running safety and invariant rule checks",
      type: "active"
    },
    {
      time: "12:28:55",
      category: "VALIDATE",
      event: "VALIDATION_PASSED",
      detail: "4 / 4 invariant & safety checks passed",
      type: "success"
    }
  ],
  STORE: [
    {
      time: "12:28:56",
      category: "SKILL",
      event: "SKILL_STORED",
      detail: "Order Food (order_food) ready for runtime",
      type: "success"
    }
  ]
};

// Scenario B: Successful Runtime Execution
export const runtimeStepLogs = [
  {
    time: "12:29:01",
    category: "ACTION",
    event: "STEP_1_SEARCH",
    detail: "Target: Search Bar -> 'Pizza Palace'",
    type: "active"
  },
  {
    time: "12:29:02",
    category: "VERIFY",
    event: "STEP_1_COMPLETE",
    detail: "Search results displayed",
    type: "success"
  },
  {
    time: "12:29:03",
    category: "ACTION",
    event: "STEP_2_SELECT",
    detail: "Target: Restaurant Result item",
    type: "active"
  },
  {
    time: "12:29:04",
    category: "VERIFY",
    event: "STEP_2_COMPLETE",
    detail: "Menu loaded",
    type: "success"
  },
  {
    time: "12:29:05",
    category: "ACTION",
    event: "STEP_3_SET_VALUE",
    detail: "Target: Quantity Field -> Margherita (Qty: 2)",
    type: "active"
  },
  {
    time: "12:29:06",
    category: "VERIFY",
    event: "STEP_3_COMPLETE",
    detail: "Items added to cart",
    type: "success"
  },
  {
    time: "12:29:07",
    category: "ACTION",
    event: "STEP_4_SELECT",
    detail: "Target: Delivery Destination -> Home",
    type: "active"
  },
  {
    time: "12:29:08",
    category: "VERIFY",
    event: "STEP_4_COMPLETE",
    detail: "Address confirmed",
    type: "success"
  },
  {
    time: "12:29:09",
    category: "ACTION",
    event: "STEP_5_CONFIRM",
    detail: "Target: Place Order Button",
    type: "active"
  },
  {
    time: "12:29:10",
    category: "VERIFY",
    event: "STEP_5_COMPLETE",
    detail: "Order confirmed successfully",
    type: "success"
  },
  {
    time: "12:29:10",
    category: "RUNTIME",
    event: "RUNTIME_COMPLETE",
    detail: "5 / 5 steps executed in 12.4s (0 failures)",
    type: "success"
  }
];

// Scenario C: User Handoff
export const handoffScenarioLogs = [
  {
    time: "12:29:05",
    category: "SAFETY",
    event: "SENSITIVE_STATE_DETECTED",
    detail: "Payment confirmation screen encountered",
    type: "safety"
  },
  {
    time: "12:29:05",
    category: "SAFETY",
    event: "USER_HANDOFF_REQUIRED",
    detail: "Payment requires user control",
    type: "safety"
  },
  {
    time: "12:29:06",
    category: "RUNTIME",
    event: "AUTOMATION_STOPPED",
    detail: "Execution suspended at Step 3 / 5",
    type: "warning"
  }
];

// Scenario D: Clarification & Recovery
export const clarificationScenarioLogs = {
  AMBIGUOUS: [
    {
      time: "12:29:03",
      category: "ACTION",
      event: "TARGET_RESOLUTION",
      detail: "Querying target: 'Restaurant'",
      type: "active"
    },
    {
      time: "12:29:03",
      category: "WAITING",
      event: "TARGET_NOT_UNIQUE",
      detail: "2 candidates found (Pizza Palace vs Domino's)",
      type: "warning"
    },
    {
      time: "12:29:04",
      category: "WAITING",
      event: "CLARIFICATION_REQUIRED",
      detail: "Waiting for user selection",
      type: "warning"
    },
    {
      time: "12:29:04",
      category: "WAITING",
      event: "WAITING_FOR_USER",
      detail: "User prompt rendered in UI",
      type: "warning"
    }
  ],
  RECOVERY: [
    {
      time: "12:29:06",
      category: "RECOVERY",
      event: "CLARIFICATION_RECEIVED",
      detail: "User selected 'Pizza Palace'",
      type: "active"
    },
    {
      time: "12:29:07",
      category: "RECOVERY",
      event: "TARGET_RESOLVED",
      detail: "Target aligned successfully",
      type: "success"
    },
    {
      time: "12:29:07",
      category: "RUNTIME",
      event: "RUNTIME_RESUMED",
      detail: "Continuing execution from Step 2",
      type: "success"
    }
  ]
};

// Scenario E: Target Unresolved
export const unresolvedScenarioLogs = [
  {
    time: "12:29:03",
    category: "ACTION",
    event: "TARGET_RESOLUTION",
    detail: "Querying target: 'Restaurant'",
    type: "active"
  },
  {
    time: "12:29:03",
    category: "WAITING",
    event: "TARGET_NOT_UNIQUE",
    detail: "Target matching ambiguity detected",
    type: "warning"
  },
  {
    time: "12:29:04",
    category: "SAFETY",
    event: "TARGET_RESOLUTION_FAILED",
    detail: "No matching element found in view hierarchy",
    type: "error"
  },
  {
    time: "12:29:04",
    category: "RUNTIME",
    event: "AUTOMATION_PAUSED",
    detail: "Automation paused due to unresolved target",
    type: "warning"
  },
  {
    time: "12:29:05",
    category: "WAITING",
    event: "USER_INPUT_REQUIRED",
    detail: "Manual user input needed to continue",
    type: "warning"
  }
];
