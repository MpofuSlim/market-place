package com.innbucks.marketplaceservice.notify;

/**
 * Last-4-digits MSISDN mask for log lines. Full phone numbers in structured
 * logs are a data-protection / banking-PII finding — the customer's MSISDN is
 * account-binding identity. Operators still get enough to disambiguate; logs
 * are no longer a join-key back to the customer's full identity if they leak.
 *
 * <p>Sibling of the booking/payment/loyalty copies — intentionally duplicated
 * per service because the fleet has no shared utility module. Keep the
 * behaviour identical across copies.
 */
public final class MsisdnMasking {

    private MsisdnMasking() {
    }

    public static String mask(String phone) {
        if (phone == null || phone.length() <= 4) return "****";
        return "****" + phone.substring(phone.length() - 4);
    }
}
