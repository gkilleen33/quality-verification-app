package com.qualityverifier.ui.home

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Short, plain-language timestamp for the history list. Falls back to an absolute
 * date past a week, where "8 days ago" stops being useful.
 */
fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val delta = now - epochMillis
    if (delta < 0) return formatDate(epochMillis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes minute${plural(minutes)} ago"
        hours < 24 -> "$hours hour${plural(hours)} ago"
        days < 7 -> "$days day${plural(days)} ago"
        else -> formatDate(epochMillis)
    }
}

private fun plural(value: Long) = if (value == 1L) "" else "s"

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMillis))
