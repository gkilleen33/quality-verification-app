package com.qualityverifier.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthLabelsTest {

    @Test
    fun `the phone's language decides, because no assessment exists yet`() {
        // Unlike a report, these screens come before the customer has chosen anything.
        assertSame(AuthLabels.SWAHILI, AuthLabels.forDevice("sw"))
        assertSame(AuthLabels.SWAHILI, AuthLabels.forDevice("sw-KE"))
        assertSame(AuthLabels.ENGLISH, AuthLabels.forDevice("en"))
    }

    @Test
    fun `an unknown language falls back to English rather than guessing`() {
        assertSame(AuthLabels.ENGLISH, AuthLabels.forDevice("fr"))
        assertSame(AuthLabels.ENGLISH, AuthLabels.forDevice(null))
        assertSame(AuthLabels.ENGLISH, AuthLabels.forDevice(""))
    }

    @Test
    fun `the accuracy is substituted into the saved-location label`() {
        // Shown rather than hidden: a stored point whose precision is unstated invites
        // more trust than it deserves.
        assertEquals("Location saved (within 8m)", AuthLabels.ENGLISH.savedLocation(8))
        assertEquals("Mahali yamehifadhiwa (ndani ya mita 8)", AuthLabels.SWAHILI.savedLocation(8))
    }

    @Test
    fun `every string is translated, and none is left as its English twin`() {
        // A label added to one language and forgotten in the other would ship a form that
        // is half in each.
        val en = AuthLabels.ENGLISH
        val sw = AuthLabels.SWAHILI
        listOf(
            en.phoneLabel to sw.phoneLabel,
            en.phoneHint to sw.phoneHint,
            en.passwordLabel to sw.passwordLabel,
            en.signIn to sw.signIn,
            en.goToRegister to sw.goToRegister,
            en.createAccount to sw.createAccount,
            en.createAccountBlurb to sw.createAccountBlurb,
            en.inviteLabel to sw.inviteLabel,
            en.nameLabel to sw.nameLabel,
            en.choosePasswordLabel to sw.choosePasswordLabel,
            en.passwordHint to sw.passwordHint,
            en.accountTypeQuestion to sw.accountTypeQuestion,
            en.forMyself to sw.forMyself,
            en.forBusiness to sw.forBusiness,
            en.businessNameLabel to sw.businessNameLabel,
            en.locationBlurb to sw.locationBlurb,
            en.saveLocation to sw.saveLocation,
            en.goToSignIn to sw.goToSignIn,
            en.accountSettings to sw.accountSettings,
            en.changePassword to sw.changePassword,
            en.changePasswordBlurb to sw.changePasswordBlurb,
            en.currentPassword to sw.currentPassword,
            en.newPassword to sw.newPassword,
            en.newPasswordAgain to sw.newPasswordAgain,
            en.passwordChanged to sw.passwordChanged,
            en.deleteAccount to sw.deleteAccount,
            en.deleteAccountBlurb to sw.deleteAccountBlurb,
            en.deleteAccountConfirmTitle to sw.deleteAccountConfirmTitle,
            en.deleteAccountConfirmBody to sw.deleteAccountConfirmBody,
            en.keepAccount to sw.keepAccount,
            en.deleteReportTitle to sw.deleteReportTitle,
            en.deleteReportBody to sw.deleteReportBody,
            en.deleteReportKeepLabel to sw.deleteReportKeepLabel,
            en.deleteReportKeepDetail to sw.deleteReportKeepDetail,
            en.deleteReportPurgeLabel to sw.deleteReportPurgeLabel,
            en.deleteReportPurgeDetail to sw.deleteReportPurgeDetail,
            en.delete to sw.delete,
            en.cancel to sw.cancel,
            en.signOut to sw.signOut,
            en.signOutConfirmBody to sw.signOutConfirmBody,
            en.staySignedIn to sw.staySignedIn,
            en.dataRetentionNotice to sw.dataRetentionNotice,
            en.humanReviewNotice to sw.humanReviewNotice,
            en.savedLocation(5) to sw.savedLocation(5),
        ).forEach { (english, swahili) ->
            assertTrue("a label is empty", english.isNotBlank() && swahili.isNotBlank())
            assertTrue("\"$english\" was not translated", english != swahili)
        }
    }

    @Test
    fun `the report retention window is stated on the option it applies to`() {
        // Seven days is a property of deleting our copy, not of deleting a report, so it
        // belongs on that option and nowhere else. On the other one it would be a lie.
        assertTrue(AuthLabels.ENGLISH.deleteReportPurgeDetail.contains("7 days"))
        assertTrue(AuthLabels.SWAHILI.deleteReportPurgeDetail.contains("siku 7"))
        assertTrue("the keep option must not claim a deletion window",
            !AuthLabels.ENGLISH.deleteReportKeepDetail.contains("7"))
        assertTrue("the keep option must not claim a deletion window",
            !AuthLabels.SWAHILI.deleteReportKeepDetail.contains("7"))
    }

    @Test
    fun `both delete options say what happens to our copy`() {
        // The two do different things to somebody's photographs, so each has to say which.
        // A pair of options that read the same is a choice nobody can actually make.
        listOf(AuthLabels.ENGLISH, AuthLabels.SWAHILI).forEach { labels ->
            assertTrue("${labels.code}", labels.deleteReportKeepLabel.isNotBlank())
            assertTrue("${labels.code}", labels.deleteReportPurgeLabel.isNotBlank())
            assertTrue(
                "${labels.code}: the two options must not read the same",
                labels.deleteReportKeepLabel != labels.deleteReportPurgeLabel,
            )
            assertTrue(
                "${labels.code}: the two details must not read the same",
                labels.deleteReportKeepDetail != labels.deleteReportPurgeDetail,
            )
        }
        // And the recommendation is stated rather than implied by button placement.
        assertTrue(AuthLabels.ENGLISH.deleteReportKeepLabel.contains("recommended"))
        assertTrue(AuthLabels.SWAHILI.deleteReportKeepLabel.contains("inapendekezwa"))
        // The recommended one has to give its reason, or it is a nudge with nothing behind it.
        assertTrue(AuthLabels.ENGLISH.deleteReportKeepDetail.contains("improve"))
        assertTrue(AuthLabels.SWAHILI.deleteReportKeepDetail.contains("kuuboresha"))
    }

    @Test
    fun `deleting an account does not promise the assessments are erased`() {
        // The behaviour changed: an account is anonymised and its assessments are kept,
        // because the pilot studies them. The wording has to say so at the moment somebody
        // reaches for the button, and it must not resurrect a deletion window we no longer
        // honour — a promise of erasure we do not keep is worse than the honest version.
        listOf(AuthLabels.ENGLISH, AuthLabels.SWAHILI).forEach { labels ->
            listOf(labels.deleteAccountBlurb, labels.deleteAccountConfirmBody).forEach { body ->
                assertTrue("${labels.code}: must not claim a 30-day deletion", !body.contains("30"))
            }
        }
        // What it must say instead: the identifiers go, the assessments stay.
        assertTrue(AuthLabels.ENGLISH.deleteAccountBlurb.contains("random number"))
        assertTrue(AuthLabels.ENGLISH.deleteAccountConfirmBody.contains("random number"))
        assertTrue(AuthLabels.SWAHILI.deleteAccountBlurb.contains("namba isiyo na uhusiano"))
        assertTrue(AuthLabels.SWAHILI.deleteAccountConfirmBody.contains("namba isiyo na uhusiano"))
        // And that it cannot be undone, since there is no grace period to undo it in.
        assertTrue(AuthLabels.ENGLISH.deleteAccountConfirmBody.contains("cannot be undone"))
        assertTrue(AuthLabels.SWAHILI.deleteAccountConfirmBody.contains("haiwezi kurudishwa"))
    }

    @Test
    fun `the retention notice admits what anonymising cannot reach`() {
        // The honest part, and the reason the notice exists at registration rather than
        // only at deletion. Photographs show identifiable premises and free text may name
        // somebody; clearing profile columns cannot reach either, so the wording asks them
        // not to put personal details there rather than claiming the record is anonymous.
        assertTrue(AuthLabels.ENGLISH.dataRetentionNotice.contains("indefinitely"))
        assertTrue(AuthLabels.ENGLISH.dataRetentionNotice.contains("photos or messages"))
        assertTrue(AuthLabels.SWAHILI.dataRetentionNotice.contains("muda usiojulikana"))
        assertTrue(AuthLabels.SWAHILI.dataRetentionNotice.contains("picha au ujumbe"))
    }

    @Test
    fun `the notice says a person may read an assessment, not just a system`() {
        // "We check the quality of our assessments" sounds like automated monitoring. The
        // part somebody would want to know is that a human opens their photographs.
        assertTrue(AuthLabels.ENGLISH.humanReviewNotice.contains("Researchers"))
        assertTrue(AuthLabels.ENGLISH.humanReviewNotice.contains("photos"))
        assertTrue(AuthLabels.SWAHILI.humanReviewNotice.contains("Watafiti"))
        assertTrue(AuthLabels.SWAHILI.humanReviewNotice.contains("picha"))
    }

    @Test
    fun `location is described as optional in both languages`() {
        assertTrue(AuthLabels.ENGLISH.locationBlurb.contains("optional"))
        assertTrue(AuthLabels.SWAHILI.locationBlurb.contains("hiari"))
        // And that we do not keep watching afterwards, which is the part somebody
        // handing over a location actually worries about.
        assertTrue(AuthLabels.ENGLISH.locationBlurb.contains("do not track"))
        assertTrue(AuthLabels.SWAHILI.locationBlurb.contains("hatufuatilii"))
    }
}
