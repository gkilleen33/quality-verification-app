package com.qualityverifier.domain

/**
 * The two rates a maker's record is judged on.
 *
 * Deliberately two numbers rather than one score, because they measure different things
 * and a shop can be strong at one and weak at the other:
 *
 *   "7 of the last 10 pieces came back with no defects the first time."
 *   "Of the last 10 pieces with a defect flagged, 6 were confirmed repaired."
 *
 * The first says whether a shop avoids mistakes. The second says what happens when it
 * makes one — and a shop that makes some mistakes but reliably puts them right is a
 * different proposition from one that does not, which a single average would hide.
 *
 * Rolling rather than lifetime, so the number moves when the work does. A maker who was
 * poor a year ago and is good now should be able to see that, and so should a buyer.
 *
 * Pure, and in `:shared` rather than in a SQL view, for three reasons: both the portal and
 * the producer app have to arrive at identical numbers; the windowing rules below are
 * fiddly enough to want unit tests rather than a fixture database; and the definition of
 * "the first time" is a research decision that will be argued about, so it belongs
 * somewhere a person can read it.
 */
object QualityRecord {

    /** How many recent pieces each rate looks at. Fewer are used when fewer exist. */
    const val WINDOW = 10

    /**
     * One piece, and what its assessments found, oldest first.
     *
     * [defectCounts] is one entry per assessment. **Null means no verdict was recorded**
     * for that assessment, which is not the same as a verdict that found nothing: every
     * full assessment between 3 and 8 September produced no verdict at all, because the
     * reply was truncated and the unparseable block dropped. Treating those as clean
     * would invert both rates.
     */
    data class Piece(
        val id: String,
        val defectCounts: List<Int?>,
    ) {
        /**
         * The first assessment that actually produced a verdict.
         *
         * "The first time" means the first time the piece was *evaluated*, and an
         * assessment that failed to produce a verdict was not an evaluation — it was a
         * failure, usually ours. Skipping it rather than disqualifying the piece means a
         * server bug costs us a data point instead of a maker their record.
         */
        val firstVerdict: Int? get() = defectCounts.firstOrNull { it != null }

        /** Whether this piece was ever evaluated at all. */
        val wasEvaluated: Boolean get() = firstVerdict != null

        /**
         * Whether a later assessment found nothing wrong.
         *
         * Any subsequent clean verdict counts, not only the immediately next one: a maker
         * who re-glues a joint, re-assesses, finds it still gapping, fixes it properly and
         * re-assesses again has repaired the piece. Counting only the first retry would
         * punish the more careful path.
         */
        val repairConfirmed: Boolean
            get() {
                val verdicts = defectCounts.filterNotNull()
                if (verdicts.isEmpty()) return false
                return verdicts.drop(1).any { it == 0 }
            }
    }

    /**
     * A rate over a window, with the denominator it actually used.
     *
     * [outOf] is not always [WINDOW]: a maker with four pieces is measured over four. The
     * wording has to say which, or "3 of 10" reads as seven failures that never happened.
     */
    data class Rate(val count: Int, val outOf: Int) {
        val hasData: Boolean get() = outOf > 0
    }

    /**
     * How often a piece was right first time.
     *
     * Denominator: the most recent [WINDOW] pieces that were evaluated at all.
     * Numerator: those whose first verdict listed no defects.
     */
    fun firstTimeClean(pieces: List<Piece>): Rate {
        val evaluated = pieces.filter { it.wasEvaluated }.takeLast(WINDOW)
        return Rate(
            count = evaluated.count { it.firstVerdict == 0 },
            outOf = evaluated.size,
        )
    }

    /**
     * How often a flagged piece was put right.
     *
     * Denominator: the most recent [WINDOW] pieces whose first verdict flagged something.
     * Numerator: those with a later verdict finding nothing.
     *
     * Note the two windows are independent. A maker whose last ten pieces were all clean
     * has no repair rate at all, and the pieces in this window may be much older than
     * those in the other — which is correct: "when you do make a mistake, how does it go"
     * is a question about the mistakes, whenever they happened.
     */
    fun repairsConfirmed(pieces: List<Piece>): Rate {
        val flagged = pieces
            .filter { (it.firstVerdict ?: 0) > 0 }
            .takeLast(WINDOW)
        return Rate(
            count = flagged.count { it.repairConfirmed },
            outOf = flagged.size,
        )
    }
}
