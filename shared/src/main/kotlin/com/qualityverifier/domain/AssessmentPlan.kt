package com.qualityverifier.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the assistant wants collected, issued in one go.
 *
 * The app runs this locally: it walks the shot list through the camera and the test list
 * through their answer screens, then sends the whole set back as a single turn. The
 * conversation used to be the state machine — ask for a photo, wait for a reply, ask for
 * the next — which cost a network round trip per shot. Worse, every one of those turns
 * re-sent all the previous images, so the token cost of an assessment grew with the
 * square of its shot count rather than linearly.
 *
 * Every field has a default, and an empty list is meaningful: a follow-up plan often
 * asks for one more photo and no tests, or one test and no photos.
 */
@Serializable
data class AssessmentPlan(
    /** One line on what is coming and roughly how long it takes. */
    val summary: String = "",
    /**
     * Language the plan is written in, so the app's own glue text — the "Test results"
     * heading it sends back, the screen titles — matches. See
     * [com.qualityverifier.text.ReportLabels].
     */
    val language: String = "",
    val photos: List<PlannedShot> = emptyList(),
    val tests: List<PlannedTest> = emptyList(),
) {
    /** A plan with nothing to collect is not a plan; the caller should ignore it. */
    val isRunnable: Boolean get() = photos.isNotEmpty() || tests.isNotEmpty()

    val stepCount: Int get() = photos.size + tests.size
}

@Serializable
data class PlannedShot(
    /** Short label for the plan list and the camera counter, e.g. "Leg joint, close". */
    val title: String = "",
    /** One-line framing note shown beside the title in the plan list. */
    val note: String = "",
    /** The full direction shown over the viewfinder while taking this shot. */
    val instruction: String = "",
)

@Serializable
data class PlannedTest(
    val title: String = "",
    /** Optional second line, often the same instruction in the other language. */
    val subtitle: String = "",
    val instruction: String = "",
    /**
     * Name of a diagram to draw above the instruction, or blank for none. Only a fixed
     * set exists — see [TestDiagram] — because the drawings ship in the app while the
     * prompts that name them do not.
     */
    val diagram: String = "",
    val options: List<TestOption> = emptyList(),
) {
    val diagramKind: TestDiagram? get() = TestDiagram.fromId(diagram)
}

@Serializable
data class TestOption(
    /** What the button says, and what gets sent back as the answer. */
    val label: String = "",
    /** Smaller second line under the label. */
    val detail: String = "",
)

/**
 * The diagrams that exist in the app.
 *
 * Deliberately a closed set. Prompts are data and can change without a release, but a
 * drawing cannot, so a prompt naming a diagram this version has never heard of must
 * degrade to no diagram rather than to a broken image. Only tests whose motion is hard
 * to put into words get one — a sentence already covers "press your thumbnail into the
 * underside".
 */
enum class TestDiagram(val id: String) {
    /** Two arrows pushing opposite corners of a seat in opposite directions. */
    RACKING("racking"),

    /** An eye at surface level looking along a top, to catch a bow. */
    SIGHT_ALONG("sight-along"),

    /** A frame lifted by one leg, the far corner sagging out of square. */
    ONE_LEG_LIFT("one-leg-lift");

    companion object {
        fun fromId(id: String): TestDiagram? =
            entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/** A shot the user has taken, paired with the plan entry that asked for it. */
data class CapturedShot(
    val shotIndex: Int,
    val path: String,
)

/** A test the user has answered. [answer] is the option label, verbatim. */
data class TestAnswer(
    val testIndex: Int,
    val answer: String,
)
