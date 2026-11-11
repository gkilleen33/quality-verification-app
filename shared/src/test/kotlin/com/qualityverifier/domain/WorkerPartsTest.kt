package com.qualityverifier.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerPartsTest {

    private val wanjiru = "7c1d3b52-0f1e-4c39-9a57-2d6a1e0b4f10"

    @Test
    fun `a table offers no upholstery`() {
        assertFalse(WorkPart.UPHOLSTERY in WorkerParts.partsFor(ItemType.WOODEN_TABLE))
    }

    @Test
    fun `sofa, padded chair, bed, and something else offer upholstery`() {
        // A bed for padded headboards; "Something else" because it could be anything.
        listOf(
            ItemType.UPHOLSTERED_SOFA,
            ItemType.UPHOLSTERED_CHAIR,
            ItemType.WOODEN_BED,
            ItemType.OTHER,
        ).forEach { itemType ->
            assertEquals(itemType.name, WorkPart.entries, WorkerParts.partsFor(itemType))
        }
    }

    @Test
    fun `every other type offers every part but upholstery`() {
        val expected = listOf(
            WorkPart.FRAME, WorkPart.JOINERY, WorkPart.FINISHING, WorkPart.ASSEMBLY, WorkPart.OTHER,
        )
        listOf(
            ItemType.WOODEN_TABLE,
            ItemType.WOODEN_CHAIR,
            ItemType.WOODEN_STOOL,
            ItemType.WOODEN_CABINET,
        ).forEach { itemType ->
            assertEquals(itemType.name, expected, WorkerParts.partsFor(itemType))
        }
    }

    @Test
    fun `upholstery already held on a table stays on screen until cleared`() {
        // Hidden, it would be dropped on the next save without the manager seeing it go.
        val held = WorkerParts.offered(ItemType.WOODEN_TABLE, setOf(WorkPart.UPHOLSTERY))
        assertEquals(WorkPart.entries, held)
        assertEquals(
            WorkerParts.partsFor(ItemType.WOODEN_TABLE),
            WorkerParts.offered(ItemType.WOODEN_TABLE, emptySet()),
        )
    }

    @Test
    fun `choosing a worker selects the whole piece`() {
        val slots = WorkerParts.toggleWorker(emptyList(), wanjiru, "Wanjiru")
        assertTrue(WorkerParts.isWholePiece(slots, wanjiru))
        assertEquals(listOf(WorkerSlot(wanjiru, "Wanjiru", part = null)), slots)
    }

    @Test
    fun `choosing joinery clears the whole piece`() {
        val chosen = WorkerParts.toggleWorker(emptyList(), wanjiru, "Wanjiru")
        val slots = WorkerParts.togglePart(chosen, wanjiru, "Wanjiru", WorkPart.JOINERY)
        assertFalse(WorkerParts.isWholePiece(slots, wanjiru))
        assertEquals(listOf(WorkerSlot(wanjiru, "Wanjiru", WorkPart.JOINERY)), slots)
    }

    @Test
    fun `clearing every part selects the whole piece again`() {
        var slots = WorkerParts.toggleWorker(emptyList(), wanjiru, "Wanjiru")
        slots = WorkerParts.togglePart(slots, wanjiru, "Wanjiru", WorkPart.JOINERY)
        slots = WorkerParts.togglePart(slots, wanjiru, "Wanjiru", WorkPart.FINISHING)
        slots = WorkerParts.togglePart(slots, wanjiru, "Wanjiru", WorkPart.JOINERY)
        slots = WorkerParts.togglePart(slots, wanjiru, "Wanjiru", WorkPart.FINISHING)
        // Still chosen, on the whole piece: taking somebody off is the name chip's job.
        assertTrue(WorkerParts.isWholePiece(slots, wanjiru))
        assertEquals(listOf(WorkerSlot(wanjiru, "Wanjiru", part = null)), slots)
    }

    @Test
    fun `tapping whole piece clears the parts`() {
        var slots = WorkerParts.toggleWorker(emptyList(), wanjiru, "Wanjiru")
        slots = WorkerParts.togglePart(slots, wanjiru, "Wanjiru", WorkPart.FRAME)
        slots = WorkerParts.togglePart(slots, wanjiru, "Wanjiru", WorkPart.JOINERY)
        slots = WorkerParts.wholePiece(slots, wanjiru, "Wanjiru")
        assertEquals(listOf(WorkerSlot(wanjiru, "Wanjiru", part = null)), slots)
        // Tapped again, it stays selected rather than leaving the worker on nothing.
        assertEquals(slots, WorkerParts.wholePiece(slots, wanjiru, "Wanjiru"))
    }

    @Test
    fun `the stored part is null for the whole piece and the part's id otherwise`() {
        // What WorkerRepository and the server write to session_workers.part (V23).
        val whole = WorkerParts.toggleWorker(emptyList(), null, null)
        assertEquals(listOf<String?>(null), whole.map { it.part?.id })
        val joinery = WorkerParts.togglePart(whole, null, null, WorkPart.JOINERY)
        assertEquals(listOf<String?>("joinery"), joinery.map { it.part?.id })
    }

    @Test
    fun `a part changes only its own worker`() {
        var slots = WorkerParts.toggleWorker(emptyList(), null, null)
        slots = WorkerParts.toggleWorker(slots, wanjiru, "Wanjiru")
        slots = WorkerParts.togglePart(slots, wanjiru, "Wanjiru", WorkPart.UPHOLSTERY)
        assertTrue(WorkerParts.isWholePiece(slots, null))
        assertEquals(setOf(WorkPart.UPHOLSTERY), WorkerParts.partsOf(slots, wanjiru))
    }

    @Test
    fun `choosing a worker again takes them off the piece with every part`() {
        var slots = WorkerParts.toggleWorker(emptyList(), wanjiru, "Wanjiru")
        slots = WorkerParts.togglePart(slots, wanjiru, "Wanjiru", WorkPart.FRAME)
        slots = WorkerParts.togglePart(slots, wanjiru, "Wanjiru", WorkPart.FINISHING)
        slots = WorkerParts.toggleWorker(slots, wanjiru, "Wanjiru")
        assertEquals(emptyList<WorkerSlot>(), slots)
        // A worker not chosen is on nothing, not on the whole piece.
        assertFalse(WorkerParts.isWholePiece(slots, wanjiru))
    }
}
