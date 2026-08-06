package com.innbucks.marketplaceservice.notify;

import java.text.Normalizer;
import org.jspecify.annotations.Nullable;

/**
 * Makes SMS text safe for the InnBucks notification API / SMS gateway, which
 * rejects non-GSM / non-ASCII characters with {@code 400 "Invalid message"}
 * (confirmed live against the gateway by the ticketing fleet — an em-dash in
 * the body is rejected, while the identical ASCII text is accepted).
 *
 * <p>Transactional copy picks up typographic punctuation (em/en dashes, curly
 * quotes, ellipsis, non-breaking space, bullet) such as the "- The InnBucks
 * Team" sign-offs (written with an em-dash) which the gateway rejects. This
 * transliterates those to their ASCII equivalents, strips diacritics
 * (accented letter to its base letter), and replaces anything still outside
 * the proven-accepted set with a space so the gateway never sees a byte it
 * rejects.
 *
 * <p>Apply on the SMS send path and on EMAIL SUBJECTS (the email endpoint
 * charset-validates the subject the same way — observed 400 "Invalid subject"
 * for an em-dash — while email BODIES accept Unicode and keep their
 * typography). WhatsApp renders Unicode fine and is never sanitized.
 *
 * <p>Faithful copy of the ticketing fleet's sanitizer (booking-service /
 * user-service / InnRewards) — keep the behaviour identical across copies.
 */
public final class SmsTextSanitizer {

    private SmsTextSanitizer() {
    }

    /**
     * Returns a GSM/ASCII-safe form of {@code text}. Null/blank pass through
     * unchanged (the callers already reject blank before sending).
     */
    public static @Nullable String toGsmSafe(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = text
                .replace("–", "-").replace("—", "-")   // en dash / em dash
                .replace("‒", "-").replace("―", "-")   // figure dash / horizontal bar
                .replace("‘", "'").replace("’", "'")   // curly single quotes / apostrophe
                .replace("‚", "'").replace("‛", "'")
                // Curly DOUBLE quotes collapse to an apostrophe, not '"' — the
                // gateway rejects the double-quote character outright.
                .replace("“", "'").replace("”", "'")
                .replace("„", "'")
                .replace("…", "...")                        // ellipsis
                .replace(" ", " ")                          // non-breaking space
                // Bullet becomes '-', not '*' — '*' is rejected too.
                .replace("•", "-").replace("·", ".");
        // Strip diacritics (accented -> base letter) so accented copy degrades to
        // GSM-safe ASCII rather than being replaced wholesale by the net below.
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        // Characters the API refuses with 400 "Invalid message", established by
        // the ticketing fleet probing it one character at a time against the
        // live gateway on 2026-07-29:   !  :  /  ?  "  *  ;
        // while ( ) - % @ & # ' + . , and alphanumerics are all accepted. They
        // are ordinary GSM-7 characters, so this is a rule of THIS API. Sentence
        // enders become '.', the rest a space so words never run together.
        s = s.replace('!', '.').replace('?', '.').replace(';', '.')
             .replace(':', ' ').replace('/', ' ').replace('*', ' ').replace('"', '\'');
        // Final net: anything outside the proven-accepted set becomes a SPACE.
        // It used to become '?' — which is itself rejected, so the sanitizer
        // could turn a merely-odd message into a guaranteed 400. Iterate by CODE
        // POINT so a supplementary character (an emoji, a UTF-16 surrogate pair)
        // collapses to one space rather than one per surrogate half.
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            out.append(accepted(cp) ? (char) cp : ' ');
            i += Character.charCount(cp);
        }
        // Collapse the runs of spaces the substitutions above can leave behind.
        return out.toString().replaceAll(" {2,}", " ");
    }

    /**
     * The character set this gateway has been observed to accept. Deliberately a
     * WHITELIST: the rejected set is not documented anywhere, so allowing only
     * what is proven safe is the only way a character we have never tried can't
     * fail a send in production.
     */
    private static boolean accepted(int cp) {
        if (cp == '\n' || cp == '\r' || cp == '\t') {
            return true;
        }
        if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')) {
            return true;
        }
        return " .,()-%@&#'+".indexOf(cp) >= 0;
    }
}
