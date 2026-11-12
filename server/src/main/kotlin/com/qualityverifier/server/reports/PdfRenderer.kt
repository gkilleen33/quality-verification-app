package com.qualityverifier.server.reports

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.Image
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.Phrase
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.BaseFont
import com.lowagie.text.pdf.ColumnText
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfPageEventHelper
import com.lowagie.text.pdf.PdfWriter
import com.qualityverifier.domain.HeadingBlock
import com.qualityverifier.domain.LineBlock
import com.qualityverifier.domain.NamesBlock
import com.qualityverifier.domain.NoteBlock
import com.qualityverifier.domain.PhotosBlock
import com.qualityverifier.domain.ProblemBlock
import com.qualityverifier.domain.TextBlock
import com.qualityverifier.text.ReportLabels
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Where a photo's bytes come from. `BlobStore.read` in production; a map in tests. */
fun interface PhotoSource {
    fun read(sha256: String): ByteArray?
}

/**
 * Draws a composed report as a PDF with OpenPDF (plan v7 §5.1), under the size cap of §5.3.
 *
 * One font, Noto Sans, embedded as a subset, so a label such as "Kiti cha Wanjirũ" or a
 * Greek or Cyrillic name prints as typed; a character the font lacks (an emoji, Arabic or
 * CJK) becomes "?" before the text reaches the writer, so nothing can fail a render.
 *
 * Photos are decoded, downscaled and re-encoded one at a time: a stored photo is up to
 * 2,576 px on its long edge (v0.4.0), about 15 to 20 MB decoded, and two of those at once
 * on the 640 MB heap beside Postgres is the figure §5.6 sets the render budget by.
 */
open class PdfRenderer(private val fonts: ReportFonts = ReportFonts.load()) {

    private val log = LoggerFactory.getLogger(PdfRenderer::class.java)

    /** How photos are re-encoded: the long edge in pixels and the JPEG quality. */
    data class PhotoSettings(val longEdge: Int, val quality: Float)

    /** A render that fit the cap: its bytes, how many photos it kept, and at which settings. */
    data class Rendered(val bytes: ByteArray, val photosPrinted: Int, val settings: PhotoSettings)

    /**
     * Renders under the cap (§5.3): first at the normal settings with every planned photo;
     * if the file is still too large, at the smaller settings; then with photos dropped from
     * the oldest assessment first, one at a time, until it fits. Never a problem or an
     * assessment: text is what the reader acts on.
     */
    open fun renderWithinCap(content: ReportContent, photos: PhotoSource): Rendered {
        val planned = content.photos
        var settings = FIRST
        var bytes = render(content, photos, settings, planned)
        if (bytes.size <= MAX_BYTES) return Rendered(bytes, planned.size, settings)

        settings = SMALLER
        bytes = render(content, photos, settings, planned)
        if (bytes.size <= MAX_BYTES) return Rendered(bytes, planned.size, settings)

        var kept = planned
        while (kept.isNotEmpty() && bytes.size > MAX_BYTES) {
            kept = kept.drop(1)
            bytes = render(content, photos, settings, kept)
        }
        if (bytes.size > MAX_BYTES) {
            // Text alone over the cap: a piece with very many assessments. Sent as it is,
            // and logged, since the cap is a download-time budget rather than a limit.
            log.warn("Report over the size cap with no photos: {} bytes", bytes.size)
        }
        return Rendered(bytes, kept.size, settings)
    }

    /**
     * One render. [keep] names the photos to print, in the order [ReportContent.photos]
     * gives them; a planned photo not in it is left out, and so is one that will not decode.
     */
    fun render(
        content: ReportContent,
        photos: PhotoSource,
        settings: PhotoSettings = FIRST,
        keep: List<String> = content.photos,
    ): ByteArray {
        val keepSet = keep.toSet()
        val out = ByteArrayOutputStream()
        val document = Document(PageSize.A4, MARGIN, MARGIN, MARGIN, MARGIN)
        val writer = PdfWriter.getInstance(document, out)
        writer.pageEvent = Footer(fonts)
        document.open()
        for (block in content.blocks) {
            when (block) {
                // Level 1 the title, 2 an assessment, 3 a part, 4 a line within a part
                // ("Found this time").
                is HeadingBlock -> document.add(
                    paragraph(
                        block.text,
                        when {
                            block.level <= 1 -> fonts.title
                            block.level == 2 -> fonts.heading
                            block.level == 3 -> fonts.subheading
                            else -> fonts.bold
                        },
                    ).apply { spacingBefore = if (block.level <= 1) 0f else if (block.level >= 4) 6f else 10f; spacingAfter = 4f },
                )
                is TextBlock -> document.add(paragraph(block.text, fonts.body).apply { spacingAfter = 4f })
                is NoteBlock -> document.add(paragraph(block.text, fonts.note).apply { spacingBefore = 4f; spacingAfter = 4f })
                is LineBlock -> document.add(
                    Paragraph().apply {
                        add(chunk("${block.label}: ", fonts.bold))
                        add(chunk(block.value, fonts.body))
                        spacingAfter = 4f
                    },
                )
                is NamesBlock -> document.add(
                    Paragraph().apply {
                        add(chunk("${block.label} ", fonts.bold))
                        add(chunk(block.names.joinToString(", "), fonts.body))
                        spacingAfter = 4f
                    },
                )
                is ProblemBlock -> document.add(problem(block, content.labels))
                is PhotosBlock -> {
                    val shown = block.sha256.filter { it in keepSet }
                    if (shown.isNotEmpty()) document.add(photoTable(shown, photos, settings))
                }
            }
        }
        document.close()
        return out.toByteArray()
    }

    /** The three headings follow the report's language, as the verdict card's do. */
    private fun problem(block: ProblemBlock, labels: ReportLabels): PdfPTable {
        // One cell with a thin left rule, so the problem reads as one item on the page
        // and cannot split its title from its lines across a column.
        val cell = PdfPCell().apply {
            border = Rectangle.LEFT
            borderWidthLeft = 1.5f
            borderColorLeft = Color(90, 90, 90)
            paddingLeft = 8f
            paddingBottom = 6f
            paddingTop = 2f
        }
        val heading = Paragraph().apply {
            add(chunk(block.title, fonts.bold))
            val tags = listOfNotNull(block.severity.takeIf { it.isNotBlank() }, block.part)
            if (tags.isNotEmpty()) add(chunk("  (${tags.joinToString(", ")})", fonts.note))
        }
        cell.addElement(heading)
        listOf(
            labels.whatISeeHeading to block.whatISee,
            labels.whatItMeansHeading to block.whatItMeans,
            labels.whatToDoHeading to block.whatToDo.orEmpty(),
        ).forEach { (label, text) ->
            if (text.isNotBlank()) {
                cell.addElement(
                    Paragraph().apply {
                        add(chunk("$label  ", fonts.small))
                        add(chunk(text, fonts.body))
                    },
                )
            }
        }
        return PdfPTable(1).apply {
            widthPercentage = 100f
            setSpacingBefore(4f)
            setSpacingAfter(6f)
            keepTogether = true
            addCell(cell)
        }
    }

    /** Two photos a row, each scaled to its cell. One decoded at a time (see the class comment). */
    private fun photoTable(shas: List<String>, photos: PhotoSource, settings: PhotoSettings): PdfPTable {
        val table = PdfPTable(2).apply {
            widthPercentage = 100f
            setSpacingBefore(4f)
            setSpacingAfter(6f)
        }
        var added = 0
        for (sha in shas) {
            val jpeg = photos.read(sha)?.let { bytes -> runCatching { downscale(bytes, settings) }.getOrNull() }
            if (jpeg == null) {
                // A photo that is missing or will not decode costs the photo, never the report.
                log.warn("Photo {} left out of a report: missing or undecodable", sha.take(12))
                continue
            }
            val image = Image.getInstance(jpeg)
            table.addCell(
                PdfPCell(image, true).apply {
                    border = Rectangle.NO_BORDER
                    setPadding(3f)
                    horizontalAlignment = Element.ALIGN_CENTER
                },
            )
            added++
        }
        if (added % 2 == 1) table.addCell(PdfPCell().apply { border = Rectangle.NO_BORDER })
        return table
    }

    private fun paragraph(text: String, font: Font): Paragraph = Paragraph(fonts.sanitise(text), font)

    private fun chunk(text: String, font: Font) = com.lowagie.text.Chunk(fonts.sanitise(text), font)

    /** The footer: "Made with Fundi Bora" is a block of the content; this is the page number only. */
    private class Footer(private val fonts: ReportFonts) : PdfPageEventHelper() {
        override fun onEndPage(writer: PdfWriter, document: Document) {
            ColumnText.showTextAligned(
                writer.directContent,
                Element.ALIGN_CENTER,
                Phrase(writer.pageNumber.toString(), fonts.note),
                (document.right() + document.left()) / 2,
                document.bottom() - 18f,
                0f,
            )
        }
    }

    companion object {
        /** 512,000 bytes (§5.3): under two minutes on a 40 kbps link. */
        const val MAX_BYTES = 512_000

        /** 600 px on the long edge at JPEG quality 65: about 30 to 50 KB a photo. */
        val FIRST = PhotoSettings(longEdge = 600, quality = 0.65f)

        /** 480 px at quality 55: about 20 to 30 KB, the second try. */
        val SMALLER = PhotoSettings(longEdge = 480, quality = 0.55f)

        private const val MARGIN = 48f

        /**
         * Decodes one photo, scales it to [settings], and re-encodes it as JPEG. The
         * decoded image is the large object; it is out of scope when this returns.
         */
        fun downscale(jpeg: ByteArray, settings: PhotoSettings): ByteArray {
            ImageIO.setUseCache(false)
            val source = ImageIO.read(ByteArrayInputStream(jpeg)) ?: throw IllegalArgumentException("not an image")
            val scaled = scale(source, settings.longEdge)
            source.flush()
            return encodeJpeg(scaled, settings.quality)
        }

        /** Halves until within twice the target, then one bilinear step: cheap and without aliasing. */
        private fun scale(source: BufferedImage, longEdge: Int): BufferedImage {
            var current = source
            var width = source.width
            var height = source.height
            val target = longEdge.toFloat() / maxOf(width, height)
            if (target >= 1f) return toRgb(source)
            while (maxOf(width, height) / 2 >= longEdge) {
                width /= 2
                height /= 2
                current = draw(current, width, height)
            }
            val finalWidth = maxOf(1, Math.round(source.width * target))
            val finalHeight = maxOf(1, Math.round(source.height * target))
            return if (width == finalWidth && height == finalHeight) toRgb(current) else draw(current, finalWidth, finalHeight)
        }

        private fun draw(source: BufferedImage, width: Int, height: Int): BufferedImage {
            val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics = out.createGraphics()
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
            graphics.dispose()
            return out
        }

        /** JPEG has no alpha; a decoded PNG or a CMYK JPEG is redrawn as RGB first. */
        private fun toRgb(image: BufferedImage): BufferedImage =
            if (image.type == BufferedImage.TYPE_INT_RGB) image else draw(image, image.width, image.height)

        private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            return ByteArrayOutputStream().use { out ->
                ImageIO.createImageOutputStream(out).use { stream ->
                    writer.output = stream
                    writer.write(null, IIOImage(image, null, null), param)
                }
                writer.dispose()
                out.toByteArray()
            }
        }
    }
}

/**
 * Noto Sans Regular and Bold (SIL Open Font License 1.1), from the server's resources,
 * and the fonts the renderer draws with. Loaded once at start, so a missing resource fails
 * the boot rather than the first report.
 */
class ReportFonts private constructor(
    private val regular: BaseFont,
    private val boldFace: BaseFont,
) {
    val title = Font(boldFace, 18f)
    val heading = Font(boldFace, 13f)
    val subheading = Font(boldFace, 11f)
    val bold = Font(boldFace, 10f)
    val body = Font(regular, 10f)
    val small = Font(boldFace, 8f, Font.NORMAL, Color(110, 110, 110))
    val note = Font(regular, 8f, Font.NORMAL, Color(110, 110, 110))

    /** Replaces every character the font cannot draw with "?", so a render cannot fail on one. */
    fun sanitise(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val count = Character.charCount(codePoint)
            when {
                codePoint == '\n'.code || codePoint == '\t'.code -> out.append(' ')
                Character.isISOControl(codePoint) -> Unit
                regular.charExists(codePoint) -> out.appendCodePoint(codePoint)
                else -> out.append('?')
            }
            i += count
        }
        return out.toString()
    }

    companion object {
        private const val REGULAR = "/fonts/NotoSans-Regular.ttf"
        private const val BOLD = "/fonts/NotoSans-Bold.ttf"

        fun load(): ReportFonts = ReportFonts(font(REGULAR), font(BOLD))

        private fun font(resource: String): BaseFont {
            val bytes = ReportFonts::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: error("missing font resource $resource")
            // Identity-H with the bytes given: the font is embedded as a subset of the glyphs
            // used, a few tens of KB, whatever the full file weighs.
            return BaseFont.createFont(resource.substringAfterLast('/'), BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null)
        }
    }
}
