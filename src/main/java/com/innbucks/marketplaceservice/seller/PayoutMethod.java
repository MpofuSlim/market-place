package com.innbucks.marketplaceservice.seller;

/**
 * The rail a seller is paid on (V13).
 *
 * <p>Two values because these are the two ways a seller here actually receives
 * money. Each owns its own fields on {@code marketplace_seller} and the
 * {@code chk_seller_payout_destination} CHECK pins them: a BANK row carrying an
 * msisdn would be ambiguous about which one a transfer should follow.
 *
 * <p>This is a SEPARATE vocabulary from {@code checkout.PaymentRail}, which
 * names how a buyer PAYS IN. The two move money in opposite directions on
 * different rails, and collapsing them would make every future addition to one
 * look like an addition to the other.
 */
public enum PayoutMethod {

    /** A mobile-money wallet, addressed by MSISDN. */
    MOBILE_MONEY,

    /** A bank account: bank name + account number. */
    BANK
}
