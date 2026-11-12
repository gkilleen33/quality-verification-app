package com.qualityverifier.server

import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.server.db.ReportPiece
import com.qualityverifier.server.db.ReportReply
import com.qualityverifier.server.db.ReportSession
import com.qualityverifier.server.db.ReportWorker
import com.qualityverifier.server.reports.PhotoSource
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.random.Random

/**
 * Made-up pieces for the report tests: sessions built from the golden replies under
 * `src/test/resources/replies/`, photos that are the size a phone sends, and a reader for
 * what a PDF says.
 */
object ReportFixtures {

    const val MAKER = "11111111-2222-3333-4444-555555555555"
    const val PIECE_ID = "c0ffee00-0000-4000-8000-00000000001a"
    const val KAMAU = "c0ffee00-0000-4000-8000-0000000000a1"
    const val OTIENO = "c0ffee00-0000-4000-8000-0000000000a2"

    /** 12 November 2026, 10:00 in Nairobi. */
    const val DAY_ONE = 1_794_466_800_000L
    const val A_DAY = 24L * 60 * 60 * 1000

    val piece = ReportPiece(id = PIECE_ID, userId = MAKER, itemTypeId = "wooden-table", label = "Kiti cha Wanjirũ")

    /** The replies of a golden session, oldest first. */
    fun replies(name: String): List<String> {
        val url = ReportFixtures::class.java.getResource("/replies/$name.txt")
            ?: error("no test reply at src/test/resources/replies/$name.txt")
        val lines = url.readText().lines()
        val replies = mutableListOf<StringBuilder>()
        lines.drop(1).forEach { line ->
            if (line.trim() == "--- reply ---") replies += StringBuilder()
            else replies.last().appendLine(line)
        }
        return replies.map { it.toString().trim() }
    }

    /** Today's shape: a table with four verdict defects, English. */
    val TABLE: List<String> by lazy { replies("today_table_evaluation") }

    /** The one-list shape: a sofa with four findings, Swahili. */
    val SOFA: List<String> by lazy { replies("one_list_sofa_evaluation") }

    /** A verdict-only reply with no problems: "all fixed". */
    const val ALL_FIXED = "```qv-verdict\n{\"verdict\":\"sound\",\"language\":\"en\",\"headline\":\"All put right\",\"summary\":\"Nothing left to fix.\",\"defects\":[]}\n```"

    fun session(
        id: String,
        createdAt: Long,
        replies: List<String>,
        photos: List<String> = emptyList(),
        workers: List<ReportWorker> = emptyList(),
        verdictLanguage: String? = null,
        clientDeleted: Boolean = false,
        intake: String? = "fundi-en-evaluate",
        messageCount: Int = replies.size * 2,
    ): ReportSession = ReportSession(
        id = id,
        createdAt = createdAt,
        clientDeleted = clientDeleted,
        intakeAnswers = intake,
        verdictLanguage = verdictLanguage,
        messageCount = messageCount,
        replies = replies.mapIndexed { index, text -> ReportReply("$id-r$index", text) },
        photos = photos,
        workers = workers,
    )

    fun worker(id: String?, name: String?, part: WorkPart?) = ReportWorker(id, part, name)

    /** A photo hash for tests: 64 hex characters from a short tag. */
    fun sha(tag: String): String = tag.hashCode().toUInt().toString(16).padStart(8, '0').repeat(8)

    /**
     * A JPEG of the size a phone sends since v0.4.0: 2,576 by 1,932 px, with enough
     * texture that it does not compress to nothing. Built once; the tests share it.
     */
    val PHONE_PHOTO: ByteArray by lazy { jpeg(2576, 1932, seed = 7) }

    fun jpeg(width: Int, height: Int, seed: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val random = Random(seed)
        val graphics = image.createGraphics()
        graphics.color = Color(200, 170, 120)
        graphics.fillRect(0, 0, width, height)
        // Grain and a few planks, so the JPEG has the entropy of a workshop photo.
        repeat(40) {
            graphics.color = Color(120 + random.nextInt(80), 80 + random.nextInt(60), 40 + random.nextInt(40))
            graphics.fillRect(random.nextInt(width), random.nextInt(height), random.nextInt(width / 2), random.nextInt(height / 8))
        }
        graphics.dispose()
        val raster = image.raster
        val pixel = IntArray(3)
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                raster.getPixel(x, y, pixel)
                val noise = random.nextInt(-18, 18)
                pixel[0] = (pixel[0] + noise).coerceIn(0, 255)
                pixel[1] = (pixel[1] + noise).coerceIn(0, 255)
                pixel[2] = (pixel[2] + noise).coerceIn(0, 255)
                raster.setPixel(x, y, pixel)
            }
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.85f
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

    /** Every listed hash answers with the phone-sized photo. */
    fun photosOf(vararg shas: String): PhotoSource {
        val set = shas.toSet()
        return PhotoSource { sha -> if (sha in set) PHONE_PHOTO else null }
    }

    /** The text of every page, in order, as OpenPDF reads it back. */
    fun text(pdf: ByteArray): String {
        val reader = PdfReader(pdf)
        val extractor = PdfTextExtractor(reader)
        return (1..reader.numberOfPages).joinToString("\n") { extractor.getTextFromPage(it) }
            .also { reader.close() }
    }

    /** Where the tests leave sample PDFs for a person to open: `server/build/report-samples/`. */
    fun save(name: String, pdf: ByteArray): File {
        val dir = File("build/report-samples").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(pdf) }
    }
}
