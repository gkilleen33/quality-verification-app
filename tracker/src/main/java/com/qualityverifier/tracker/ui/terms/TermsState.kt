package com.qualityverifier.tracker.ui.terms

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * The rules of PesaLog's terms screen, copied from Fundi Bora's (fundi/ui/terms/TermsState.kt,
 * PR #81 and #82): both boxes start unticked and stay disabled until the person has reached
 * the end of the text; reaching it is latched; Continue needs both ticks.
 *
 * Copied rather than shared because Fundi Bora's lives in its app module, which another app
 * cannot depend on, and moving it to a library would change Fundi Bora, which this build
 * leaves alone. `TermsStateTest` here holds the same rules.
 */
data class TermsChecks(
    val reachedEnd: Boolean = false,
    val terms: Boolean = false,
    val transfer: Boolean = false,
) {
    val boxesEnabled: Boolean get() = reachedEnd

    fun canContinue(busy: Boolean): Boolean = reachedEnd && terms && transfer && !busy

    fun reached(): TermsChecks = if (reachedEnd) this else copy(reachedEnd = true)

    /** Ignored until the end is reached, so a tap that lands before the layout settles does nothing. */
    fun tickTerms(value: Boolean): TermsChecks = if (reachedEnd) copy(terms = value) else this

    fun tickTransfer(value: Boolean): TermsChecks = if (reachedEnd) copy(transfer = value) else this

    companion object {
        val Saver: Saver<TermsChecks, Any> = listSaver(
            save = { listOf(it.reachedEnd, it.terms, it.transfer) },
            restore = { TermsChecks(reachedEnd = it[0], terms = it[1], transfer = it[2]) },
        )
    }
}

/** What the end rule needs of one laid-out list item, so tests need no Compose. */
data class LaidOutItem(val index: Int, val offset: Int, val size: Int)

/** Whether the end-of-text marker is wholly on screen. False before the list's first layout. */
fun reachedEnd(visible: List<LaidOutItem>, markerIndex: Int, viewportStart: Int, viewportEnd: Int): Boolean =
    visible.any { it.index == markerIndex && it.offset >= viewportStart && it.offset + it.size <= viewportEnd }
