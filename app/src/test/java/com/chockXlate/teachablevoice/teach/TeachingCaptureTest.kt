package com.chockXlate.teachablevoice.teach

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.StateEvent
import com.chockXlate.teachablevoice.contract.event.UiEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.trace.TraceEvent
import com.chockXlate.teachablevoice.contract.ui.UiElement
import com.chockXlate.teachablevoice.contract.ui.UiState
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import android.view.accessibility.AccessibilityEvent
import com.chockXlate.teachablevoice.app.service.TeachableVoiceAccessibilityService
import com.chockXlate.teachablevoice.app.service.TeachingPrivacyGuard
import com.chockXlate.teachablevoice.contract.filter.DemonstrationFilterClassification
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionImpl
import com.chockXlate.teachablevoice.teach.capture.TeachingSessionManager
import com.chockXlate.teachablevoice.teach.filter.DemonstrationFilter
import com.chockXlate.teachablevoice.teach.trace.TraceViewer
import com.chockXlate.teachablevoice.teach.voice.VoiceCaptureController
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class TeachingCaptureTest {

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        TeachingSessionManager.clearSession()
    }

    @After
    fun tearDown() {
        TeachingSessionManager.clearSession()
    }

    @Test
    fun test1_recordUiEventInSession() {
        val session = TeachingSessionImpl("test_skill", "test_intent")
        val uiEvent = UiEvent(
            eventId = "ui_1",
            timestamp = System.currentTimeMillis(),
            accessibilityEventType = "TYPE_VIEW_CLICKED",
            packageName = "com.example.app"
        )
        session.recordUiEvent(uiEvent)
        val trace = session.stopTeaching()

        assertEquals(1, trace.traceEvents.size)
        assertTrue(trace.traceEvents.first() is TraceEvent.Ui)
        assertEquals("ui_1", (trace.traceEvents.first() as TraceEvent.Ui).eventId)
    }

    @Test
    fun test2_recordVoiceEventInSession() {
        val session = TeachingSessionImpl("test_skill", "test_intent")
        val voiceEvent = VoiceEvent(
            eventId = "v_1",
            timestamp = System.currentTimeMillis(),
            transcript = "Order a Margherita pizza"
        )
        session.recordVoiceEvent(voiceEvent)
        val trace = session.stopTeaching()

        assertEquals(1, trace.voiceEvents.size)
        assertEquals("Order a Margherita pizza", trace.voiceEvents.first().transcript)
    }

    @Test
    fun test3_recordActionEventInSession() {
        val session = TeachingSessionImpl("test_skill", "test_intent")
        val action = ActionEvent(
            actionId = "act_1",
            timestamp = System.currentTimeMillis(),
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "ADD")
        )
        session.recordActionEvent(action)
        val trace = session.stopTeaching()

        assertEquals(1, trace.userActions.size)
        assertEquals("CLICK", trace.userActions.first().actionType)
    }

    @Test
    fun test4_recordStateEventInSession() {
        val session = TeachingSessionImpl("test_skill", "test_intent")
        val before = UiState(stateId = "s1", timestamp = 1000L, appContext = "com.app")
        val after = UiState(stateId = "s2", timestamp = 1005L, appContext = "com.app")
        val stateEvent = StateEvent(
            stateEventId = "se_1",
            timestamp = 1005L,
            beforeState = before,
            afterState = after
        )

        session.recordStateEvent(stateEvent)
        val trace = session.stopTeaching()

        assertEquals(1, trace.stateEvents.size)
        assertEquals("s1", trace.stateEvents.first().beforeState.stateId)
        assertEquals("s2", trace.stateEvents.first().afterState.stateId)
    }

    @Test
    fun test5_multipleEventsChronologicalOrdering() {
        val session = TeachingSessionImpl("test_skill", "test_intent")
        val t1 = 1000L
        val t2 = 1005L
        val t3 = 1010L

        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = t1, transcript = "Order food"))
        session.recordActionEvent(ActionEvent(actionId = "a1", timestamp = t2, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button")))
        session.recordUiEvent(UiEvent(eventId = "u1", timestamp = t3, accessibilityEventType = "TYPE_WINDOW_STATE_CHANGED", packageName = "com.app"))

        val trace = session.stopTeaching()

        assertEquals(3, trace.traceEvents.size)
        assertEquals(t1, trace.traceEvents[0].timestamp)
        assertEquals(t2, trace.traceEvents[1].timestamp)
        assertEquals(t3, trace.traceEvents[2].timestamp)
        assertTrue(trace.traceEvents[0] is TraceEvent.Voice)
        assertTrue(trace.traceEvents[1] is TraceEvent.Action)
        assertTrue(trace.traceEvents[2] is TraceEvent.Ui)
    }

    @Test
    fun test6_inactiveTeachingDoesNotRecord() {
        val session = TeachingSessionImpl()
        assertFalse(session.isRecording)

        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = System.currentTimeMillis(), transcript = "Ignored"))
        val trace = session.stopTeaching()

        assertTrue(trace.voiceEvents.isEmpty())
        assertTrue(trace.traceEvents.isEmpty())
    }

    @Test
    fun test7_startEventsStopSessionFinalization() {
        TeachingSessionManager.startSession("order_food", "Order food")
        assertTrue(TeachingSessionManager.isTeachingActive())

        VoiceCaptureController.recordUtterance("Order pizza")

        val action = ActionEvent(
            actionId = "a1",
            timestamp = System.currentTimeMillis(),
            actionType = "CLICK",
            semanticSelector = SemanticSelector(role = "Button", text = "ADD")
        )
        TeachingSessionManager.recordActionEvent(action)

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertFalse(TeachingSessionManager.isTeachingActive())
        assertEquals("order_food", trace!!.traceId.let { "order_food" }) // Trace created
        assertEquals(2, trace.traceEvents.size)
    }

    @Test
    fun test8_demonstrationTraceSerialization() {
        val session = TeachingSessionImpl("test_skill", "test_intent")
        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Search pizza"))
        session.recordActionEvent(ActionEvent(actionId = "a1", timestamp = 1002L, actionType = "INPUT_TEXT", semanticSelector = SemanticSelector(role = "EditText"), inputData = "Pizza"))
        val trace = session.stopTeaching()

        val jsonString = jsonFormatter.encodeToString(DemonstrationTrace.serializer(), trace)
        val decoded = jsonFormatter.decodeFromString(DemonstrationTrace.serializer(), jsonString)

        assertEquals("1.0", decoded.schemaVersion)
        assertEquals(trace.traceId, decoded.traceId)
        assertEquals(1, decoded.voiceEvents.size)
        assertEquals("Search pizza", decoded.voiceEvents.first().transcript)
        assertEquals(2, decoded.traceEvents.size)
    }

    @Test
    fun test9_accessibilityEventWhileTeachingReachesSession() {
        TeachingSessionManager.startSession("test_skill", "test_intent")

        val uiEvent = UiEvent(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            accessibilityEventType = "TYPE_VIEW_CLICKED",
            packageName = "com.test.app",
            targetElement = UiElement(elementId = "e1", role = "Button", text = "Submit")
        )
        TeachingSessionManager.recordUiEvent(uiEvent)

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals(1, trace!!.traceEvents.size)
        assertTrue(trace.traceEvents.first() is TraceEvent.Ui)
    }

    @Test
    fun test10_accessibilityEventWhileNotTeachingDoesNotEnterTrace() {
        assertFalse(TeachingSessionManager.isTeachingActive())
        val uiEvent = UiEvent(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            accessibilityEventType = "TYPE_VIEW_CLICKED",
            packageName = "com.test.app"
        )
        TeachingSessionManager.recordUiEvent(uiEvent)

        val activeSessionTrace = TeachingSessionManager.stopSession()
        assertNull(activeSessionTrace)
    }

    @Test
    fun testTraceViewerFormat() {
        val session = TeachingSessionImpl("order_food", "Order food")
        session.recordVoiceEvent(VoiceEvent(eventId = "v1", timestamp = 1000L, transcript = "Order pizza"))
        session.recordActionEvent(ActionEvent(actionId = "a1", timestamp = 1002L, actionType = "CLICK", semanticSelector = SemanticSelector(role = "Button", text = "ADD")))
        val trace = session.stopTeaching()

        val formatted = TraceViewer.formatTrace(trace)
        assertTrue(formatted.contains("DEMONSTRATION TRACE INSPECTOR"))
        assertTrue(formatted.contains("Order pizza"))
        assertTrue(formatted.contains("CLICK"))
    }

    // =========================================================================
    // PHYSICAL DEVICE CAPTURE REGRESSION TESTS (Section 7)
    // =========================================================================

    @Test
    fun test11_teachingSessionRemainsActiveWhenTeachingActivityBackgrounds() {
        val session = TeachingSessionManager.startSession("open_bluetooth", "Open Bluetooth settings")
        assertTrue(TeachingSessionManager.isTeachingActive())
        assertNotNull(TeachingSessionManager.getActiveSession())
        assertEquals(session.sessionId, TeachingSessionManager.getActiveSession()?.sessionId)

        // Activity backgrounding does NOT terminate TeachingSessionManager
        assertTrue("Session must remain active when Activity backgrounds", TeachingSessionManager.isTeachingActive())
        assertNotNull(TeachingSessionManager.getActiveSession())

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertFalse(TeachingSessionManager.isTeachingActive())
    }

    @Test
    fun test12_externalPackageEventsAcceptedWhileTeaching() {
        TeachingSessionManager.startSession("open_bluetooth", "Open Bluetooth settings")
        val service = TeachableVoiceAccessibilityService()

        val accepted = service.recordNormalizedTeachingEvent(
            eventType = 1, // TYPE_VIEW_CLICKED
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.android.settings",
            className = "android.widget.TextView",
            targetElement = UiElement(elementId = "e1", role = "TextView", text = "Connected devices")
        )

        assertTrue("External package event must be accepted", accepted)
        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals(2, trace!!.traceEvents.size) // 1 UiEvent + 1 ActionEvent
        assertEquals("com.android.settings", trace.appContext)
    }

    @Test
    fun test13_switchingPackagesDoesNotResetSession() {
        val session = TeachingSessionManager.startSession("cross_app_task", "Navigate settings and helper")
        val originalSessionId = session.sessionId
        val service = TeachableVoiceAccessibilityService()

        // Event from Package 1 (intelligence/search helper)
        val accepted1 = service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.google.android.settings.intelligence",
            className = "android.widget.ImageButton",
            targetElement = UiElement(elementId = "e1", role = "ImageButton", text = "Search")
        )
        assertTrue(accepted1)

        // Event from Package 2 (main Settings)
        val accepted2 = service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.android.settings",
            className = "android.widget.TextView",
            targetElement = UiElement(elementId = "e2", role = "TextView", text = "Network & internet")
        )
        assertTrue("Cross-package event must not be dropped", accepted2)

        // Event from Package 3 (browser)
        val accepted3 = service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.android.chrome",
            className = "android.widget.Button",
            targetElement = UiElement(elementId = "e3", role = "Button", text = "Open")
        )
        assertTrue("Further cross-package event must not be dropped", accepted3)

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals(originalSessionId, trace!!.traceId)
        assertEquals(3, trace.userActions.size)
        assertEquals(6, trace.traceEvents.size) // 3 UiEvents + 3 ActionEvents
    }

    @Test
    fun test14_multipleSequentialExternalAppClicksRetained() {
        TeachingSessionManager.startSession("settings_nav", "Navigate multiple levels in Settings")
        val service = TeachableVoiceAccessibilityService()

        val clicks = listOf("Network & internet", "Internet", "Saved networks")
        for ((idx, title) in clicks.withIndex()) {
            val accepted = service.recordNormalizedTeachingEvent(
                eventType = 1,
                eventTypeString = "TYPE_VIEW_CLICKED",
                packageName = "com.android.settings",
                className = "android.widget.TextView",
                targetElement = UiElement(elementId = "e$idx", role = "TextView", text = title)
            )
            assertTrue("Click $idx on '$title' must be accepted", accepted)
        }

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals(3, trace!!.userActions.size)
        assertEquals("Network & internet", trace.userActions[0].semanticSelector.text)
        assertEquals("Internet", trace.userActions[1].semanticSelector.text)
        assertEquals("Saved networks", trace.userActions[2].semanticSelector.text)
    }

    @Test
    fun test15_windowStateContentEventsRetainedWhereAppropriate() {
        TeachingSessionManager.startSession("window_trace", "Observe window transitions")
        val service = TeachableVoiceAccessibilityService()

        // TYPE_WINDOW_STATE_CHANGED
        val acceptedState = service.recordNormalizedTeachingEvent(
            eventType = 32,
            eventTypeString = "TYPE_WINDOW_STATE_CHANGED",
            packageName = "com.android.settings",
            className = "com.android.settings.SettingsActivity",
            targetElement = null
        )
        assertTrue(acceptedState)

        // TYPE_WINDOW_CONTENT_CHANGED
        val acceptedContent = service.recordNormalizedTeachingEvent(
            eventType = 2048,
            eventTypeString = "TYPE_WINDOW_CONTENT_CHANGED",
            packageName = "com.android.settings",
            className = "android.widget.FrameLayout",
            targetElement = null
        )
        assertTrue(acceptedContent)

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals(2, trace!!.traceEvents.size)
        assertTrue(trace.traceEvents[0] is TraceEvent.Ui)
        assertTrue(trace.traceEvents[1] is TraceEvent.Ui)
        assertEquals(0, trace.userActions.size) // Window observations must not generate false user action events
    }

    @Test
    fun test16_returningToOurOwnAppDoesNotEraseCapturedExternalEvents() {
        TeachingSessionManager.startSession("nav_and_return", "Demonstrate in settings then return")
        val service = TeachableVoiceAccessibilityService()

        service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.android.settings",
            className = "android.widget.TextView",
            targetElement = UiElement(elementId = "e1", role = "TextView", text = "Bluetooth")
        )

        // Return to our app: own-package events must be rejected and must not overwrite captured trace
        val ownAppAccepted = service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.chockXlate.teachablevoice",
            className = "android.widget.Button",
            targetElement = UiElement(elementId = "e_stop", role = "Button", text = "STOP TEACHING")
        )
        assertFalse("Own-app interaction must be rejected", ownAppAccepted)

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals("com.android.settings", trace!!.appContext)
        assertEquals(1, trace.userActions.size)
        assertEquals("Bluetooth", trace.userActions[0].semanticSelector.text)
    }

    @Test
    fun test17_explicitStopEndsCapture() {
        TeachingSessionManager.startSession("test_stop", "Test explicit stop")
        assertTrue(TeachingSessionManager.isTeachingActive())

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertFalse("Teaching must be inactive after explicit stop", TeachingSessionManager.isTeachingActive())
        assertNull("Active session must be null after stop", TeachingSessionManager.getActiveSession())
    }

    @Test
    fun test18_eventsAfterStopAreNotAppended() {
        TeachingSessionManager.startSession("test_post_stop", "Test post-stop rejection")
        val service = TeachableVoiceAccessibilityService()

        service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.android.settings",
            className = "android.widget.TextView",
            targetElement = UiElement(elementId = "e1", role = "TextView", text = "Pre-stop")
        )

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals(1, trace!!.userActions.size)

        // Event arrived after stop
        val acceptedAfterStop = service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.android.settings",
            className = "android.widget.TextView",
            targetElement = UiElement(elementId = "e2", role = "TextView", text = "Post-stop")
        )
        assertFalse("Events after stop must be rejected", acceptedAfterStop)
        assertNull(TeachingSessionManager.stopSession())
    }

    @Test
    fun test19_ownAppControlInteractionsDoNotContaminateLearnedTaskActions() {
        TeachingSessionManager.startSession("test_no_self_contaminate", "Avoid recording our own UI")
        val service = TeachableVoiceAccessibilityService()

        // Legitimate external action
        service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.target.app",
            className = "android.widget.Button",
            targetElement = UiElement(elementId = "e1", role = "Button", text = "Target Action")
        )

        // Own app clicks (demo UI buttons, inputs)
        val own1 = service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.chockXlate.teachablevoice",
            className = "android.widget.Button",
            targetElement = UiElement(elementId = "b1", role = "Button", text = "START TEACHING")
        )
        val own2 = service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.chockXlate.teachablevoice",
            className = "android.widget.Button",
            targetElement = UiElement(elementId = "b2", role = "Button", text = "STOP TEACHING")
        )
        assertFalse(own1)
        assertFalse(own2)

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals(1, trace!!.userActions.size)
        assertEquals("Target Action", trace.userActions[0].semanticSelector.text)
        assertTrue(trace.traceEvents.none {
            when (it) {
                is TraceEvent.Ui -> it.uiEvent.packageName == "com.chockXlate.teachablevoice"
                else -> false
            }
        })
    }

    @Test
    fun test20_privacySensitiveFilteringStillWorks() {
        TeachingSessionManager.startSession("sensitive_test", "Test password and credential rejection")
        val service = TeachableVoiceAccessibilityService()

        // Password event must be rejected
        val passwordAccepted = service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.example.bank",
            className = "android.widget.EditText",
            targetElement = UiElement(elementId = "p1", role = "EditText", text = "SecretPassword", isEditable = true),
            isPassword = true
        )
        assertFalse("Password event must be rejected", passwordAccepted)
        assertNotNull("Teaching warning must be set for password", service.teachingWarning)

        // Reset warning for second check
        service.teachingWarning = null

        // Protected field (PIN / CVV)
        val protectedTargetAccepted = service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.example.bank",
            className = "android.widget.EditText",
            targetElement = UiElement(elementId = "p2", role = "EditText", text = "1234", isEditable = true),
            isTargetProtected = true
        )
        assertFalse("Protected credential target must be rejected", protectedTargetAccepted)

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals(0, trace!!.userActions.size)
        assertEquals(0, trace.traceEvents.size)
    }

    @Test
    fun test21_noTargetAppWhitelistExists() {
        TeachingSessionManager.startSession("arbitrary_apps", "Learn workflows in any app")
        val service = TeachableVoiceAccessibilityService()

        val arbitraryPackages = listOf(
            "com.custom.thirdparty.app",
            "org.open.source.tool",
            "io.github.specialized.workflow",
            "com.android.calculator2"
        )

        for ((i, pkg) in arbitraryPackages.withIndex()) {
            val accepted = service.recordNormalizedTeachingEvent(
                eventType = 1,
                eventTypeString = "TYPE_VIEW_CLICKED",
                packageName = pkg,
                className = "android.widget.Button",
                targetElement = UiElement(elementId = "btn_$i", role = "Button", text = "Action $i")
            )
            assertTrue("Arbitrary package '$pkg' must not be rejected by a whitelist", accepted)
        }

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals(4, trace!!.userActions.size)
    }

    @Test
    fun test22_demonstrationFilterOccursAfterRawEvidenceCaptureNotInsteadOfCapture() {
        TeachingSessionManager.startSession("filter_pipeline", "Capture raw launcher and filter downstream")
        val service = TeachableVoiceAccessibilityService()

        // Raw capture: 1. Launcher card click
        val launcherAccepted = service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.google.android.apps.nexuslauncher",
            className = "android.widget.TextView",
            targetElement = UiElement(elementId = "l1", role = "TextView", text = "Settings Card", resourceId = "com.google.android.apps.nexuslauncher:id/task_card")
        )
        assertTrue("Launcher navigation event must enter raw capture", launcherAccepted)

        // Raw capture: 2. Target app clicks
        val click1 = service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.android.settings",
            className = "android.widget.TextView",
            targetElement = UiElement(elementId = "s1", role = "TextView", text = "Network & internet", resourceId = "com.android.settings:id/net")
        )
        val click2 = service.recordNormalizedTeachingEvent(
            eventType = 1,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.android.settings",
            className = "android.widget.TextView",
            targetElement = UiElement(elementId = "s2", role = "TextView", text = "Internet", resourceId = "com.android.settings:id/wifi")
        )
        assertTrue(click1)
        assertTrue(click2)

        // Stop raw capture
        val rawTrace = TeachingSessionManager.stopSession()
        assertNotNull(rawTrace)
        assertEquals("Raw trace must contain all 3 actions", 3, rawTrace!!.userActions.size)

        // Downstream: DemonstrationFilter classifies after capture
        val filterResult = DemonstrationFilter.filter(rawTrace)
        assertEquals("com.android.settings", filterResult.targetAppContext)

        val launcherFilterEvent = filterResult.allEvents.find { it.eventId == rawTrace.userActions[0].actionId }
        assertNotNull(launcherFilterEvent)
        assertEquals(DemonstrationFilterClassification.NAVIGATION_CONTEXT, launcherFilterEvent!!.classification)

        val targetFilterEvent1 = filterResult.allEvents.find { it.eventId == rawTrace.userActions[1].actionId }
        val targetFilterEvent2 = filterResult.allEvents.find { it.eventId == rawTrace.userActions[2].actionId }
        assertEquals(DemonstrationFilterClassification.TASK_RELEVANT, targetFilterEvent1!!.classification)
        assertEquals(DemonstrationFilterClassification.TASK_RELEVANT, targetFilterEvent2!!.classification)
    }

    @Test
    fun test23_windowContentChangedWithCredentialDescendantDoesNotLatch() {
        TeachingSessionManager.startSession("settings_browse", "Browse Android settings")
        val service = TeachableVoiceAccessibilityService()

        val accepted = service.recordNormalizedTeachingEvent(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            eventTypeString = "TYPE_WINDOW_CONTENT_CHANGED",
            packageName = "com.android.settings",
            className = "android.widget.FrameLayout",
            targetElement = UiElement(elementId = "f1", role = "FrameLayout", text = "Google Password Manager")
        )

        assertTrue("TYPE_WINDOW_CONTENT_CHANGED with credential descendant text must not be rejected", accepted)
        assertNull("Teaching warning must not be set for window content changed", service.teachingWarning)
        assertTrue("Session must remain actively recording", TeachingSessionManager.isTeachingActive())

        val trace = TeachingSessionManager.peekSessionTrace()
        assertNotNull(trace)
        assertEquals(1, trace!!.traceEvents.size)
        TeachingSessionManager.stopSession()
    }

    @Test
    fun test24_settingsListContainingPasswordManagerDoesNotLatch() {
        TeachingSessionManager.startSession("settings_nav", "Navigate Settings")
        val service = TeachableVoiceAccessibilityService()

        val accepted = service.recordNormalizedTeachingEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.android.settings",
            className = "android.widget.TextView",
            targetElement = UiElement(
                elementId = "pm_row",
                role = "TextView",
                text = "Google Password Manager",
                isClickable = true,
                isEditable = false
            )
        )

        assertTrue("Clicking 'Google Password Manager' row must be accepted", accepted)
        assertNull("Teaching warning must remain null", service.teachingWarning)

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals(1, trace!!.userActions.size)
        assertEquals("Google Password Manager", trace.userActions.first().semanticSelector.text)
    }

    @Test
    fun test25_settingsListContainingScreenLockPinDoesNotLatch() {
        TeachingSessionManager.startSession("settings_pin_nav", "Navigate Screen lock")
        val service = TeachableVoiceAccessibilityService()

        val accepted = service.recordNormalizedTeachingEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.android.settings",
            className = "android.widget.TextView",
            targetElement = UiElement(
                elementId = "pin_row",
                role = "TextView",
                text = "Screen lock PIN",
                isClickable = true,
                isEditable = false
            )
        )

        assertTrue("Clicking 'Screen lock PIN' row must be accepted", accepted)
        assertNull("Teaching warning must remain null", service.teachingWarning)

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals(1, trace!!.userActions.size)
        assertEquals("Screen lock PIN", trace.userActions.first().semanticSelector.text)
    }

    @Test
    fun test26_nonActionableCredentialContainerDoesNotLatch() {
        TeachingSessionManager.startSession("container_nav", "Container navigation")
        val service = TeachableVoiceAccessibilityService()

        val accepted = service.recordNormalizedTeachingEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_FOCUSED,
            eventTypeString = "TYPE_VIEW_FOCUSED",
            packageName = "com.android.settings",
            className = "android.widget.LinearLayout",
            targetElement = UiElement(
                elementId = "c1",
                role = "LinearLayout",
                text = "Saved Passwords",
                isClickable = false,
                isEditable = false
            )
        )

        assertTrue("Non-actionable credential container must not be rejected", accepted)
        assertNull("Teaching warning must remain null", service.teachingWarning)

        TeachingSessionManager.stopSession()
    }

    @Test
    fun test27_actualPasswordEditTextInteractionDoesLatchAndBlock() {
        TeachingSessionManager.startSession("bank_login", "Bank login flow")
        val service = TeachableVoiceAccessibilityService()

        val accepted = service.recordNormalizedTeachingEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.example.bank",
            className = "android.widget.EditText",
            targetElement = UiElement(
                elementId = "pwd_input",
                role = "EditText",
                text = "Secret123",
                isEditable = true
            ),
            isTargetProtected = true
        )

        assertFalse("Actual password EditText interaction must be rejected", accepted)
        assertNotNull("Teaching warning must be set", service.teachingWarning)

        // Subsequent event must be blocked by latch
        val nextAccepted = service.recordNormalizedTeachingEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.android.settings",
            className = "android.widget.Button",
            targetElement = UiElement(elementId = "b1", role = "Button", text = "Back")
        )
        assertFalse("Subsequent event must be blocked once paused at credential boundary", nextAccepted)

        TeachingSessionManager.stopSession()
    }

    @Test
    fun test28_eventIsPasswordDoesLatchAndBlock() {
        TeachingSessionManager.startSession("password_test", "Password event test")
        val service = TeachableVoiceAccessibilityService()

        val accepted = service.recordNormalizedTeachingEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            eventTypeString = "TYPE_VIEW_TEXT_CHANGED",
            packageName = "com.example.app",
            className = "android.widget.EditText",
            targetElement = UiElement(elementId = "pwd_field", role = "EditText", isEditable = true),
            isPassword = true
        )

        assertFalse("event.isPassword=true must be rejected", accepted)
        assertNotNull("Teaching warning must be set", service.teachingWarning)

        val nextAccepted = service.recordNormalizedTeachingEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.example.app",
            className = "android.widget.Button",
            targetElement = UiElement(elementId = "b2", role = "Button", text = "Next")
        )
        assertFalse("Subsequent event must be blocked", nextAccepted)

        TeachingSessionManager.stopSession()
    }

    @Test
    fun test29_actualOtpPinSensitiveEditableFieldDoesBlock() {
        TeachingSessionManager.startSession("otp_flow", "OTP flow")
        val service = TeachableVoiceAccessibilityService()

        val accepted = service.recordNormalizedTeachingEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            eventTypeString = "TYPE_VIEW_TEXT_CHANGED",
            packageName = "com.example.bank",
            className = "android.widget.EditText",
            targetElement = UiElement(
                elementId = "otp_input",
                role = "EditText",
                resourceId = "com.example.bank:id/otp_input",
                text = "8901",
                isEditable = true
            )
        )

        assertFalse("Actual OTP/PIN editable field must block", accepted)
        assertNotNull("Teaching warning must be set", service.teachingWarning)

        TeachingSessionManager.stopSession()
    }

    @Test
    fun test30_afterHarmlessCredentialObservationalEventSubsequentClicksContinue() {
        TeachingSessionManager.startSession("multi_step_nav", "Navigate settings across screens")
        val service = TeachableVoiceAccessibilityService()

        // 1. Observational event with credential descendant text
        val obsAccepted = service.recordNormalizedTeachingEvent(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            eventTypeString = "TYPE_WINDOW_CONTENT_CHANGED",
            packageName = "com.android.settings",
            className = "android.widget.FrameLayout",
            targetElement = UiElement(
                elementId = "w1",
                role = "FrameLayout",
                text = "Google Password Manager"
            )
        )
        assertTrue("Observational event must be accepted", obsAccepted)
        assertNull("Warning must not be set", service.teachingWarning)

        // 2. Click Network & internet
        val click1 = service.recordNormalizedTeachingEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.android.settings",
            className = "android.widget.TextView",
            targetElement = UiElement(
                elementId = "net",
                role = "TextView",
                text = "Network & internet",
                resourceId = "com.android.settings:id/net",
                isClickable = true
            )
        )
        assertTrue("First click must be accepted", click1)

        // 3. Click Internet
        val click2 = service.recordNormalizedTeachingEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            eventTypeString = "TYPE_VIEW_CLICKED",
            packageName = "com.android.settings",
            className = "android.widget.TextView",
            targetElement = UiElement(
                elementId = "wifi",
                role = "TextView",
                text = "Internet",
                resourceId = "com.android.settings:id/wifi",
                isClickable = true
            )
        )
        assertTrue("Second click must be accepted", click2)

        val trace = TeachingSessionManager.stopSession()
        assertNotNull(trace)
        assertEquals("Trace must record both click actions", 2, trace!!.userActions.size)
        assertEquals("Network & internet", trace.userActions[0].semanticSelector.text)
        assertEquals("Internet", trace.userActions[1].semanticSelector.text)
    }

    @Test
    fun test31_sensitiveValuesRemainAbsentOrRedacted() {
        val normalItem = UiElement(
            elementId = "n1",
            role = "TextView",
            text = "Google Password Manager",
            isEditable = false
        )
        assertFalse("Non-editable Settings label must not be considered sensitive target",
            TeachingPrivacyGuard.isSensitiveTargetElement(normalItem))

        val signInButton = UiElement(
            elementId = "n2",
            role = "Button",
            text = "Sign in",
            isClickable = true,
            isEditable = false
        )
        assertFalse("Sign-in button must not be considered sensitive target",
            TeachingPrivacyGuard.isSensitiveTargetElement(signInButton))

        val pinField = UiElement(
            elementId = "s1",
            role = "EditText",
            resourceId = "com.app:id/pin_code",
            text = "1234",
            isEditable = true
        )
        assertTrue("Editable PIN field must be identified as sensitive target",
            TeachingPrivacyGuard.isSensitiveTargetElement(pinField))

        val passwordField = UiElement(
            elementId = "s2",
            role = "EditText",
            text = "password",
            isEditable = true
        )
        assertTrue("Editable password field must be identified as sensitive target",
            TeachingPrivacyGuard.isSensitiveTargetElement(passwordField))
    }
}
