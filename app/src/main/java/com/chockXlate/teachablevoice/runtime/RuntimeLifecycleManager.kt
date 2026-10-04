package com.chockXlate.teachablevoice.runtime

import com.chockXlate.teachablevoice.skill.repository.SkillRepositoryProvider
import java.io.File

/**
 * Explicit runtime lifecycle states for Phase 1 (P1.7).
 */
enum class RuntimeLifecycleState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    FAILED
}

/**
 * Manages the explicit lifecycle and automatic startup readiness of the Teachable Voice Runtime (P1.6, P1.7).
 * Attached to Application / Service startup, ensuring persistent skills become available and
 * runtime enters READY state without manual hidden triggers.
 */
object RuntimeLifecycleManager {

    @Volatile
    var state: RuntimeLifecycleState = RuntimeLifecycleState.UNINITIALIZED
        private set

    @Volatile
    var lastError: String? = null
        private set

    val isReady: Boolean
        get() = state == RuntimeLifecycleState.READY

    /**
     * Initializes runtime dependencies and persistent skill store.
     * Transitions state: UNINITIALIZED -> INITIALIZING -> READY (or FAILED).
     */
    @Synchronized
    fun initialize(storageDir: File? = null) {
        state = RuntimeLifecycleState.INITIALIZING
        try {
            // Step 1: Initialize Persistent Skill Store
            SkillRepositoryProvider.initialize(storageDir)

            // Step 2: Validate persistent repository accessibility
            val repo = SkillRepositoryProvider.getRepository()
            repo.getAllWorkflows()

            // Step 3: Transition to READY
            state = RuntimeLifecycleState.READY
            lastError = null
        } catch (e: Exception) {
            state = RuntimeLifecycleState.FAILED
            lastError = e.message ?: "Failed to initialize runtime dependencies"
        }
    }

    /**
     * Resets runtime lifecycle (used in tests or service restarts).
     */
    @Synchronized
    fun reset() {
        state = RuntimeLifecycleState.UNINITIALIZED
        lastError = null
        SkillRepositoryProvider.reset()
    }
}
