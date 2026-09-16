package com.qualityverifier.domain

/**
 * A collection run in progress: the plan the assistant issued, and what has been
 * gathered against it so far.
 *
 * [shots] and [answers] are keyed by index into the plan's own lists. Presence in the
 * map means the step has been dealt with; a **null value means it was skipped**, which
 * is a real outcome and gets said out loud in the submitted turn. A heavy wardrobe
 * nobody could tip over must not read as a wardrobe with a clean underside.
 *
 * In `:shared` rather than beside the chat view model, where it was.
 *
 * It is domain and not UI: a plan, and progress against it, with no Android in it and no
 * opinion about how any of it is drawn. Two things follow from that. Both halves of the
 * project run the same collection loop — the producer app's shot runner is the buyer
 * app's, down to "Shot 5 of 7" — so this has to be reachable from both. And the screens
 * that read it (`PlanCard`, `InspectingScreen`) become depend-only-on-`:shared`, which
 * is what makes moving them into a capture module a file move rather than an untangling.
 */
data class PlanRun(
    val plan: AssessmentPlan,
    val sourceMessageId: String,
    val shots: Map<Int, String?> = emptyMap(),
    val answers: Map<Int, String?> = emptyMap(),
) {
    val nextShot: Int? get() = plan.photos.indices.firstOrNull { it !in shots }
    val nextTest: Int? get() = plan.tests.indices.firstOrNull { it !in answers }
    val photosDone: Boolean get() = nextShot == null
    val isComplete: Boolean get() = nextShot == null && nextTest == null
    val photosTaken: Int get() = plan.photos.indices.count { shots[it] != null }

    /** Paths in plan order, which is the order the assistant expects to see them. */
    val takenPaths: List<String> get() = plan.photos.indices.mapNotNull { shots[it] }
}
