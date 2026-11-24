package com.qualityverifier.text

import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.PlanRun
import com.qualityverifier.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The English and Kiswahili switch (release scope row 29): the check a translation must pass
 * before the server stores it, and the conversation the phone draws from the stored ones.
 */
class ReplyTranslationTest {

    private val original = """
        The back rail moves. Put it right first.
        ```fb-diagnosis
        {"language":"en","findings":[
          {"title":"Glue the back rail to its posts","severity":"serious","area":"structural","dimension":"joint_fit",
           "fix_line":"Open the joint, glue it and clamp it for an hour.","customer_line":"A rail that flexes makes customers offer less.",
           "row_line":"A flexing rail makes customers offer less.","measured_value":3,"measured_unit":"mm",
           "where":{"photo":2,"x":0.4,"y":0.6}},
          {"title":"Sand the top smooth","severity":"minor","area":"surface","dimension":"finishing",
           "fix_line":"Sand the top with 180 grit.","customer_line":"A rough top looks unfinished.","row_line":"A rough top looks unfinished."}
        ],"one_check":""}
        ```
        ```qv-options
        Tell me more
        I have fixed it
        ```
    """.trimIndent()

    private val translated = """
        Reli ya nyuma inasogea. Irekebishe kwanza.
        ```fb-diagnosis
        {"language":"en","findings":[
          {"title":"Gundisha reli ya nyuma kwenye nguzo zake","severity":"serious","area":"structural","dimension":"joint_fit",
           "fix_line":"Fungua kiungo, weka gundi na ukibane kwa saa moja.","customer_line":"Reli inayopinda inawafanya wateja watoe bei ndogo.",
           "row_line":"Reli inayopinda inawafanya wateja watoe kidogo.","measured_value":3,"measured_unit":"mm",
           "where":{"photo":2,"x":0.4,"y":0.6}},
          {"title":"Piga msasa juu iwe laini","severity":"minor","area":"surface","dimension":"finishing",
           "fix_line":"Piga msasa juu kwa 180.","customer_line":"Juu korofi inaonekana haijakamilika.","row_line":"Juu korofi inaonekana haijakamilika."}
        ],"one_check":""}
        ```
        ```qv-options
        Niambie zaidi
        Nimerekebisha
        ```
    """.trimIndent()

    @Test
    fun `row 29 - a translation that changes only words passes the check`() {
        assertNull(ReplyTranslation.problem(original, translated))
    }

    @Test
    fun `row 29 - a translated block with a changed enum is refused`() {
        val changed = translated.replace("\"severity\":\"serious\"", "\"severity\":\"kubwa\"")
        assertNotNull(ReplyTranslation.problem(original, changed))
        val area = translated.replace("\"area\":\"surface\"", "\"area\":\"uso\"")
        assertNotNull(ReplyTranslation.problem(original, area))
        val language = translated.replace("\"language\":\"en\"", "\"language\":\"sw\"")
        assertNotNull(ReplyTranslation.problem(original, language))
    }

    @Test
    fun `row 29 - a translated block with a missing finding is refused`() {
        val start = translated.indexOf(",\n  {\"title\":\"Piga msasa")
        val end = translated.indexOf("\n],\"one_check\"")
        val missing = translated.removeRange(start, end)
        assertTrue(ReplyTranslation.problem(original, missing)!!.contains("findings") ||
            ReplyTranslation.problem(original, missing)!!.contains("number of items"))
    }

    @Test
    fun `row 29 - a changed number, mark, chip count or block is refused`() {
        assertNotNull(ReplyTranslation.problem(original, translated.replace("\"measured_value\":3", "\"measured_value\":4")))
        assertNotNull(ReplyTranslation.problem(original, translated.replace("\"x\":0.4", "\"x\":0.5")))
        assertNotNull(ReplyTranslation.problem(original, translated.replace("Nimerekebisha\n", "")))
        assertNotNull(ReplyTranslation.problem(original, translated.replace("```fb-diagnosis", "```fb-utambuzi")))
        assertNotNull("a key translated", ReplyTranslation.problem(original, translated.replace("\"fix_line\"", "\"rekebisha\"")))
        assertNotNull("the block broken", ReplyTranslation.problem(original, translated.replace("\"one_check\":\"\"}", "\"one_check\":\"\"")))
        assertNotNull("emptied", ReplyTranslation.problem(original, "  "))
    }

    @Test
    fun `row 29 - the translator is sent the reply between tags, and its answer is read back without them`() {
        val request = ReplyTranslation.request(original, AssessmentLanguage.SWAHILI)
        assertTrue(request.startsWith("Translate this reply into Kiswahili.\n\n<reply>\n"))
        assertTrue(request.endsWith("$original\n</reply>"))
        assertEquals(translated, ReplyTranslation.answerOf("<reply>\n$translated\n</reply>\n"))
        assertEquals(translated, ReplyTranslation.answerOf(translated))
    }

    @Test
    fun `row 29 - with no translations the conversation is the same list`() {
        val messages = listOf(ChatMessage("a1", Role.ASSISTANT, original))
        assertSame(messages, translatedConversation(messages, emptyMap()).messages)
    }

    @Test
    fun `row 29 - a translated reply is shown in its original's place, with every page and detail found by its shown title`() {
        val title = "Glue the back rail to its posts"
        val shownTitle = "Gundisha reli ya nyuma kwenye nguzo zake"
        val messages = listOf(
            ChatMessage("u1", Role.USER, "I have finished this sofa.", composed = true),
            ChatMessage("a1", Role.ASSISTANT, original),
            ChatMessage("u2", Role.USER, DetailKind.CAUSE.request(title), composed = true),
            ChatMessage("a2", Role.ASSISTANT, "The glue was spread thin."),
            ChatMessage("u3", Role.USER, ImprovementQuestion.of(1, title, "Can I use screws?").text),
            ChatMessage("a3", Role.ASSISTANT, "Screws alone will loosen."),
            ChatMessage("u4", Role.USER, "Thank you"),
        )
        val view = translatedConversation(
            messages,
            mapOf("a1" to translated, "a2" to "Gundi ilienezwa kidogo.", "a3" to "Skrubu peke yake zitalegea."),
        )
        val shown = view.messages
        assertEquals(messages.map { it.id }, shown.map { it.id })
        assertEquals(translated, shown[1].text)
        // The maker's own words stay as typed; the app's turns name the shown title.
        assertEquals("Thank you", shown[6].text)
        assertEquals("I have finished this sofa.", shown[0].text)
        assertEquals(DetailKind.CAUSE.request(shownTitle), shown[2].text)
        assertEquals(ImprovementQuestion.of(1, shownTitle, "Can I use screws?").text, shown[4].text)

        // What the screen pairs by title finds each answer under the translated improvement.
        val details = detailAnswersOf(shown)
        assertEquals("Gundi ilienezwa kidogo.", details.answers[DetailKey(DetailKind.CAUSE, shownTitle)])
        val rows = improvementRows(parseAssistantContent(shown[1].text).diagnosis!!, pageQuestionsOf(shown))
        assertEquals(1, rows[0].questions)
        assertEquals("Skrubu peke yake zitalegea.", pageQuestionsOf(shown).byImprovement[rows[0].key]!!.single().answer)

        // A tap on the shown improvement asks about the one Claude wrote, found by its place.
        assertEquals(title, view.originalTitle("a1", 1))
        assertEquals("Sand the top smooth", view.originalTitle("a1", 2))
        assertNull(view.originalTitle("a1", 3))
        assertNull(view.originalTitle("a2", 1))
    }

    @Test
    fun `row 29 - a tap on an improvement asks about the one at its place, even when two titles translate alike`() {
        // Both improvements came back with one title: a map from shown title to original would
        // send a tap on the second to the first.
        val alike = translated
            .replace("Gundisha reli ya nyuma kwenye nguzo zake", "Rekebisha kiungo")
            .replace("Piga msasa juu iwe laini", "Rekebisha kiungo")
        val messages = listOf(ChatMessage("u1", Role.USER, "Evaluate", composed = true), ChatMessage("a1", Role.ASSISTANT, original))
        val view = translatedConversation(messages, mapOf("a1" to alike))
        val rows = improvementRows(parseAssistantContent(view.messages[1].text).diagnosis!!)
        assertEquals(rows[0].finding.title, rows[1].finding.title)
        assertEquals("Glue the back rail to its posts", view.originalTitle("a1", rows[0].number))
        assertEquals("Sand the top smooth", view.originalTitle("a1", rows[1].number))
        // The turn the page sends names the original.
        val asked = ImprovementQuestion.of(rows[1].number, view.originalTitle("a1", rows[1].number)!!, "Which grit?")
        assertEquals("About improvement 2, 'Sand the top smooth': Which grit?", asked.text)
    }

    @Test
    fun `row 29 - a tapped translated chip sends the original text`() {
        val chips = replyChips(original, translated)
        assertEquals(listOf("Niambie zaidi", "Nimerekebisha"), chips.map { it.shown })
        assertEquals("I have fixed it", chips[1].sends)
        assertEquals(listOf("Tell me more", "I have fixed it"), chips.map { it.sends })
        // Shown as written when the language shown is the original's, or the counts differ.
        assertEquals(listOf("Tell me more", "I have fixed it"), replyChips(original, original).map { it.shown })
        assertEquals(
            listOf("Tell me more", "I have fixed it"),
            replyChips(original, translated.replace("Nimerekebisha\n", "")).map { it.shown },
        )
        assertTrue(replyChips(null, null).isEmpty())
    }

    private val planReply = """
        Two things to collect.
        ```qv-plan
        {"summary":"One photo and one test.","language":"en",
         "photos":[{"title":"Leg joint, close","note":"Fill the frame","instruction":"Hold the phone 20 cm from the joint."}],
         "tests":[{"title":"Wobble test","subtitle":"","instruction":"Push the top corner.","diagram":"wobble",
                   "options":[{"label":"Solid","detail":"No movement"},{"label":"Moves a little","detail":"Under 5 mm"}]}]}
        ```
    """.trimIndent()

    private val planReplySw = """
        Mambo mawili ya kukusanya.
        ```qv-plan
        {"summary":"Picha moja na jaribio moja.","language":"en",
         "photos":[{"title":"Kiungo cha mguu, karibu","note":"Jaza fremu","instruction":"Shika simu sm 20 kutoka kwenye kiungo."}],
         "tests":[{"title":"Jaribio la kutikisa","subtitle":"","instruction":"Sukuma kona ya juu.","diagram":"wobble",
                   "options":[{"label":"Imara","detail":"Haisogei"},{"label":"Inasogea kidogo","detail":"Chini ya 5 mm"}]}]}
        ```
    """.trimIndent()

    @Test
    fun `row 29 - the plan's camera and test screens show the translation, and a tapped answer records the original option`() {
        assertNull("the fixture is a translation the server would keep", ReplyTranslation.problem(planReply, planReplySw))
        val plan = parseAssistantContent(planReply).plan!!
        val en = ReportLabels.forLanguage("en")
        val sw = ReportLabels.forLanguage("sw")

        // The maker taps the second button, which reads "Inasogea kidogo"; the run records the
        // option Claude wrote in that place.
        val tapped = PlanRun(plan, "a1").optionAnswer(0, 1)!!
        assertEquals("Moves a little", tapped)
        val run = PlanRun(plan, "a1", shots = mapOf(0 to "/p/1.jpg"), answers = mapOf(0 to tapped))

        val shown = translatedRun(run, planReplySw, en.notSure, sw.notSure)
        assertEquals("Shika simu sm 20 kutoka kwenye kiungo.", shown.plan.photos[0].instruction)
        assertEquals("Jaribio la kutikisa", shown.plan.tests[0].title)
        assertEquals(listOf("Imara", "Inasogea kidogo"), shown.plan.tests[0].options.map { it.label })
        assertEquals("wobble", shown.plan.tests[0].diagram)
        // The chosen button shows as chosen, in the language shown.
        assertEquals("Inasogea kidogo", shown.answerShown(0))
        assertEquals(run.shots, shown.shots)
        assertEquals(sw.notSure, translatedRun(run.copy(answers = mapOf(0 to en.notSure)), planReplySw, en.notSure, sw.notSure).answerShown(0))

        // What is submitted is the run as gathered, in Claude's words.
        val submitted = buildSubmissionText(run.plan, run.shots, run.answers, en)
        assertTrue(submitted, submitted.contains("- Wobble test: Moves a little"))
        assertTrue(submitted, submitted.contains("- Leg joint, close"))
        assertTrue(submitted, !submitted.contains("Inasogea") && !submitted.contains("Kiungo"))

        // A translation with a different shape is not drawn; the plan shows as written.
        val fewer = planReplySw.replace(""",{"label":"Inasogea kidogo","detail":"Chini ya 5 mm"}""", "")
        assertEquals(plan, translatedRun(run, fewer, en.notSure, sw.notSure).plan)
        assertEquals(plan, translatedRun(run, null, en.notSure, en.notSure).plan)
    }

    private val detailAnswer =
        "The glue was spread thin, so the joint opened.\n\n" +
            "Clamp it for 30 minutes before you sand it with 120 grit paper. Check the gap is under 1 mm."
    private val detailAnswerSw =
        "Gundi ilienezwa kidogo, kwa hiyo kiungo kilifunguka.\n\n" +
            "Kibane kwa dakika 30 kabla ya kukipiga msasa kwa karatasi ya 120. Hakikisha pengo ni chini ya 1 mm."

    @Test
    fun `row 29 - a prose-only reply translated faithfully passes`() {
        assertNull(ReplyTranslation.problem(detailAnswer, detailAnswerSw))
    }

    @Test
    fun `row 29 - a prose-only reply with a dropped or added paragraph is refused`() {
        // The opening paragraph holds no number, so the paragraph rule is what refuses this.
        val dropped = detailAnswerSw.substringAfter("\n\n")
        assertEquals("the prose had 2 paragraphs and has 1", ReplyTranslation.problem(detailAnswer, dropped))
        // A note about the translation, as its own paragraph.
        val added = "Hii ni tafsiri ya jibu.\n\n$detailAnswerSw"
        assertEquals("the prose had 2 paragraphs and has 3", ReplyTranslation.problem(detailAnswer, added))
    }

    @Test
    fun `row 29 - a changed number in prose or inside a written value is refused`() {
        assertNotNull(ReplyTranslation.problem(detailAnswer, detailAnswerSw.replace("120", "180")))
        assertNotNull(ReplyTranslation.problem(detailAnswer, detailAnswerSw.replace(" 30 ", " thelathini ")))
        // "180 grit" inside a finding's fix_line, a value the JSON check lets change.
        assertNotNull(ReplyTranslation.problem(original, translated.replace("Piga msasa juu kwa 180.", "Piga msasa juu kwa 120.")))
        assertNotNull(ReplyTranslation.problem(original, translated.replace("Piga msasa juu kwa 180.", "Piga msasa juu.")))
        assertNotNull(ReplyTranslation.problem(original, translated.replace("Irekebishe kwanza.", "Irekebishe kwanza ndani ya siku 2.")))
    }

    @Test
    fun `row 29 - a translation far shorter or longer than the original is refused`() {
        // The same paragraphs and numbers, most of the words gone.
        val cut = "Gundi.\n\nDakika 30, 120, 1 mm."
        assertTrue(ReplyTranslation.problem(detailAnswer, cut)!!.contains("times the original's length"))
        // Padded out with words the original never had.
        val padded = detailAnswerSw.replace(
            "Hakikisha",
            "Kumbuka kwamba kazi nzuri inahitaji uvumilivu na uangalifu mwingi sana kila siku. ".repeat(4) + "Hakikisha",
        )
        assertTrue(ReplyTranslation.problem(detailAnswer, padded)!!.contains("times the original's length"))
        // A short reply is not held to the ratio: "OK" to "Sawa" is twice as long and faithful.
        assertNull(ReplyTranslation.problem("OK.", "Sawa kabisa."))
    }

    @Test
    fun `row 29 - a reply is named by the SHA-256 of its text`() {
        assertEquals(64, ReplyTranslation.keyOf(original).length)
        assertEquals(ReplyTranslation.keyOf(original), ReplyTranslation.keyOf(original))
        assertTrue(ReplyTranslation.keyOf(original) != ReplyTranslation.keyOf("$original "))
    }

    @Test
    fun `row 29 - the Kiswahili screen wording changes no word the conversation carries`() {
        val sw = FundiLabels.SWAHILI
        val en = FundiLabels.ENGLISH
        assertEquals("sw", sw.code)
        assertSame(sw, FundiLabels.forLanguage("sw"))
        assertSame(en, FundiLabels.forLanguage("en"))
        assertSame(en, FundiLabels.forLanguage(null))
        // The opening turn's words are ENGLISH's in both sets.
        assertEquals(en.contextLanguage, sw.contextLanguage)
        assertEquals(en.saysEvaluate, sw.saysEvaluate)
        assertEquals(en.previousRecordIntro, sw.previousRecordIntro)
        // And the screen's are not.
        assertTrue(sw.replyHeading != en.replyHeading && sw.detailCause != en.detailCause)
    }
}
