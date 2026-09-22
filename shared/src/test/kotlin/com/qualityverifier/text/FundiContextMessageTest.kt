package com.qualityverifier.text

import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.Workshop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FundiContextMessageTest {

    private val labels = FundiLabels.ENGLISH

    private fun message(profile: FundiProfile) = buildFundiContextMessage(profile, labels)

    @Test
    fun `an empty profile says nothing at all`() {
        assertEquals("", message(FundiProfile()))
    }

    // The single failure prompts/fundi-master.txt calls out by name: "a fix that needs
    // four sash cramps is not a fix for somebody who owns none". The model can only avoid
    // it if the message says they own none, so this is the load-bearing assertion here.
    @Test
    fun `tools they do not have are stated, not left out`() {
        val profile = FundiProfile(
            tools = listOf(
                OwnedTool(ToolKind.CHISELS, ToolOwnership.OWNED),
                OwnedTool(ToolKind.CLAMPS, ToolOwnership.NONE),
            ),
        )

        val text = message(profile)
        assertTrue("clamps must be named as absent", text.contains("clamps"))
        assertTrue(text.contains(labels.contextToolsNone))
        assertEquals(
            "Tools I have: chisels.\nTools I do not have: clamps.",
            text,
        )
    }

    // A tool nobody asked about is a different thing from a tool they said they lack, and
    // the message must not blur them: an unasked tool appears in neither list.
    @Test
    fun `a tool nobody asked about appears in neither list`() {
        val profile = FundiProfile(
            tools = listOf(OwnedTool(ToolKind.CHISELS, ToolOwnership.OWNED)),
        )

        val text = message(profile)
        assertFalse("router was never asked about", text.contains("router"))
        assertFalse(text.contains(labels.contextToolsNone))
    }

    @Test
    fun `a borrowed tool is available but marked`() {
        val profile = FundiProfile(
            tools = listOf(
                OwnedTool(ToolKind.SQUARE, ToolOwnership.BORROWED),
                OwnedTool(ToolKind.DRILL, ToolOwnership.OWNED),
            ),
        )

        // Square before drill: ToolKind's declaration order, not the order they were
        // answered in and not alphabetical, so the same profile always reads the same way.
        assertEquals("Tools I have: square (borrowed), drill.", message(profile))
    }

    @Test
    fun `the workshop reads as the maker's own sentences`() {
        val profile = FundiProfile(
            workshop = Workshop(
                worksAt = "Gikomba, third row",
                yearsInTrade = 8,
                workers = 2,
                makes = "beds and wardrobes",
                piecesPerMonth = 12,
                usualTimber = "mvule",
            ),
        )

        assertEquals(
            "About my workshop: I work at Gikomba, third row. " +
                "I have been in the trade 8 years. There are 2 of us working. " +
                "I mostly make beds and wardrobes. I finish about 12 pieces a month. " +
                "I usually work in mvule.",
            message(profile),
        )
    }

    @Test
    fun `a half-answered workshop sends what there is`() {
        val profile = FundiProfile(workshop = Workshop(makes = "stools"))

        assertEquals("About my workshop: I mostly make stools.", message(profile))
    }

    @Test
    fun `blank free text is not a sentence`() {
        val profile = FundiProfile(
            workshop = Workshop(worksAt = "   ", makes = "stools"),
        )

        assertEquals("About my workshop: I mostly make stools.", message(profile))
    }

    // rentsTools and dayRateKes are for the deferred marketplace. They are about a future
    // feature rather than about this piece, so nothing about them reaches the assistant.
    @Test
    fun `renting out tools is never mentioned to the assistant`() {
        val profile = FundiProfile(
            workshop = Workshop(makes = "stools", rentsTools = true),
            tools = listOf(OwnedTool(ToolKind.ROUTER, ToolOwnership.OWNED, dayRateKes = 400)),
        )

        val text = message(profile)
        assertFalse(text.contains("rent"))
        assertFalse(text.contains("400"))
    }

    @Test
    fun `a workshop with only the rental answer says nothing`() {
        assertEquals("", message(FundiProfile(workshop = Workshop(rentsTools = true))))
    }

    @Test
    fun `goals come last, in a stable order`() {
        val profile = FundiProfile(
            goals = setOf(FundiGoal.ZERO_COMEBACKS, FundiGoal.PRICE_PER_PIECE),
        )

        assertEquals(
            "What I want from this: a better price per piece, " +
                "no pieces coming back for repair.",
            message(profile),
        )
    }

    @Test
    fun `a full profile reads in one order every time`() {
        val profile = FundiProfile(
            workshop = Workshop(makes = "stools"),
            tools = listOf(
                OwnedTool(ToolKind.CLAMPS, ToolOwnership.NONE),
                OwnedTool(ToolKind.HAND_SAW, ToolOwnership.OWNED),
            ),
            goals = setOf(FundiGoal.MORE_ORDERS),
        )

        assertEquals(
            listOf(
                "About my workshop: I mostly make stools.",
                "Tools I have: hand saw.",
                "Tools I do not have: clamps.",
                "What I want from this: more orders.",
            ).joinToString("\n"),
            message(profile),
        )
    }
}
