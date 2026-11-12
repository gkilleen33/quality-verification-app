package com.qualityverifier.fundi.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The provider and the share intent are Android plumbing that a JVM test cannot run, so
 * what is pinned here is the agreement between the three places that name them: the
 * manifest, the paths file, and the code. A mismatch in any one is a crash at the share
 * sheet on a real phone and nowhere before.
 */
class ReportFilesTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val paths = File("src/main/res/xml/file_paths.xml").readText()

    @Test
    fun `the manifest declares the provider with the authority the code uses, not exported`() {
        assertTrue(manifest.contains("android:name=\".reports.ReportFileProvider\""))
        assertTrue(manifest.contains("android:authorities=\"${ReportFiles.AUTHORITY}\""))
        val provider = manifest.substringAfter("<provider").substringBefore("</provider>")
        assertTrue(provider.contains("android:exported=\"false\""))
        assertTrue(provider.contains("android:grantUriPermissions=\"true\""))
        assertTrue(provider.contains("android:resource=\"@xml/file_paths\""))
    }

    @Test
    fun `the provider serves only the reports folder under the cache directory`() {
        assertTrue(paths.contains("<cache-path name=\"reports\" path=\"reports/\" />"))
        assertEquals("only one path", 1, Regex("<[a-z-]+-path ").findAll(paths).count())
    }

    @Test
    fun `a report is shared as a PDF`() {
        assertEquals("application/pdf", ReportFiles.MIME_TYPE)
    }
}
