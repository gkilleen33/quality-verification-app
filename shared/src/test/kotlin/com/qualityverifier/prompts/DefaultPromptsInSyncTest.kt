package com.qualityverifier.prompts

import com.qualityverifier.data.prompts.DefaultPrompts
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.MeasurementUnit
import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.SkillDimension
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.TestDiagram
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.buildFundiContextMessage
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
    fun `compiled-in fundi master matches its prompt file`() {
        val onDisk = promptFile("fundi-master.txt").readText().trimEnd('\n')
        assertEquals(
            "DefaultPrompts.FUNDI_MASTER is stale - regenerate it",
            onDisk,
            DefaultPrompts.FUNDI_MASTER,
        )
    }

    @Test
    fun `compiled-in compaction prompt matches its prompt file`() {
        val onDisk = promptFile("compact.txt").readText().trimEnd('\n')
        assertEquals("DefaultPrompts.COMPACT is stale - regenerate it", onDisk, DefaultPrompts.COMPACT)
    }

    @Test
    fun `compiled-in bench prompt matches its prompt file, and names every tool kind`() {
        val onDisk = promptFile("fundi-bench.txt").readText().trimEnd('\n')
        assertEquals("DefaultPrompts.FUNDI_BENCH is stale - regenerate it", onDisk, DefaultPrompts.FUNDI_BENCH)
        // A kind the prompt never names can never be drafted; one it names that the app
        // does not know is dropped by the parser.
        ToolKind.entries.forEach { kind ->
            assertTrue("${kind.id} is missing from the bench prompt", DefaultPrompts.FUNDI_BENCH.contains("- ${kind.id}:"))
        }
        assertTrue(DefaultPrompts.FUNDI_BENCH.contains("qv-bench"))
    }

    @Test
    fun `both masters keep a follow-up short and know how to look again`() {
        // Follow-ups were slow because nearly every one re-issued the whole verdict.
        for (master in listOf(DefaultPrompts.MASTER, DefaultPrompts.FUNDI_MASTER)) {
            assertTrue(master.contains("One to three sentences is the usual answer"))
            assertTrue(master.contains("only when the answer changes it"))
            assertTrue(master.contains("view_photos"))
        }
        // The tool name the server offers is the one the prompts use.
        assertTrue(DefaultPrompts.COMPACT.contains("view_photos"))
    }

    @Test
    fun `the fundi master defines every block the app parses`() {
        // Renaming a fence tag in the prompt without renaming it in AssistantBlocks
        // silently stops the cards from ever appearing, with no error anywhere.
        val fundi = DefaultPrompts.FUNDI_MASTER
        listOf("qv-options", "qv-plan", "fb-diagnosis", "qv-verdict")
            .forEach { tag ->
                assertTrue("$tag is undocumented in the fundi master", fundi.contains(tag))
            }
        // No fix plan since prompt E (Nathan, 18 November 2026): the fix line and the three
        // detail buttons replace it. The app still parses fb-fixplan, for older replies.
        assertFalse("the fundi master asks for a fix plan again", fundi.contains("fb-fixplan"))
    }

    @Test
    fun `every full assessment issues a verdict, because the rates depend on it`() {
        // The two rates a maker is judged on are computed from
        // sessions.verdict_defect_count, which is filled from a qv-verdict block. This used
        // to require one only on re-assessment — so the *first* evaluation of every piece
        // recorded nothing, and "first time clean" had no possible input. A full
        // assessment that stops emitting one leaves the piece unrecorded, with nothing
        // failing.
        val fundi = DefaultPrompts.FUNDI_MASTER
        assertTrue(
            "the fundi master no longer asks every full assessment for a verdict",
            fundi.contains("Every full assessment issues one, the first as well as the re-evaluation"),
        )
        assertTrue(
            "the reason the verdict matters is no longer stated",
            fundi.contains("leaves the piece unrecorded"),
        )
    }

    // Each problem is written once, as a finding in the diagnosis. The verdict used to
    // repeat every finding as a defect "using the same titles as your diagnosis", linked
    // only by title, and the two lists could disagree. problemsOf reads either shape, but
    // a prompt that asked for both again would bring the mismatch back.
    @Test
    fun `each problem is written once, as a finding with its consequence`() {
        val fundi = DefaultPrompts.FUNDI_MASTER
        assertTrue(
            "the fundi master no longer says the verdict lists no defects",
            fundi.contains("The verdict lists no defects."),
        )
        assertTrue(
            "the findings no longer carry the three consequence fields",
            fundi.contains("carries what_i_see, what_it_means and what_to_do, as well as a title"),
        )
        // The phone and the admin portal read a reply's problems from that reply alone, so
        // a verdict sent without its diagnosis would show a grade with no problems under it.
        assertTrue(
            "a verdict may again arrive without the diagnosis it depends on",
            fundi.contains("A new verdict always comes with the whole diagnosis again, in the same message"),
        )
        assertFalse(
            "the verdict asks for defects again",
            fundi.contains("\"defects\"") || fundi.contains("using the same titles as your diagnosis"),
        )
    }

    // The other half of the same rule. A targeted check that came back clean says nothing
    // about the rest of the piece; if it issued a verdict it would be counted as a clean
    // piece and inflate the number a maker is judged on.
    @Test
    fun `a targeted check never issues a verdict`() {
        val fundi = DefaultPrompts.FUNDI_MASTER
        assertTrue(fundi.contains("Only a full assessment issues one — never a targeted check"))
        assertTrue(fundi.contains("Do not issue a verdict: a clean result on one joint"))
    }

    @Test
    fun `the fundi master puts a price on nothing`() {
        // Not the finished piece, not a repair, not a tool. We have no price data for
        // their market, their suppliers or their customers, and a number invented here
        // becomes one a maker plans around or quotes to somebody. Repair costs and tool
        // price ranges were both allowed at first and both dropped once a real
        // assessment produced them.
        val fundi = DefaultPrompts.FUNDI_MASTER
        assertTrue(fundi.contains("Do not put a figure on what the finished piece is worth"))
        assertTrue(fundi.contains("Never estimate what a repair costs"))
        assertTrue(fundi.contains("Never put a price on anything"))
        // And no example teaches the model a price field to fill in.
        listOf("cost_kes", "price_kes", "KSh").forEach { field ->
            assertFalse("the prompt still shows $field", fundi.contains(field))
        }
    }

    @Test
    fun `every finding has its cause worked out, and a fix is asked for one at a time`() {
        // Issue #41: causes were once given for the worst finding only. That decided for
        // the maker what to work on; they can choose to take one at a time themselves.
        // Since prompt E the three answers are worked out for every finding and given on
        // request, through the detail buttons.
        val fundi = DefaultPrompts.FUNDI_MASTER
        assertTrue(
            "the prompt no longer distinguishes a cause from a grade",
            fundi.contains("THE DIAGNOSIS IS A CAUSE, NOT A GRADE"),
        )
        assertTrue(
            "every finding must have all three worked out",
            fundi.contains("work out all three for every one of them"),
        )
        assertFalse("the old one-cause rule is back", fundi.contains("coach only the first one"))
        assertFalse(fundi.contains("Only the first carries what_happened"))
        assertTrue(
            "how to fix an improvement is no longer answered in steps",
            fundi.contains("When they ask how to fix an improvement, answer in short numbered steps"),
        )
        assertTrue(
            "the one-question limit is gone",
            fundi.contains("One check, and only one"),
        )
    }

    @Test
    fun `the habit is named and the person is not`() {
        // The one field where an insult would land. A fundi told they are careless stops
        // using the app, and the coaching is worthless without them.
        val fundi = DefaultPrompts.FUNDI_MASTER
        assertTrue(
            "the ban on judging the person is gone",
            fundi.contains("never describe a maker as careless"),
        )
        assertTrue(
            "the habit-not-the-person rule is gone",
            fundi.contains("The habit is the thing you name, never the person"),
        )
    }

    @Test
    fun `the fix works with the tools the maker actually has`() {
        val fundi = DefaultPrompts.FUNDI_MASTER
        assertTrue(
            "tool-awareness is gone from the fix plan",
            fundi.contains("Work with the tools they told you they have"),
        )
        assertTrue(
            "the borrowed-tool caveat is gone",
            fundi.contains("available for this piece and possibly not the next"),
        )
        assertTrue(
            "buying a tool may now be the whole of the fix",
            fundi.contains("Never make buying a tool the whole of the fix"),
        )
    }

    // Arm 1 (release scope row 34, Nathan, 17 November 2026): the full analysis stays, its
    // detail is written briefly, and each finding gains short fields for a later layout to
    // show. Phones before that layout ignore the new fields, so nothing on screen fails if
    // a rewrite drops them; these assertions are what notice.
    @Test
    fun `the fundi master writes short fields after the full analysis`() {
        val fundi = DefaultPrompts.FUNDI_MASTER
        assertTrue("the analysis no longer comes first", fundi.contains("Analyse first and decide what to show second"))
        assertTrue("the detail limits are gone", fundi.contains("what_happened in at most five short sentences"))
        assertTrue("the habit is no longer an instruction", fundi.contains("Write root_habit as the habit to build"))
        listOf("\"fix_line\"", "\"customer_line\"", "\"tool_line\"").forEach { field ->
            assertTrue("the diagnosis example no longer shows $field", fundi.contains(field))
        }
        assertTrue("the title is no longer the action", fundi.contains("the action that improves the finding, in at most ten words"))
        // Prompt C (Nathan, 18 November 2026): "fix the arm firmly" said nothing about what to.
        assertTrue(
            "titles may again leave out what a part is fixed to",
            fundi.contains("When a part is to be fixed to another, name the other part"),
        )
        assertTrue("fix_line has lost its length limit", fundi.contains("at most 20 words in all"))
        assertTrue(
            "customer_line is no longer gain framing",
            fundi.contains("names the quality the customer gets once the improvement is made"),
        )
        // Prompts D and E (Nathan, 18 November 2026): the second sentence goes from the
        // defect, to what the customer thinks of the quality, to what they do; and it stays
        // in one moment, because a defect found after the sale cannot lower the price paid.
        assertTrue(
            "customer_line no longer keeps to one moment",
            fundi.contains("follows the customer through one moment and stays in it"),
        )
        assertTrue(
            "customer_line may again lower a price already paid",
            fundi.contains("They have already paid, so never say they offer less"),
        )
        assertTrue("customer_line may give a figure", fundi.contains("Never give a figure"))
        // Prompt C: the opening above the cards is two short sentences.
        assertTrue(
            "the opening has lost its length",
            fundi.contains("Write that paragraph as two sentences, each at most 20 words"),
        )
        // The phone shows only a diagnosis reply's first paragraph, so a correction has to
        // say what changed there, or the maker sees a new card with no reason for it.
        assertTrue(
            "a corrected diagnosis no longer opens with what changed",
            fundi.contains("opens instead with the one sentence that says what changed"),
        )
        // The button message is English whatever the maker's language.
        assertTrue(
            "a detail answer may follow the English button message",
            fundi.contains("Write it in the language of the assessment"),
        )
        assertTrue("tool_line is no longer optional", fundi.contains("tool_line: optional"))
        assertTrue("the numbers rule is gone", fundi.contains("spell out numbers from one to nine"))
        // The verdict level is for the record and research; the maker never reads its word.
        assertTrue("the rating word may reach the maker", fundi.contains("Never rate the piece with the verdict level's word"))
        assertFalse("general praise is back in the verdict example", fundi.contains("Good work overall"))
    }

    // Row 42 (Nathan, 20 and 23 November 2026): the phone shows two lines of a photo instruction
    // before "Tap to read it all" (row 39), so where to stand and what is in the frame come first.
    @Test
    fun `row 42 - the prompt carries the photo-instruction rule and its examples`() {
        val fundi = DefaultPrompts.FUNDI_MASTER
        assertTrue(
            "the 20-word rule is gone",
            fundi.contains(
                "Each photo instruction is at most 20 words, with where to stand and what must be in the frame " +
                    "in the first 12, since the phone shows two lines before \"Tap to read it all\".",
            ),
        )
        assertTrue(
            "the do-it-first rule is gone",
            fundi.contains("If something must be done first (scratch, press, wipe), say it in the first sentence, then what to photograph."),
        )
        val joint = "Fill the frame with the joint between the back rail and left leg. Hold the phone level with it."
        val scratch = "Scratch the seat's underside with a fingernail. Then photograph the scratch, close up."
        assertTrue(fundi.contains(joint))
        assertTrue(fundi.contains(scratch))
        // The plan block's own example follows the rule, since the model copies its shape.
        assertTrue("the plan example still carries the long instruction", fundi.contains("\"instruction\": \"$joint\""))
        assertFalse(fundi.contains("Get close enough that the joint where the back rail goes into the left leg"))
        listOf(joint, scratch).forEach { assertTrue("$it is over 20 words", it.split(' ').size <= 20) }
        // The phone's own words, so the rule names what the maker sees.
        assertTrue(File(repoRoot, "capture/src/main/java/com/qualityverifier/ui/capture/CaptureScreen.kt").readText().contains("\"Tap to read it all\""))
    }

    // Row 43 (Nathan, 23 November 2026): each improvement has a short line for its row, and a
    // page whose questions arrive named; a piece in progress is written about as finished.
    @Test
    fun `row 43 - the prompt writes row_line, answers a page question about its improvement only, and keeps the opening's rule`() {
        val fundi = DefaultPrompts.FUNDI_MASTER
        assertTrue(
            fundi.contains(
                "- row_line: one sentence, at most 15 words, cut from the finding's customer_line: " +
                    "the defect as the customer sees it, and what they do.",
            ),
        )
        assertTrue(fundi.contains("A rail that flexes makes customers think the sofa is weak, and offer less."))
        assertTrue("the diagnosis example has no row_line", fundi.contains("\"row_line\""))
        assertTrue(fundi.contains("title, fix_line, customer_line, row_line: as \"What the maker reads first\" says. Every finding has all four."))
        // The protocol the phone and the server write, word for word.
        val example = com.qualityverifier.text.ImprovementQuestion.of(3, "<title>", "").text.trimEnd()
        assertEquals("About improvement 3, '<title>':", example)
        assertTrue(fundi.contains("reaches you as \"About improvement N, '<title>': \" followed by the maker's own words"))
        assertTrue(
            fundi.contains(
                "When the maker's message starts \"About improvement N\", answer about that improvement only. " +
                    "If the question is about another part of the piece, say so in one sentence, then answer briefly.",
            ),
        )
        assertTrue(
            "customer_line no longer writes about the finished piece",
            fundi.contains(
                "On a piece still being made, write about the finished piece the customer will buy: what this defect " +
                    "becomes once the piece is done, and what it makes them think. A defect the finishing will hide, such as " +
                    "a crooked slat under the foam, reaches the customer only after use.",
            ),
        )
        // Prompt E's opening is unchanged: no list or count of the improvements.
        assertTrue(fundi.contains("no list or count of the improvements"))
        // Every prompt E field stays, so a v0.6.1 phone still draws its card and three buttons.
        listOf("\"fix_line\"", "\"customer_line\"", "\"tool_line\"").forEach { assertTrue(fundi.contains(it)) }
    }

    // tool_line may only name a tool the maker has. The opening message lists those under
    // the headings FundiLabels writes, so the tool_line rule has to use the same words. A
    // rental goes out marked "(borrowed)" since V35, and a borrowed tool did before it; the
    // prompt is fetched by every build, so the rule has to fit both.
    @Test
    fun `tool_line names only a tool from the list the opening message carries`() {
        val fundi = DefaultPrompts.FUNDI_MASTER
        val labels = FundiLabels.ENGLISH
        val rule = fundi.lineSequence().single { it.startsWith("- tool_line: optional. When a tool") }
        fun quoted(heading: String) = "\"${heading.removeSuffix(":")}\""
        assertTrue("tool_line may suggest a tool off the list", rule.contains("Suggest only a tool listed under ${quoted(labels.contextToolsHave)}"))
        assertTrue(
            "tool_line may suggest a tool the maker lacks",
            rule.contains("Never suggest a tool listed under ${quoted(labels.contextToolsNone)} or ${quoted(labels.contextToolsUnknown)}"),
        )
        assertTrue("tool_line may give a price", rule.contains("Never give a price"))
        assertTrue(
            "a rented tool is no longer recognised by its marker",
            rule.contains("A tool marked borrowed there was rented or borrowed: say they could rent or borrow it again"),
        )
        val profile = FundiProfile(tools = listOf(OwnedTool(ToolKind.SANDER, ToolOwnership.RENTAL)))
        assertTrue(
            "a rental no longer reaches Claude marked borrowed",
            buildFundiContextMessage(profile, labels).contains(labels.borrowed(labels.nameOf(ToolKind.SANDER))),
        )
    }

    @Test
    fun `every vocabulary the fundi master names is one the schema accepts`() {
        // The prompt is data and changes without a release. A value it invents reaches a
        // CHECK constraint and fails a turn the maker spent twenty minutes photographing.
        val fundi = DefaultPrompts.FUNDI_MASTER
        SkillDimension.entries.forEach {
            assertTrue("dimension ${it.id} is not offered to the prompt", fundi.contains(it.id))
        }
        MeasurementUnit.entries.forEach {
            assertTrue("unit ${it.id} is not offered to the prompt", fundi.contains(it.id))
        }
        // The tool kinds were offered for the fix plan's tool_to_buy, which went with the fix
        // plan in prompt E (Nathan, 18 November 2026). A kind the prompt names must still be
        // one the schema accepts, so tool_to_buy coming back brings this check back with it.
        if (fundi.contains("tool_to_buy")) {
            ToolKind.entries.forEach {
                assertTrue("tool ${it.id} is not offered to the prompt", fundi.contains(it.id))
            }
        }
        listOf("serious", "moderate", "minor", "cosmetic").forEach {
            assertTrue("severity $it is undocumented", fundi.contains(it))
        }
    }

    @Test
    fun `the fundi master says out loud that its coaching content is a draft`() {
        // It is machinery written by somebody who has never cut a mortise. The blocks and
        // the discipline are settled; the carpentry and the prices are not, and anybody
        // reading this file should know that before a fundi does.
        assertTrue(
            "the draft warning is gone",
            DefaultPrompts.FUNDI_MASTER.contains("DRAFT, v0.1"),
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

    // The fundi master once said the plan and the verdict used "the same schema the
    // buyer-facing assistant uses — refer to that prompt's definition". The model never
    // sees that prompt: the server sends the fundi master and the item protocol, nothing
    // else. So it guessed, and the first real plan came back with no shot titles and tests
    // with no answer options, which left the maker only the app's own "not sure" and
    // "cannot do this" buttons. Every block the app parses has to be defined in full here.
    @Test
    fun `the fundi master defines every block it asks for, itself`() {
        val fundi = DefaultPrompts.FUNDI_MASTER
        listOf("qv-plan", "fb-diagnosis", "qv-verdict", "qv-options").forEach { tag ->
            assertTrue("no fenced example of $tag", fundi.contains("```$tag"))
        }
        // The fields whose absence produced the reported symptoms.
        listOf("\"title\"", "\"note\"", "\"instruction\"", "\"options\"", "\"verdict\"")
            .forEach { field -> assertTrue("the schema no longer shows $field", fundi.contains(field)) }
        assertFalse(
            "the fundi master points at a prompt the model never receives",
            fundi.contains("buyer-facing") || fundi.contains("that prompt"),
        )
    }
}
