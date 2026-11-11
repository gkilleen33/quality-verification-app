package com.qualityverifier.server

import com.qualityverifier.data.prompts.DefaultPrompts
import com.qualityverifier.domain.SkillDimension
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.server.reports.DefaultParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The part of a piece a problem is put down to.
 *
 * A wrong part sends a problem to the wrong worker's record, and nothing on screen lets the
 * manager move it, so every row of the table is pinned here.
 */
class DefaultPartsTest {

    @Test
    fun `the upholstery area wins whatever the dimension`() {
        SkillDimension.entries.forEach { dimension ->
            assertEquals(dimension.id, WorkPart.UPHOLSTERY, DefaultParts.of("upholstery", dimension.id))
        }
        assertEquals(WorkPart.UPHOLSTERY, DefaultParts.of("upholstery", ""))
    }

    @Test
    fun `the dimension is read before the area`() {
        // A structural problem caused by the frame being out of square is the frame maker's.
        assertEquals(WorkPart.FRAME, DefaultParts.of("structural", "frame_squareness"))
        assertEquals(WorkPart.JOINERY, DefaultParts.of("structural", "joint_fit"))
        assertEquals(WorkPart.JOINERY, DefaultParts.of("level", "joint_fit"))
        // A hardware-area problem whose cause was the finish goes to the finisher.
        assertEquals(WorkPart.FINISHING, DefaultParts.of("hardware", "finishing"))
    }

    @Test
    fun `each dimension maps as the table says`() {
        assertEquals(WorkPart.FRAME, DefaultParts.of("", "frame_squareness"))
        assertEquals(WorkPart.JOINERY, DefaultParts.of("", "joint_fit"))
        assertEquals(WorkPart.FINISHING, DefaultParts.of("", "finishing"))
        assertEquals(WorkPart.FINISHING, DefaultParts.of("", "surface"))
        assertEquals(WorkPart.ASSEMBLY, DefaultParts.of("", "hardware"))
        assertNull(DefaultParts.of("", "material"))
    }

    @Test
    fun `material gets no part, and a material dimension is not passed on to the area`() {
        assertNull(DefaultParts.of("material", ""))
        assertNull(DefaultParts.of("material", "material"))
        // Without the stop, the surface area would send a timber fault to the finisher.
        assertNull(DefaultParts.of("surface", "material"))
    }

    @Test
    fun `with no dimension, each area maps as the table says`() {
        assertEquals(WorkPart.UPHOLSTERY, DefaultParts.of("upholstery", ""))
        assertEquals(WorkPart.FINISHING, DefaultParts.of("surface", ""))
        assertEquals(WorkPart.ASSEMBLY, DefaultParts.of("hardware", ""))
        assertNull(DefaultParts.of("structural", ""))
        assertNull(DefaultParts.of("level", ""))
        assertNull(DefaultParts.of("material", ""))
        assertNull(DefaultParts.of("other", ""))
    }

    @Test
    fun `labels are trimmed and lowercased, and an unknown one matches nothing`() {
        assertEquals(WorkPart.JOINERY, DefaultParts.of(" Structural ", " JOINT_FIT"))
        assertEquals(WorkPart.UPHOLSTERY, DefaultParts.of("Upholstery", ""))
        // An unknown dimension falls to the area.
        assertEquals(WorkPart.FINISHING, DefaultParts.of("surface", "polish"))
        assertNull(DefaultParts.of("cushions", "polish"))
        assertNull(DefaultParts.of("", ""))
    }

    @Test
    fun `the table never puts a problem down to other`() {
        // No report on "other" could be handed to a person, and the linkage measure never
        // links a worker named on it.
        assertTrue(DefaultParts.RULES.none { it.part == WorkPart.OTHER })
    }

    @Test
    fun `every dimension and every area the prompts allow has a row`() {
        // Read off the prompts themselves, so a value added there without a row here fails.
        val prompts = DefaultPrompts.FUNDI_MASTER + "\n" + DefaultPrompts.MASTER
        val areas = listed(prompts, "area: one of ").toSet()
        val dimensions = listed(prompts, "dimension: which skill this belongs to, so it can be tracked over months. One of ").toSet()
        assertTrue("expected the prompts to list the seven areas, found $areas", areas.size >= 7)
        assertEquals(SkillDimension.entries.map { it.id }.toSet(), dimensions)

        val rows = DefaultParts.RULES
        areas.forEach { area ->
            assertTrue(
                "area '$area' has no row",
                rows.any { it.label == DefaultParts.Label.AREA && it.value == area },
            )
        }
        SkillDimension.entries.forEach { dimension ->
            assertTrue(
                "dimension '${dimension.id}' has no row",
                rows.any { it.label == DefaultParts.Label.DIMENSION && it.value == dimension.id },
            )
        }
    }

    /** The comma-separated ids after [prefix], up to the full stop, on every line that has one. */
    private fun listed(text: String, prefix: String): List<String> =
        text.lines().mapNotNull { line ->
            val start = line.indexOf(prefix).takeIf { it >= 0 } ?: return@mapNotNull null
            line.substring(start + prefix.length).substringBefore(".").split(",").map { it.trim() }
        }.flatten().filter { it.isNotEmpty() }
}
