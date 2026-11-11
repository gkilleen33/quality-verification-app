package com.qualityverifier.fundi.ui.terms

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * The rules of the terms screen, kept out of the composable so they can be tested on the
 * JVM: when the boxes may be ticked, and when Continue may be tapped.
 *
 * Both boxes start unticked and stay disabled until the maker has reached the end of the
 * text. Reaching it is latched: scrolling back up does not disable them again. Continue
 * needs both ticks.
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
        /**
         * Keeps the latch and both ticks across rotation and process death. Keyed by the
         * caller on the text's hash, so a different text starts from nothing.
         */
        val Saver: Saver<TermsChecks, Any> = listSaver(
            save = { listOf(it.reachedEnd, it.terms, it.transfer) },
            restore = { TermsChecks(reachedEnd = it[0], terms = it[1], transfer = it[2]) },
        )
    }
}

/** What the end rule needs of one laid-out list item. A copy of LazyListItemInfo's fields, so tests need no Compose. */
data class LaidOutItem(val index: Int, val offset: Int, val size: Int)

/**
 * Whether the end-of-text marker is on screen.
 *
 * True when the item at [markerIndex] is among [visible] and lies wholly inside the viewport.
 * Before the list's first layout nothing is visible, so this is false and both boxes are
 * disabled in the first frame. A text short enough to fit places the marker in the first
 * layout, so the boxes enable straight after it with no separate rule for short texts. The
 * largest font and a longer translation only move the marker further down.
 */
fun reachedEnd(visible: List<LaidOutItem>, markerIndex: Int, viewportStart: Int, viewportEnd: Int): Boolean =
    visible.any { it.index == markerIndex && it.offset >= viewportStart && it.offset + it.size <= viewportEnd }
