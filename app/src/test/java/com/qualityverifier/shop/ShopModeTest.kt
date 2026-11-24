package com.qualityverifier.shop

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.qualityverifier.data.auth.EncryptedPrefsTokenStore
import com.qualityverifier.data.auth.RefreshOutcome
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
import com.qualityverifier.data.db.ImageFileStore
import com.qualityverifier.data.shop.PendingTabletEnd
import com.qualityverifier.data.shop.ShopTabletFlag
import com.qualityverifier.data.shop.ShopTabletWipe
import com.qualityverifier.data.shop.TabletEndQueue
import com.qualityverifier.data.sync.TabletEndApi
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.TabletEnd
import com.qualityverifier.domain.Usage
import com.qualityverifier.ui.chat.ChatControls
import com.qualityverifier.ui.intake.IntakeProgress
import com.qualityverifier.ui.intake.IntakeQuestion
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Kagua on a shop's tablet (release scope row 12), the parts that do not need a screen: the
 * intake's questions, the controls after a verdict, the idle watch, which end leaving the screen
 * is, a forced sign-out, which saved screens come back, and the screen timeout. The wipe itself
 * is tested in :core (ShopTabletWipeTest), and the server's refusals in SyncRouteTest and
 * ChatRouteTest.
 */
class ShopModeTest {

    // ---------------------------------------------------------------- the intake

    @Test
    fun `row 12 - the intake skips the buying question and records buying`() {
        var progress = IntakeProgress.start(needsUpholstery = false, fixedOwnership = Ownership.BUYING)

        assertEquals(IntakeQuestion.LANGUAGE, progress.question)
        progress = progress.copy(language = AssessmentLanguage.ENGLISH)
        // Straight from the language to the price: the customer is buying.
        assertEquals(IntakeQuestion.PRICE, progress.question)
        progress = progress.copy(priceAnswered = true)
        assertEquals(IntakeQuestion.USAGE, progress.question)
        progress = progress.copy(usage = Usage.DAILY)
        assertEquals(IntakeQuestion.DEPTH, progress.question)

        assertEquals(Ownership.BUYING, progress.ownership)
        // Language, price, usage, depth: four questions, none of them the buying one.
        assertEquals(4, progress.totalSteps)
    }

    @Test
    fun `row 12 - Back on the tablet's intake never reaches the buying question`() {
        var progress: IntakeProgress? = IntakeProgress.start(needsUpholstery = true, fixedOwnership = Ownership.BUYING)
            .copy(language = AssessmentLanguage.SWAHILI, upholstered = true, priceAnswered = true)
        val seen = mutableListOf<IntakeQuestion>()
        while (progress != null) {
            seen += progress.question
            assertEquals(Ownership.BUYING, progress.ownership)
            progress = progress.back()
        }
        assertFalse(seen.toString(), IntakeQuestion.OWNERSHIP in seen)
        assertEquals(IntakeQuestion.LANGUAGE, seen.last())
    }

    @Test
    fun `row 12 - a personal intake asks the buying question, counted as in v0_6_1`() {
        // v0.6.1 counted 4 + 1 for a price + 1 for the upholstery question, and showed the
        // counter only once ownership was answered.
        var progress = IntakeProgress.start(needsUpholstery = true, fixedOwnership = null)
        assertFalse(progress.showCounter)
        progress = progress.copy(language = AssessmentLanguage.ENGLISH)
        assertEquals(IntakeQuestion.UPHOLSTERY, progress.question)
        progress = progress.copy(upholstered = false)
        assertEquals(IntakeQuestion.OWNERSHIP, progress.question)
        assertEquals(3, progress.step)

        val buying = progress.copy(ownership = Ownership.BUYING)
        assertTrue(buying.showCounter)
        assertEquals(IntakeQuestion.PRICE, buying.question)
        assertEquals(6, buying.totalSteps)
        assertEquals(buying.totalSteps - 2, buying.step)

        val owning = progress.copy(ownership = Ownership.ALREADY_OWN, priceAnswered = true)
        assertEquals(IntakeQuestion.USAGE, owning.question)
        assertEquals(5, owning.totalSteps)
        assertEquals(owning.totalSteps - 1, owning.step)

        // Back from the price question returns to the buying question.
        assertEquals(IntakeQuestion.OWNERSHIP, buying.back()!!.question)
    }

    // ---------------------------------------------------------------- after the verdict

    @Test
    fun `row 12 - the next-steps card and Share are hidden on the tablet, and Done is shown`() {
        val controls = ChatControls.of(shopMode = true, hasVerdict = true, runActive = false, sending = false)

        assertFalse("Share", controls.share)
        assertFalse("next steps", controls.nextSteps)
        assertTrue("Done", controls.done)
        assertFalse("gallery", controls.gallery)
        assertFalse("Settings from a failed turn", controls.settingsOnError)
    }

    @Test
    fun `row 12 - a personal Kagua account keeps Share and the next-steps card, and has no Done`() {
        val controls = ChatControls.of(shopMode = false, hasVerdict = true, runActive = false, sending = false)

        assertTrue(controls.share)
        assertTrue(controls.nextSteps)
        assertFalse(controls.done)
        assertTrue(controls.gallery)
        assertTrue(controls.settingsOnError)
    }

    @Test
    fun `row 12 - Done waits for the verdict and for nothing to be under way`() {
        assertFalse(ChatControls.of(shopMode = true, hasVerdict = false, runActive = false, sending = false).done)
        assertFalse(ChatControls.of(shopMode = true, hasVerdict = true, runActive = true, sending = false).done)
        assertFalse(ChatControls.of(shopMode = true, hasVerdict = true, runActive = false, sending = true).done)
    }

    // ---------------------------------------------------------------- the triggers

    @Test
    fun `row 12 - five minutes with no touch is idle, and a touch starts the five minutes again`() {
        var now = 0L
        val watch = IdleWatch(5L * 60 * 1000) { now }

        now += 4L * 60 * 1000 + 59_000
        assertFalse(watch.isIdle())
        now += 1_000
        assertTrue(watch.isIdle())

        watch.touched()
        assertFalse(watch.isIdle())
        now += 5L * 60 * 1000
        assertTrue(watch.isIdle())
    }

    @Test
    fun `row 12 - the screen going off or locking is screen_lock, anything else is left_app`() {
        assertEquals(TabletEnd.SCREEN_LOCK, endOnLeaving(screenInteractive = false, keyguardLocked = false))
        assertEquals(TabletEnd.SCREEN_LOCK, endOnLeaving(screenInteractive = true, keyguardLocked = true))
        assertEquals(TabletEnd.SCREEN_LOCK, endOnLeaving(screenInteractive = false, keyguardLocked = true))
        assertEquals(TabletEnd.LEFT_APP, endOnLeaving(screenInteractive = true, keyguardLocked = false))
    }

    @Test
    fun `row 12 - the four ends are the server's four words`() {
        assertEquals(listOf("done", "left_app", "screen_lock", "idle"), TabletEnd.entries.map { it.id })
        assertNull(TabletEnd.fromId("deleted"))
    }

    // ---------------------------------------------------------------- a forced sign-out

    @Test
    fun `row 12 - a forced sign-out on a shop tablet still wipes the assessment`() = runTest {
        // A customer's verdict is on the screen when the server refuses the refresh token (it
        // expired, the owner's device was revoked in the portal, or reuse was detected). The
        // phone clears its tokens. The tablet must still be a shop's, so the customer's Done
        // (or leaving, locking, idle) still deletes the photos and the verdict.
        val flag = ShopTabletFlag(MemoryPrefs())
        val real = EncryptedPrefsTokenStore(ContextWrapper(null), flag)
        val tokens = HeldTokens(real)
        tokens.setShopMode(true) // the sign-in answer for a shop account

        val provider = TokenProvider(store = tokens, refresher = { RefreshOutcome.Rejected }, now = { 1_000L })
        assertNull("the refresh is refused", provider.accessToken())
        assertFalse("the tokens are cleared", tokens.isSignedIn())
        assertTrue("still a shop's tablet", real.isShopMode())

        val rows = mutableSetOf(SESSION)
        val images = ImageFileStore(ContextWrapper(null), filesDir = { folder.root })
        val photos = (1..3).map { n -> images.newImageFile(SESSION).apply { writeBytes(ByteArray(16) { n.toByte() }) } }
        val queue = MemoryQueue()
        val wipe = ShopTabletWipe(
            localSessionIds = { rows.toSet() },
            clearLocal = {
                rows.clear()
                images.deleteAll()
            },
            pending = queue,
            // As SyncClient does: with no sign-in nothing is sent, and the end is kept.
            api = object : TabletEndApi {
                override suspend fun reportTabletEnd(sessionId: String, end: TabletEnd) = provider.accessToken() != null
            },
            now = { 1_000L },
        )
        var photoCacheCleared = false
        // Built as QualityVerifierApp builds it: AppContainer.shopMode reads the same flag.
        val shop = ShopMode(
            isShopTablet = { flag.isShopTablet },
            tablet = wipe,
            clearPhotoCache = { photoCacheCleared = true },
            scope = this,
            clock = { 0L },
        )
        assertTrue(shop.active)

        shop.end(TabletEnd.DONE)
        advanceUntilIdle()

        assertEquals("the start screen comes back", 1, shop.resets.value)
        assertTrue("the assessment row is gone", rows.isEmpty())
        assertTrue("a photo is left", photos.none { it.exists() })
        assertTrue(photoCacheCleared)
        // Sent when the owner signs back in, by the next wipe or the next start.
        assertEquals(listOf(SESSION to TabletEnd.DONE), queue.items.map { it.sessionId to it.end })
    }

    @Test
    fun `row 12 - only the next sign-in changes whether the handset is a shop's tablet`() {
        val flag = ShopTabletFlag(MemoryPrefs())
        val store = EncryptedPrefsTokenStore(ContextWrapper(null), flag)

        store.setShopMode(true)
        store.clear()
        assertTrue("signing out keeps it", store.isShopMode())

        // A personal account signing in on the handset: its answer says false.
        store.setShopMode(false)
        assertFalse(store.isShopMode())
        assertFalse(flag.isShopTablet)
    }

    // ---------------------------------------------------------------- screens Android restores

    @Test
    fun `row 12 - a shop tablet restores no saved screen except across a rotation`() {
        // After a process death Android would reopen the earlier customer's chat; the tablet
        // starts at the empty start screen instead.
        assertFalse(restoresSavedScreens(shopTablet = true, recreatedForRotation = false))
        // Turning the tablet round mid-assessment keeps the same customer where they were.
        assertTrue(restoresSavedScreens(shopTablet = true, recreatedForRotation = true))
        // A personal phone restores as it always has.
        assertTrue(restoresSavedScreens(shopTablet = false, recreatedForRotation = false))
        assertTrue(restoresSavedScreens(shopTablet = false, recreatedForRotation = true))
    }

    @Test
    fun `row 12 - a rotation is forgotten once the screen it kept is restored`() = runTest {
        val shop = ShopMode(
            isShopTablet = { true },
            tablet = ShopTabletWipe({ emptySet() }, {}, MemoryQueue(), object : TabletEndApi {
                override suspend fun reportTabletEnd(sessionId: String, end: TabletEnd) = true
            }),
            clearPhotoCache = {},
            scope = this,
            clock = { 0L },
        )
        shop.activityDestroyed(changingConfigurations = true)
        assertTrue(shop.mayRestoreScreens())
        // The next creation is not that rotation's: Home, a crash, or the system closing it.
        assertFalse(shop.mayRestoreScreens())
        shop.activityDestroyed(changingConfigurations = false)
        assertFalse(shop.mayRestoreScreens())
    }

    // ---------------------------------------------------------------- the screen timeout

    @Test
    fun `row 12 - the tablet keeps the screen on through an assessment, a personal phone does not`() {
        assertTrue(keepsScreenOn(shopTablet = true))
        assertFalse(keepsScreenOn(shopTablet = false))
    }

    // ---------------------------------------------------------------- harness

    @get:Rule
    val folder = TemporaryFolder()

    /**
     * Tokens held in memory, as the encrypted file holds them on a phone (a JVM test has no
     * Keystore); the shop flag and [clear] are the real store's.
     */
    private class HeldTokens(private val real: EncryptedPrefsTokenStore) : TokenStore {
        private var access: String? = "expired"
        private var refresh: String? = "refresh"
        private var user: String? = "shop"
        override fun accessToken() = access
        override fun refreshToken() = refresh
        override fun userId() = user
        override fun accessTokenExpiresAt() = 0L
        override fun save(accessToken: String, expiresInSeconds: Long, refreshToken: String, userId: String) = Unit
        override fun isTester() = false
        override fun setTester(value: Boolean) = Unit
        override fun isShopMode() = real.isShopMode()
        override fun setShopMode(value: Boolean) = real.setShopMode(value)
        override fun clear() {
            access = null
            refresh = null
            user = null
            real.clear()
        }
    }

    private class MemoryQueue : TabletEndQueue {
        val items = mutableListOf<PendingTabletEnd>()
        override fun add(item: PendingTabletEnd) {
            if (items.none { it.sessionId == item.sessionId }) items += item
        }
        override fun all() = items.toList()
        override fun remove(sessionId: String) {
            items.removeAll { it.sessionId == sessionId }
        }
    }

    /** Shared preferences in memory; the shop flag stores one boolean. */
    private class MemoryPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
            values[key] as MutableSet<String>? ?: defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                puts[key!!] = value
                return this
            }

            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)

            override fun remove(key: String?): SharedPreferences.Editor {
                removals += key!!
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                if (clearAll) values.clear()
                removals.forEach { values.remove(it) }
                values.putAll(puts)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }

    private companion object {
        const val SESSION = "2ff77920-3928-46e5-8a77-5e16c1e901c6"
    }
}
