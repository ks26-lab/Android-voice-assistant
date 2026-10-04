/**
 * Dashboard UI Mock Data (Phases 1-5: Standalone Presentation Data)
 * Complete visual lifecycle for Teaching, Learned Skill, Runtime Execution,
 * Safety Handoff, Clarification, Recovery, and Last Run Outcomes.
 */

export const UI_STATES = {
  READY: "READY",
  TEACHING: "TEACHING",
  TRACE_CAPTURED: "TRACE_CAPTURED",
  NORMALIZING: "NORMALIZING",
  NORMALIZED: "NORMALIZED",
  EXTRACTING: "EXTRACTING",
  VALIDATING: "VALIDATING",
  SKILL_STORED: "SKILL_STORED"
};

export const RUNTIME_STATES = {
  NO_WORKFLOW: "NO_WORKFLOW",
  READY: "READY",
  STARTING: "STARTING",
  RUNNING: "RUNNING",
  PAUSED: "PAUSED",
  USER_HANDOFF: "USER_HANDOFF",
  CLARIFICATION_NEEDED: "CLARIFICATION_NEEDED",
  RECOVERING: "RECOVERING",
  TARGET_NOT_RESOLVED: "TARGET_NOT_RESOLVED",
  COMPLETED: "COMPLETED"
};

export const RUNTIME_SCENARIOS = {
  SUCCESS: "SUCCESS",
  SAFETY_HANDOFF: "SAFETY_HANDOFF",
  CLARIFICATION: "CLARIFICATION",
  UNRESOLVED_TARGET: "UNRESOLVED_TARGET"
};

export const runtimeMockSteps = [
  {
    stepNumber: 1,
    action: "SEARCH",
    target: "Search Bar",
    description: "Searching for restaurant",
    log: "STEP 1/5: SEARCH -> 'Pizza Palace'"
  },
  {
    stepNumber: 2,
    action: "SELECT",
    target: "Restaurant Result",
    description: "Selecting restaurant",
    log: "STEP 2/5: SELECT -> Restaurant item"
  },
  {
    stepNumber: 3,
    action: "SET_VALUE",
    target: "Quantity Field",
    description: "Setting item / quantity",
    log: "STEP 3/5: SET_VALUE -> Item: Margherita, Qty: 2"
  },
  {
    stepNumber: 4,
    action: "SELECT",
    target: "Delivery Destination",
    description: "Selecting destination",
    log: "STEP 4/5: SELECT -> Address: Home"
  },
  {
    stepNumber: 5,
    action: "CONFIRM",
    target: "Place Order Button",
    description: "Confirming order",
    log: "STEP 5/5: CONFIRM -> Order Placed"
  }
];

export const mockStateDefinitions = {
  [UI_STATES.READY]: {
    header: {
      title: "Teachable Voice Automation",
      subtitle: "One-Shot Semantic Workflow Learning",
      status: "READY",
      statusVariant: "ready"
    },
    currentState: {
      statusBadge: "READY",
      statusVariant: "ready",
      title: "No active workflow",
      description: "Ready to teach or execute a learned workflow."
    },
    command: {
      placeholder: "Search for headphones",
      value: ""
    },
    teach: {
      state: UI_STATES.READY,
      canStart: true,
      canStop: false,
      primaryAction: { label: "Start Teaching", action: "START_TEACHING" },
      secondaryAction: { label: "Stop Teaching", disabled: true }
    },
    pipeline: [
      { id: "capture", label: "Capture", status: "inactive" },
      { id: "trace", label: "Trace", status: "inactive" },
      { id: "normalize", label: "Normalize", status: "inactive" },
      { id: "extract", label: "Extract", status: "inactive" },
      { id: "validate", label: "Validate", status: "inactive" },
      { id: "store", label: "Store", status: "inactive" }
    ],
    learnedSkill: {
      state: "EMPTY",
      hasSkill: false,
      emptyTitle: "No learned skill yet",
      emptyDescription: "Teach a workflow once to create a reusable skill."
    },
    runtime: {
      state: RUNTIME_STATES.NO_WORKFLOW,
      hasWorkflow: false,
      title: "No workflow available",
      statusMessage: "Teach a skill to enable execution",
      canExecute: false,
      canResume: false
    },
    lastRun: {
      hasHistory: false,
      emptyTitle: "No previous run",
      emptyDescription: "Run history will appear here."
    },
    voiceCommand: {
      controls: [
        { id: "recordType", label: "Record Type", active: false },
        { id: "micStop", label: "Mic / Stop", active: false }
      ]
    },
    technicalLog: [
      "SYSTEM READY",
      "UI INITIALIZED",
      "WAITING FOR USER ACTION"
    ]
  },

  [UI_STATES.TEACHING]: {
    header: {
      title: "Teachable Voice Automation",
      subtitle: "One-Shot Semantic Workflow Learning",
      status: "TEACHING",
      statusVariant: "teaching"
    },
    currentState: {
      statusBadge: "TEACHING",
      statusVariant: "teaching",
      title: "Recording demonstration",
      description: "Perform the workflow in the target app."
    },
    command: {
      placeholder: "Search for headphones",
      value: "Order food"
    },
    teach: {
      state: UI_STATES.TEACHING,
      canStart: false,
      canStop: true,
      primaryAction: { label: "Stop Teaching", action: "STOP_TEACHING", variant: "destructive" },
      secondaryAction: null
    },
    pipeline: [
      { id: "capture", label: "Capture", status: "active" },
      { id: "trace", label: "Trace", status: "active" },
      { id: "normalize", label: "Normalize", status: "inactive" },
      { id: "extract", label: "Extract", status: "inactive" },
      { id: "validate", label: "Validate", status: "inactive" },
      { id: "store", label: "Store", status: "inactive" }
    ],
    learnedSkill: {
      state: "BUILDING",
      hasSkill: false,
      buildingTitle: "Building skill...",
      buildingDescription: "Semantic structure being prepared from stream."
    },
    runtime: {
      state: RUNTIME_STATES.NO_WORKFLOW,
      hasWorkflow: false,
      title: "No workflow available",
      statusMessage: "Runtime locked during demonstration",
      canExecute: false,
      canResume: false
    },
    lastRun: {
      hasHistory: false,
      emptyTitle: "No previous run",
      emptyDescription: "Run history will appear here."
    },
    voiceCommand: {
      controls: [
        { id: "recordType", label: "Record Type", active: true },
        { id: "micStop", label: "Stop Mic", active: true }
      ]
    },
    technicalLog: [
      "SYSTEM READY",
      "TEACHING STARTED",
      "CAPTURING DEMONSTRATION"
    ]
  },

  [UI_STATES.TRACE_CAPTURED]: {
    header: {
      title: "Teachable Voice Automation",
      subtitle: "One-Shot Semantic Workflow Learning",
      status: "TRACE CAPTURED",
      statusVariant: "warning"
    },
    currentState: {
      statusBadge: "TRACE CAPTURED",
      statusVariant: "warning",
      title: "Demonstration captured",
      description: "Trace ready for normalization."
    },
    command: {
      placeholder: "Search for headphones",
      value: "Order food"
    },
    teach: {
      state: UI_STATES.TRACE_CAPTURED,
      canStart: false,
      canStop: false,
      primaryAction: { label: "Normalize Trace", action: "NORMALIZE_TRACE" },
      secondaryAction: { label: "Reset", action: "RESET" }
    },
    pipeline: [
      { id: "capture", label: "Capture", status: "done" },
      { id: "trace", label: "Trace", status: "done" },
      { id: "normalize", label: "Normalize", status: "active" },
      { id: "extract", label: "Extract", status: "inactive" },
      { id: "validate", label: "Validate", status: "inactive" },
      { id: "store", label: "Store", status: "inactive" }
    ],
    learnedSkill: {
      state: "BUILDING",
      hasSkill: false,
      buildingTitle: "Building skill...",
      buildingDescription: "Raw event trace captured (14 events)."
    },
    runtime: {
      state: RUNTIME_STATES.NO_WORKFLOW,
      hasWorkflow: false,
      title: "No workflow available",
      statusMessage: "Awaiting skill normalization",
      canExecute: false,
      canResume: false
    },
    lastRun: {
      hasHistory: false,
      emptyTitle: "No previous run",
      emptyDescription: "Run history will appear here."
    },
    voiceCommand: {
      controls: [
        { id: "recordType", label: "Record Type", active: false },
        { id: "micStop", label: "Mic / Stop", active: false }
      ]
    },
    technicalLog: [
      "TEACHING STOPPED",
      "TRACE CAPTURED",
      "TRACE READY FOR NORMALIZATION"
    ]
  },

  [UI_STATES.NORMALIZING]: {
    header: {
      title: "Teachable Voice Automation",
      subtitle: "One-Shot Semantic Workflow Learning",
      status: "NORMALIZING",
      statusVariant: "learning"
    },
    currentState: {
      statusBadge: "NORMALIZING",
      statusVariant: "learning",
      title: "Normalizing demonstration trace",
      description: "Filtering noise and canonicalizing UI interactions."
    },
    command: {
      placeholder: "Search for headphones",
      value: "Order food"
    },
    teach: {
      state: UI_STATES.NORMALIZING,
      canStart: false,
      canStop: false,
      primaryAction: { label: "Normalizing...", disabled: true },
      secondaryAction: null
    },
    pipeline: [
      { id: "capture", label: "Capture", status: "done" },
      { id: "trace", label: "Trace", status: "done" },
      { id: "normalize", label: "Normalize", status: "active" },
      { id: "extract", label: "Extract", status: "inactive" },
      { id: "validate", label: "Validate", status: "inactive" },
      { id: "store", label: "Store", status: "inactive" }
    ],
    learnedSkill: {
      state: "BUILDING",
      hasSkill: false,
      buildingTitle: "Building skill...",
      buildingDescription: "Synthesizing normalized interaction graph."
    },
    runtime: {
      state: RUNTIME_STATES.NO_WORKFLOW,
      hasWorkflow: false,
      title: "No workflow available",
      statusMessage: "Workflow under construction",
      canExecute: false,
      canResume: false
    },
    lastRun: {
      hasHistory: false,
      emptyTitle: "No previous run",
      emptyDescription: "Run history will appear here."
    },
    voiceCommand: {
      controls: [
        { id: "recordType", label: "Record Type", active: false },
        { id: "micStop", label: "Mic / Stop", active: false }
      ]
    },
    technicalLog: [
      "NORMALIZATION STARTED",
      "FILTERING JITTER & REDUNDANCIES",
      "CANONICALIZING ACTIONS"
    ]
  },

  [UI_STATES.NORMALIZED]: {
    header: {
      title: "Teachable Voice Automation",
      subtitle: "One-Shot Semantic Workflow Learning",
      status: "NORMALIZED",
      statusVariant: "warning"
    },
    currentState: {
      statusBadge: "NORMALIZED",
      statusVariant: "warning",
      title: "Trace normalized",
      description: "Ready to extract semantic actions and intent parameters."
    },
    command: {
      placeholder: "Search for headphones",
      value: "Order food"
    },
    teach: {
      state: UI_STATES.NORMALIZED,
      canStart: false,
      canStop: false,
      primaryAction: { label: "Extract Semantic Actions", action: "EXTRACT_ACTIONS" },
      secondaryAction: { label: "Reset", action: "RESET" }
    },
    pipeline: [
      { id: "capture", label: "Capture", status: "done" },
      { id: "trace", label: "Trace", status: "done" },
      { id: "normalize", label: "Normalize", status: "done" },
      { id: "extract", label: "Extract", status: "active" },
      { id: "validate", label: "Validate", status: "inactive" },
      { id: "store", label: "Store", status: "inactive" }
    ],
    learnedSkill: {
      state: "BUILDING",
      hasSkill: false,
      buildingTitle: "Building skill...",
      buildingDescription: "Trace normalized (5 candidate actions)."
    },
    runtime: {
      state: RUNTIME_STATES.NO_WORKFLOW,
      hasWorkflow: false,
      title: "No workflow available",
      statusMessage: "Awaiting action extraction",
      canExecute: false,
      canResume: false
    },
    lastRun: {
      hasHistory: false,
      emptyTitle: "No previous run",
      emptyDescription: "Run history will appear here."
    },
    voiceCommand: {
      controls: [
        { id: "recordType", label: "Record Type", active: false },
        { id: "micStop", label: "Mic / Stop", active: false }
      ]
    },
    technicalLog: [
      "NORMALIZATION COMPLETE",
      "TRACE NORMALIZED",
      "AWAITING SEMANTIC EXTRACTION"
    ]
  },

  [UI_STATES.EXTRACTING]: {
    header: {
      title: "Teachable Voice Automation",
      subtitle: "One-Shot Semantic Workflow Learning",
      status: "LEARNING",
      statusVariant: "learning"
    },
    currentState: {
      statusBadge: "LEARNING",
      statusVariant: "learning",
      title: "Extracting semantic actions",
      description: "Converting demonstrated interactions into semantic actions."
    },
    command: {
      placeholder: "Search for headphones",
      value: "Order food"
    },
    teach: {
      state: UI_STATES.EXTRACTING,
      canStart: false,
      canStop: false,
      primaryAction: { label: "Validate Learned Workflow", action: "VALIDATE_WORKFLOW" },
      secondaryAction: { label: "Reset", action: "RESET" }
    },
    pipeline: [
      { id: "capture", label: "Capture", status: "done" },
      { id: "trace", label: "Trace", status: "done" },
      { id: "normalize", label: "Normalize", status: "done" },
      { id: "extract", label: "Extract", status: "active" },
      { id: "validate", label: "Validate", status: "inactive" },
      { id: "store", label: "Store", status: "inactive" }
    ],
    semanticActionsPreview: [
      { step: 1, type: "SEARCH", target: "Search Bar", value: "$restaurant" },
      { step: 2, type: "SELECT", target: "First Match Item", value: "$restaurant" },
      { step: 3, type: "SET_VALUE", target: "Quantity Field", value: "$quantity" },
      { step: 4, type: "SELECT", target: "Add to Cart Button", value: null },
      { step: 5, type: "CONFIRM", target: "Place Order Button", value: null }
    ],
    learnedSkill: {
      state: "BUILDING",
      hasSkill: false,
      buildingTitle: "Building skill...",
      buildingDescription: "5 semantic actions detected, 4 parameter slots bound."
    },
    runtime: {
      state: RUNTIME_STATES.NO_WORKFLOW,
      hasWorkflow: false,
      title: "No workflow available",
      statusMessage: "Workflow under construction",
      canExecute: false,
      canResume: false
    },
    lastRun: {
      hasHistory: false,
      emptyTitle: "No previous run",
      emptyDescription: "Run history will appear here."
    },
    voiceCommand: {
      controls: [
        { id: "recordType", label: "Record Type", active: false },
        { id: "micStop", label: "Mic / Stop", active: false }
      ]
    },
    technicalLog: [
      "SEMANTIC EXTRACTION COMPLETE",
      "PARAMETERS IDENTIFIED: 4",
      "SEMANTIC ACTIONS DETECTED: 5"
    ]
  },

  [UI_STATES.VALIDATING]: {
    header: {
      title: "Teachable Voice Automation",
      subtitle: "One-Shot Semantic Workflow Learning",
      status: "VALIDATING",
      statusVariant: "validating"
    },
    currentState: {
      statusBadge: "VALIDATING",
      statusVariant: "validating",
      title: "Validating learned workflow",
      description: "Verifying trace completeness and semantic safety boundaries."
    },
    command: {
      placeholder: "Search for headphones",
      value: "Order food"
    },
    teach: {
      state: UI_STATES.VALIDATING,
      canStart: false,
      canStop: false,
      primaryAction: { label: "Store Learned Skill", action: "STORE_SKILL" },
      secondaryAction: { label: "Reset", action: "RESET" }
    },
    pipeline: [
      { id: "capture", label: "Capture", status: "done" },
      { id: "trace", label: "Trace", status: "done" },
      { id: "normalize", label: "Normalize", status: "done" },
      { id: "extract", label: "Extract", status: "done" },
      { id: "validate", label: "Validate", status: "active" },
      { id: "store", label: "Store", status: "inactive" }
    ],
    validationSummary: [
      { id: "v1", label: "Trace complete", passed: true },
      { id: "v2", label: "Actions detected", passed: true },
      { id: "v3", label: "Required values identified", passed: true },
      { id: "v4", label: "Workflow structure valid", passed: true }
    ],
    learnedSkill: {
      state: "VALIDATING",
      hasSkill: false,
      buildingTitle: "Validating skill...",
      buildingDescription: "Running invariant checks & safety validation."
    },
    runtime: {
      state: RUNTIME_STATES.NO_WORKFLOW,
      hasWorkflow: false,
      title: "No workflow available",
      statusMessage: "Awaiting final validation check",
      canExecute: false,
      canResume: false
    },
    lastRun: {
      hasHistory: false,
      emptyTitle: "No previous run",
      emptyDescription: "Run history will appear here."
    },
    voiceCommand: {
      controls: [
        { id: "recordType", label: "Record Type", active: false },
        { id: "micStop", label: "Mic / Stop", active: false }
      ]
    },
    technicalLog: [
      "VALIDATION STARTED",
      "CHECKS: 4/4 PASSED",
      "VALIDATION PASSED"
    ]
  },

  [UI_STATES.SKILL_STORED]: {
    header: {
      title: "Teachable Voice Automation",
      subtitle: "One-Shot Semantic Workflow Learning",
      status: "READY",
      statusVariant: "ready"
    },
    currentState: {
      statusBadge: "READY",
      statusVariant: "ready",
      title: "Learned skill ready",
      description: "One-shot workflow ready for voice-driven semantic execution."
    },
    command: {
      placeholder: "Search for headphones",
      value: "Order 2 pizzas to 123 Main St"
    },
    teach: {
      state: UI_STATES.SKILL_STORED,
      canStart: true,
      canStop: false,
      primaryAction: { label: "Teach Another Workflow", action: "START_TEACHING" },
      secondaryAction: { label: "Reset All", action: "RESET" }
    },
    pipeline: [
      { id: "capture", label: "Capture", status: "done" },
      { id: "trace", label: "Trace", status: "done" },
      { id: "normalize", label: "Normalize", status: "done" },
      { id: "extract", label: "Extract", status: "done" },
      { id: "validate", label: "Validate", status: "done" },
      { id: "store", label: "Store", status: "done" }
    ],
    learnedSkill: {
      state: "STORED",
      hasSkill: true,
      name: "Order Food",
      technicalId: "skill.order_food.v1",
      status: "VALIDATED",
      intent: "order_food",
      slots: [
        { name: "restaurant", example: "Pizza Palace" },
        { name: "item", example: "Margherita Pizza" },
        { name: "quantity", example: "1" },
        { name: "address", example: "Home" }
      ],
      semanticActionCount: 5,
      semanticActions: [
        { step: 1, type: "SEARCH", target: "Search Bar", value: "$restaurant" },
        { step: 2, type: "SELECT", target: "First Match Item", value: "$restaurant" },
        { step: 3, type: "SET_VALUE", target: "Quantity Field", value: "$quantity" },
        { step: 4, type: "SELECT", target: "Add to Cart Button", value: null },
        { step: 5, type: "CONFIRM", target: "Place Order Button", value: null }
      ],
      actionsLabel: "5 semantic actions"
    },
    runtime: {
      state: RUNTIME_STATES.READY,
      hasWorkflow: true,
      skillName: "Order Food",
      title: "Order Food",
      statusMessage: "Ready to execute",
      canExecute: true,
      canResume: false
    },
    lastRun: {
      hasHistory: false,
      emptyTitle: "No previous run",
      emptyDescription: "Run history will appear here."
    },
    voiceCommand: {
      controls: [
        { id: "recordType", label: "Record Type", active: false },
        { id: "micStop", label: "Mic / Stop", active: false }
      ]
    },
    technicalLog: [
      "VALIDATION PASSED",
      "SKILL STORED: order_food",
      "SKILL READY FOR RUNTIME"
    ]
  }
};
