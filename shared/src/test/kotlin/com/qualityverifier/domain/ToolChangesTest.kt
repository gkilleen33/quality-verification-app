package com.qualityverifier.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the research record ends up containing when a maker re-answers their tools.
 *
 * Pure, and separated from the store on purpose: every case here was a way to lose or
 * invent history, and none of them needs a database to state. The first version of the
 * save path deleted every tool row and reinserted, which lost all of it.
 */
class ToolChangesTest {

    private fun tool(kind: ToolKind, ownership: ToolOwnership) = OwnedTool(kind, ownership)

    // The whole point. A shop that owned a circular saw and does not now has told us
    // something, and it has to survive the save that recorded it.
    @Test
    fun `getting rid of a tool is recorded, with where it came from`() {
        val changes = toolChanges(
            previous = listOf(tool(ToolKind.CIRCULAR_SAW, ToolOwnership.OWNED)),
            next = listOf(
                OwnedTool(
                    ToolKind.CIRCULAR_SAW,
                    ToolOwnership.NONE,
                    changeReason = ToolChangeReason.SOLD,
                ),
            ),
        )

        assertEquals(
            listOf(
                ToolChange(
                    kind = ToolKind.CIRCULAR_SAW,
                    from = ToolOwnership.OWNED,
                    to = ToolOwnership.NONE,
                    reason = ToolChangeReason.SOLD,
                ),
            ),
            changes,
        )
    }

    // The evidence that the coaching did anything: a fix plan named the absence of a
    // marking gauge as the cause, and later they have one.
    @Test
    fun `acquiring a tool is recorded too`() {
        val changes = toolChanges(
            previous = listOf(tool(ToolKind.MARKING_GAUGE, ToolOwnership.NONE)),
            next = listOf(
                OwnedTool(
                    ToolKind.MARKING_GAUGE,
                    ToolOwnership.OWNED,
                    changeReason = ToolChangeReason.BOUGHT,
                ),
            ),
        )

        assertEquals(ToolOwnership.NONE, changes.single().from)
        assertEquals(ToolOwnership.OWNED, changes.single().to)
        assertEquals(ToolChangeReason.BOUGHT, changes.single().reason)
    }

    // Silence is not disposal. A tool nobody asked about this time keeps whatever answer
    // it had, and writing history for it would be inventing a change nobody reported.
    @Test
    fun `a tool left out of the answer is not a tool disposed of`() {
        val changes = toolChanges(
            previous = listOf(
                tool(ToolKind.CHISELS, ToolOwnership.OWNED),
                tool(ToolKind.ROUTER, ToolOwnership.OWNED),
            ),
            next = listOf(tool(ToolKind.CHISELS, ToolOwnership.OWNED)),
        )

        assertEquals(emptyList<ToolChange>(), changes)
    }

    // Re-saving an untouched profile must not read as selling and rebuying everything.
    @Test
    fun `an unchanged answer is not a change`() {
        val tools = listOf(
            tool(ToolKind.CHISELS, ToolOwnership.OWNED),
            tool(ToolKind.CLAMPS, ToolOwnership.NONE),
        )

        assertEquals(emptyList<ToolChange>(), toolChanges(tools, tools))
    }

    // Never told us about it before is a different fact from told us they had none.
    @Test
    fun `the first answer about a tool has no from`() {
        val changes = toolChanges(
            previous = emptyList(),
            next = listOf(tool(ToolKind.DRILL, ToolOwnership.OWNED)),
        )

        assertNull(changes.single().from)
        assertEquals(ToolOwnership.OWNED, changes.single().to)
    }

    @Test
    fun `a first answer of none is still recorded`() {
        val changes = toolChanges(
            previous = emptyList(),
            next = listOf(tool(ToolKind.CLAMPS, ToolOwnership.NONE)),
        )

        assertNull(changes.single().from)
        assertEquals(ToolOwnership.NONE, changes.single().to)
    }

    // A borrowed tool going back is its own story, and not the same as selling one.
    @Test
    fun `returning a borrowed tool is a change of its own`() {
        val changes = toolChanges(
            previous = listOf(tool(ToolKind.CIRCULAR_SAW, ToolOwnership.BORROWED)),
            next = listOf(
                OwnedTool(
                    ToolKind.CIRCULAR_SAW,
                    ToolOwnership.NONE,
                    changeReason = ToolChangeReason.RETURNED,
                ),
            ),
        )

        assertEquals(ToolOwnership.BORROWED, changes.single().from)
        assertEquals(ToolChangeReason.RETURNED, changes.single().reason)
    }

    // The reason is always optional, and has to stay so: a maker who would rather not say
    // should still be able to correct their tool list.
    @Test
    fun `a change with no reason is still a change`() {
        val changes = toolChanges(
            previous = listOf(tool(ToolKind.PLANE_NO4, ToolOwnership.OWNED)),
            next = listOf(tool(ToolKind.PLANE_NO4, ToolOwnership.NONE)),
        )

        assertEquals(1, changes.size)
        assertNull(changes.single().reason)
    }

    @Test
    fun `a blank note is no note`() {
        val changes = toolChanges(
            previous = emptyList(),
            next = listOf(
                OwnedTool(ToolKind.SANDER, ToolOwnership.OWNED, changeNote = "   "),
            ),
        )

        assertNull(changes.single().note)
    }

    @Test
    fun `the maker's own words are kept, trimmed`() {
        val changes = toolChanges(
            previous = listOf(tool(ToolKind.SANDER, ToolOwnership.OWNED)),
            next = listOf(
                OwnedTool(
                    ToolKind.SANDER,
                    ToolOwnership.NONE,
                    changeReason = ToolChangeReason.OTHER,
                    changeNote = "  lent to my brother and not back yet  ",
                ),
            ),
        )

        assertEquals("lent to my brother and not back yet", changes.single().note)
    }

    @Test
    fun `a tool answered twice is counted once`() {
        val changes = toolChanges(
            previous = emptyList(),
            next = listOf(
                tool(ToolKind.CHISELS, ToolOwnership.OWNED),
                tool(ToolKind.CHISELS, ToolOwnership.NONE),
            ),
        )

        assertEquals(1, changes.size)
        assertEquals(ToolOwnership.OWNED, changes.single().to)
    }
}
