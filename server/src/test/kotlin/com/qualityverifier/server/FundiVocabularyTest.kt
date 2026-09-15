package com.qualityverifier.server

import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.Defect
import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.MeasurementUnit
import com.qualityverifier.domain.SkillDimension
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin enums and the SQL CHECKs have to agree.
 *
 * They are written in two files that nothing links, and the failure is asymmetric and
 * silent in the bad direction: an id the enum has and the CHECK does not produces a
 * constraint violation at runtime, on a turn a fundi has spent twenty minutes
 * photographing. Nothing in the compiler or the migration notices.
 *
 * So this reads the migration and asserts containment. Deliberately one-directional: the
 * CHECK may legitimately allow values no enum has yet — a column can be widened a
 * migration before the code that writes it — but the code must never be able to produce
 * something the column will refuse.
 */
class FundiVocabularyTest {

    private val migration: String by lazy {
        val file = File("db/migrations/V14__fundi_bora.sql")
        assertTrue(
            "expected the migration at ${file.absolutePath}; the module root moved?",
            file.exists(),
        )
        file.readText()
    }

    private fun assertAllAllowed(what: String, ids: List<String>) {
        ids.forEach { id ->
            assertTrue(
                "$what '$id' is in the Kotlin enum but not in any CHECK in V14 — " +
                    "writing it would violate a constraint at runtime",
                migration.contains("'$id'"),
            )
        }
    }

    @Test
    fun `every audience is allowed by the constraint`() {
        assertAllAllowed("audience", Audience.entries.map { it.id })
    }

    @Test
    fun `every tool kind is allowed by the constraint`() {
        assertAllAllowed("tool kind", ToolKind.entries.map { it.id })
    }

    @Test
    fun `every ownership state is allowed by the constraint`() {
        assertAllAllowed("ownership", ToolOwnership.entries.map { it.id })
    }

    @Test
    fun `every goal is allowed by the constraint`() {
        assertAllAllowed("goal", FundiGoal.entries.map { it.id })
    }

    @Test
    fun `every skill dimension is allowed by the constraint`() {
        assertAllAllowed("dimension", SkillDimension.entries.map { it.id })
    }

    @Test
    fun `every measurement unit is allowed by the constraint`() {
        assertAllAllowed("unit", MeasurementUnit.entries.map { it.id })
    }

    @Test
    fun `the skill dimensions that overlap a defect area use the same words`() {
        // The two halves of the project have to be talking about the same thing. A defect
        // the buyer's app files under "surface" and a weakness the maker's app tracks as
        // "finish quality" would be one concept with two names, and no analysis spanning
        // both apps could join them.
        //
        // Read off Defect.area's own vocabulary, which is the master prompt's list.
        val defectAreas = listOf(
            "structural", "level", "surface", "material", "upholstery", "hardware", "other",
        )
        val shared = SkillDimension.entries.map { it.id }.filter { it in defectAreas }

        assertTrue(
            "expected the skill dimensions to reuse some defect areas verbatim; " +
                "if this is empty the two vocabularies have diverged",
            shared.isNotEmpty(),
        )
        // And the ones that do not overlap are deliberate additions, not typos of an
        // area: frame squareness and joint fit are finer than "structural" because that
        // is the grain at which a habit is coached.
        val additions = SkillDimension.entries.map { it.id }.filterNot { it in defectAreas }
        assertTrue(
            "unexpected skill dimensions: $additions",
            additions.all { it in setOf("frame_squareness", "joint_fit", "finishing") },
        )
    }

    @Test
    fun `a defect still carries an area, so the two apps remain joinable`() {
        // Guards the assumption above: if Defect loses its area the join disappears.
        assertTrue(Defect(area = "structural").area == "structural")
    }
}
