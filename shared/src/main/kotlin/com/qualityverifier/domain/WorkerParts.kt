package com.qualityverifier.domain

/**
 * The rules behind "Who worked on this?": which part chips a piece offers, and how a tap
 * changes the answer. Pure, so the intake and the card's edit behave the same and the
 * rules can be tested without a screen.
 *
 * The answer is a list of [WorkerSlot]s. A chosen worker with no part is one slot with a
 * null part, which is the whole piece; a worker with parts is one slot per part. That is
 * how V23 stores it (`session_workers.part` null for the whole piece), so the "Whole
 * piece" chip changes what the screen says and nothing about what is stored.
 */
object WorkerParts {

    /**
     * The types whose pieces can carry padding. A bed is here for padded headboards
     * (Nathan, 10 November 2026), and "Something else" because it could be anything.
     */
    private val padded = setOf(
        ItemType.UPHOLSTERED_SOFA,
        ItemType.UPHOLSTERED_CHAIR,
        ItemType.WOODEN_BED,
        ItemType.OTHER,
    )

    /**
     * The part chips for a type of furniture, in [WorkPart] order. Upholstery only where
     * there is padding to do; a manager asked who upholstered a table has been asked a
     * question with no answer.
     */
    fun partsFor(itemType: ItemType): List<WorkPart> =
        if (itemType in padded) WorkPart.entries
        else WorkPart.entries.filterNot { it == WorkPart.UPHOLSTERY }

    /**
     * [partsFor], plus any part the worker already holds that the type does not offer.
     *
     * An older assessment, or one recorded before this rule, can name somebody on the
     * upholstery of a table. Hiding that chip would make the next save drop the row
     * without the manager seeing it go, so it stays on screen, selected, until they clear
     * it; once cleared it is not offered again.
     */
    fun offered(itemType: ItemType, held: Set<WorkPart>): List<WorkPart> {
        val parts = partsFor(itemType)
        return WorkPart.entries.filter { it in parts || it in held }
    }

    /** The parts [workerId] is named on. Empty for a worker on the whole piece, or not chosen. */
    fun partsOf(slots: List<WorkerSlot>, workerId: String?): Set<WorkPart> =
        slots.filter { it.workerId == workerId }.mapNotNull { it.part }.toSet()

    /** True when [workerId] is chosen with no part, which is what "Whole piece" shows. */
    fun isWholePiece(slots: List<WorkerSlot>, workerId: String?): Boolean =
        slots.any { it.workerId == workerId } && partsOf(slots, workerId).isEmpty()

    /**
     * Chooses a worker, on the whole piece, or takes them off the piece with every part
     * they were on. [name] is null for the manager ("Me"), who has no roster entry.
     */
    fun toggleWorker(slots: List<WorkerSlot>, workerId: String?, name: String?): List<WorkerSlot> =
        if (slots.any { it.workerId == workerId }) slots.filter { it.workerId != workerId }
        else slots + WorkerSlot(workerId, name)

    /**
     * Adds or removes one part. The first part replaces the whole piece; clearing the last
     * part puts the worker back on the whole piece rather than off it, since taking them
     * off is the name chip's job.
     */
    fun togglePart(
        slots: List<WorkerSlot>,
        workerId: String?,
        name: String?,
        part: WorkPart,
    ): List<WorkerSlot> {
        val parts = partsOf(slots, workerId)
        val next = if (part in parts) parts - part else parts + part
        return if (next.isEmpty()) wholePiece(slots, workerId, name)
        else slots.filter { it.workerId != workerId } +
            WorkPart.entries.filter { it in next }.map { WorkerSlot(workerId, name, it) }
    }

    /**
     * Puts the worker on the whole piece, clearing any parts. Tapping "Whole piece" when it
     * is already selected leaves it selected: there is no state with a worker chosen and
     * neither a part nor the whole piece.
     */
    fun wholePiece(slots: List<WorkerSlot>, workerId: String?, name: String?): List<WorkerSlot> =
        slots.filter { it.workerId != workerId } + WorkerSlot(workerId, name)
}
