package com.qualityverifier.server

import com.qualityverifier.domain.VerdictLevel
import com.qualityverifier.domain.VerdictSwatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone and the portal have to agree about what a verdict looks like.
 *
 * A reviewer judging assessment accuracy holds this page beside a handset, and a badge
 * that is a different green in the two places is a difference they have to consciously
 * discount on every row. The colours used to be hand-copied into the portal's stylesheet
 * under a comment asking somebody to keep them in step; these assert the mechanism that
 * replaced the comment.
 */
class VerdictPaletteTest {

    @Test
    fun `every level has a swatch in both schemes`() {
        // A missing entry would render a badge with no background at all — which reads as
        // "no verdict" rather than as a bug.
        VerdictLevel.entries.forEach { level ->
            assertTrue(
                "no light swatch for $level",
                VerdictSwatches.light.containsKey(level),
            )
            assertTrue("no dark swatch for $level", VerdictSwatches.dark.containsKey(level))
        }
    }

    @Test
    fun `swatches are opaque six-digit hex`() {
        // Parsed with a prepended FF by the Compose binding and pasted raw into CSS, so a
        // shorthand or an alpha channel breaks one renderer and not the other.
        val hex = Regex("^#[0-9A-Fa-f]{6}$")
        (VerdictSwatches.light + VerdictSwatches.dark).forEach { (level, swatch) ->
            assertTrue("$level container: ${swatch.container}", hex.matches(swatch.container))
            assertTrue("$level onContainer: ${swatch.onContainer}", hex.matches(swatch.onContainer))
        }
    }

    // The guard that matters: the stylesheet and the class the markup applies come from
    // one function, so a level cannot get a rule nothing selects or a class nothing styles.
    @Test
    fun `the portal styles every class it emits`() {
        val css = com.qualityverifier.server.admin.CSS
        VerdictLevel.entries.forEach { level ->
            val cssClass = com.qualityverifier.server.admin.levelClass(level)
            assertTrue(
                "the stylesheet has no rule for .$cssClass, which TurnView emits",
                css.contains(".$cssClass {"),
            )
            val swatch = VerdictSwatches.of(level, dark = false)
            assertTrue(
                "the rule for .$cssClass does not use the shared swatch",
                css.contains("background:${swatch.container}"),
            )
        }
    }

    // VerdictLevel.id is the wire vocabulary and is deliberately not the CSS class. If
    // somebody "simplifies" levelClass to use it, SERIOUS becomes .lv-serious_concerns
    // and UNKNOWN becomes .lv- — both of which style nothing.
    @Test
    fun `the CSS class is not the wire id`() {
        assertEquals("lv-serious", com.qualityverifier.server.admin.levelClass(VerdictLevel.SERIOUS))
        assertEquals("serious_concerns", VerdictLevel.SERIOUS.id)
        assertEquals("lv-unknown", com.qualityverifier.server.admin.levelClass(VerdictLevel.UNKNOWN))
        assertEquals("", VerdictLevel.UNKNOWN.id)
    }
}
