package com.qualityverifier.domain

/**
 * The closed vocabularies Fundi Bora adds.
 *
 * Every one of these is mirrored by a CHECK constraint in `V14__fundi_bora.sql`, and the
 * pairing is load-bearing rather than tidy: prompts are data fetched from GitHub and can
 * change without a release, so the model can name a tool or a dimension that this version
 * has never heard of. Closed sets here mean such an answer degrades to "unrecognised"
 * instead of reaching the database and failing a constraint on a turn the maker has spent
 * twenty minutes photographing. The same argument as [TestDiagram].
 *
 * `FundiVocabularyTest` in the server module asserts that every id below appears in the
 * migration's CHECK, so the two cannot drift apart silently.
 */

/**
 * Which half of the project a session belongs to.
 *
 * Both halves run the same assessment engine — the brief's words are that Fundi Bora
 * "runs the same assessment engine as Kagua, but points it inward" — so this selects the
 * master prompt and the reading of the result, not a different pipeline.
 */
enum class Audience(val id: String) {
    /** Somebody deciding whether to buy a piece. Every session before Fundi Bora existed. */
    BUYER("buyer"),

    /** The maker, assessing their own work. */
    FUNDI("fundi");

    companion object {
        fun fromId(id: String): Audience? =
            entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/**
 * What the maker can work with.
 *
 * An input to the coaching, not a profile ornament: the fix plan in the mockup proposes a
 * rope tourniquet because no clamps are owned, and prices a marking gauge because its
 * absence is what caused the defect. Advice that assumes a tool the fundi does not have
 * is advice they cannot follow.
 *
 * Wider than the six the setup screen lists, because the coaching needs to name the tools
 * whose *absence* it is working around.
 */
enum class ToolKind(val id: String) {
    HAND_SAW("hand_saw"),
    HAMMER_MALLET("hammer_mallet"),
    CHISELS("chisels"),
    PLANE_NO4("plane_no4"),
    CIRCULAR_SAW("circular_saw"),
    ROUTER("router"),
    MARKING_GAUGE("marking_gauge"),
    CLAMPS("clamps"),
    SQUARE("square"),
    DRILL("drill"),
    SANDER("sander"),
    OTHER("other");

    companion object {
        fun fromId(id: String): ToolKind? =
            entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/**
 * Three states, not two.
 *
 * "Borrowed" is its own answer because it changes the advice rather than the inventory: a
 * borrowed circular saw is available for this piece and possibly not for the next one, so
 * a habit built around it is a habit that will break.
 */
enum class ToolOwnership(val id: String) {
    OWNED("owned"),
    BORROWED("borrowed"),
    NONE("none");

    companion object {
        fun fromId(id: String): ToolOwnership? =
            entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/**
 * Why a tool arrived or left.
 *
 * Recorded because the change is the interesting part. A shop that owned a circular saw
 * last March and does not now has told us something — sold to cover a bad month, broken
 * and never replaced, stolen — and each is a different story about the business. The
 * reverse is the clearest evidence the coaching did anything: a maker who bought a marking
 * gauge after a fix plan named its absence as the cause.
 *
 * Always optional. It is null whenever nobody asked, and it has to stay allowed to be null
 * even once the setup screen does ask, because a maker who would rather not say should
 * still be able to correct their tool list.
 *
 * The list is a guess and flagged as one in `V17`; it belongs with the setup-copy review
 * in issue #41.
 */
enum class ToolChangeReason(val id: String) {
    BOUGHT("bought"),
    /** Given or inherited. */
    GIFT("gift"),
    SOLD("sold"),
    /** Broke and was not replaced. */
    BROKE("broke"),
    STOLEN("stolen"),
    /** A borrowed tool went back to whoever owns it. */
    RETURNED("returned"),
    OTHER("other");

    companion object {
        fun fromId(id: String): ToolChangeReason? =
            entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/** What the maker said they wanted, from the three the setup screen offers. */
enum class FundiGoal(val id: String) {
    /** "+ KSh 500 per piece". */
    PRICE_PER_PIECE("price_per_piece"),
    MORE_ORDERS("more_orders"),
    /** "Zero comebacks" — pieces returned for repair. */
    ZERO_COMEBACKS("zero_comebacks");

    companion object {
        fun fromId(id: String): FundiGoal? =
            entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/**
 * The axes a maker's skill is tracked along.
 *
 * The first three are what the mockup's skill file shows — "Frame squareness L3", "Joint
 * fit L2", "Finishing L3". The rest deliberately reuse the names already in
 * [com.qualityverifier.domain.Defect.area], so a defect found by the buyer's app and a
 * weakness tracked by the maker's app are talking about the same thing. Two vocabularies
 * for one concept is how the two halves would stop being comparable.
 */
enum class SkillDimension(val id: String) {
    FRAME_SQUARENESS("frame_squareness"),
    JOINT_FIT("joint_fit"),
    FINISHING("finishing"),
    SURFACE("surface"),
    MATERIAL("material"),
    HARDWARE("hardware");

    companion object {
        fun fromId(id: String): SkillDimension? =
            entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/**
 * What a measurement is in.
 *
 * Never optional alongside a value. "A gap of 3" means nothing, and the migration's CHECK
 * enforces that the two are present or absent together — the same false-precision
 * argument that keeps accuracy attached to a location.
 */
enum class MeasurementUnit(val id: String) {
    MILLIMETRES("mm"),
    DEGREES("deg"),
    PERCENT("pct");

    companion object {
        fun fromId(id: String): MeasurementUnit? =
            entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/**
 * One measurement from one assessment.
 *
 * Measurements rather than scores, because the aggregation into a level is a rubric
 * decision that will change and should stay in code. A stored score freezes a judgement;
 * a stored millimetre survives the judgement being revised.
 */
data class SkillObservation(
    val dimension: SkillDimension,
    val value: Double?,
    val unit: MeasurementUnit?,
    /** The coarse 1–5 reading, as "L2" in the skill file. */
    val level: Int?,
) {
    /** A row carrying neither a measurement nor a level says nothing and is not stored. */
    val isMeaningful: Boolean get() = (value != null && unit != null) || level != null
}
