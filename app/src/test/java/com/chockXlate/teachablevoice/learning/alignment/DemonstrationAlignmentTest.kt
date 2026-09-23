package com.chockXlate.teachablevoice.learning.alignment

import com.chockXlate.teachablevoice.contract.workflow.SlotType
import com.chockXlate.teachablevoice.learning.intent.Intent
import com.chockXlate.teachablevoice.learning.intent.IntentExtractionResult
import com.chockXlate.teachablevoice.learning.slots.ExtractedSlot
import com.chockXlate.teachablevoice.learning.slots.SlotExtractionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemonstrationAlignmentTest {

    private fun createDataset(
        demoId: String,
        intentName: String,
        slots: List<ExtractedSlot>
    ): DemonstrationDataset {
        val intent = Intent(intentId = "int_$demoId", canonicalName = intentName, confidence = 1.0, confidenceLevel = "HIGH")
        val intentRes = IntentExtractionResult(intent = intent, confidence = 1.0, evidenceSummary = "Extracted $intentName")
        val slotRes = SlotExtractionResult(intentName = intentName, extractedSlots = slots)
        return DemonstrationDataset(
            demonstrationId = demoId,
            traceId = "tr_$demoId",
            intentResult = intentRes,
            slotResult = slotRes
        )
    }

    @Test
    fun test1_sameSlotNamesAlignAcrossDemonstrations() {
        val slot1 = ExtractedSlot(slotId = "s1", name = "item", type = SlotType.TEXT, rawValue = "Pizza", typedValue = "Pizza")
        val slot2 = ExtractedSlot(slotId = "s2", name = "item", type = SlotType.TEXT, rawValue = "Burger", typedValue = "Burger")

        val ds1 = createDataset("d1", "order_food", listOf(slot1))
        val ds2 = createDataset("d2", "order_food", listOf(slot2))

        val result = DemonstrationAlignment.align(listOf(ds1, ds2))
        assertEquals(1, result.alignedSlotGroups.size)
        val group = result.alignedSlotGroups.first()
        assertEquals("item", group.slotName)
        assertEquals(2, group.occurrences.size)
    }

    @Test
    fun test2_sameIntentDemonstrationsAlign() {
        val ds1 = createDataset("d1", "order_food", emptyList())
        val ds2 = createDataset("d2", "order_food", emptyList())

        val result = DemonstrationAlignment.align(listOf(ds1, ds2))
        assertTrue(result.isCompatible)
        assertEquals(2, result.alignedDemonstrationIds.size)
        assertTrue(result.incompatibleDemonstrationIds.isEmpty())
    }

    @Test
    fun test3_differentIntentsDoNotAlign() {
        val ds1 = createDataset("d1", "order_food", emptyList())
        val ds2 = createDataset("d2", "send_message", emptyList())

        val result = DemonstrationAlignment.align(listOf(ds1, ds2))
        assertEquals(1, result.alignedDemonstrationIds.size)
        assertEquals(1, result.incompatibleDemonstrationIds.size)
        assertEquals("d2", result.incompatibleDemonstrationIds.first())
    }

    @Test
    fun test9_typeMismatchHandledSafely() {
        val slot1 = ExtractedSlot(slotId = "s1", name = "quantity", type = SlotType.INTEGER, rawValue = "2", typedValue = "2")
        val slot2 = ExtractedSlot(slotId = "s2", name = "quantity", type = SlotType.ADDRESS, rawValue = "Home", typedValue = "Home")

        val ds1 = createDataset("d1", "order_food", listOf(slot1))
        val ds2 = createDataset("d2", "order_food", listOf(slot2))

        val result = DemonstrationAlignment.align(listOf(ds1, ds2))
        assertEquals(1, result.alignedSlotGroups.size)
        val group = result.alignedSlotGroups.first()
        assertTrue(group.isTypeMismatch)
        assertNotNull(group.typeMismatchDetails)
    }
}
