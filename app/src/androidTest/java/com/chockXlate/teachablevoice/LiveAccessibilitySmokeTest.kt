package com.chockXlate.teachablevoice

import android.content.Intent
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chockXlate.teachablevoice.app.TeachingDemoActivity
import com.chockXlate.teachablevoice.app.service.TeachableVoiceAccessibilityService
import com.chockXlate.teachablevoice.contract.runtime.*
import com.chockXlate.teachablevoice.contract.workflow.*
import com.chockXlate.teachablevoice.runtime.ExecutionEngine
import com.chockXlate.teachablevoice.runtime.trace.RuntimeReport
import com.chockXlate.teachablevoice.runtime.ui.AccessibilityUiDriver
import com.chockXlate.teachablevoice.skill.repository.SkillRepositoryProvider
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.*

/** Test-only generic controls. Every tested action goes through the unmodified production driver. */
@RunWith(AndroidJUnit4::class)
class LiveAccessibilitySmokeTest {
    private fun run(block: suspend () -> RuntimeReport): RuntimeReport {
        var completed: Result<RuntimeReport>? = null
        val latch = CountDownLatch(1)
        block.startCoroutine(object : Continuation<RuntimeReport> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<RuntimeReport>) { completed = result; latch.countDown() }
        })
        assertTrue("Runtime did not finish", latch.await(30, TimeUnit.SECONDS))
        return completed!!.getOrThrow()
    }

    @Test fun liveThreeStepTextClickAndTransitionVerification() = exercise(protected = false)
    @Test fun liveProtectedFieldHandoffAndStickyLock() = exercise(protected = true)

    private fun exercise(protected: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Instrumentation normally suppresses user-enabled services; preserve them for this test.
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, TeachingDemoActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TeachingDemoActivity
        try {
            for (attempt in 0 until 50) {
                if (TeachableVoiceAccessibilityService.instance?.isRuntimeReady == true) break
                android.os.SystemClock.sleep(100)
            }
            assumeTrue("Manually enable the Accessibility service before running this test",
                TeachableVoiceAccessibilityService.instance?.isRuntimeReady == true)
            lateinit var input: EditText
            lateinit var output: TextView
            var clicks = 0
            instrumentation.runOnMainSync {
                input = EditText(activity).apply {
                    contentDescription = "Practice value"
                    inputType = if (protected) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
                }
                output = TextView(activity).apply { text = "Ready" }
                val button = Button(activity).apply {
                    text = "Apply value"
                    setOnClickListener { clicks++; output.text = "Applied" }
                }
                activity.setContentView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(input); addView(button); addView(output)
                })
            }
            instrumentation.waitForIdleSync()
            val id = "instrumentation_${UUID.randomUUID()}"
            val field = SemanticSelector(role = "EditText", contentDescription = "Practice value", textSlot = "value")
            val workflow = Workflow(skillId = id, name = "Generic live test", intent = "generic",
                appContext = context.packageName,
                slots = listOf(WorkflowSlot(name = "value", type = SlotType.TEXT, required = true)),
                steps = listOf(
                    WorkflowStep(stepId = "enter", semanticAction = "INPUT_TEXT", semanticSelector = field),
                    WorkflowStep(stepId = "apply", semanticAction = "CLICK", semanticSelector = SemanticSelector(text = "Apply value"),
                        expectedTransition = ExpectedTransition(expectedElementAppeared = SemanticSelector(text = "Applied"))),
                    WorkflowStep(stepId = "replace", semanticAction = "INPUT_TEXT", semanticSelector = field,
                        parameters = mapOf("input_literal" to "Updated"))
                ))
            // Third input is explicitly constant, independent of the first bound variable.
            val stored = workflow.copy(steps = workflow.steps.map { if (it.stepId == "replace")
                it.copy(semanticSelector = field.copy(textSlot = null)) else it })
            val repository = SkillRepositoryProvider.getRepository()
            assertTrue(repository.saveWorkflow(stored))
            try {
                val engine = ExecutionEngine(repository, AccessibilityUiDriver())
                val request = ExecutionRequest(executionId = UUID.randomUUID().toString(), skillId = id,
                    boundSlots = mapOf("value" to "Changed"))
                val report = run { engine.execute(request) }
                if (protected) {
                    assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, report.result.finalState)
                    assertEquals(0, report.result.stepsCompleted)
                    instrumentation.runOnMainSync { input.inputType = InputType.TYPE_CLASS_TEXT }
                    val next = run { engine.execute(request.copy(executionId = UUID.randomUUID().toString())) }
                    assertEquals(ExecutionState.PAUSED_FOR_HANDOFF, next.result.finalState)
                    instrumentation.runOnMainSync { assertEquals("", input.text.toString()); assertEquals(0, clicks) }
                } else {
                    assertTrue(report.result.errorMessage, report.result.success)
                    assertEquals(3, report.result.stepsCompleted)
                    instrumentation.runOnMainSync { assertEquals("Updated", input.text.toString()); assertEquals(1, clicks); assertEquals("Applied", output.text) }
                }
            } finally { repository.deleteWorkflow(id) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
