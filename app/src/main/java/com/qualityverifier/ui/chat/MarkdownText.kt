package com.qualityverifier.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.qualityverifier.text.MdBlock
import com.qualityverifier.text.MdInline
import com.qualityverifier.text.MdStyle
import com.qualityverifier.text.parseMarkdown

/**
 * Renders the Markdown Claude emits in its replies. See [parseMarkdown] for the supported
 * subset and its two deliberate deviations from CommonMark.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    // Parsing is pure and depends only on the string, so it survives recomposition.
    val blocks = remember(text) { parseMarkdown(text) }

    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(block.spacingBefore()))
            when (block) {
                is MdBlock.Paragraph -> Text(block.content.annotated(), style = style)

                is MdBlock.Heading -> Text(
                    text = block.content.annotated(),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> style.copy(fontWeight = FontWeight.Bold)
                    },
                )

                is MdBlock.Bullet -> MarkerRow(
                    marker = "•",
                    content = block.content.annotated(),
                    indent = block.indent,
                    style = style,
                )

                is MdBlock.Numbered -> MarkerRow(
                    marker = "${block.number}.",
                    content = block.content.annotated(),
                    indent = block.indent,
                    style = style,
                )

                MdBlock.Rule -> HorizontalDivider(Modifier.fillMaxWidth())
            }
        }
    }
}

/** List items sit closer together than paragraphs so a list reads as one thing. */
private fun MdBlock.spacingBefore() = when (this) {
    is MdBlock.Bullet, is MdBlock.Numbered -> 4.dp
    is MdBlock.Heading -> 12.dp
    else -> 8.dp
}

@Composable
private fun MarkerRow(
    marker: String,
    content: AnnotatedString,
    indent: Int,
    style: TextStyle,
) {
    Row(Modifier.padding(start = (indent.coerceAtMost(3) * 16).dp)) {
        Text(marker, style = style)
        Spacer(Modifier.width(8.dp))
        Text(content, style = style, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MdInline.annotated(): AnnotatedString = buildAnnotatedString {
    append(text)
    spans.forEach { span ->
        // Guard against a malformed span rather than letting it throw in the UI.
        if (span.start in 0..span.end && span.end <= text.length) {
            addStyle(span.style.toSpanStyle(), span.start, span.end)
        }
    }
}

@Composable
private fun MdStyle.toSpanStyle(): SpanStyle = when (this) {
    MdStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    MdStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    MdStyle.CODE -> SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    MdStyle.LINK -> SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
}
