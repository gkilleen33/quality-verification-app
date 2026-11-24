package com.qualityverifier.text

import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.ChatMessage
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

        // A tap on the shown improvement asks about the one Claude wrote.
        assertEquals(title, view.originalTitle(shownTitle))
        assertEquals("A title no reply wrote", view.originalTitle(" A title no reply wrote "))
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
