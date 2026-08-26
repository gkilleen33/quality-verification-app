package com.qualityverifier.prompts

import com.qualityverifier.data.prompts.DefaultPrompts
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.TestDiagram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DefaultPrompts.kt is generated from the files under `prompts/`, and the two drifting
 * apart is exactly the failure this guards: the app would keep shipping an old prompt as
 * its offline fallback while the repo showed the new one.
 *
 * If this fails, run: python3 tools/generate_default_prompts.py
 */
class DefaultPromptsInSyncTest {

    private val repoRoot: File = run {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "prompts/master.txt").isFile) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "Could not locate prompts/master.txt above the test working dir" }
    }

    private fun promptFile(relative: String) = File(repoRoot, "prompts/$relative")

    @Test
    fun `compiled-in master matches prompts master file`() {
        val onDisk = promptFile("master.txt").readText().trimEnd('\n')
        assertEquals(
            "DefaultPrompts.MASTER is stale - regenerate it",
            onDisk,
            DefaultPrompts.MASTER,
        )
    }

    @Test
    fun `compiled-in item prompts match their prompt files`() {
        ItemType.entries.forEach { itemType ->
            val onDisk = promptFile("items/${itemType.id}.txt").readText().trimEnd('\n')
            // Empty files are intentionally absent from the map, which reads back as "".
            val expected = if (onDisk.isBlank()) "" else onDisk
            assertEquals(
                "Compiled-in prompt for ${itemType.id} is stale - regenerate it",
                expected,
                DefaultPrompts.forItem(itemType),
            )
        }
    }

    @Test
    fun `the master prompt never puts a number on money`() {
        // We have no price data, and a wrong figure quoted back to a seller in a
        // negotiation is worse than no figure. The prompt has to keep saying so.
        val master = DefaultPrompts.MASTER
        assertTrue(
            "the money ban is gone from the master prompt",
            master.contains("never give a figure in shillings"),
        )
        assertTrue(
            "the repair-cost ban is gone from the master prompt",
            master.contains("estimate of what a repair should cost"),
        )
        // The worked example in the verdict schema is the likeliest place for a stray
        // figure to creep back in.
        assertFalse(
            "the verdict example quotes a cost",
            Regex("""KSh\s*\d""").containsMatchIn(master),
        )
    }

    @Test
    fun `the assistant assesses the piece and does not coach a negotiation`() {
        // Quality is what this app is for. What the customer then pays, and what they say
        // in the shop, is theirs. Describing a repair is objective and stays; telling
        // somebody to ask for a discount is advice about a negotiation we are not in.
        val master = DefaultPrompts.MASTER
        assertTrue(
            "the negotiation ban is gone from the master prompt",
            master.contains("Do not negotiate, and do not coach the customer to"),
        )
        assertTrue(
            "the prompt no longer forbids suggesting a discount",
            master.contains("Never suggest asking for a discount"),
        )
        // The field that used to carry a script for the customer to say out loud.
        assertFalse("ask_seller is back in the verdict schema", master.contains("ask_seller"))
    }

    @Test
    fun `asking the seller a question is still allowed`() {
        // The first attempt at the negotiation ban went too far and forbade this too.
        // A question that settles something a photograph cannot show is how an
        // assessment gets finished, not how a price gets haggled over.
        val master = DefaultPrompts.MASTER
        assertTrue(
            "clarifying questions are no longer permitted",
            master.contains("Asking the seller a question is a different thing, and is welcome"),
        )
        assertTrue(
            "the prompt no longer gives examples of what to ask",
            master.contains("whether the top has been sealed"),
        )
    }

    @Test
    fun `a comparison stays evidence-bound and issues no verdict of its own`() {
        // Two pieces are never evenly evidenced: this one is in front of you in
        // photographs, the earlier one only in your own notes. A comparison that forgets
        // that starts inventing differences, and one that emits a verdict block would
        // overwrite a report the customer already has.
        val master = DefaultPrompts.MASTER
        assertTrue(
            "the comparison rules are gone from the master prompt",
            master.contains("Comparing two pieces:"),
        )
        assertTrue(
            "the uneven evidence caveat is gone",
            master.contains("The two are not evenly evidenced"),
        )
        assertTrue(
            "nothing stops absence of a finding reading as absence of the defect",
            master.contains(
                "A defect recorded on one piece and not on the other is not evidence",
            ),
        )
        assertTrue(
            "a comparison may now emit a verdict block over the top of an existing one",
            master.contains("No verdict block and no plan block in a comparison"),
        )
        assertTrue(
            "the comparison may now pick a winner for the customer",
            master.contains("Do not declare an overall winner"),
        )
        assertTrue(
            "the money ban is not restated where the temptation is strongest",
            master.contains("Say nothing about price, value for money"),
        )
    }

    @Test
    fun `the master prompt defines both app-directed blocks`() {
        // The app parses these two fence tags. Renaming one in the prompt without
        // renaming it in AssistantBlocks would silently stop the cards and the chips
        // from ever appearing, with no error anywhere.
        val master = DefaultPrompts.MASTER
        assertTrue("qv-options is undocumented", master.contains("qv-options"))
        assertTrue("qv-verdict is undocumented", master.contains("qv-verdict"))
        assertTrue("qv-plan is undocumented", master.contains("qv-plan"))
        listOf("sound", "fair", "serious_concerns").forEach { level ->
            assertTrue("verdict level $level is undocumented", master.contains(level))
        }
        // Without this field the app cannot tell which language to put its own card
        // headings in, and lands back on English headings over Swahili findings.
        assertTrue(
            "the verdict no longer declares its language",
            master.contains("the two letter code for the language"),
        )
    }

    @Test
    fun `collection is asked for in one batch, not shot by shot`() {
        // Reverting to one photo per turn costs a network round trip per shot, and
        // because every turn re-sends the earlier images, the token cost of an
        // assessment grows with the square of its shot count. This is the instruction
        // that stops it, and it is easy to lose in a rewrite.
        val master = DefaultPrompts.MASTER
        assertTrue(
            "the prompt no longer forbids asking shot by shot",
            master.contains("Do not ask for photos one at a time"),
        )
        assertTrue(
            "the prompt no longer says everything is asked for at once",
            master.contains("Ask for everything you need in the one plan"),
        )
    }

    @Test
    fun `the checklist is checked against the piece before it is committed to`() {
        // The item protocols describe a typical piece of that kind. Committing to seven
        // shots of wooden joinery before looking is how somebody ends up being asked to
        // photograph the grain on a steel leg. The app sends a photo of the whole piece
        // with the context so the protocol can be checked against it first.
        val master = DefaultPrompts.MASTER
        assertTrue(
            "the prompt no longer expects a photo with the opening message",
            master.contains("opening message arrives with a photo already attached"),
        )
        assertTrue(
            "the prompt no longer looks at it before planning",
            master.contains("Start by looking at the photo attached to their opening message"),
        )
        assertTrue(
            "the prompt no longer adapts the checklist to what it saw",
            master.contains("decide, for each one, whether it still applies"),
        )
        assertTrue(
            "the prompt may now ask again for the photo it already has",
            master.contains("Never include the photo of the whole piece"),
        )
    }

    @Test
    fun `a test that did not happen is not treated as a failure`() {
        // A wobble test nobody could perform is not a wobbly stool. Without this the
        // absence of a result reads as a defect, which is the worst possible direction for
        // an error in this app to go.
        val master = DefaultPrompts.MASTER
        assertTrue(
            "the prompt no longer knows about the app's two escape answers",
            master.contains("I cannot do this one"),
        )
        assertTrue(
            "the prompt no longer forbids reading a missing result as a defect",
            master.contains("Never treat it as evidence of a defect"),
        )
    }

    @Test
    fun `the plan is not asked for in prose as well as in the block`() {
        // Listing the shots in both places put the whole plan on screen twice and buried
        // the start-camera button under it, which read as the assistant asking for photos
        // one at a time -- the exact behaviour the plan block exists to replace.
        val master = DefaultPrompts.MASTER
        assertTrue(
            "the prompt no longer forbids listing the shots in the prose",
            master.contains("Do not list the shots or the tests in that paragraph"),
        )
    }

    @Test
    fun `only diagrams the app can draw are offered to the prompt`() {
        // The drawings ship in the APK while the prompts do not, so the prompt must
        // name only what this build has. A name it invents draws nothing, silently.
        val master = DefaultPrompts.MASTER
        val drawable = TestDiagram.entries.map { it.id }
        drawable.forEach { id ->
            assertTrue("diagram $id is not offered in the prompt", master.contains(id))
        }
        // And every diagram named in an item protocol has to be one of those.
        ItemType.entries.forEach { itemType ->
            Regex("""Diagram:\s*(\S+)""").findAll(DefaultPrompts.forItem(itemType))
                .map { it.groupValues[1] }
                .forEach { named ->
                    assertTrue(
                        "${itemType.id} names diagram '$named', which the app cannot draw",
                        named in drawable,
                    )
                }
        }
    }

    @Test
    fun `the master prompt still offers both assessment depths`() {
        val master = DefaultPrompts.MASTER
        assertTrue(master.contains("Full assessment"))
        assertTrue(master.contains("Rapid assessment"))
        assertTrue(
            "the rapid path no longer warns that it is less reliable",
            master.contains("much more likely to miss something"),
        )
    }

    @Test
    fun `the language is taken from the customer's choice, not guessed`() {
        // Left to inference the assistant picked a language from the item name and then
        // would not switch when written to in the other one. The app asks outright now,
        // and the prompt has to honour that rather than re-deriving it.
        val master = DefaultPrompts.MASTER
        assertTrue(
            "the prompt no longer takes the language from the opening message",
            master.contains("first message tells you which language to answer in"),
        )
        assertTrue(
            "the prompt no longer follows a mid-conversation language switch",
            master.contains("If they later write to you in a different language"),
        )
    }

    @Test
    fun `the context questions are not asked again over the network`() {
        // The app collects ownership, price, usage and language on the phone before any
        // request is made. If the prompt starts asking for them too, every assessment
        // pays for three round trips it does not need, and the customer is asked things
        // they have already answered.
        val master = DefaultPrompts.MASTER
        assertTrue(
            "Stage 1 no longer says the context is normally collected",
            master.contains("Normally collected already"),
        )
        assertTrue(
            "the prompt no longer forbids re-asking what it was told",
            master.contains("Do not ask again for anything that message tells you"),
        )
        assertTrue(
            "the depth is no longer expected in the opening message",
            master.contains("Stage 2, the depth. Normally chosen already"),
        )
    }

    @Test
    fun `the assistant takes over when the intake was abandoned`() {
        // The app's questions are buttons, and somebody whose answer is not one of them
        // hands the conversation over. If the prompt does not know that can happen, it
        // either ignores a half-answered context or barrels on to a plan it cannot make.
        val master = DefaultPrompts.MASTER
        assertTrue(
            "the prompt does not expect a partial context",
            master.contains("Sometimes it will not tell you everything"),
        )
        assertTrue(
            "the prompt does not know to ask only for what is missing",
            master.contains("ask only for what is missing"),
        )
    }

    @Test
    fun `every item prompt has a photo plan and hands-on tests`() {
        ItemType.entries.forEach { itemType ->
            val prompt = DefaultPrompts.forItem(itemType)
            assertTrue("${itemType.id} has no photo plan", prompt.contains("PHOTO PLAN"))
            assertTrue("${itemType.id} has no tests", prompt.contains("HANDS-ON TESTS"))
            assertTrue(
                "${itemType.id} gives the verdict no item-specific emphasis",
                prompt.contains("VERDICT EMPHASIS"),
            )
        }
    }

    @Test
    fun `every item prompt offers tappable outcomes for at least one test`() {
        // A test whose outcomes are not enumerated cannot become chips, which is the
        // whole point of the hands-on stage being answerable one-handed.
        ItemType.entries.forEach { itemType ->
            assertTrue(
                "${itemType.id} enumerates no test outcomes",
                DefaultPrompts.forItem(itemType).contains("Choices:"),
            )
        }
    }

    @Test
    fun `upholstered checklists assess the frame as well as the covering`() {
        // The frame is hidden under padding, so these prompts have to reach it
        // indirectly. This is the requirement most easily lost in a future edit.
        listOf(ItemType.UPHOLSTERED_CHAIR, ItemType.UPHOLSTERED_SOFA).forEach { itemType ->
            val prompt = DefaultPrompts.forItem(itemType)
            listOf(
                "Judging the hidden frame:",
                "Judging the upholstery:",
                "exposed",
                "creak",
                "foam",
            ).forEach { expected ->
                assert(prompt.contains(expected)) {
                    "${itemType.id} prompt is missing \"$expected\""
                }
            }
        }
    }

    @Test
    fun `every item type has a prompt file`() {
        ItemType.entries.forEach { itemType ->
            assert(promptFile("items/${itemType.id}.txt").isFile) {
                "Missing prompts/items/${itemType.id}.txt for ${itemType.name}"
            }
        }
    }
}
