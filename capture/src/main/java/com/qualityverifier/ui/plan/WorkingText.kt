package com.qualityverifier.ui.plan

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

/**
 * A line that cycles through what the assistant is doing while a reply is outstanding.
 *
 * The waits are long — half a minute for a plan, longer for a verdict — and a spinner with
 * one fixed sentence under it reads, after twenty seconds, as an app that has hung. A line
 * that keeps changing says the work is still going.
 *
 * **Every phrase has to be true for the whole wait.** This is not a progress report: the
 * request is one call with no streaming, so the app cannot know which part the model is
 * on, and the earlier version of the Inspecting screen refused to tick off content areas
 * for exactly that reason. "Checking the images" is true of every moment of a request
 * that carries images; "Joints checked" would be a claim about an order of work nobody can
 * see. So the lists are activities, chosen per kind of wait, never stages — and the
 * caller picks a list that fits what was actually sent.
 *
 * Cycles in order and wraps, so the first phrase is the one shown during a short wait.
 */
@Composable
fun WorkingText(
    phrases: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    intervalMillis: Long = PHRASE_MILLIS,
) {
    if (phrases.isEmpty()) return
    var index by remember(phrases) { mutableIntStateOf(0) }
    var dots by remember { mutableIntStateOf(0) }

    LaunchedEffect(phrases, intervalMillis) {
        while (true) {
            delay(intervalMillis)
            index = (index + 1) % phrases.size
        }
    }
    // The trailing dots move faster than the phrase, so something on screen changes at
    // least twice a second even while one phrase is showing.
    LaunchedEffect(Unit) {
        while (true) {
            delay(DOT_MILLIS)
            dots = (dots + 1) % 4
        }
    }

    AnimatedContent(
        targetState = index,
        transitionSpec = {
            (fadeIn() + slideInVertically { it / 2 }) togetherWith
                (fadeOut() + slideOutVertically { -it / 2 })
        },
        modifier = modifier,
        label = "working",
    ) { shown ->
        // Dots padded to a fixed width with invisible ones, so the line does not jitter
        // sideways as they come and go.
        Text(
            text = phrases[shown] + ".".repeat(dots) + " ".repeat(3 - dots),
            style = style,
            color = color,
        )
    }
}

private const val PHRASE_MILLIS = 2_800L
private const val DOT_MILLIS = 450L
