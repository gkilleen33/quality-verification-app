package com.qualityverifier.data.location

/**
 * Whether an assessment records its point, as a rule with no Android in it.
 *
 * Split out of [LocationPreference] and [LocationCapture] so the decision can be tested
 * without a phone: both of those read Android state, and the part worth testing is how
 * the answers combine, not how they are read.
 */
object LocationRule {

    /**
     * What the person has agreed to.
     *
     * [answerRequired] is Fundi Bora's: its switch defaulted to on before makers were ever
     * asked about a point per assessment, so on that app the switch counts only once they
     * have answered. Kagua asks on its sign-up form, which every Kagua account has passed,
     * so there the switch alone is the answer.
     */
    fun records(switchOn: Boolean, answered: Boolean, answerRequired: Boolean): Boolean =
        switchOn && (answered || !answerRequired)

    /**
     * Whether to start a fix: agreed, Android's permission held now, and a provider on.
     *
     * The last two are functions so they are read only when the answer before them is
     * yes, which keeps the common refusals free. The permission is read at the moment of
     * asking, never remembered: "Only this time" lapses when the app closes, and a fix
     * taken on a permission that has since lapsed is one the person did not give.
     */
    fun shouldCapture(
        records: Boolean,
        permitted: () -> Boolean,
        providerOn: () -> Boolean,
    ): Boolean = records && permitted() && providerOn()
}
