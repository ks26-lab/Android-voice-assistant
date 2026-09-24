package com.chockXlate.teachablevoice.app

import com.chockXlate.teachablevoice.runtime.ExecutionEngine
import com.chockXlate.teachablevoice.runtime.ui.AccessibilityUiDriver
import com.chockXlate.teachablevoice.skill.repository.SkillRepositoryProvider
import java.util.concurrent.atomic.AtomicBoolean

/** Process-scoped: Activity recreation must not reset the safety latch or last report. */
internal object RuntimeSession {
    val executing = AtomicBoolean(false)
    val pendingRequest = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val engine by lazy { ExecutionEngine(SkillRepositoryProvider.getRepository(), AccessibilityUiDriver()) }
}
