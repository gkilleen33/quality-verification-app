// Ported from Drew Burd's money-tracking-kenya (https://github.com/drewburd4/money-tracking-kenya),
// commit 5355a31: the file of the same name in android/app/src/main/java/com/utafiti/sms/.
// Changed only in its package. Part of his parser, ported with it and its tests; nothing in
// Fundi Bora v0.5.0 calls it yet (no message text leaves the phone, and Fuliza charges are not
// shown). Used under the MIT licence below, which the licence requires to travel with the file.
//
// MIT License
//
// Copyright (c) 2026 Drew Burd
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package com.qualityverifier.mpesa

/**
 * What leaves the phone for a message the parser could not read fully: the provider's
 * boilerplate with everything personal taken out, so a rule can be written on the server
 * (docs/SMS_TEMPLATES.md) without any wording that belongs to the participant.
 *
 *  - A number becomes "#". A bare run of five or more digits (a phone, till or account number,
 *    an all-digit transaction id) becomes "[numN]" with its length, except after a currency
 *    word, where an amount or balance is always "#".
 *  - A token that mixes letters and digits (a transaction code) becomes "[codeN]".
 *  - A word stays only if it is in VOCABULARY, a closed list of mobile money boilerplate that
 *    leaves out any word doubling as a given name (Grace, Faith, Leo, Said, Max). Every other
 *    word becomes "X" and a run of them collapses to one "X", so a name, a business or an
 *    account label can never travel.
 *
 * The server drops any template that still carries three digits in a row (migration 012).
 */
object MessageTemplate {
    private const val MAX_LENGTH = 400

    private val CURRENCY = setOf("ksh", "kshs", "kes", "sh", "shs")

    /** "Ksh1,400.00" is written with no space; split it so the amount is seen as a number. */
    private val GLUED_CURRENCY = Regex("""(?i)(?<![\p{L}\p{N}])(kshs?|kes|shs?)\.?(?=\d)""")
    private val TOKEN = Regex("""\p{L}+(?:-\p{L}+)+|[\p{L}\p{N}]+|\s+|.""", RegexOption.DOT_MATCHES_ALL)
    private val RUN = Regex("""\p{L}+|\p{N}+""")
    private val NUMBER_PARTS = Regex("""#(?:[.,]#)+""")
    private val X_RUN = Regex("""\bX\b(?:[\s\-'.&/]+\bX\b)+""")
    private val SPACES = Regex("""\s+""")

    fun of(text: String): String {
        val out = StringBuilder()
        var afterCurrency = false
        for (m in TOKEN.findAll(GLUED_CURRENCY.replace(text) { it.groupValues[1] + " " })) {
            val t = m.value
            when {
                t.isBlank() -> out.append(' ')
                t.length == 1 && !t[0].isLetterOrDigit() -> {
                    out.append(t)
                    // "Ksh. 50", "Bal: Ksh 94.0": punctuation does not end the amount.
                    if (t != "." && t != "," && t != ":") afterCurrency = false
                }
                t.all { it.isDigit() } -> out.append(number(t, afterCurrency))
                t.none { it.isDigit() } -> {
                    // "M-PESA" is listed whole; "top-up" is kept because both halves are listed.
                    val known = isWord(t) || (t.contains('-') && t.split('-').all { isWord(it) })
                    out.append(if (known) t else "X")
                    afterCurrency = known && CURRENCY.contains(t.lowercase())
                }
                else -> {
                    // Letters and digits together: a unit glued to a number ("500MB", "17PM")
                    // reads as its parts, anything else is a transaction code.
                    val runs = RUN.findAll(t).map { it.value }.toList()
                    val readable = runs.all { r -> r[0].isDigit() || isWord(r) }
                    if (readable) runs.forEach { r -> out.append(if (r[0].isDigit()) number(r, afterCurrency) else r) }
                    else out.append("[code").append(minOf(t.length, 99)).append(']')
                    afterCurrency = false
                }
            }
        }
        var s = NUMBER_PARTS.replace(out, "#")
        s = X_RUN.replace(s, "X")
        s = SPACES.replace(s, " ").trim()
        return if (s.length > MAX_LENGTH) s.substring(0, MAX_LENGTH) else s
    }

    /** Single letters are never kept: an initial is part of a name. */
    private fun isWord(s: String): Boolean = s.length > 1 && VOCABULARY.contains(s.lowercase())

    private fun number(digits: String, afterCurrency: Boolean): String =
        if (afterCurrency || digits.length < 5) "#" else "[num${minOf(digits.length, 99)}]"

    /**
     * Lowercase. Provider and product names, transaction words in English and Swahili, and the
     * small words that hold a sentence together. Before adding a word, check it is not also a
     * name someone in Kenya could carry: that is the whole guarantee.
     */
    private val VOCABULARY: Set<String> = """
        m-pesa mpesa pesa m-shwari mshwari kcb safaricom airtel money networks kenya ltd limited plc
        fuliza lipa na pochi la biashara hustler fund okoa jahazi bonga points telkom t-kash equitel
        equity bank co-op coop cooperative absa ncba stanbic dtb kplc prepaid postpaid token tokens
        units dstv gotv zuku nhif sha nssf kra ecitizen paypal western union worldremit remitly
        sendwave global pay

        confirmed confirm confirmation sent send sending received receive paid pay payment payments
        paybill till buy bought goods services service purchase purchased purchasing withdraw
        withdrawn withdrawal withdrew deposit deposited give gave given cash transfer transferred
        transaction transactions txn tid id ref reference receipt code cost costs charge charges
        charged fee fees free amount balance bal new available account acc acct number no agent
        merchant customer dear you your yours have has had been be is are was were will can cannot
        could would should not now on at to from for of in into by with via through and or the
        this that it its as an if within per day days daily week weekly month monthly year hour
        hours hr hrs min mins minute minutes sec secs time date today tomorrow midnight am pm
        limit limits maximum minimum total successful successfully failed fail failure
        unsuccessful declined cancelled canceled insufficient funds reversal reversed reverse
        reversing initiated completed complete processed processing pending request requested
        approved accepted rejected expired expire expires expiry expiring valid validity until
        upto up before after between loan loans repay repaid repayment partially fully
        outstanding due overdue interest access overdraft principal savings saving save saved
        lock locked unlock unlocked target goal moved move credited credit debited debit airtime
        bundle bundles data internet voice sms smses mb mbs gb gbs kb plan plans pack package
        offer offers subscription subscribed subscribe unsubscribe activated activate activation
        renew renewed renewal auto recharge recharged top topup topped own other another mobile
        phone line sim wallet float commission ksh kshs kes sh shs usd ugx tzs thank thanks please
        kindly dial call visit click download app use using used get enjoy more details info
        information help care terms conditions apply also only all any each one two first last
        next previous statement mini full international abroad remittance sender recipient
        receiver beneficiary name names unregistered registered user pin password otp never share
        do did does network cross

        imethibitishwa imethibitika umepokea umetuma umelipa umelipia umenunua umetoa umeweka
        umepata umehamisha imetumwa imepokelewa imelipwa kutoka kwa kwenda kwenye katika tarehe
        saa salio lako jipya mpya ya wa za ni gharama muamala miamala kiasi kiwango fedha akaunti
        nambari namba wakala mteja mpendwa unaweza kutuma kupokea kulipa kununua kutoa kuweka
        kesho siku wiki mwezi kila muda maongezi kifurushi vifurushi bando intaneti dakika ujumbe
        asubuhi mchana jioni usiku yako zako wako chako sasa tafadhali piga bonyeza tembelea bure
        mkopo riba lipia kupitia imefanikiwa imeshindikana haitoshi halitoshi imekamilika
        imerudishwa kurudishwa umerudishiwa
        umefanikiwa kujisajili umejisajili mpaka inatumika zinatumika kinatumika itaisha itakwisha masaa
        bonasi ofa kimewashwa imewashwa umewasha ununuzi cha uliyonunua umeongeza nunua tumia kutumia
        kinachotumika inayotumika tunukiwa sambaza
        gifted active added shared spent duration whatsapp youtube tiktok facebook instagram social
        unlimited night weekend video browse chat stream umezawadiwa imeongezwa
        deducted recovered borrowed advance debt unpaid being shortly try again later retry unable
        enough wrong incorrect exceeded congratulations reward rewards cashback bonus redeem
        redeemed converted check checked easily dialing dialling know option we our us ve got
        taken https http www
    """.trimIndent().split(Regex("""\s+""")).filter { it.isNotEmpty() }.toSet()
}
