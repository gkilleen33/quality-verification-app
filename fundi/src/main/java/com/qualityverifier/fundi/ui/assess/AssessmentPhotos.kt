package com.qualityverifier.fundi.ui.assess

import com.qualityverifier.domain.AssessmentPlan
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.PlanRun
import com.qualityverifier.domain.PlannedShot
import com.qualityverifier.domain.Role
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.parseAssistantContent
import com.qualityverifier.ui.photo.ViewerPhoto

/**
 * Every photograph in an assessment, in the order they were taken, each captioned
 * with what the assessment asked for when the app knows it.
 *
 * The viewer swipes through this list, so tapping any thumbnail opens the same set at
 * a different place. Photographs come from the maker's turns in the conversation, in
 * order, followed by the shots of a plan still being collected, which are not on any
 * turn yet.
 *
 * Captions come from what asked for the photograph. The opening photograph answers the
 * intake's instruction, and is named after it. A plan submission's photographs answer
 * the plan's shots: the turn carries them in plan order, skipping the shots the maker
 * could not take, and its text lists every shot with "not done" against the skipped
 * ones (see `buildSubmissionText`), so the two are read side by side. Where that
 * reading does not add up, the photographs are shown without captions rather than
 * under the wrong ones.
 *
 * [purpose] is the intake's purpose; null before it is known, which leaves the opening
 * photograph uncaptioned. [reportLabels] follows the assessment's language, as the
 * submission text did when it was written.
 */
fun assessmentPhotos(
    messages: List<ChatMessage>,
    run: PlanRun?,
    purpose: FundiPurpose?,
    labels: FundiLabels,
    reportLabels: ReportLabels,
): List<ViewerPhoto> {
    val photos = mutableListOf<ViewerPhoto>()
    val openingId = messages.firstOrNull { it.role == Role.USER }?.id
    // The plan a submission answers is the latest one issued before it. A later
    // assistant turn without a plan, a clarifying question, does not replace it.
    var plan: AssessmentPlan? = null
    for (message in messages) {
        if (message.role == Role.ASSISTANT) {
            parseAssistantContent(message.text).plan?.let { plan = it }
            continue
        }
        if (message.attachments.isEmpty()) continue
        val captions: List<String?> = when {
            message.id == openingId -> List(message.attachments.size) { purpose?.let(labels::openingPhotoName) }
            message.composed -> submittedShotTitles(plan, message.text, message.attachments.size, reportLabels)
            else -> List(message.attachments.size) { null }
        }
        message.attachments.forEachIndexed { index, attachment ->
            photos += ViewerPhoto(attachment.path, captions[index])
        }
    }
    run?.let { current ->
        current.plan.photos.forEachIndexed { index, shot ->
            current.shots[index]?.let { path ->
                photos += ViewerPhoto(path, shotTitle(shot, index, current.plan, reportLabels))
            }
        }
    }
    return photos
}

/**
 * The titles of the shots a submission turn's [count] photographs answer, in order, or
 * nulls when the turn cannot be read against [plan].
 *
 * The turn's text opens with the count line, then one line per planned shot in plan
 * order, the skipped ones ending in the "not done" label. The tests follow under their
 * own heading, which is not a list line, so taking the plan's number of lines after the
 * first never reaches them.
 */
private fun submittedShotTitles(
    plan: AssessmentPlan?,
    text: String,
    count: Int,
    labels: ReportLabels,
): List<String?> {
    val unknown = List<String?>(count) { null }
    val answered = plan ?: return unknown
    if (answered.photos.isEmpty()) return unknown
    val shotLines = text.lines().drop(1).take(answered.photos.size)
    if (shotLines.size != answered.photos.size || shotLines.any { !it.startsWith("- ") }) return unknown
    val skipped = ": ${labels.notDone}"
    val taken = answered.photos.indices
        .filter { !shotLines[it].endsWith(skipped) }
        .map { shotTitle(answered.photos[it], it, answered, labels) }
    return if (taken.size == count) taken else unknown
}

/** What the plan calls a shot: its title, or its number when it has none, as the camera shows it. */
private fun shotTitle(shot: PlannedShot, index: Int, plan: AssessmentPlan, labels: ReportLabels): String =
    shot.title.ifBlank { labels.shotOf(index + 1, plan.photos.size) }
