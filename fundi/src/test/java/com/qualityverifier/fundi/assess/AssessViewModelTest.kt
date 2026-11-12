package com.qualityverifier.fundi.assess

import android.net.Uri
import com.qualityverifier.data.chat.ChatErrorKind
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.fundi.FundiProfiles
import com.qualityverifier.data.fundi.ProfileOutcome
import com.qualityverifier.data.location.LocationSource
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.data.session.SyncedMessage
import com.qualityverifier.data.session.SyncedSession
import com.qualityverifier.data.session.LocalTesterFeedback
import com.qualityverifier.data.session.PieceLabelChange
import com.qualityverifier.data.workers.PendingSessionSet
import com.qualityverifier.data.workers.ServerWorker
import com.qualityverifier.data.workers.WorkerChange
import com.qualityverifier.data.workers.WorkerRepository
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.SessionStart
import com.qualityverifier.domain.PriorIssue
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.LocationFix
import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.domain.Workshop
import com.qualityverifier.fundi.ui.assess.AssessViewModel
import com.qualityverifier.fundi.ui.assess.PhotoTarget
import com.qualityverifier.images.ImageQuality
import com.qualityverifier.images.UNREADABLE_PHOTO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The parts of an assessment that are decisions rather than layout.
 *
 * Two of them are worth the words. A plan must not be offered twice, because the second
 * offer asks a maker to retake photographs already attached to a sent turn. And the
 * maker's tool list has to reach the opening turn, because a fix built around clamps
 * nobody owns is the failure the coaching prompt names outright.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssessViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val plannedReply = """
        Here is what I need to see.
        ```qv-plan
        {"summary":"Six shots","photos":[{"title":"Whole piece","instruction":"Stand back"}],
         "tests":[]}
        ```
    """.trimIndent()

    private fun model(
        chat: ChatService = FakeChat(),
        sessions: FakeSessions = FakeSessions(),
        profiles: FundiProfiles = FakeProfiles(),
        pieceId: String = "s1",
        returning: Boolean = false,
        workers: FakeWorkers = FakeWorkers(),
        location: LocationSource = LocationSource.None,
        images: FakeImages = FakeImages(),
    ) = AssessViewModel(
        sessionId = "s1",
        itemType = ItemType.WOODEN_TABLE,
        pieceId = pieceId,
        returning = returning,
        sessions = sessions,
        chat = chat,
        images = images,
        profiles = profiles,
        workers = workers,
        location = location,
        io = dispatcher,
    )

    // ------------------------------------------------------------------ the location point

    private val fix = LocationFix(-1.2864, 36.8172, 12.0, 1_000L)
    private val intake = FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.EVALUATE)

    // Outdoors a fix can land before the intake is sent, when there is no session row to
    // write it into. Held until there is, and written before the opening turn is stored,
    // so that turn carries it to the server.
    @Test
    fun `a fix that arrives before the intake goes out with the opening turn`() = runTest {
        val sessions = FakeSessions()
        val gps = FakeLocation(available = true)
        val model = model(sessions = sessions, location = gps)
        advanceUntilIdle()
        gps.result.complete(fix)
        advanceUntilIdle()
        assertTrue("nothing is written before the row exists", sessions.locations.isEmpty())

        model.start(intake, "/tmp/whole.jpg")
        advanceUntilIdle()

        assertEquals(listOf(fix), sessions.locations)
        assertEquals("written before the opening turn", 0, sessions.turnsBeforeLocation.single())
    }

    // Indoors it often lands minutes later; the row exists by then, and the next turn
    // carries it.
    @Test
    fun `a fix that arrives after the intake is written once`() = runTest {
        val sessions = FakeSessions()
        val gps = FakeLocation(available = true)
        val model = model(sessions = sessions, location = gps)
        advanceUntilIdle()

        model.start(intake, "/tmp/whole.jpg")
        advanceUntilIdle()
        assertTrue(sessions.locations.isEmpty())

        gps.result.complete(fix)
        advanceUntilIdle()

        assertEquals(listOf(fix), sessions.locations)
        assertEquals("one fix per assessment", 1, gps.captures)
    }

    // Switched off, unanswered, no permission, or location services off: the source says
    // unavailable, and nothing is even started. No prompt, no point.
    @Test
    fun `nothing is captured when the source is unavailable`() = runTest {
        val sessions = FakeSessions()
        val gps = FakeLocation(available = false)
        val model = model(sessions = sessions, location = gps)
        advanceUntilIdle()

        model.start(intake, "/tmp/whole.jpg")
        advanceUntilIdle()

        assertEquals(0, gps.captures)
        assertTrue(sessions.locations.isEmpty())
    }

    // Reopening a finished assessment from Home must not record where it was read.
    @Test
    fun `reopening an assessment records no point`() = runTest {
        val sessions = FakeSessions().apply { exists = true }
        val gps = FakeLocation(available = true)
        model(sessions = sessions, location = gps)
        advanceUntilIdle()

        assertEquals(0, gps.captures)
    }

    // A re-assessment is a new session on the same piece, so it gets its own point.
    @Test
    fun `a re-assessment records its own point`() = runTest {
        val sessions = FakeSessions().apply { existingLabel = "Old stool" }
        val gps = FakeLocation(available = true)
        val model = model(sessions = sessions, pieceId = "p1", returning = true, location = gps)
        advanceUntilIdle()
        gps.result.complete(fix)

        model.start(FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.REEVALUATE), "/tmp/whole.jpg")
        advanceUntilIdle()

        assertEquals(1, gps.captures)
        assertEquals(listOf("s1" to fix), sessions.locationsBySession)
    }

    // No fix in the window is not an error the maker sees.
    @Test
    fun `no fix records nothing and the assessment carries on`() = runTest {
        val sessions = FakeSessions()
        val gps = FakeLocation(available = true)
        val model = model(sessions = sessions, location = gps)
        advanceUntilIdle()
        gps.result.complete(null)

        model.start(intake, "/tmp/whole.jpg")
        advanceUntilIdle()

        assertTrue(sessions.locations.isEmpty())
        assertEquals(1, sessions.userTurns.size)
        assertNull(model.error.value)
    }

    /** A GPS whose one fix the test hands over when it chooses. */
    private class FakeLocation(private val available: Boolean) : LocationSource {
        val result = CompletableDeferred<LocationFix?>()
        var captures = 0
        override val isAvailable: Boolean get() = available
        override suspend fun capture(): LocationFix? {
            captures++
            return result.await()
        }
    }

    // The name goes with the session row at creation, so the repository can decide
    // whether it names a new piece or renames an old one.
    @Test
    fun `the piece's name is stored with the session`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions).start(
            FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.EVALUATE),
            "/tmp/whole.jpg",
            pieceLabel = "Kiti cha Wanjirũ",
        )
        advanceUntilIdle()

        assertEquals("Kiti cha Wanjirũ", sessions.created.single().pieceLabel)
    }

    @Test
    fun `a skipped name passes no name, and the intake prefills the existing one`() = runTest {
        val sessions = FakeSessions().apply { existingLabel = "Old stool" }
        val model = model(sessions = sessions, pieceId = "p1", returning = true)
        advanceUntilIdle()

        assertEquals("Old stool", model.pieceLabel.value)

        model.start(FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.REEVALUATE), "/tmp/whole.jpg")
        advanceUntilIdle()

        assertNull(sessions.created.single().pieceLabel)
    }

    // Who worked on the piece is recorded with the session, as already sent: the opening
    // turn carries the set, and the server writes it when it creates the session.
    @Test
    fun `who worked on it is recorded with the session`() = runTest {
        val workers = FakeWorkers()
        val kamau = WorkerSlot("w1", "Kamau", WorkPart.JOINERY)

        model(workers = workers).start(
            FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.EVALUATE),
            "/tmp/whole.jpg",
            workedOn = listOf(kamau, WorkerSlot(null)),
        )
        advanceUntilIdle()

        assertEquals(listOf(kamau, WorkerSlot(null)), workers.recorded["s1"])
    }

    // On a returning piece the picker starts from last time's people: the same hands
    // usually do the same parts, and the manager changes only what differs.
    @Test
    fun `a returning piece starts the picker from the previous assessment's people`() = runTest {
        val earlier = SessionSummary(
            id = "s0", itemType = ItemType.WOODEN_TABLE, createdAt = 1L, updatedAt = 2L,
            preview = "", messageCount = 3, pieceId = "s0",
        )
        val workers = FakeWorkers().apply { recorded["s0"] = listOf(WorkerSlot("w1", null, WorkPart.FRAME)) }
        val model = model(
            sessions = FakeSessions(earlier = listOf(earlier to emptyList<String>())),
            pieceId = "s0",
            returning = true,
            workers = workers,
        )
        advanceUntilIdle()

        assertEquals(listOf(WorkerSlot("w1", null, WorkPart.FRAME)), model.previousWorkers.value)
    }

    // The tool list is the whole argument for collecting a profile. If it does not reach
    // the opening turn, the coaching is guessing and issue #41 cannot be answered.
    @Test
    fun `the maker's tools travel with the opening photo`() = runTest {
        val sessions = FakeSessions()
        val profiles = FakeProfiles(
            ProfileOutcome.Loaded(
                FundiProfile(
                    workshop = Workshop(makes = "stools"),
                    tools = listOf(
                        OwnedTool(ToolKind.CHISELS, ToolOwnership.OWNED),
                        OwnedTool(ToolKind.CLAMPS, ToolOwnership.NONE),
                    ),
                )
            )
        )

        model(sessions = sessions, profiles = profiles).start(FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.EVALUATE), "/tmp/whole.jpg")
        advanceUntilIdle()

        val opening = sessions.userTurns.single()
        assertTrue(opening.text, opening.text.contains("chisels"))
        // Named as absent, not merely left out. Only a stated "I have none" licenses the
        // fix plan to work around it.
        assertTrue(opening.text, opening.text.contains("Tools I do not have: clamps"))
        assertEquals(1, opening.attachments.size)
    }

    // Offline at the worst moment. Refusing to look at the piece would be the wrong
    // trade: tool-blind coaching is worse advice, not no advice.
    @Test
    fun `a profile that will not load does not block the assessment`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions, profiles = FakeProfiles(ProfileOutcome.Unavailable))
            .start(FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.EVALUATE), "/tmp/whole.jpg")
        advanceUntilIdle()

        assertEquals(1, sessions.userTurns.size)
        // Not empty: the language line goes out even with no profile behind it. Coaching
        // without the tool list is worse advice; coaching in a language the maker cannot
        // read is none at all.
        assertTrue(
            sessions.userTurns.single().text,
            sessions.userTurns.single().text.trimEnd().endsWith("Please answer me in English."),
        )
    }

    @Test
    fun `a plan in the reply becomes a run`() = runTest {
        val model = model()

        model.offerPlanFrom(ChatMessage("a1", Role.ASSISTANT, plannedReply))

        assertNotNull(model.run.value)
        assertEquals("a1", model.run.value?.sourceMessageId)
    }

    @Test
    fun `the maker's own turn is never a plan`() = runTest {
        val model = model()

        model.offerPlanFrom(ChatMessage("u1", Role.USER, plannedReply))

        assertNull(model.run.value)
    }

    // The rule that matters. Once a run is sent, its plan must never be offered again —
    // the photographs are already attached to a stored turn, and re-offering asks the
    // maker to take them a second time.
    @Test
    fun `a plan already answered is not offered again`() = runTest {
        val model = model()
        val reply = ChatMessage("a1", Role.ASSISTANT, plannedReply)

        model.offerPlanFrom(reply)
        model.attachShot(0, "/tmp/shot0.jpg")
        model.submitRun()
        advanceUntilIdle()
        assertNull("the run is cleared once sent", model.run.value)

        model.offerPlanFrom(reply)

        assertNull("and must not come back", model.run.value)
    }

    // Marked answered before the request rather than after, so a send that fails does
    // not re-offer a plan whose photographs are already on a stored turn.
    @Test
    fun `a failed send still does not re-offer the plan`() = runTest {
        val model = model(chat = FakeChat(ChatResult.Failure(ChatErrorKind.NETWORK, "offline")))
        val reply = ChatMessage("a1", Role.ASSISTANT, plannedReply)

        model.offerPlanFrom(reply)
        model.submitRun()
        advanceUntilIdle()

        assertNotNull("the maker is told", model.error.value)
        model.offerPlanFrom(reply)
        assertNull(model.run.value)
    }

    // Skipping is an answer. A shot recorded as absent would let the model assume it
    // passed, which is the one failure the whole run flow exists to avoid.
    @Test
    fun `a skipped shot is recorded, not dropped`() = runTest {
        val model = model()
        model.offerPlanFrom(ChatMessage("a1", Role.ASSISTANT, plannedReply))

        model.skipShot(0)

        val run = model.run.value!!
        assertTrue("the key is present", run.shots.containsKey(0))
        assertNull("with no photo behind it", run.shots[0])
        assertTrue("so the run counts as complete", run.photosDone)
    }

    @Test
    fun `an empty turn is never sent`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions).send("   ")
        advanceUntilIdle()

        assertTrue(sessions.userTurns.isEmpty())
    }

    // ------------------------------------------------------------------ photographs

    private val dark = ImageQuality(sharpness = 140.0, brightness = 10.0)

    // The upload hashes the stored file, so the file must already be the processed one
    // when it is attached; otherwise the SHA-256 names the camera's original.
    @Test
    fun `a plan shot is processed before it is attached`() = runTest {
        val images = FakeImages()
        val model = model(images = images)
        model.offerPlanFrom(ChatMessage("a1", Role.ASSISTANT, plannedReply))
        val file = images.newImageFile("s1")

        model.onPhotoCaptured(file, PhotoTarget.PlanShot(0))
        advanceUntilIdle()

        assertEquals(listOf(file), images.normalised)
        assertEquals(file.absolutePath, model.run.value?.shots?.get(0))
        assertNull("a usable photograph must not stop the shot list", model.review.value)
    }

    // The warning Kagua shows, from the same check: held back, and the maker decides.
    @Test
    fun `a dark plan shot is held for the maker to look at, and keeping it attaches it`() = runTest {
        val images = FakeImages(quality = dark)
        val model = model(images = images)
        model.offerPlanFrom(ChatMessage("a1", Role.ASSISTANT, plannedReply))
        val file = images.newImageFile("s1")

        model.onPhotoCaptured(file, PhotoTarget.PlanShot(0))
        advanceUntilIdle()

        val review = model.review.value
        assertNotNull("a dark photograph should be held back", review)
        assertEquals(dark.warning, review!!.warning)
        assertFalse("not attached while under review", model.run.value!!.shots.containsKey(0))

        model.keepReviewedPhoto()

        assertNull(model.review.value)
        assertEquals(file.absolutePath, model.run.value?.shots?.get(0))
    }

    @Test
    fun `retaking a held shot deletes it and attaches nothing`() = runTest {
        val images = FakeImages(quality = dark)
        val model = model(images = images)
        model.offerPlanFrom(ChatMessage("a1", Role.ASSISTANT, plannedReply))
        val file = images.newImageFile("s1")
        model.onPhotoCaptured(file, PhotoTarget.PlanShot(0))
        advanceUntilIdle()

        model.discardReviewedPhoto()
        advanceUntilIdle()

        assertNull(model.review.value)
        assertFalse(model.run.value!!.shots.containsKey(0))
        assertEquals(listOf(file), images.deleted)
    }

    // Never sent as the camera wrote it: a file that will not process is dropped.
    @Test
    fun `a photograph that cannot be read is dropped with an explanation`() = runTest {
        val images = FakeImages(normalises = false)
        val model = model(images = images)
        model.offerPlanFrom(ChatMessage("a1", Role.ASSISTANT, plannedReply))
        val file = images.newImageFile("s1")

        model.onPhotoCaptured(file, PhotoTarget.PlanShot(0))
        advanceUntilIdle()

        assertFalse(model.run.value!!.shots.containsKey(0))
        assertEquals(listOf(file), images.deleted)
        assertEquals(UNREADABLE_PHOTO, model.error.value?.message)
    }

    // The intake waits for the opening photograph to be kept: a dark one is shown back
    // first, and nothing reaches the model until the maker answers.
    @Test
    fun `a dark opening photograph holds the intake until it is kept`() = runTest {
        val sessions = FakeSessions()
        val images = FakeImages(quality = dark)
        val model = model(sessions = sessions, images = images)
        val file = images.newImageFile("s1")

        model.onPhotoCaptured(file, PhotoTarget.Opening(intake, "Stool", emptyList()))
        advanceUntilIdle()

        assertNotNull(model.review.value)
        assertTrue("nothing sent while under review", sessions.userTurns.isEmpty())
        assertFalse(model.openingSent.value)

        model.keepReviewedPhoto()
        advanceUntilIdle()

        assertTrue(model.openingSent.value)
        val opening = sessions.userTurns.single()
        assertEquals(listOf(file.absolutePath), opening.attachments.map { it.path })
        assertEquals("Stool", sessions.created.single().pieceLabel)
    }

    // Processing takes a moment on a cheap phone. A maker who backs out to the intake in
    // that moment has not sent it, and the photograph is dropped.
    @Test
    fun `leaving the camera while a photograph is processed sends nothing`() = runTest {
        val sessions = FakeSessions()
        val images = FakeImages()
        val model = model(sessions = sessions, images = images)
        advanceUntilIdle()
        val file = images.newImageFile("s1")

        model.onPhotoCaptured(file, PhotoTarget.Opening(intake, null, emptyList()))
        model.leaveCamera()
        advanceUntilIdle()

        assertTrue(sessions.userTurns.isEmpty())
        assertFalse(model.openingSent.value)
        assertEquals(listOf(file), images.deleted)
    }

    @Test
    fun `a usable opening photograph is processed and sent with the intake`() = runTest {
        val sessions = FakeSessions()
        val images = FakeImages()
        val model = model(sessions = sessions, images = images)
        val file = images.newImageFile("s1")

        model.onPhotoCaptured(file, PhotoTarget.Opening(intake, null, emptyList()))
        advanceUntilIdle()

        assertEquals(listOf(file), images.normalised)
        assertTrue(model.openingSent.value)
        assertEquals(listOf(file.absolutePath), sessions.userTurns.single().attachments.map { it.path })
    }

    // The camera frees its shutter once the file is saved, before processing ends. A
    // second tap in that moment must not start a second opening photograph, which would
    // send the intake twice or come up for review on a later shot.
    @Test
    fun `a second photograph taken while the first is processed is deleted, not kept`() = runTest {
        val sessions = FakeSessions()
        val images = FakeImages()
        val model = model(sessions = sessions, images = images)
        advanceUntilIdle()
        val first = images.newImageFile("s1")
        val second = images.newImageFile("s1")

        model.onPhotoCaptured(first, PhotoTarget.Opening(intake, null, emptyList()))
        assertTrue("the shutter waits", model.processing.value)
        model.onPhotoCaptured(second, PhotoTarget.Opening(intake, null, emptyList()))
        advanceUntilIdle()

        assertEquals(listOf(first), images.normalised)
        assertEquals(listOf(second), images.deleted)
        assertEquals(listOf(first.absolutePath), sessions.userTurns.single().attachments.map { it.path })
        assertNull(model.review.value)
        assertFalse("and is freed once the first is done", model.processing.value)
    }

    // An opening photograph held for review while the intake went out another way has
    // nothing to go with. Kept anyway, it would start the assessment again mid-plan.
    @Test
    fun `keeping an opening photograph after the intake is out sends nothing`() = runTest {
        val sessions = FakeSessions()
        val images = FakeImages()
        val model = model(sessions = sessions, images = images)
        advanceUntilIdle()
        model.onPhotoCaptured(images.newImageFile("s1"), PhotoTarget.Opening(intake, null, emptyList()))
        advanceUntilIdle()
        assertTrue(model.openingSent.value)

        images.quality = dark
        val late = images.newImageFile("s1")
        model.onPhotoCaptured(late, PhotoTarget.Opening(intake, null, emptyList()))
        advanceUntilIdle()
        assertNotNull(model.review.value)

        model.keepReviewedPhoto()
        advanceUntilIdle()

        assertEquals("only the first opening turn", 1, sessions.userTurns.size)
        assertEquals(listOf(late), images.deleted)
        assertNull(model.review.value)
    }

    // ------------------------------------------------------------------ the intake

    // The rule Kagua keeps and this one must: nothing reaches the model until the intake
    // is whole. A diagnose with no description is half a question.
    @Test
    fun `an incomplete intake sends nothing`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions).start(
            FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.DIAGNOSE),
            "/tmp/problem.jpg",
        )
        advanceUntilIdle()

        assertTrue(sessions.userTurns.isEmpty())
        assertTrue(sessions.created.isEmpty())
    }

    @Test
    fun `the opening turn leads with the purpose and is marked as the app's own`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions).start(
            FundiIntake(AssessmentLanguage.SWAHILI, FundiPurpose.DIAGNOSE, details = "loose leg"),
            "/tmp/problem.jpg",
        )
        advanceUntilIdle()

        val opening = sessions.userTurns.single()
        assertTrue(opening.text, opening.text.startsWith("I want to diagnose and fix"))
        assertTrue(opening.text, opening.text.trimEnd().endsWith("Tafadhali nijibu kwa Kiswahili."))
        // Shown compactly, not word for word — the first build printed its own prompt.
        assertTrue("the opening turn is the app's, not the maker's", opening.composed)
    }

    // A first assessment names its own piece; the stored code is what reopening reads.
    @Test
    fun `a new assessment is its own piece, and the intake is stored`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions).start(
            FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.EVALUATE),
            "/tmp/whole.jpg",
        )
        advanceUntilIdle()

        val created = sessions.created.single()
        assertEquals("s1", created.pieceId)
        assertEquals("fundi-en-evaluate", created.intakeCode)
    }

    // The view model outlives the screen. A rebuilt screen can walk the maker through the
    // intake again; a second start must not put a second opening turn in the session.
    @Test
    fun `the intake is sent once, however often it is started`() = runTest {
        val sessions = FakeSessions()
        val model = model(sessions = sessions)

        model.start(intake, "/tmp/whole.jpg")
        advanceUntilIdle()
        model.start(intake, null)
        advanceUntilIdle()

        assertTrue(model.openingSent.value)
        assertEquals(1, sessions.userTurns.size)
        assertEquals(1, sessions.created.size)
    }

    // Skipped is sent too: the screen leaves the camera on the same flag.
    @Test
    fun `an intake sent without a photograph also counts as sent`() = runTest {
        val model = model()

        model.start(FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.LEARN, details = "dovetails"), null)

        assertTrue(model.openingSent.value)
    }

    // Learning a skill may come before anything has been cut.
    @Test
    fun `an intake with no photo still sends`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions).start(
            FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.LEARN, details = "dovetails"),
            photoPath = null,
        )
        advanceUntilIdle()

        assertEquals(1, sessions.userTurns.size)
        assertTrue(sessions.userTurns.single().attachments.isEmpty())
    }

    // A re-assessment is a new session on the same piece. Its findings to check come from
    // that piece's earlier assessments — and only that piece's.
    @Test
    fun `checking a fix offers the piece's own earlier findings, and nobody else's`() = runTest {
        val diagnosis = """
            ```fb-diagnosis
            {"findings": [{"title": "Gapping joint", "what_happened": "3mm at the back"}]}
            ```
        """.trimIndent()
        val otherPiece = """
            ```fb-diagnosis
            {"findings": [{"title": "Cupped top"}]}
            ```
        """.trimIndent()
        val sessions = FakeSessions(
            earlier = listOf(
                summary(id = "first", piece = "stool") to listOf(diagnosis),
                summary(id = "elsewhere", piece = "table") to listOf(otherPiece),
            ),
        )

        val model = model(sessions = sessions, pieceId = "stool", returning = true)
        advanceUntilIdle()

        assertEquals(
            listOf(PriorIssue("Gapping joint", "3mm at the back")),
            model.priorIssues.value,
        )
    }

    // An assessment made before pieces existed has no piece id, and counts as its own.
    @Test
    fun `an old assessment with no piece id is its own piece`() = runTest {
        val legacy = """
            ```fb-diagnosis
            {"findings": [{"title": "Rough edge"}]}
            ```
        """.trimIndent()
        val sessions = FakeSessions(
            earlier = listOf(summary(id = "legacy", piece = null) to listOf(legacy)),
        )

        val model = model(sessions = sessions, pieceId = "legacy", returning = true)
        advanceUntilIdle()

        assertEquals(listOf("Rough edge"), model.priorIssues.value.map { it.title })
    }

    @Test
    fun `a plan submission is marked as the app's own`() = runTest {
        val sessions = FakeSessions()
        val model = model(sessions = sessions)

        model.offerPlanFrom(ChatMessage("a1", Role.ASSISTANT, plannedReply))
        model.attachShot(0, "/tmp/shot0.jpg")
        model.submitRun()
        advanceUntilIdle()

        assertTrue(sessions.userTurns.single().composed)
    }

    @Test
    fun `a reply the maker types is not`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions).send("It was cut freehand")
        advanceUntilIdle()

        assertFalse(sessions.userTurns.single().composed)
    }

    // ------------------------------------------------------------------ the turn with no reply

    private val offline = ChatResult.Failure(ChatErrorKind.NETWORK, "offline")

    // The case D4 is for: the network drops while the intake and first photograph are on
    // their way. Retry must deliver that turn, under the id the server may already hold,
    // and not a rebuilt one.
    @Test
    fun `Retry resends a failed opening turn with its id, intake and photograph`() = runTest {
        val sessions = FakeSessions()
        val chat = FakeChat(offline, ChatResult.Success(plannedReply))
        val model = model(chat = chat, sessions = sessions)

        model.start(intake, "/tmp/whole.jpg")
        advanceUntilIdle()
        assertEquals(true, model.error.value?.retryable)

        model.retry()
        advanceUntilIdle()

        assertEquals(2, chat.sent.size)
        val (first, again) = chat.sent
        assertEquals("the same turn: id, text, photograph and all", first, again)
        assertTrue("the intake", again.text.startsWith("I have finished this piece"))
        assertEquals(listOf("/tmp/whole.jpg"), again.attachments.map { it.path })
        assertEquals("stored once", 1, sessions.userTurns.size)
        assertEquals(Role.ASSISTANT, sessions.messagesOnce("s1").last().role)
    }

    // A plan's photographs are on the stored turn already. Retry sends that turn; it does
    // not ask for them again or store a second copy.
    @Test
    fun `Retry resends a failed run submission with its photographs`() = runTest {
        val sessions = FakeSessions()
        val chat = FakeChat(offline, ChatResult.Success("ok"))
        val model = model(chat = chat, sessions = sessions)
        model.offerPlanFrom(ChatMessage("a1", Role.ASSISTANT, plannedReply))
        model.attachShot(0, "/tmp/shot0.jpg")

        model.submitRun()
        advanceUntilIdle()
        model.retry()
        advanceUntilIdle()

        val (first, again) = chat.sent
        assertEquals(first, again)
        assertEquals(listOf("/tmp/shot0.jpg"), again.attachments.map { it.path })
        assertEquals(1, sessions.userTurns.size)
        assertNull("and the plan is not offered again", model.run.value)
    }

    @Test
    fun `a Retry that lands clears the error and the offer`() = runTest {
        val sessions = FakeSessions()
        val model = model(chat = FakeChat(offline, ChatResult.Success("ok")), sessions = sessions)
        backgroundScope.launch { model.unansweredTurn.collect { } }

        model.send("It was cut freehand")
        advanceUntilIdle()
        assertNotNull(model.error.value)
        assertTrue(model.unansweredTurn.value)

        model.retry()
        advanceUntilIdle()

        assertNull(model.error.value)
        assertFalse(model.unansweredTurn.value)
    }

    // The opening turn is resent, not started again. The row, the people and the point
    // were written the first time, and start() stays once per assessment.
    @Test
    fun `Retry of the opening turn writes no second session or location point`() = runTest {
        val sessions = FakeSessions()
        val gps = FakeLocation(available = true)
        val chat = FakeChat(offline, ChatResult.Success("ok"))
        val model = model(chat = chat, sessions = sessions, location = gps)
        advanceUntilIdle()
        gps.result.complete(fix)
        advanceUntilIdle()

        model.start(intake, "/tmp/whole.jpg")
        advanceUntilIdle()
        model.retry()
        advanceUntilIdle()
        model.start(intake, "/tmp/whole.jpg")
        advanceUntilIdle()

        assertEquals(1, sessions.created.size)
        assertEquals(listOf(fix), sessions.locations)
        assertEquals(1, gps.captures)
        assertEquals(1, sessions.userTurns.size)
        assertEquals(2, chat.sent.size)
    }

    // Closed mid-turn and reopened from Home: the error went with the old view model, and
    // the stored conversation is what says the turn has no reply.
    @Test
    fun `reopening on an unanswered turn offers Send again, which uses the same id`() = runTest {
        val opening = ChatMessage(
            "u-open", Role.USER, "I want to check my work",
            listOf(Attachment("p1", "/tmp/whole.jpg")), composed = true,
        )
        val sessions = FakeSessions().apply {
            exists = true
            seed(opening)
        }
        val chat = FakeChat()
        val model = model(chat = chat, sessions = sessions)
        backgroundScope.launch { model.unansweredTurn.collect { } }
        advanceUntilIdle()

        assertTrue(model.unansweredTurn.value)
        assertTrue("offered, never sent unasked", chat.sent.isEmpty())

        model.retry()
        advanceUntilIdle()

        assertEquals(opening, chat.sent.single())
        assertFalse(model.unansweredTurn.value)
        assertTrue("nothing new stored for the maker", sessions.userTurns.isEmpty())
    }

    @Test
    fun `a conversation that ends with a reply offers nothing, and Retry sends nothing`() = runTest {
        val sessions = FakeSessions().apply {
            exists = true
            seed(ChatMessage("u1", Role.USER, "loose leg"), ChatMessage("a1", Role.ASSISTANT, "ok"))
        }
        val chat = FakeChat()
        val model = model(chat = chat, sessions = sessions)
        backgroundScope.launch { model.unansweredTurn.collect { } }
        advanceUntilIdle()

        assertFalse(model.unansweredTurn.value)
        model.retry()
        advanceUntilIdle()

        assertTrue(chat.sent.isEmpty())
        assertEquals("the reply is not stored twice", 2, sessions.messagesOnce("s1").size)
    }

    // Reopened on a run submission that never got its reply, the plan before it has been
    // answered. Offered again, it would ask for photographs already on the stored turn.
    @Test
    fun `a plan with a turn after it is not offered again on reopening`() = runTest {
        val model = model()
        val plan = ChatMessage("a1", Role.ASSISTANT, plannedReply)
        val submission = ChatMessage(
            "u2", Role.USER, "shots", listOf(Attachment("p1", "/tmp/shot0.jpg")), composed = true,
        )

        model.offerPlanFromLatest(listOf(plan, submission))
        assertNull(model.run.value)

        model.offerPlanFromLatest(listOf(plan))
        assertEquals("a1", model.run.value?.sourceMessageId)
    }

    // Sending the same turn again cannot fix a sign-in, so the row offers no Retry.
    @Test
    fun `a sign-in failure is not offered for Retry`() = runTest {
        val model = model(chat = FakeChat(ChatResult.Failure(ChatErrorKind.AUTH, "Please sign in again.")))

        model.send("loose leg")
        advanceUntilIdle()

        assertEquals(false, model.error.value?.retryable)
    }

    private fun summary(id: String, piece: String?) = SessionSummary(
        id = id,
        itemType = ItemType.WOODEN_STOOL,
        createdAt = 0L,
        updatedAt = 0L,
        preview = "",
        messageCount = 2,
        pieceId = piece,
    )

    // ------------------------------------------------------------------ fakes

    private class Turn(
        val text: String,
        val attachments: List<Attachment>,
        val composed: Boolean,
    )

    private class Created(
        val sessionId: String,
        val pieceId: String?,
        val intakeCode: String?,
        val pieceLabel: String?,
    )

    /**
     * Only the handful of methods an assessment touches. The rest of SessionRepository
     * is sync and housekeeping, and a fake that pretended to implement them would invite
     * a test to lean on behaviour nothing here has.
     *
     * [earlier] seeds other assessments on the handset, for the re-assessment tests:
     * each is a summary and the assistant turns its conversation holds.
     */
    private class FakeSessions(
        private val earlier: List<Pair<SessionSummary, List<String>>> = emptyList(),
    ) : SessionRepository {
        val userTurns = mutableListOf<Turn>()
        val created = mutableListOf<Created>()
        private val stream = MutableStateFlow<List<ChatMessage>>(emptyList())

        /** A conversation already on the handset, as when an assessment is reopened. */
        fun seed(vararg messages: ChatMessage) {
            stream.value = messages.toList()
        }

        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> = stream
        override suspend fun messagesOnce(sessionId: String): List<ChatMessage> =
            earlier.firstOrNull { it.first.id == sessionId }?.second
                ?.mapIndexed { i, text -> ChatMessage("$sessionId-$i", Role.ASSISTANT, text) }
                ?: stream.value
        override fun observeSummaries(): Flow<List<SessionSummary>> =
            MutableStateFlow(earlier.map { it.first })
        override suspend fun startOf(sessionId: String): SessionStart? = null
        override suspend fun createSession(
            sessionId: String,
            itemType: ItemType,
            previousSessionId: String?,
            intake: AssessmentContext?,
            pieceId: String?,
            intakeCode: String?,
            pieceLabel: String?,
        ) {
            created += Created(sessionId, pieceId, intakeCode, pieceLabel)
        }

        /** The name this piece already has, for the prefilled intake. */
        var existingLabel: String? = null
        override suspend fun pieceLabel(pieceId: String): String? = existingLabel
        override suspend fun setPieceLabel(pieceId: String, label: String?) = no()
        override suspend fun unsyncedPieceLabels() = no()
        override suspend fun markPieceLabelSynced(change: PieceLabelChange) = no()
        /** Whether the assessment is already on the handset, as when reopened from Home. */
        var exists = false
        override suspend fun sessionExists(sessionId: String) = exists
        override suspend fun appendUserMessage(
            sessionId: String,
            text: String,
            attachments: List<Attachment>,
            composed: Boolean,
        ): ChatMessage {
            userTurns += Turn(text, attachments, composed)
            val message = ChatMessage(
                "u${userTurns.size}", Role.USER, text, attachments, composed = composed,
            )
            stream.value = stream.value + message
            return message
        }
        override suspend fun appendAssistantMessage(sessionId: String, text: String): ChatMessage {
            val message = ChatMessage("a${stream.value.size}", Role.ASSISTANT, text)
            stream.value = stream.value + message
            return message
        }

        private fun no(): Nothing = error("an assessment does not touch this")

        val locationsBySession = mutableListOf<Pair<String, LocationFix>>()
        val locations: List<LocationFix> get() = locationsBySession.map { it.second }
        /** How many user turns were stored when each point was written. */
        val turnsBeforeLocation = mutableListOf<Int>()
        override suspend fun recordLocation(sessionId: String, fix: LocationFix) {
            locationsBySession += sessionId to fix
            turnsBeforeLocation += userTurns.size
        }
        override suspend fun deleteMessage(messageId: String) = no()
        override suspend fun deleteSession(sessionId: String) = no()
        override suspend fun pruneOrphanImages() = no()
        override suspend fun knownSessions() = no()
        override suspend fun writeSynced(
            session: SyncedSession,
            messages: List<SyncedMessage>,
        ) = no()
        override suspend fun pendingRemoteDeletes() = no()
        override suspend fun recordPendingRemoteDelete(sessionId: String) = no()
        override suspend fun clearPendingRemoteDelete(sessionId: String) = no()
        override suspend fun recordTesterFeedback(feedback: LocalTesterFeedback) = no()
        override suspend fun pendingTesterFeedback() = no()
        override suspend fun hasPendingTesterFeedback(sessionId: String) = no()
        override suspend fun clearTesterFeedback(sessionId: String) = no()
        override suspend fun dismissLocally(sessionId: String) = no()
        override suspend fun dismissedSessions() = no()
    }

    /** The roster and the per-session sets, as far as an assessment touches them. */
    private class FakeWorkers : WorkerRepository {
        val recorded = mutableMapOf<String, List<WorkerSlot>>()
        val added = mutableListOf<String>()
        override fun observeWorkers(): Flow<List<Worker>> = MutableStateFlow(emptyList())
        override suspend fun add(details: WorkerDetails): Worker {
            added += details.name
            return Worker("w${added.size}", details.name, archived = false)
        }
        override suspend fun workersOf(sessionId: String) = recorded[sessionId].orEmpty()
        override suspend fun recordWorkersOf(sessionId: String, workers: List<WorkerSlot>) {
            recorded[sessionId] = workers
        }

        private fun no(): Nothing = error("an assessment does not touch this")

        override suspend fun edit(workerId: String, details: WorkerDetails) = no()
        override suspend fun archive(workerId: String) = no()
        override suspend fun unsynced(): List<WorkerChange> = no()
        override suspend fun markSynced(change: WorkerChange) = no()
        override suspend fun mergeFromServer(workers: List<ServerWorker>) = no()
        override suspend fun setWorkersOf(sessionId: String, workers: List<WorkerSlot>) = no()
        override suspend fun pendingSessions(): List<PendingSessionSet> = no()
        override suspend fun clearPending(pending: PendingSessionSet) = no()
        override suspend fun applySynced(sessionId: String, workers: List<WorkerSlot>) = no()
    }

    /**
     * Answers with [results] in order, then "ok". Records the turn each request carried:
     * the newest user turn of the history, which is what `ServerChatService` puts on the
     * wire, its id as the `message_id`.
     */
    private class FakeChat(vararg results: ChatResult) : ChatService {
        private val queue = ArrayDeque(results.toList())
        val sent = mutableListOf<ChatMessage>()

        override suspend fun send(
            sessionId: String,
            itemType: ItemType,
            history: List<ChatMessage>,
        ): ChatResult {
            history.lastOrNull { it.role == Role.USER }?.let { sent += it }
            return queue.removeFirstOrNull() ?: ChatResult.Success("ok")
        }
    }

    private class FakeProfiles(
        private val outcome: ProfileOutcome = ProfileOutcome.NotSetUpYet,
    ) : FundiProfiles {
        override suspend fun load() = outcome
        override suspend fun save(profile: FundiProfile) = true
        override suspend fun saveWorkshopLocation(fix: com.qualityverifier.domain.LocationFix) = true
    }

    /**
     * A store backed by real temporary files, because the capture path checks that the
     * camera wrote something before processing it. Records what it processed and deleted.
     */
    private class FakeImages(
        private val normalises: Boolean = true,
        /** What the check reports; changed mid-test to make a later photograph dark. */
        var quality: ImageQuality? = null,
    ) : SessionImageStore {
        val normalised = mutableListOf<File>()
        val deleted = mutableListOf<File>()

        override fun newImageFile(sessionId: String): File =
            File.createTempFile("capture", ".jpg").apply {
                writeBytes(ByteArray(16))
                deleteOnExit()
            }
        override fun importFromUri(sessionId: String, uri: Uri): File? = null
        override fun normaliseInPlace(file: File): Boolean {
            if (normalises) normalised += file
            return normalises
        }
        override fun delete(file: File) {
            deleted += file
            file.delete()
        }
        override fun measureQuality(file: File): ImageQuality? = quality
    }
}
