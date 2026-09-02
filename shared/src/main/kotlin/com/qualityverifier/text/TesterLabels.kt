package com.qualityverifier.text

/**
 * The questions an evaluator is asked after finishing an assessment.
 *
 * Only ever shown to one of our own evaluators, which is why the wording addresses somebody
 * doing a job rather than somebody buying furniture: it asks about "the assistant" plainly,
 * and about mistakes without softening, because a reviewer who feels rude saying so is a
 * reviewer whose data is useless.
 *
 * TRANSLATION STATUS: the Swahili here is unreviewed, exactly like [ReportLabels] and
 * [AuthLabels]. Evaluators may well prefer to work in Swahili and the questions are
 * research instrumentation, so a mistranslated scale is a corrupted measure rather than an
 * awkward sentence — the two rating questions are the ones to check hardest, since they
 * carry the direction of the scale in words.
 */
data class TesterLabels(
    val code: String,
    val title: String,
    val blurb: String,

    /** REVIEW CRITICAL: asks about mistakes without inviting politeness. */
    val mistakesQuestion: String,
    val mistakesYes: String,
    val mistakesNo: String,
    val mistakesUnsure: String,
    val mistakesDetailLabel: String,
    val mistakesDetailHint: String,

    /** REVIEW CRITICAL: carries the direction of the 1-5 scale. */
    val adviceQuestion: String,
    val adviceLow: String,
    val adviceHigh: String,

    /** REVIEW CRITICAL: carries the direction of the 1-10 scale. */
    val itemQuestion: String,
    val itemLow: String,
    val itemHigh: String,

    val extraLabel: String,
    val extraHint: String,

    val submit: String,
    val later: String,
    val prompt: String,
    val promptAction: String,
    val thanks: String,
    val saved: String,
) {
    companion object {
        val ENGLISH = TesterLabels(
            code = "en",
            title = "How did that go?",
            blurb = "Five questions about the assessment you just did. This is for us, not " +
                "for the customer.",

            mistakesQuestion = "Did the assistant make any mistakes?",
            mistakesYes = "Yes",
            mistakesNo = "No",
            mistakesUnsure = "Not sure",
            mistakesDetailLabel = "What did it get wrong?",
            mistakesDetailHint = "Which claim, and what the piece was actually like",

            adviceQuestion = "How good was the advice?",
            adviceLow = "Not helpful at all",
            adviceHigh = "Very helpful",

            itemQuestion = "How good was the furniture itself?",
            itemLow = "1 — among the worst built you have seen",
            itemHigh = "10 — no defects at all",

            extraLabel = "Anything else",
            extraHint = "Optional",

            submit = "Send review",
            later = "Not now",
            prompt = "You are an evaluator on this account. Tell us how the assistant did?",
            promptAction = "Review this assessment",
            thanks = "Thank you.",
            saved = "Saved. It will be sent when you have signal.",
        )

        val SWAHILI = TesterLabels(
            code = "sw",
            title = "Ilikuwaje?",
            blurb = "Maswali matano kuhusu ukaguzi uliomaliza. Haya ni kwa ajili yetu, si " +
                "kwa mteja.",

            mistakesQuestion = "Msaidizi alifanya makosa yoyote?",
            mistakesYes = "Ndiyo",
            mistakesNo = "Hapana",
            mistakesUnsure = "Sina hakika",
            mistakesDetailLabel = "Alikosea nini?",
            mistakesDetailHint = "Alisema nini, na hali ya kweli ya kipande hicho",

            adviceQuestion = "Ushauri ulikuwa mzuri kiasi gani?",
            adviceLow = "Haukusaidia kabisa",
            adviceHigh = "Ulisaidia sana",

            itemQuestion = "Samani yenyewe ilikuwa nzuri kiasi gani?",
            itemLow = "1 — kati ya zilizojengwa vibaya zaidi ulizoona",
            itemHigh = "10 — hakuna kasoro yoyote",

            extraLabel = "Kitu kingine",
            extraHint = "Si lazima",

            submit = "Tuma maoni",
            later = "Sasa hivi hapana",
            prompt = "Wewe ni mkaguzi kwenye akaunti hii. Tuambie msaidizi alifanyaje?",
            promptAction = "Kagua ukaguzi huu",
            thanks = "Asante.",
            saved = "Yamehifadhiwa. Yatatumwa utakapopata mtandao.",
        )

        /**
         * Same resolution as [ReportLabels]: the assessment's own language first, the
         * device's only when the assessment never declared one.
         */
        fun forLanguage(
            assessmentLanguage: String?,
            deviceLanguage: String? = null,
        ): TesterLabels = match(assessmentLanguage) ?: match(deviceLanguage) ?: ENGLISH

        private fun match(code: String?): TesterLabels? {
            val normalised = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val swahili = normalised.startsWith("sw") ||
                normalised.contains("swahili") ||
                normalised.contains("kiswahili")
            return when {
                swahili -> SWAHILI
                normalised.startsWith("en") || normalised.contains("english") -> ENGLISH
                else -> null
            }
        }
    }
}
