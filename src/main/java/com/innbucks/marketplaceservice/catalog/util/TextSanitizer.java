package com.innbucks.marketplaceservice.catalog.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;

/**
 * Strips ALL HTML from user-supplied free text (stored-XSS defense, OWASP A03)
 * on every listing write path. {@code Safelist.none()} removes every tag; the
 * surviving text is entity-unescaped back to plain characters so the DB stores
 * {@code Tom & Jerry}, not {@code Tom &amp; Jerry} — output encoding is the
 * consumer's job, storage stays plain text.
 */
public final class TextSanitizer {

    private TextSanitizer() {
    }

    /** Non-pretty-printed output so jsoup does not reflow whitespace/newlines
     *  inside long descriptions while stripping tags. */
    private static final Document.OutputSettings PRESERVE_WHITESPACE =
            new Document.OutputSettings().prettyPrint(false);

    /**
     * @return the input with all HTML removed, entities unescaped and
     *         surrounding whitespace trimmed; {@code null} in, {@code null} out.
     *         Output is never longer than the input, so column-length Bean
     *         Validation on the raw value still holds post-sanitization.
     */
    public static String sanitize(String input) {
        if (input == null) {
            return null;
        }
        String stripped = Jsoup.clean(input, "", Safelist.none(), PRESERVE_WHITESPACE);
        return Parser.unescapeEntities(stripped, false).trim();
    }
}
