package com.qualityverifier.domain

/**
 * Why the maker opened this assessment.
 *
 * Asked before anything reaches the model, for two reasons. It decides what the model is
 * asked to do: only [fullAssessment] purposes get the whole-piece plan, the rest get
 * questions tailored to what the maker said. And it decides what the assessment is worth
 * to the record: only a full assessment issues a verdict, so only a full assessment
 * counts towards the two quality rates. A targeted check of one joint that came back
 * clean says nothing about the rest of the piece, and recording it as a clean piece
 * would inflate exactly the number a maker is judged on.
 *
 * Two separate lists, because a new piece and a returning one are different questions.
 * [forReassessment] says which list a purpose belongs to.
 */
enum class FundiPurpose(
    val id: String,
    /** Whether the model should run the whole-piece plan and issue a verdict. */
    val fullAssessment: Boolean,
    /** Whether the maker is asked to describe what they want in their own words. */
    val needsDetails: Boolean,
    /** Offered when the maker brings back a piece already assessed. */
    val forReassessment: Boolean,
    /**
     * Whether the opening photograph may be skipped. Only where there may be no piece
     * at all: somebody learning a skill might be asking before they have cut anything.
     */
    val photoOptional: Boolean = false,
) {
    EVALUATE("evaluate", fullAssessment = true, needsDetails = false, forReassessment = false),

    /**
     * A piece part way through — a joint dry-fitted before glue-up, a frame before the
     * top goes on. The cheapest moment to catch a mistake, and not a full assessment:
     * an unfinished piece has no verdict to give, and recording one would count half a
     * stool towards the maker's rates.
     */
    WORK_IN_PROGRESS(
        "work_in_progress", fullAssessment = false, needsDetails = true, forReassessment = false,
    ),
    DIAGNOSE("diagnose", fullAssessment = false, needsDetails = true, forReassessment = false),
    LEARN(
        "learn", fullAssessment = false, needsDetails = true, forReassessment = false,
        photoOptional = true,
    ),
    OTHER(
        "other", fullAssessment = false, needsDetails = true, forReassessment = false,
        photoOptional = true,
    ),
    REEVALUATE("reevaluate", fullAssessment = true, needsDetails = false, forReassessment = true),

    /**
     * Checking one earlier finding was put right. Picks the finding from the piece's own
     * history rather than asking the maker to describe it again: they would describe it
     * differently from how it was recorded, and the check is only meaningful against
     * what was actually found.
     */
    VERIFY_FIX("verify_fix", fullAssessment = false, needsDetails = false, forReassessment = true),
    NEW_ISSUE("new_issue", fullAssessment = false, needsDetails = true, forReassessment = true),

    /**
     * A customer returned the piece. Kept apart from [NEW_ISSUE] because it is a
     * comeback, and "no pieces coming back for repair" is one of the three goals a maker
     * picks at setup: counting comebacks needs them recorded as comebacks. The purpose is
     * stored with the session, so the count is a query rather than a guess.
     */
    CUSTOMER_RETURN(
        "customer_return", fullAssessment = false, needsDetails = true, forReassessment = true,
    );

    companion object {
        fun fromId(id: String): FundiPurpose? = entries.firstOrNull { it.id == id }

        /** The buttons for a piece nobody has assessed before. */
        val forNewPiece: List<FundiPurpose> get() = entries.filter { !it.forReassessment }

        /** The buttons for a piece coming back. */
        val forReturningPiece: List<FundiPurpose> get() = entries.filter { it.forReassessment }
    }
}

/** A finding from an earlier assessment of the same piece, offered for [FundiPurpose.VERIFY_FIX]. */
data class PriorIssue(
    val title: String,
    /** The observation as it was recorded, so the check is against what was found. */
    val whatHappened: String = "",
)

/**
 * Everything the maker answered before the first request.
 *
 * Nothing here is sent until the whole intake is complete — the same rule Kagua keeps.
 * An abandoned intake costs nothing, and the model never sees half a question.
 */
data class FundiIntake(
    val language: AssessmentLanguage,
    val purpose: FundiPurpose,
    /** The maker's own words, for the purposes that ask for them. Blank otherwise. */
    val details: String = "",
    /** The finding being checked, for [FundiPurpose.VERIFY_FIX] only. */
    val issue: PriorIssue? = null,
) {
    /**
     * Whether every question this purpose asks has been answered.
     *
     * A verify-fix with no earlier finding to pick from falls back to the maker's own
     * description, so it is complete with either.
     */
    val isComplete: Boolean
        get() = when {
            purpose == FundiPurpose.VERIFY_FIX -> issue != null || details.isNotBlank()
            purpose.needsDetails -> details.isNotBlank()
            else -> true
        }

    companion object {
        private const val PREFIX = "fundi"
        private const val SEPARATOR = "-"

        /**
         * Stored in the session's intake column, next to Kagua's own code.
         *
         * Prefixed so neither app can misread the other's: Kagua's decoder expects four
         * parts and rejects this, and this rejects Kagua's. The maker's words and the
         * chosen finding are deliberately not in it — they are in the conversation
         * itself, and a second copy in a code would be one that could disagree.
         */
        fun encode(intake: FundiIntake): String =
            listOf(PREFIX, intake.language.code, intake.purpose.id).joinToString(SEPARATOR)

        /** The language and purpose back out of a stored code, or null for anything else. */
        fun decode(encoded: String?): Pair<AssessmentLanguage, FundiPurpose>? {
            val parts = encoded?.trim()?.split(SEPARATOR) ?: return null
            if (parts.size != 3 || parts[0] != PREFIX) return null
            val language = AssessmentLanguage.entries.firstOrNull { it.code == parts[1] }
                ?: return null
            val purpose = FundiPurpose.fromId(parts[2]) ?: return null
            return language to purpose
        }
    }
}
