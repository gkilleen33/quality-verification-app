package com.qualityverifier.text

import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.ToolOwnership

/**
 * The maker's context, written into their own opening turn.
 *
 * `prompts/fundi-master.txt` expects it there: *"The opening message arrives with a photo
 * of the whole piece and the maker's context"*, and *"Their tool list is in the opening
 * message, with each tool marked owned, borrowed or none."*
 *
 * **A message, not a field.** Same reasoning as [buildIntakeMessage], and it matters more
 * here. The profile is a description of the maker: it belongs in the conversation where
 * they can read what was said about them, rather than in a header they cannot see. Keeping
 * it out of the system prompt also keeps the cache breakpoint shared — a per-maker system
 * prompt would mean a per-maker 8.3k-token cache write instead of one entry for everybody
 * assessing the same kind of piece.
 *
 * **Absent tools are stated, not implied.** A tool left out of this message could mean
 * either that they have none or that nobody asked, and only the first licenses a fix plan
 * to work around it. So the ones they told us they lack are listed under their own heading.
 * Getting this wrong in the quiet direction — omitting them — would produce coaching that
 * proposes clamps to somebody who said they own none, which is the single failure the
 * prompt calls out by name.
 *
 * Returns an empty string when there is nothing to say, so the caller appends nothing
 * rather than an empty preamble.
 */
fun buildFundiContextMessage(profile: FundiProfile, labels: FundiLabels): String {
    if (!profile.hasAnything) return ""

    val lines = mutableListOf<String>()

    val workshop = profile.workshop
    if (workshop.hasAnything) {
        val sentences = listOfNotNull(
            workshop.worksAt?.trim()?.takeIf { it.isNotEmpty() }?.let(labels::worksAt),
            workshop.yearsInTrade?.let(labels::years),
            workshop.workers?.let(labels::workers),
            workshop.makes?.trim()?.takeIf { it.isNotEmpty() }?.let(labels::makes),
            workshop.piecesPerMonth?.let(labels::piecesPerMonth),
            workshop.usualTimber?.trim()?.takeIf { it.isNotEmpty() }?.let(labels::timber),
        )
        if (sentences.isNotEmpty()) {
            lines += labels.contextIntro + " " + sentences.joinToString(" ")
        }
    }

    // Enum order rather than the order they were answered in, so the same profile always
    // produces the same message. Borrowed tools are marked inline rather than split into a
    // third list: they are available for this piece, which is what matters for the fix, and
    // the caveat about the next piece is the prompt's job to apply.
    val available = profile.available.sortedBy { it.kind.ordinal }
    if (available.isNotEmpty()) {
        val named = available.map { tool ->
            val name = labels.nameOf(tool.kind)
            if (tool.ownership == ToolOwnership.BORROWED) labels.borrowed(name) else name
        }
        lines += labels.contextToolsHave + " " + named.joinToString(", ") + "."
    }

    val missing = profile.missing.sortedBy { it.ordinal }
    if (missing.isNotEmpty()) {
        lines += labels.contextToolsNone + " " +
            missing.map(labels::nameOf).joinToString(", ") + "."
    }

    if (profile.goals.isNotEmpty()) {
        val named = profile.goals.sortedBy { it.ordinal }.map(labels::nameOf)
        lines += labels.contextGoal + " " + named.joinToString(", ") + "."
    }

    return lines.joinToString("\n")
}
