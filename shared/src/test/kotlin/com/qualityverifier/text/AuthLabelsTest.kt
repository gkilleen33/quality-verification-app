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
            en.delete to sw.delete,
            en.cancel to sw.cancel,
            en.signOut to sw.signOut,
            en.signOutConfirmBody to sw.signOutConfirmBody,
            en.staySignedIn to sw.staySignedIn,
            en.humanReviewNotice to sw.humanReviewNotice,
            en.savedLocation(5) to sw.savedLocation(5),
        ).forEach { (english, swahili) ->
            assertTrue("a label is empty", english.isNotBlank() && swahili.isNotBlank())
            assertTrue("\"$english\" was not translated", english != swahili)
        }
    }

    @Test
    fun `the retention windows are stated, in both languages`() {
        // These are the strings that make the retention we built true rather than a claim
        // in a document nobody reads. If somebody softens them, this fails.
        assertTrue(AuthLabels.ENGLISH.deleteReportBody.contains("7 days"))
        assertTrue(AuthLabels.SWAHILI.deleteReportBody.contains("siku 7"))
        assertTrue(AuthLabels.ENGLISH.deleteAccountBlurb.contains("30 days"))
        assertTrue(AuthLabels.SWAHILI.deleteAccountBlurb.contains("siku 30"))
        // And the confirmation repeats it: somebody reaching for an irreversible button
        // is not reading the paragraph above it.
        assertTrue(AuthLabels.ENGLISH.deleteAccountConfirmBody.contains("30 days"))
        assertTrue(AuthLabels.SWAHILI.deleteAccountConfirmBody.contains("siku 30"))
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
