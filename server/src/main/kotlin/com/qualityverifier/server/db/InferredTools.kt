package com.qualityverifier.server.db

import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership

/**
 * The tools an assessment had to hand (V35, release scope row 10; Nathan, 4 and 14 November
 * 2026): the worker's own tools plus the tools of the studio the piece is for. Computed by
 * the server when the session is created and stored in `session_tools`. Never shown to a
 * fundi and never sent to Claude: the opening turn still carries the maker's own list, as
 * before.
 *
 * "The worker" is the piece's maker. On a piece labelled for a studio, everything on the
 * maker's list that is to hand counts, since a worker's list names the studio's and the
 * manager's tools where they work, and so does everything to hand on the list of the
 * account that runs the studio. On "My own job", or a piece never asked, only the tools the
 * maker owns themselves: the manager's and the studio's stay at the studio, and a rental
 * was for one job.
 */
object InferredTools {

    /** One `session_tools` row. [source] is `maker` or `studio`. */
    data class Row(val kind: ToolKind, val source: String, val owner: ToolOwnership)

    const val MAKER = "maker"
    const val STUDIO = "studio"

    /**
     * [studio] is the list of the account that runs the studio the piece is labelled for,
     * or null when the piece is not labelled for one, or the link does not hold.
     */
    fun of(maker: List<OwnedTool>, labelled: Boolean, studio: List<OwnedTool>?): List<Row> {
        val own = maker.filter { tool ->
            if (labelled) tool.ownership.toHand else tool.ownership == ToolOwnership.WORKER
        }.map { Row(it.kind, MAKER, it.ownership) }
        val studios = if (labelled) studio.orEmpty().filter { it.ownership.toHand }.map { Row(it.kind, STUDIO, it.ownership) }
        else emptyList()
        // One row per kind and source, in the enum's order, so the table reads the same
        // however the lists were stored.
        return (own + studios).distinctBy { it.kind to it.source }
            .sortedWith(compareBy({ it.source }, { it.kind.ordinal }))
    }
}
