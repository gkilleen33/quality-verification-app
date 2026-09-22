package com.qualityverifier.ui.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.TestDiagram

/**
 * Schematic drawings for the three hands-on tests whose motion words struggle with.
 *
 * Drawn in code rather than shipped as vector XML because each one needs two colours —
 * the piece of furniture in the text colour, the motion in an accent — and a single
 * tinted drawable cannot do that. Drawing them here also means they follow the palette
 * into dark mode for free.
 *
 * The rest of the tests get no diagram. "Press your thumbnail into the underside" does
 * not need a picture, and a drawing per test would be noise that teaches the reader to
 * skip past all of them.
 */
@Composable
fun TestDiagramImage(
    kind: TestDiagram,
    objectColor: Color,
    motionColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(DIAGRAM_HEIGHT.dp),
    ) {
        // Everything below is laid out in a 150 x 92 design space and scaled to fit,
        // so the drawing code stays readable as plain coordinates.
        val scale = minOf(size.width / DESIGN_W, size.height / DESIGN_H)
        val offsetX = (size.width - DESIGN_W * scale) / 2f
        val offsetY = (size.height - DESIGN_H * scale) / 2f
        val d = Design(this, scale, offsetX, offsetY, objectColor, motionColor)
        when (kind) {
            TestDiagram.RACKING -> d.racking()
            TestDiagram.SIGHT_ALONG -> d.sightAlong()
            TestDiagram.ONE_LEG_LIFT -> d.oneLegLift()
        }
    }
}

private const val DESIGN_W = 150f
private const val DESIGN_H = 92f
private const val DIAGRAM_HEIGHT = 104

/** Maps design-space coordinates onto the canvas and draws the primitives. */
private class Design(
    private val scope: DrawScope,
    private val scale: Float,
    private val dx: Float,
    private val dy: Float,
    private val objectColor: Color,
    private val motionColor: Color,
) {
    private fun p(x: Float, y: Float) = Offset(dx + x * scale, dy + y * scale)

    private fun line(
        x1: Float, y1: Float, x2: Float, y2: Float,
        color: Color = objectColor,
        width: Float = 3f,
        dashed: Boolean = false,
    ) = scope.drawLine(
        color = color,
        start = p(x1, y1),
        end = p(x2, y2),
        strokeWidth = width * scale,
        cap = StrokeCap.Round,
        pathEffect = if (dashed) {
            PathEffect.dashPathEffect(floatArrayOf(4f * scale, 4f * scale))
        } else {
            null
        },
    )

    /** An arrow from one point to another, with a solid head at the far end. */
    private fun arrow(x1: Float, y1: Float, x2: Float, y2: Float) {
        line(x1, y1, x2, y2, color = motionColor, width = 3f)
        val angle = kotlin.math.atan2(y2 - y1, x2 - x1)
        val head = 7f
        val spread = 0.45f
        val tip = p(x2, y2)
        val left = p(
            x2 - head * kotlin.math.cos(angle - spread),
            y2 - head * kotlin.math.sin(angle - spread),
        )
        val right = p(
            x2 - head * kotlin.math.cos(angle + spread),
            y2 - head * kotlin.math.sin(angle + spread),
        )
        scope.drawPath(
            path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(left.x, left.y)
                lineTo(right.x, right.y)
                close()
            },
            color = motionColor,
        )
    }

    private fun curve(
        x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float,
        color: Color = objectColor,
        width: Float = 3f,
    ) {
        val start = p(x1, y1)
        val control = p(cx, cy)
        val end = p(x2, y2)
        scope.drawPath(
            path = Path().apply {
                moveTo(start.x, start.y)
                quadraticTo(control.x, control.y, end.x, end.y)
            },
            color = color,
            style = Stroke(width = width * scale, cap = StrokeCap.Round),
        )
    }

    /** Two hands pushing opposite corners of a seat in opposite directions. */
    fun racking() {
        // Seat, seen slightly from above so both near corners are visible.
        line(40f, 26f, 110f, 26f)
        line(40f, 26f, 48f, 18f)
        line(110f, 26f, 118f, 18f)
        line(48f, 18f, 118f, 18f)
        // Legs and the stretcher between them.
        line(44f, 26f, 42f, 78f)
        line(106f, 26f, 108f, 78f)
        line(44f, 58f, 106f, 58f, width = 2.5f)
        // Ground.
        line(30f, 82f, 120f, 82f, width = 2f, dashed = true)
        // The motion: near corner pushed away, far corner pulled towards you.
        arrow(20f, 34f, 38f, 27f)
        arrow(130f, 12f, 114f, 18f)
    }

    /** An eye down at surface level, looking along a top that is not quite flat. */
    fun sightAlong() {
        // The surface, bowed upward in the middle.
        curve(20f, 48f, 75f, 34f, 130f, 46f)
        // A straight reference so the bow is legible rather than just a curve.
        line(20f, 48f, 130f, 46f, width = 1.5f, dashed = true)
        // Support underneath, to read as a table rather than a floating line.
        line(30f, 48f, 30f, 74f, width = 2.5f)
        line(120f, 47f, 120f, 74f, width = 2.5f)
        line(22f, 78f, 128f, 78f, width = 2f, dashed = true)
        // The eye, at the height of the surface.
        scope.drawCircle(
            color = motionColor,
            radius = 5f * scale,
            center = p(12f, 48f),
        )
        arrow(18f, 48f, 44f, 46f)
    }

    /** Lifting by one leg: the frame twists and the far corner drops out of square. */
    fun oneLegLift() {
        // Seat, tilted, because one corner is off the ground.
        line(38f, 30f, 112f, 22f)
        // The lifted leg, clear of the floor.
        line(42f, 30f, 40f, 62f)
        // The planted leg.
        line(108f, 23f, 110f, 78f)
        // Ground, with a visible gap under the lifted leg.
        line(28f, 82f, 122f, 82f, width = 2f, dashed = true)
        // What to watch: the far corner sagging away from square.
        curve(108f, 23f, 118f, 40f, 112f, 56f, color = motionColor, width = 2.5f)
        // The lift itself.
        arrow(40f, 74f, 40f, 50f)
    }
}
