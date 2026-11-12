package com.qualityverifier.fundi.assess

import com.qualityverifier.domain.AssessmentPlan
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.PlanRun
import com.qualityverifier.domain.PlannedShot
import com.qualityverifier.domain.Role
import com.qualityverifier.fundi.ui.assess.assessmentPhotos
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.buildSubmissionText
import com.qualityverifier.text.parseAssistantContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the photo viewer swipes through, and what it writes under each photo.
 *
 * The order is the order the photographs were taken, because that is how the maker
 * remembers them. The captions are read back from the submission turn's text, so the
 * case that matters is a plan with a skipped shot: three photographs answering a
 * four-shot plan must carry the three titles that were photographed, not the first three.
 */
class AssessmentPhotosTest {

    private val labels = FundiLabels.ENGLISH
    private val reportLabels = ReportLabels.ENGLISH

    private val plan = AssessmentPlan(
        photos = listOf(
            PlannedShot(title = "Whole piece"),
            PlannedShot(title = "Leg joint, close"),
            PlannedShot(title = ""),
            PlannedShot(title = "Underside"),
        ),
    )

    private val planText = "```qv-plan\n" +
        """{"photos":[{"title":"Whole piece"},{"title":"Leg joint, close"},{"title":""},{"title":"Underside"}]}""" +
        "\n```"

    private fun user(id: String, vararg paths: String, composed: Boolean = true, text: String = "") =
        ChatMessage(
            id = id,
            role = Role.USER,
            text = text,
            attachments = paths.map { Attachment(id = "a-$it", path = it) },
            composed = composed,
        )

    private fun assistant(id: String, text: String) = ChatMessage(id = id, role = Role.ASSISTANT, text = text)

    @Test
    fun `photos come in the order they were taken, opening first`() {
        // The maker could not get under the piece: shot 4 was skipped.
        val submission = buildSubmissionText(
            plan,
            shots = mapOf(0 to "/p/1.jpg", 1 to "/p/2.jpg", 2 to "/p/3.jpg", 3 to null),
            answers = emptyMap(),
            labels = reportLabels,
        )
        val messages = listOf(
            user("m1", "/p/opening.jpg", text = "intake"),
            assistant("m2", planText),
            user("m3", "/p/1.jpg", "/p/2.jpg", "/p/3.jpg", text = submission),
            assistant("m4", "Verdict."),
        )
        val photos = assessmentPhotos(messages, run = null, FundiPurpose.EVALUATE, labels, reportLabels)

        assertEquals(listOf("/p/opening.jpg", "/p/1.jpg", "/p/2.jpg", "/p/3.jpg"), photos.map { it.path })
        assertEquals(
            listOf("The whole piece", "Whole piece", "Leg joint, close", "Shot 3 of 4"),
            photos.map { it.caption },
        )
    }

    @Test
    fun `a skipped shot in the middle shifts the captions, not the photos`() {
        val submission = buildSubmissionText(
            plan,
            shots = mapOf(0 to "/p/1.jpg", 1 to null, 2 to "/p/3.jpg", 3 to "/p/4.jpg"),
            answers = emptyMap(),
            labels = reportLabels,
        )
        // An intake turn whose photo was skipped: the opening turn is still the first one.
        val messages = listOf(
            user("m1", text = "intake"),
            assistant("m2", planText),
            user("m3", "/p/1.jpg", "/p/3.jpg", "/p/4.jpg", text = submission),
        )
        val photos = assessmentPhotos(messages, run = null, FundiPurpose.EVALUATE, labels, reportLabels)
        assertEquals(listOf("Whole piece", "Shot 3 of 4", "Underside"), photos.map { it.caption })
    }

    @Test
    fun `the plan being collected comes after the conversation, in plan order`() {
        val run = PlanRun(
            plan = plan,
            sourceMessageId = "m2",
            // Taken out of order after a retake; the plan's order still holds.
            shots = mapOf(1 to "/p/joint.jpg", 0 to "/p/whole.jpg", 2 to null),
        )
        val messages = listOf(user("m1", "/p/opening.jpg", text = "intake"), assistant("m2", planText))
        val photos = assessmentPhotos(messages, run, FundiPurpose.DIAGNOSE, labels, reportLabels)

        assertEquals(listOf("/p/opening.jpg", "/p/whole.jpg", "/p/joint.jpg"), photos.map { it.path })
        assertEquals(listOf("The problem", "Whole piece", "Leg joint, close"), photos.map { it.caption })
    }

    @Test
    fun `a submission that does not read against its plan keeps its photos and loses its captions`() {
        // Two photographs on a turn whose text says three were taken: whatever happened,
        // a wrong caption is worse than none.
        val submission = buildSubmissionText(
            plan,
            shots = mapOf(0 to "/p/1.jpg", 1 to "/p/2.jpg", 2 to "/p/3.jpg", 3 to null),
            answers = emptyMap(),
            labels = reportLabels,
        )
        val messages = listOf(
            user("m1", text = "intake"),
            assistant("m2", planText),
            user("m3", "/p/1.jpg", "/p/2.jpg", text = submission),
        )
        val photos = assessmentPhotos(messages, run = null, FundiPurpose.EVALUATE, labels, reportLabels)
        assertEquals(listOf("/p/1.jpg", "/p/2.jpg"), photos.map { it.path })
        assertTrue(photos.all { it.caption == null })
    }

    @Test
    fun `the opening photo is uncaptioned until the purpose is known`() {
        val photos = assessmentPhotos(
            listOf(user("m1", "/p/opening.jpg", text = "intake")),
            run = null,
            purpose = null,
            labels = labels,
            reportLabels = reportLabels,
        )
        assertEquals(1, photos.size)
        assertNull(photos.single().caption)
    }

    @Test
    fun `an opening turn without a photo contributes nothing`() {
        val photos = assessmentPhotos(
            listOf(user("m1", text = "intake"), assistant("m2", "A question?"), user("m3", composed = false, text = "Yes")),
            run = null,
            purpose = FundiPurpose.LEARN,
            labels = labels,
            reportLabels = reportLabels,
        )
        assertTrue(photos.isEmpty())
    }

    @Test
    fun `the plan text in the fixture parses as a plan`() {
        // Guards the other tests: a fixture that silently stopped parsing would make
        // every caption null and the uncaptioned test pass for the wrong reason.
        assertEquals(4, parseAssistantContent(planText).plan?.photos?.size)
    }
}
