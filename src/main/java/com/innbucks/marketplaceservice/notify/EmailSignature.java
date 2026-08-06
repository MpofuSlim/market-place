package com.innbucks.marketplaceservice.notify;

/**
 * The standard InnBucks sign-off + contact + regulatory footer appended to
 * every outbound <b>email</b> (see {@link EmailNotificationClient#sendEmail}).
 *
 * <p>Why this exists: transactional emails that are a single bare line with no
 * signature, no contact route, and no issuer identity read as spam/phishing to
 * both customers and mail filters. A consistent, signed footer with a real
 * contact path and the statutory Deposit-Protection-Scheme disclosure is what
 * makes a plain-text email read as a genuine InnBucks message.
 *
 * <p><b>Email channel only.</b> It is intentionally NOT applied to SMS/WhatsApp
 * (those stay terse — length is cost, and the footer's punctuation would trip
 * the GSM-7 SMS sanitizer). Kept in lock-step with the ticketing-system and
 * InnRewards copies of this class.
 */
public final class EmailSignature {

    private EmailSignature() {
    }

    /**
     * The footer block, including the leading blank lines that separate it from
     * the message body. Contact details mirror the corporate signature.
     */
    public static final String FOOTER = "\n\n"
            + "—\n"
            + "The InnBucks Team\n\n"
            + "InnBucks Microfinance Bank\n"
            + "+263 (0) 8677 569 569  ·  www.innbucks.co.zw  ·  Dial *569#\n"
            + "2 Northridge Close, Northridge Park, Harare\n\n"
            + "InnBucks MicroBank Ltd is a registered Deposit-Taking Microfinance Bank "
            + "and a member of the Deposit Protection Scheme.";

    /**
     * Append the standard footer to a message body. Returns the body unchanged
     * when it is null/blank (the caller's own blank-guard fires first, so this
     * is belt-and-braces and never turns a blank body into a footer-only email).
     */
    public static String appendTo(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        return body + FOOTER;
    }
}
