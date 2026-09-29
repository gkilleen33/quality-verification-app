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

    /**
     * Always the last line, always present.
     *
     * The first real assessment came back in Kiswahili to a maker whose context was
     * entirely in English, because nothing told the assistant which to use and a
     * furniture maker in Nairobi is a fair bet for Kiswahili. Stated, it is a decision;
     * inferred, it is a guess that the model then sticks to.
     */
    private val language = "Please answer me in English."

    @Test
    fun `an empty profile still says which language to answer in`() {
        // A maker who skipped setup entirely gets no tool list, which costs them worse
        // coaching. Getting it in a language they cannot read costs them all of it.
        assertEquals(language, message(FundiProfile()))
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
            "Tools I have: chisels.\nTools I do not have: clamps.\n" + language,
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
        assertEquals("Tools I have: square (borrowed), drill.\n" + language, message(profile))
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
                "I usually work in mvule.\n" + language,
            message(profile),
        )
    }

    @Test
    fun `a half-answered workshop sends what there is`() {
        val profile = FundiProfile(workshop = Workshop(makes = "stools"))

        assertEquals("About my workshop: I mostly make stools.\n" + language, message(profile))
    }

    @Test
    fun `blank free text is not a sentence`() {
        val profile = FundiProfile(
            workshop = Workshop(worksAt = "   ", makes = "stools"),
        )

        assertEquals("About my workshop: I mostly make stools.\n" + language, message(profile))
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
    fun `a workshop with only the rental answer adds nothing of its own`() {
        assertEquals(language, message(FundiProfile(workshop = Workshop(rentsTools = true))))
    }

    @Test
    fun `goals come in a stable order, above the language line`() {
        val profile = FundiProfile(
            goals = setOf(FundiGoal.ZERO_COMEBACKS, FundiGoal.PRICE_PER_PIECE),
        )

        assertEquals(
            "What I want from this: a better price per piece, " +
                "no pieces coming back for repair.\n" + language,
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
                language,
            ).joinToString("\n"),
            message(profile),
        )
    }
}
