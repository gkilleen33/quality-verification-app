package com.qualityverifier.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    private val policy = VersionPolicy(latest = 5, minimum = 1, downloadUrl = "https://example.test/")

    @Test
    fun `an install below latest shows the banner`() {
        assertEquals(UpdatePrompt.AVAILABLE, policy.promptFor(versionCode = 4, bannerDismissed = false))
    }

    @Test
    fun `a dismissed banner stays hidden for the rest of the run`() {
        assertEquals(UpdatePrompt.NONE, policy.promptFor(versionCode = 4, bannerDismissed = true))
    }

    @Test
    fun `an install at or above latest shows nothing`() {
        assertEquals(UpdatePrompt.NONE, policy.promptFor(versionCode = 5, bannerDismissed = false))
        // A build newer than the server knows about: a tester's, before the deploy.
        assertEquals(UpdatePrompt.NONE, policy.promptFor(versionCode = 6, bannerDismissed = false))
    }

    @Test
    fun `below the minimum blocks, and dismissing the banner does not get round it`() {
        val raised = policy.copy(minimum = 5)
        assertEquals(UpdatePrompt.REQUIRED, raised.promptFor(versionCode = 4, bannerDismissed = false))
        assertEquals(UpdatePrompt.REQUIRED, raised.promptFor(versionCode = 4, bannerDismissed = true))
    }

    @Test
    fun `the server refuses only a known version below the minimum`() {
        val raised = policy.copy(minimum = 5)
        assertTrue(raised.refuses(4))
        assertFalse(raised.refuses(5))
        // No header: an app older than the header. Never refused.
        assertFalse(raised.refuses(null))
    }

    @Test
    fun `with the minimum at 1 nothing is refused`() {
        assertFalse(VersionPolicy.UNCONFIGURED.refuses(1))
        assertFalse(VersionPolicy.UNCONFIGURED.refuses(null))
        assertEquals(UpdatePrompt.NONE, VersionPolicy.UNCONFIGURED.promptFor(3, bannerDismissed = false))
    }

    @Test
    fun `the version header reads only a positive integer`() {
        assertEquals(4, AppVersionHeaders.parseVersion("4"))
        assertEquals(4, AppVersionHeaders.parseVersion(" 4 "))
        assertNull(AppVersionHeaders.parseVersion(null))
        assertNull(AppVersionHeaders.parseVersion(""))
        assertNull(AppVersionHeaders.parseVersion("four"))
        assertNull(AppVersionHeaders.parseVersion("0"))
        assertNull(AppVersionHeaders.parseVersion("-3"))
    }

    @Test
    fun `app ids are the two apps, and nothing else`() {
        assertEquals(AppId.KAGUA, AppId.fromId("kagua"))
        assertEquals(AppId.FUNDI, AppId.fromId(" Fundi "))
        assertNull(AppId.fromId("buyer"))
        assertNull(AppId.fromId(null))
    }
}
