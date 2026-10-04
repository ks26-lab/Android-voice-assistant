package com.chockXlate.teachablevoice.teach

import com.chockXlate.teachablevoice.contract.event.ActionEvent
import com.chockXlate.teachablevoice.contract.event.VoiceEvent
import com.chockXlate.teachablevoice.contract.filter.DemonstrationFilterClassification
import com.chockXlate.teachablevoice.contract.trace.DemonstrationTrace
import com.chockXlate.teachablevoice.contract.workflow.SemanticSelector
import com.chockXlate.teachablevoice.learning.actions.SemanticActionExtractor
import com.chockXlate.teachablevoice.learning.synthesis.WorkflowSynthesizer
import com.chockXlate.teachablevoice.teach.filter.DemonstrationFilter
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 3A — Intent-Conditioned Demonstration Relevance, Mistake Detection & Selective Workflow Learning.
 * Verifies P3A-T1 through P3A-T14.
 */
class IntentConditionedDemonstrationFilterTest {

    // Helper to build action
    private fun action(
        id: String,
        timestamp: Long,
        type: String,
        pkg: String,
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        role: String? = "android.widget.Button",
        inputData: String? = null
    ): ActionEvent {
        return ActionEvent(
            actionId = id,
            timestamp = timestamp,
            actionType = type,
            packageName = pkg,
            inputData = inputData,
            semanticSelector = SemanticSelector(
                role = role,
                text = text,
                contentDescription = desc,
                resourceId = resId
            )
        )
    }

    // P3A-T1: Correct App
    @Test
    fun test_P3A_T1_correct_app_all_meaningful_actions_learned() {
        val skillName = "Search Headphones"
        val skillDesc = "Search for headphones on Google."

        val trace = DemonstrationTrace(
            traceId = "t1",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            userActions = listOf(
                action("a1", 1000L, "CLICK", "com.google.android.googlequicksearchbox", text = "Search Box", role = "android.widget.EditText"),
                action("a2", 1010L, "INPUT_TEXT", "com.google.android.googlequicksearchbox", inputData = "headphones", role = "android.widget.EditText"),
                action("a3", 1020L, "CLICK", "com.google.android.googlequicksearchbox", text = "Search", desc = "Submit search")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        assertTrue(filterResult.isTaskRelevant("a1"))
        assertTrue(filterResult.isTaskRelevant("a2"))
        assertTrue(filterResult.isTaskRelevant("a3"))

        val synthesis = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_g", skillName, skillDesc)
        assertNotNull(synthesis.workflow)
        val wf = synthesis.workflow!!
        assertEquals("com.google.android.googlequicksearchbox", wf.appContext)
        assertEquals(3, wf.steps.size)
    }

    // P3A-T2: Wrong App Detour
    @Test
    fun test_P3A_T2_wrong_app_detour_not_learned() {
        val skillName = "Search Headphones"
        val skillDesc = "Search for headphones on Google."

        val trace = DemonstrationTrace(
            traceId = "t2",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            userActions = listOf(
                // 1. In Google
                action("g1", 1000L, "CLICK", "com.google.android.googlequicksearchbox", text = "Search Box", role = "android.widget.EditText"),
                // 2. Accidental Amazon detour
                action("amz1", 1010L, "CLICK", "com.amazon.mShop.android.shopping", text = "Deals"),
                action("back1", 1020L, "BACK", "com.amazon.mShop.android.shopping", text = "Back"),
                // 3. Back to Google
                action("g2", 1030L, "INPUT_TEXT", "com.google.android.googlequicksearchbox", inputData = "headphones", role = "android.widget.EditText"),
                action("g3", 1040L, "CLICK", "com.google.android.googlequicksearchbox", text = "Search")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        // Amazon and Back actions are NOT task relevant
        assertFalse(filterResult.isTaskRelevant("amz1"))
        assertFalse(filterResult.isTaskRelevant("back1"))
        // Google actions ARE task relevant
        assertTrue(filterResult.isTaskRelevant("g1"))
        assertTrue(filterResult.isTaskRelevant("g2"))
        assertTrue(filterResult.isTaskRelevant("g3"))

        // Workflow synthesis must not include Amazon or Back
        val synthesis = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_g", skillName, skillDesc)
        assertNotNull(synthesis.workflow)
        val wf = synthesis.workflow!!
        assertEquals("com.google.android.googlequicksearchbox", wf.appContext)
        assertTrue(wf.steps.none { it.semanticSelector.text == "Deals" })
        assertTrue(wf.steps.none { it.semanticAction == "BACK" })
        assertEquals(3, wf.steps.size)
    }

    // P3A-T3: Settings Detour
    @Test
    fun test_P3A_T3_settings_detour_not_learned() {
        val skillName = "Search YouTube"
        val skillDesc = "Search YouTube for a video."

        val trace = DemonstrationTrace(
            traceId = "t3",
            timestamp = 1000L,
            appContext = "com.google.android.youtube",
            userActions = listOf(
                action("yt1", 1000L, "CLICK", "com.google.android.youtube", text = "Search", role = "android.widget.ImageView"),
                // Accidental Settings opening
                action("set1", 1010L, "CLICK", "com.android.settings", text = "Network & internet"),
                action("back1", 1020L, "BACK", "com.android.settings", text = "Back"),
                // Return to YouTube
                action("yt2", 1030L, "INPUT_TEXT", "com.google.android.youtube", inputData = "guitar tutorial", role = "android.widget.EditText")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        assertFalse("Settings action must not be task relevant", filterResult.isTaskRelevant("set1"))
        assertFalse("Back action escaping settings must not be task relevant", filterResult.isTaskRelevant("back1"))
        assertTrue("YouTube click must be relevant", filterResult.isTaskRelevant("yt1"))
        assertTrue("YouTube input must be relevant", filterResult.isTaskRelevant("yt2"))

        val synthesis = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_yt", skillName, skillDesc)
        val wf = synthesis.workflow!!
        assertEquals("com.google.android.youtube", wf.appContext)
        assertTrue(wf.steps.none { it.semanticSelector.text == "Network & internet" })
        assertEquals(2, wf.steps.size)
    }

    // P3A-T4: Multiple Wrong Apps
    @Test
    fun test_P3A_T4_multiple_wrong_apps_only_relevant_path_learned() {
        val skillName = "Search Headphones"
        val skillDesc = "Search for headphones on Google."

        val trace = DemonstrationTrace(
            traceId = "t4",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            userActions = listOf(
                // 1. Google
                action("g1", 1000L, "CLICK", "com.google.android.googlequicksearchbox", text = "Search Box"),
                // 2. Settings detour
                action("s1", 1010L, "CLICK", "com.android.settings", text = "Display"),
                action("sb", 1020L, "BACK", "com.android.settings"),
                // 3. Amazon detour
                action("a1", 1030L, "CLICK", "com.amazon.mShop.android.shopping", text = "Cart"),
                action("ab", 1040L, "BACK", "com.amazon.mShop.android.shopping"),
                // 4. Myntra detour
                action("m1", 1050L, "CLICK", "com.myntra.android", text = "Sale"),
                action("mb", 1060L, "BACK", "com.myntra.android"),
                // 5. Back to Google
                action("g2", 1070L, "INPUT_TEXT", "com.google.android.googlequicksearchbox", inputData = "headphones", role = "android.widget.EditText"),
                action("g3", 1080L, "CLICK", "com.google.android.googlequicksearchbox", text = "Search")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        assertFalse(filterResult.isTaskRelevant("s1"))
        assertFalse(filterResult.isTaskRelevant("a1"))
        assertFalse(filterResult.isTaskRelevant("m1"))
        assertFalse(filterResult.isTaskRelevant("sb"))
        assertFalse(filterResult.isTaskRelevant("ab"))
        assertFalse(filterResult.isTaskRelevant("mb"))

        assertTrue(filterResult.isTaskRelevant("g1"))
        assertTrue(filterResult.isTaskRelevant("g2"))
        assertTrue(filterResult.isTaskRelevant("g3"))

        val synthesis = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_g", skillName, skillDesc)
        val wf = synthesis.workflow!!
        assertEquals(3, wf.steps.size)
        assertEquals("com.google.android.googlequicksearchbox", wf.appContext)
    }

    // P3A-T5: Wrong Input Correction
    @Test
    fun test_P3A_T5_wrong_input_correction_superseded_value_not_learned() {
        val skillName = "Search Headphones"
        val skillDesc = "Search for headphones on Google."

        val trace = DemonstrationTrace(
            traceId = "t5",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            userActions = listOf(
                action("g1", 1000L, "CLICK", "com.google.android.googlequicksearchbox", text = "Search Bar", role = "android.widget.EditText"),
                // User accidentally types "cars"
                action("inp_cars", 1010L, "INPUT_TEXT", "com.google.android.googlequicksearchbox", inputData = "cars", role = "android.widget.EditText"),
                // User corrects and types "headphones"
                action("inp_headphones", 1020L, "INPUT_TEXT", "com.google.android.googlequicksearchbox", inputData = "headphones", role = "android.widget.EditText"),
                action("g2", 1030L, "CLICK", "com.google.android.googlequicksearchbox", text = "Search")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        assertFalse("Superseded input 'cars' should not be task relevant", filterResult.isTaskRelevant("inp_cars"))
        assertTrue("Corrected input 'headphones' should be task relevant", filterResult.isTaskRelevant("inp_headphones"))

        val synthesis = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_g", skillName, skillDesc)
        val wf = synthesis.workflow!!
        val slot = wf.slots.find { it.name.contains("query") || it.name.contains("headphones") || it.name.contains("item") }
        assertNotNull("Should infer slot for query", slot)
        assertEquals("headphones", slot?.exampleValue)
    }

    // P3A-T6: Wrong Product Correction
    @Test
    fun test_P3A_T6_wrong_product_correction_undone_target_not_learned() {
        val skillName = "Order Headphones on Amazon"
        val skillDesc = "Order headphones using Amazon."

        val trace = DemonstrationTrace(
            traceId = "t6",
            timestamp = 1000L,
            appContext = "com.amazon.mShop.android.shopping",
            userActions = listOf(
                action("a1", 1000L, "CLICK", "com.amazon.mShop.android.shopping", text = "Search"),
                action("a2", 1010L, "INPUT_TEXT", "com.amazon.mShop.android.shopping", inputData = "sony headphones"),
                // User accidentally taps wrong product A
                action("prodA", 1020L, "CLICK", "com.amazon.mShop.android.shopping", text = "Cheap Earbuds A"),
                // Realizes mistake and navigates back
                action("back", 1030L, "BACK", "com.amazon.mShop.android.shopping", text = "Back"),
                // Selects intended product B
                action("prodB", 1040L, "CLICK", "com.amazon.mShop.android.shopping", text = "Sony WH-1000XM5"),
                action("cart", 1050L, "CLICK", "com.amazon.mShop.android.shopping", text = "Add to Cart")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        assertFalse("Undone product A tap should not be task relevant", filterResult.isTaskRelevant("prodA"))
        assertFalse("Backtrack action should not be task relevant", filterResult.isTaskRelevant("back"))
        assertTrue("Product B tap should be task relevant", filterResult.isTaskRelevant("prodB"))
        assertTrue("Add to Cart should be task relevant", filterResult.isTaskRelevant("cart"))

        val synthesis = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_amz", skillName, skillDesc)
        val wf = synthesis.workflow!!
        assertTrue(wf.steps.none { it.semanticSelector.text == "Cheap Earbuds A" })
        assertTrue(wf.steps.any { it.semanticSelector.text == "Sony WH-1000XM5" })
    }

    // P3A-T7: Legitimate Multi-App Workflow
    @Test
    fun test_P3A_T7_legitimate_multi_app_both_applications_retained() {
        val skillName = "Share Location"
        val skillDesc = "Find a location in Google Maps and send it to a WhatsApp contact."

        val trace = DemonstrationTrace(
            traceId = "t7",
            timestamp = 1000L,
            appContext = "com.google.android.apps.maps",
            userActions = listOf(
                // Maps steps
                action("m1", 1000L, "CLICK", "com.google.android.apps.maps", text = "Search here"),
                action("m2", 1010L, "INPUT_TEXT", "com.google.android.apps.maps", inputData = "Central Park"),
                action("m3", 1020L, "CLICK", "com.google.android.apps.maps", text = "Share Location"),
                // WhatsApp steps
                action("w1", 1030L, "CLICK", "com.whatsapp", text = "John Doe"),
                action("w2", 1040L, "CLICK", "com.whatsapp", text = "Send", desc = "Send message")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        assertTrue("Maps action m1 must be relevant", filterResult.isTaskRelevant("m1"))
        assertTrue("Maps action m2 must be relevant", filterResult.isTaskRelevant("m2"))
        assertTrue("Maps action m3 must be relevant", filterResult.isTaskRelevant("m3"))
        assertTrue("WhatsApp action w1 must be relevant", filterResult.isTaskRelevant("w1"))
        assertTrue("WhatsApp action w2 must be relevant", filterResult.isTaskRelevant("w2"))

        val synthesis = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_multi", skillName, skillDesc)
        val wf = synthesis.workflow!!
        assertEquals(5, wf.steps.size)
        assertTrue(wf.steps.any { it.preconditions.requiredPackage == "com.google.android.apps.maps" })
        assertTrue(wf.steps.any { it.preconditions.requiredPackage == "com.whatsapp" })
    }

    // P3A-T8: Short App Visit
    @Test
    fun test_P3A_T8_short_app_visit_not_rejected_when_intent_compatible() {
        val skillName = "Order Headphones"
        val skillDesc = "Order headphones using Amazon."

        // Very short visit (300ms total) with immediate relevant click
        val trace = DemonstrationTrace(
            traceId = "t8",
            timestamp = 1000L,
            appContext = "com.amazon.mShop.android.shopping",
            userActions = listOf(
                action("a1", 1000L, "CLICK", "com.amazon.mShop.android.shopping", text = "Buy Now", resId = "com.amazon.mShop.android.shopping:id/buy_now")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        assertTrue("Short visit to intended app must be task relevant", filterResult.isTaskRelevant("a1"))

        val synthesis = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_short", skillName, skillDesc)
        assertNotNull(synthesis.workflow)
        assertEquals(1, synthesis.workflow!!.steps.size)
    }

    // P3A-T9: Uncertain Action
    @Test
    fun test_P3A_T9_uncertain_action_preserved_in_trace_not_promoted_to_workflow() {
        val skillName = "Search Google"
        val skillDesc = "Search on Google."

        // Anonymous action with no text, role, or resourceId
        val trace = DemonstrationTrace(
            traceId = "t9",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            userActions = listOf(
                action("u1", 1000L, "CLICK", "com.google.android.googlequicksearchbox", role = "android.view.View"),
                action("a2", 1010L, "CLICK", "com.google.android.googlequicksearchbox", text = "Search Button")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        assertTrue("Anonymous target must be classified UNCERTAIN", filterResult.uncertainEventIds.contains("u1"))
        assertFalse("Uncertain target must not be task relevant", filterResult.isTaskRelevant("u1"))
        assertTrue("Valid target must be task relevant", filterResult.isTaskRelevant("a2"))

        // Raw trace preserves u1
        assertEquals(2, trace.userActions.size)

        // Synthesized workflow only contains a2
        val synthesis = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_u", skillName, skillDesc)
        assertEquals(1, synthesis.workflow!!.steps.size)
        assertEquals("Search Button", synthesis.workflow!!.steps.first().semanticSelector.text)
    }

    // P3A-T10: No Task Progress
    @Test
    fun test_P3A_T10_no_task_progress_transient_noise_not_learned() {
        val skillName = "Search Google"
        val skillDesc = "Search on Google."

        val trace = DemonstrationTrace(
            traceId = "t10",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            userActions = listOf(
                // Volume slider / system overlay
                action("vol", 1000L, "CLICK", "com.android.systemui", text = "Volume Up"),
                action("g1", 1010L, "CLICK", "com.google.android.googlequicksearchbox", text = "Search Box")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        assertTrue(filterResult.systemNoiseEventIds.contains("vol"))
        assertFalse(filterResult.isTaskRelevant("vol"))
        assertTrue(filterResult.isTaskRelevant("g1"))

        val synthesis = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_vol", skillName, skillDesc)
        assertEquals(1, synthesis.workflow!!.steps.size)
    }

    // P3A-T11: Back as Correction
    @Test
    fun test_P3A_T11_back_as_correction_excluded_from_workflow() {
        val skillName = "Search Google"
        val skillDesc = "Search for headphones on Google."

        val trace = DemonstrationTrace(
            traceId = "t11",
            timestamp = 1000L,
            appContext = "com.google.android.googlequicksearchbox",
            userActions = listOf(
                action("g1", 1000L, "CLICK", "com.google.android.googlequicksearchbox", text = "Search Box"),
                // Detour to settings
                action("set", 1010L, "CLICK", "com.android.settings", text = "Wi-Fi"),
                action("back_corr", 1020L, "BACK", "com.android.settings"),
                // Google action
                action("g2", 1030L, "CLICK", "com.google.android.googlequicksearchbox", text = "Submit")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        assertFalse(filterResult.isTaskRelevant("set"))
        assertFalse(filterResult.isTaskRelevant("back_corr"))

        val synthesis = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_back", skillName, skillDesc)
        val wf = synthesis.workflow!!
        assertTrue(wf.steps.none { it.semanticAction == "BACK" })
        assertEquals(2, wf.steps.size)
    }

    // P3A-T12: Target Application Priority
    @Test
    fun test_P3A_T12_target_application_priority_inferred_from_description() {
        val skillName = "Search Headphones"
        val skillDesc = "Search headphones on Google."

        val trace = DemonstrationTrace(
            traceId = "t12",
            timestamp = 1000L,
            appContext = "unknown",
            userActions = listOf(
                action("g1", 1000L, "CLICK", "com.google.android.googlequicksearchbox", text = "Search"),
                action("amz", 1010L, "CLICK", "com.amazon.mShop.android.shopping", text = "Deals"),
                action("g2", 1020L, "CLICK", "com.google.android.googlequicksearchbox", text = "Results")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        assertEquals("com.google.android.googlequicksearchbox", filterResult.targetAppContext)
        assertTrue(filterResult.isTaskRelevant("g1"))
        assertTrue(filterResult.isTaskRelevant("g2"))
        assertFalse(filterResult.isTaskRelevant("amz"))
    }

    // P3A-T13: Generic Application
    @Test
    fun test_P3A_T13_generic_application_works_without_hardcoding() {
        val skillName = "Search Dresses"
        val skillDesc = "Search dresses on Myntra."

        val trace = DemonstrationTrace(
            traceId = "t13",
            timestamp = 1000L,
            appContext = "com.myntra.android",
            userActions = listOf(
                action("m1", 1000L, "CLICK", "com.myntra.android", text = "Search Bar"),
                action("m2", 1010L, "INPUT_TEXT", "com.myntra.android", inputData = "red dress"),
                action("m3", 1020L, "CLICK", "com.myntra.android", text = "Apply Filter")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        assertEquals("com.myntra.android", filterResult.targetAppContext)
        assertTrue(filterResult.isTaskRelevant("m1"))
        assertTrue(filterResult.isTaskRelevant("m2"))
        assertTrue(filterResult.isTaskRelevant("m3"))

        val synthesis = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_myntra", skillName, skillDesc)
        val wf = synthesis.workflow!!
        assertEquals("com.myntra.android", wf.appContext)
        assertEquals(3, wf.steps.size)
    }

    // P3A-T14: Multi-App Intent
    @Test
    fun test_P3A_T14_multi_app_intent_preserves_both_applications() {
        val skillName = "Cross App Task"
        val skillDesc = "Find a restaurant on Maps and share it on WhatsApp."

        val trace = DemonstrationTrace(
            traceId = "t14",
            timestamp = 1000L,
            appContext = "com.google.android.apps.maps",
            userActions = listOf(
                action("maps1", 1000L, "CLICK", "com.google.android.apps.maps", text = "Restaurant"),
                action("maps2", 1010L, "CLICK", "com.google.android.apps.maps", text = "Share"),
                action("wa1", 1020L, "CLICK", "com.whatsapp", text = "Contact"),
                action("wa2", 1030L, "CLICK", "com.whatsapp", text = "Send")
            )
        )

        val filterResult = DemonstrationFilter.filter(trace, skillName, skillDesc)
        assertTrue(filterResult.isTaskRelevant("maps1"))
        assertTrue(filterResult.isTaskRelevant("maps2"))
        assertTrue(filterResult.isTaskRelevant("wa1"))
        assertTrue(filterResult.isTaskRelevant("wa2"))

        val synthesis = WorkflowSynthesizer.synthesizeFromTrace(trace, "skill_cross", skillName, skillDesc)
        val wf = synthesis.workflow!!
        assertEquals(4, wf.steps.size)
        assertEquals(2, wf.steps.mapNotNull { it.preconditions.requiredPackage }.distinct().size)
    }
}
