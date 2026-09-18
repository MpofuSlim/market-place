package com.innbucks.marketplaceservice.seller;

import java.util.UUID;

/**
 * A seller's payout destination moved (V13).
 *
 * <p>Published so the seller's own admin users are TOLD. That notification is
 * the real mitigation against the attack this feature invites: an account
 * takeover that quietly re-points the next payout at the attacker. Nothing
 * else in the flow would show the legitimate seller anything — the money
 * simply goes somewhere else and they find out when they chase a payout that
 * never arrived.
 *
 * <p>Carries no account details. The message says a change happened and tells
 * them to look, which is all a warning needs; putting the new destination in
 * an SMS would hand an attacker who also holds the phone a confirmation
 * receipt, and hand anyone else who reads it the account number.
 *
 * @param changedBySeller false when an admin set it on the seller's behalf —
 *                        worth distinguishing, because "we changed this for
 *                        you" and "someone changed this" call for different
 *                        reactions from the reader.
 */
public record PayoutDestinationChanged(UUID merchantId,
                                       PayoutMethod method,
                                       boolean replacedAnExistingOne,
                                       boolean changedBySeller) {
}
