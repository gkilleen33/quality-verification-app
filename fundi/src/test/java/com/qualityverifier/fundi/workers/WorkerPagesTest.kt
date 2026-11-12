package com.qualityverifier.fundi.workers

import com.qualityverifier.data.workers.WorkerNaming
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.VerdictLevel
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.fundi.ui.workers.WorkerPages
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worker page's rules: which pieces it lists, what it says the worker did on each,
 * how many assessments it shows, and what the filters keep. No score anywhere.
 */
class WorkerPagesTest {

    private fun summary(id: String, piece: String?, at: Long, label: String? = null) = SessionSummary(
        id = id,
        itemType = ItemType.WOODEN_TABLE,
        createdAt = at,
        updatedAt = at,
        preview = "",
        messageCount = 2,
        verdictLevel = VerdictLevel.FAIR,
        pieceId = piece,
        pieceLabel = label,
    )

    private val plain: suspend (SessionSummary) -> WorkerPages.Assessment = { summary ->
        WorkerPages.Assessment(summary, FundiPurpose.EVALUATE, emptyList())
    }

    @Test
    fun `a worker named on two pieces with different parts shows both, newest first`() = runTest {
        val stool = summary("s1", piece = "s1", at = 1_000, label = "Stool")
        val table = summary("t1", piece = "t1", at = 2_000, label = "Hotel table")
        val pieces = WorkerPages.build(
            listOf(stool, table),
            listOf(
                WorkerNaming("s1", WorkPart.FRAME),
                WorkerNaming("t1", WorkPart.FINISHING),
                WorkerNaming("t1", WorkPart.JOINERY),
            ),
            plain,
        )

        assertEquals(listOf("t1", "s1"), pieces.map { it.pieceId })
        // Parts in the picker's order, not the order they were recorded in.
        assertEquals(listOf(WorkPart.JOINERY, WorkPart.FINISHING), pieces[0].parts)
        assertEquals(listOf(WorkPart.FRAME), pieces[1].parts)
        assertEquals("Hotel table", pieces[0].latest.pieceLabel)
    }

    @Test
    fun `a piece re-assessed three times shows three assessments, newest first`() = runTest {
        val first = summary("a1", piece = "a1", at = 1_000)
        val second = summary("a2", piece = "a1", at = 2_000)
        val third = summary("a3", piece = "a1", at = 3_000)
        // Named only on the first assessment: the piece still shows every assessment of
        // it, since the later ones found what they found on the same piece.
        val pieces = WorkerPages.build(
            listOf(first, second, third),
            listOf(WorkerNaming("a1", WorkPart.UPHOLSTERY)),
            plain,
        )

        val piece = pieces.single()
        assertEquals(listOf("a3", "a2", "a1"), piece.assessments.map { it.summary.id })
        assertEquals("a3", piece.latest.id)
    }

    @Test
    fun `a null part reads as the whole piece, first, beside any parts`() = runTest {
        val piece = summary("p1", piece = "p1", at = 1_000)
        val again = summary("p2", piece = "p1", at = 2_000)
        val pieces = WorkerPages.build(
            listOf(piece, again),
            listOf(WorkerNaming("p2", WorkPart.ASSEMBLY), WorkerNaming("p1", null)),
            plain,
        )

        assertEquals(listOf(null, WorkPart.ASSEMBLY), pieces.single().parts)
    }

    @Test
    fun `an assessment no longer on the phone is skipped, not shown empty`() = runTest {
        val pieces = WorkerPages.build(
            listOf(summary("here", piece = "here", at = 1_000)),
            listOf(WorkerNaming("gone", WorkPart.FRAME), WorkerNaming("here", null)),
            plain,
        )
        assertEquals(listOf("here"), pieces.map { it.pieceId })
    }

    @Test
    fun `the filters narrow by piece and by date`() = runTest {
        val stool = summary("s1", piece = "s1", at = 1_000)
        val stoolAgain = summary("s2", piece = "s1", at = 5_000)
        val table = summary("t1", piece = "t1", at = 3_000)
        val pieces = WorkerPages.build(
            listOf(stool, stoolAgain, table),
            listOf(WorkerNaming("s1", null), WorkerNaming("t1", WorkPart.FRAME)),
            plain,
        )
        assertEquals(2, pieces.size)

        val onlyStool = WorkerPages.filter(pieces, WorkerPages.Filter(pieceId = "s1"))
        assertEquals(listOf("s1"), onlyStool.map { it.pieceId })
        assertEquals(2, onlyStool.single().assessments.size)

        // The range is on when the assessment was started, inclusive at both ends; a piece
        // with nothing left in it drops out.
        val middle = WorkerPages.filter(pieces, WorkerPages.Filter(from = 2_000, to = 3_000))
        assertEquals(listOf("t1"), middle.map { it.pieceId })

        val late = WorkerPages.filter(pieces, WorkerPages.Filter(from = 5_000))
        assertEquals(listOf("s1"), late.map { it.pieceId })
        assertEquals(listOf("s2"), late.single().assessments.map { it.summary.id })

        val both = WorkerPages.filter(pieces, WorkerPages.Filter(pieceId = "t1", from = 4_000))
        assertTrue(both.isEmpty())

        assertEquals(pieces, WorkerPages.filter(pieces, WorkerPages.Filter()))
    }

    @Test
    fun `the problems are the newest verdict's, with no count and nothing added`() {
        val reply = """
            One joint to close.

            ```fb-diagnosis
            {"findings":[{"title":"Gap at the back joint","area":"structural","severity":"moderate","dimension":"joint_fit"}]}
            ```

            ```qv-verdict
            {"verdict":"fair","headline":"One joint","defects":[
              {"title":"Gap at the back joint","area":"structural","severity":"moderate","what_i_see":"A gap."},
              {"title":"Knot near the edge","area":"material","severity":"minor","what_i_see":"A knot."}
            ]}
            ```
        """.trimIndent()
        val problems = WorkerPages.problemsIn(
            listOf(
                ChatMessage("u1", Role.USER, "Here is the table."),
                ChatMessage("a1", Role.ASSISTANT, reply),
            ),
        )

        assertEquals(listOf("Gap at the back joint", "Knot near the edge"), problems.map { it.title })
        assertEquals("joint_fit", problems[0].dimension)

        // Nothing found, or no verdict and no diagnosis: an empty list, not an error.
        assertTrue(WorkerPages.problemsIn(listOf(ChatMessage("a2", Role.ASSISTANT, "Send one more photo."))).isEmpty())
        assertTrue(WorkerPages.problemsIn(emptyList()).isEmpty())
    }
}
