package com.qualityverifier.domain

/**
 * The verdict badge colours, as hex, in the one module both the phone and the server can
 * read.
 *
 * Deliberately outside any Material colour scheme: these do not mean "primary" or "error",
 * they mean sound, fair and serious concerns, and somebody has to be able to tell them
 * apart at a glance in daylight without reading the label. Only three (plus a neutral for
 * an assessment with no level), and never used for anything else, so the association stays
 * learnable.
 *
 * **Here rather than in the theme because there are two renderers.** The handset draws
 * these through Compose; the admin portal draws the same verdicts as CSS so that a
 * reviewer comparing the page against a phone in their other hand sees the same thing.
 * The portal used to hard-code the hex with a comment saying it was copied from the
 * theme — which is a comment asking a future reader to keep two files in step by hand.
 * Now both read this.
 *
 * Strings rather than a colour type, because `:shared` is pure Kotlin and has neither
 * Compose's `Color` nor any notion of CSS. Each renderer converts.
 */
data class VerdictSwatch(
    /** The badge background, `#RRGGBB`. */
    val container: String,
    /** Text on that background, chosen for contrast rather than for the palette. */
    val onContainer: String,
)

object VerdictSwatches {

    val light: Map<VerdictLevel, VerdictSwatch> = mapOf(
        VerdictLevel.SOUND to VerdictSwatch("#D6E8CE", "#1F3D14"),
        VerdictLevel.FAIR to VerdictSwatch("#F7E3B8", "#4A3305"),
        VerdictLevel.SERIOUS to VerdictSwatch("#F6D6D2", "#5B1410"),
        VerdictLevel.UNKNOWN to VerdictSwatch("#E4DACD", "#4E4237"),
    )

    val dark: Map<VerdictLevel, VerdictSwatch> = mapOf(
        VerdictLevel.SOUND to VerdictSwatch("#2E4222", "#D6E8CE"),
        VerdictLevel.FAIR to VerdictSwatch("#4B3A14", "#F7E3B8"),
        VerdictLevel.SERIOUS to VerdictSwatch("#5A2320", "#F6D6D2"),
        VerdictLevel.UNKNOWN to VerdictSwatch("#3B322B", "#D3C5B4"),
    )

    /** Every level has a swatch in both schemes, or something renders as nothing. */
    fun of(level: VerdictLevel, dark: Boolean): VerdictSwatch =
        (if (dark) VerdictSwatches.dark else light).getValue(level)
}
