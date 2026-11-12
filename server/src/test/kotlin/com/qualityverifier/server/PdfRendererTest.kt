package com.qualityverifier.server

import com.qualityverifier.domain.ReportKind
import com.qualityverifier.server.ReportFixtures.A_DAY
import com.qualityverifier.server.ReportFixtures.DAY_ONE
import com.qualityverifier.server.ReportFixtures.session
import com.qualityverifier.server.ReportFixtures.sha
import com.qualityverifier.server.db.ReportPiece
import com.qualityverifier.server.reports.PdfRenderer
import com.qualityverifier.server.reports.PhotoSource
import com.qualityverifier.server.reports.ReportAssessment
import com.qualityverifier.server.reports.ReportComposer
import com.qualityverifier.server.reports.ReportContent
import com.qualityverifier.server.reports.ReportFonts
import com.qualityverifier.server.reports.ReportInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

/**
 * The PDF itself: that OpenPDF draws what the composer said, with the embedded font, under
 * the size cap, one decoded photo at a time, and within the render budget of plan v7 §5.6.
 *
 * The measurement test prints its figures and writes them with the sample PDFs to
 * `server/build/report-samples/`, which is where the PR description's numbers come from.
 */
class PdfRendererTest {

    private val renderer = PdfRenderer()
    private val composer = ReportComposer()

    private fun customer(piece: ReportPiece = ReportFixtures.piece, vararg sessions: com.qualityverifier.server.db.ReportSession): ReportContent =
        composer.compose(
            ReportInput(
                kind = ReportKind.CUSTOMER,
                piece = piece,
                assessments = sessions.map(ReportAssessment::of),
                makerName = null,
                madeAt = DAY_ONE + 3 * A_DAY,
            ),
        )

    @Test
    fun `a report of three assessments contains all three dates and every problem`() {
        val content = customer(
            ReportFixtures.piece,
            session("s1", DAY_ONE, ReportFixtures.TABLE),
            session("s2", DAY_ONE + A_DAY, ReportFixtures.SOFA, verdictLanguage = "en"),
            session("s3", DAY_ONE + 2 * A_DAY, listOf(ReportFixtures.ALL_FIXED)),
        )
        val pdf = renderer.render(content, PhotoSource { null })
        val text = ReportFixtures.text(pdf)

        listOf("12 Nov 2026", "13 Nov 2026", "14 Nov 2026").forEach { assertTrue(it, text.contains(it)) }
        assertTrue(text, text.contains("Gap at the back left joint"))
        assertTrue(text, text.contains("Kiungo cha reli ya nyuma kimefunguka"))
        assertTrue(text, text.contains("All put right"))
        assertTrue(text, text.contains("Made with Fundi Bora"))
        ReportFixtures.save("customer_three_assessments.pdf", pdf)
    }

    @Test
    fun `a label with accented and non-Latin characters renders intact, and an emoji becomes a question mark`() {
        val fonts = ReportFonts.load()
        assertEquals("Kiti cha Wanjirũ, café, Ελλάδα ?", fonts.sanitise("Kiti cha Wanjirũ, café, Ελλάδα 🪑"))
        assertEquals("Кресло", fonts.sanitise("Кресло"))

        val content = customer(
            ReportFixtures.piece.copy(label = "Kiti cha Wanjirũ, café, Ελλάδα 🪑"),
            session("s1", DAY_ONE, ReportFixtures.TABLE),
        )
        val pdf = renderer.render(content, PhotoSource { null })
        val text = ReportFixtures.text(pdf)
        assertTrue(text, text.contains("Kiti cha Wanjirũ, café, Ελλάδα ?"))
        ReportFixtures.save("customer_label_characters.pdf", pdf)
    }

    @Test
    fun `the font is embedded as a subset, so the file stays small without photos`() {
        val content = customer(ReportFixtures.piece, session("s1", DAY_ONE, ReportFixtures.TABLE))
        val pdf = renderer.render(content, PhotoSource { null })
        // Two full Noto Sans files are 1.6 MB; a subset of the glyphs used is a few tens of KB.
        assertTrue("${pdf.size} bytes", pdf.size < 120_000)
        assertTrue(String(pdf, Charsets.ISO_8859_1).contains("NotoSans"))
    }

    @Test
    fun `a 20-photo piece renders under 512000 bytes with photos decoded one at a time`() {
        val shas = (1..20).map { sha("photo-$it") }
        val content = customer(
            ReportFixtures.piece,
            session("s1", DAY_ONE, ReportFixtures.TABLE, photos = shas.take(10)),
            session("s2", DAY_ONE + A_DAY, listOf(ReportFixtures.ALL_FIXED), photos = shas.drop(10)),
        )
        assertEquals(8, content.photos.size)

        // Counts how many photos are decoded at once: the renderer must never hold two.
        var open = 0
        var mostOpen = 0
        val source = PhotoSource { sha ->
            if (sha !in shas) null else {
                synchronized(this) { open++; mostOpen = maxOf(mostOpen, open) }
                ReportFixtures.PHONE_PHOTO
            }
        }
        val rendered = renderer.renderWithinCap(content, source)

        assertTrue("${rendered.bytes.size} bytes", rendered.bytes.size <= PdfRenderer.MAX_BYTES)
        assertEquals(8, rendered.photosPrinted)
        assertTrue(ReportFixtures.text(rendered.bytes).contains("Gap at the back left joint"))
        ReportFixtures.save("customer_twenty_photos.pdf", rendered.bytes)
    }

    @Test
    fun `a photo that is missing or will not decode costs the photo and never the report`() {
        val content = customer(
            ReportFixtures.piece,
            session("s1", DAY_ONE, ReportFixtures.TABLE, photos = listOf(sha("good"), sha("missing"), sha("broken"))),
        )
        val source = PhotoSource { sha ->
            when (sha) {
                sha("good") -> ReportFixtures.PHONE_PHOTO
                sha("broken") -> "not a jpeg".toByteArray()
                else -> null
            }
        }
        val pdf = renderer.render(content, source)
        assertTrue(ReportFixtures.text(pdf).contains("Strong table"))
    }

    @Test
    fun `downscaling keeps the long edge at the setting and the proportions of the photo`() {
        val small = PdfRenderer.downscale(ReportFixtures.PHONE_PHOTO, PdfRenderer.FIRST)
        val image = javax.imageio.ImageIO.read(small.inputStream())
        assertEquals(600, image.width)
        assertEquals(450, image.height)
        assertTrue("${small.size} bytes", small.size < 80_000)
        val smaller = PdfRenderer.downscale(ReportFixtures.PHONE_PHOTO, PdfRenderer.SMALLER)
        assertTrue(smaller.size < small.size)
    }

    /**
     * The spike measurement (plan v7 §5.1 and §5.6, folded into this PR): three assessments
     * and 18 photos, timed, with the heap's peak use. Over 5 seconds or 150 MB would mean
     * PDFBox instead; the figures go in the PR description.
     */
    @Test
    fun `three assessments and 18 photos render within the budget`() {
        val shas = (1..18).map { sha("spike-$it") }
        val content = customer(
            ReportFixtures.piece,
            session("s1", DAY_ONE, ReportFixtures.TABLE, photos = shas.take(6)),
            session("s2", DAY_ONE + A_DAY, ReportFixtures.SOFA, verdictLanguage = "en", photos = shas.drop(6).take(6)),
            session("s3", DAY_ONE + 2 * A_DAY, listOf(ReportFixtures.ALL_FIXED), photos = shas.drop(12)),
        )
        val source = ReportFixtures.photosOf(*shas.toTypedArray())
        // The photo fixture is built lazily; make it before the clock starts.
        ReportFixtures.PHONE_PHOTO.size

        val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        System.gc()
        heapPools.forEach { it.resetPeakUsage() }
        val used = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        val started = System.nanoTime()
        val rendered = renderer.renderWithinCap(content, source)
        val millis = (System.nanoTime() - started) / 1_000_000
        val peak = heapPools.sumOf { it.peakUsage.used }
        val peakAboveStart = (peak - used) / (1024 * 1024)

        val summary = "render: ${millis} ms; heap peak ${peak / (1024 * 1024)} MB (about ${peakAboveStart} MB above the " +
            "start); file ${rendered.bytes.size} bytes; ${rendered.photosPrinted} photos at ${rendered.settings.longEdge} px; " +
            "photo fixture ${ReportFixtures.PHONE_PHOTO.size} bytes each"
        println("Report render measurement: $summary")
        File(ReportFixtures.save("customer_spike_three_assessments.pdf", rendered.bytes).parentFile, "measurements.txt")
            .writeText(summary + "\n")

        assertTrue(summary, millis < 8_000)
        assertTrue(summary, peak < 150L * 1024 * 1024)
        assertTrue(summary, rendered.bytes.size <= PdfRenderer.MAX_BYTES)
        assertFalse(ReportFixtures.text(rendered.bytes).contains("Kamau"))
    }
}
