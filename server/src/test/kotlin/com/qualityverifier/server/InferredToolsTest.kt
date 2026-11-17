package com.qualityverifier.server

import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.server.db.InferredTools
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tools an assessment had to hand (V35, release scope row 10): the worker's own tools,
 * plus the studio's on a piece labelled for one; on "My own job", the worker's own only.
 */
class InferredToolsTest {

    private val workersList = listOf(
        OwnedTool(ToolKind.HAND_SAW, ToolOwnership.WORKER),
        OwnedTool(ToolKind.CLAMPS, ToolOwnership.STUDIO),
        OwnedTool(ToolKind.ROUTER, ToolOwnership.STUDIO),
        OwnedTool(ToolKind.SANDER, ToolOwnership.RENTAL),
        OwnedTool(ToolKind.DRILL, ToolOwnership.NONE),
        OwnedTool(ToolKind.MARKING_GAUGE, ToolOwnership.UNKNOWN),
    )
    private val studiosList = listOf(
        OwnedTool(ToolKind.CIRCULAR_SAW, ToolOwnership.WORKER),
        OwnedTool(ToolKind.PLANE_NO4, ToolOwnership.STUDIO),
        OwnedTool(ToolKind.SQUARE, ToolOwnership.NONE),
    )

    @Test
    fun `on My own job only the tools the worker owns themselves`() {
        val rows = InferredTools.of(workersList, labelled = false, studio = studiosList)
        assertEquals(listOf(InferredTools.Row(ToolKind.HAND_SAW, InferredTools.MAKER, ToolOwnership.WORKER)), rows)
    }

    @Test
    fun `on a piece for a studio, everything to hand on both lists`() {
        val rows = InferredTools.of(workersList, labelled = true, studio = studiosList)
        assertEquals(
            listOf(
                InferredTools.Row(ToolKind.HAND_SAW, InferredTools.MAKER, ToolOwnership.WORKER),
                InferredTools.Row(ToolKind.ROUTER, InferredTools.MAKER, ToolOwnership.STUDIO),
                InferredTools.Row(ToolKind.CLAMPS, InferredTools.MAKER, ToolOwnership.STUDIO),
                InferredTools.Row(ToolKind.SANDER, InferredTools.MAKER, ToolOwnership.RENTAL),
                InferredTools.Row(ToolKind.PLANE_NO4, InferredTools.STUDIO, ToolOwnership.STUDIO),
                InferredTools.Row(ToolKind.CIRCULAR_SAW, InferredTools.STUDIO, ToolOwnership.WORKER),
            ).sortedWith(compareBy({ it.source }, { it.kind.ordinal })),
            rows,
        )
    }

    @Test
    fun `a label whose link does not hold gives the worker's whole list and nothing of the studio's`() {
        val rows = InferredTools.of(workersList, labelled = true, studio = null)
        assertEquals(setOf(InferredTools.MAKER), rows.map { it.source }.toSet())
        assertEquals(4, rows.size)
    }
}
