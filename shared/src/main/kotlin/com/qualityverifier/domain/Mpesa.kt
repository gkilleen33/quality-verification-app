package com.qualityverifier.domain

/**
 * The closed vocabularies of Fundi Bora's M-Pesa records, shared by the phone that writes
 * them and the server that stores them. Each is mirrored by a CHECK in V29, and
 * `MpesaVocabularyTest` holds the two in step in both directions.
 */

/** Which way the money moved, from the fundi's wallet's point of view. */
enum class MpesaDirection(val id: String) {
    IN("in"),
    OUT("out"),

    /**
     * Money that stayed the fundi's own: Fuliza draws and repayments, moves to and from
     * M-Shwari or KCB M-PESA, reversals. Listed, never counted in the totals.
     */
    OTHER("other");

    companion object {
        fun fromId(id: String?): MpesaDirection? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What the message says happened, as the parser names it (MobileMoneyParser's
 * TRANSACTION_TYPES, without `failed`: a refused payment moved no money, so the phone
 * never stores it).
 */
enum class MpesaType(val id: String) {
    SEND("send"),
    RECEIVE("receive"),
    PAYBILL("paybill"),
    BUY_GOODS("buy_goods"),
    BUNDLE("bundle"),
    WITHDRAW("withdraw"),
    DEPOSIT("deposit"),
    AIRTIME("airtime"),
    FULIZA_DRAW("fuliza_draw"),
    FULIZA_REPAY("fuliza_repay"),
    SAVINGS_DEPOSIT("savings_deposit"),
    SAVINGS_WITHDRAW("savings_withdraw"),
    REVERSAL("reversal");

    companion object {
        fun fromId(id: String?): MpesaType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What the fundi says a transaction was for (Grady's list, Slack, 11 November 2026).
 * Money out has four; money in has two; [OTHER] is the only one for money that stayed
 * the fundi's own.
 */
enum class MpesaCategory(val id: String, val directions: Set<MpesaDirection>) {
    MATERIAL_PURCHASES("material_purchases", setOf(MpesaDirection.OUT)),
    WAGES("wages", setOf(MpesaDirection.OUT)),
    MACHINE_RENTAL("machine_rental", setOf(MpesaDirection.OUT)),
    CUSTOMER_PAYMENT("customer_payment", setOf(MpesaDirection.IN)),
    OTHER("other", MpesaDirection.entries.toSet());

    fun allowedFor(direction: MpesaDirection): Boolean = direction in directions

    companion object {
        fun fromId(id: String?): MpesaCategory? = entries.firstOrNull { it.id == id }

        /** The choices the transaction screen offers for [direction], in this order. */
        fun choicesFor(direction: MpesaDirection): List<MpesaCategory> = entries.filter { it.allowedFor(direction) }
    }
}

/** What a counterparty code was computed from. The value itself never reaches the database. */
enum class CounterpartyBasis(val id: String) {
    PHONE("phone"),
    NAME("name");

    companion object {
        fun fromId(id: String?): CounterpartyBasis? = entries.firstOrNull { it.id == id }
    }
}
