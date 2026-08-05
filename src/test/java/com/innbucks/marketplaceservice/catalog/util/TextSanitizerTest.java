package com.innbucks.marketplaceservice.catalog.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link TextSanitizer}'s stored-XSS defence on listing free text: ALL
 * HTML is stripped, entities are unescaped back to plain characters (storage
 * is plain text; output encoding is the consumer's job), whitespace inside
 * text survives, and the result never grows past the input.
 */
class TextSanitizerTest {

    @Test
    void nullInNullOut() {
        assertThat(TextSanitizer.sanitize(null)).isNull();
    }

    @Test
    void stripsMarkupButKeepsText() {
        assertThat(TextSanitizer.sanitize("<b>Bold</b> move")).isEqualTo("Bold move");
        assertThat(TextSanitizer.sanitize("<a href=\"https://evil.example\">link</a> text"))
                .isEqualTo("link text");
    }

    @Test
    void stripsScriptElementsIncludingTheirCode() {
        // Script bodies are data nodes, not text — they must vanish entirely,
        // not surface as literal "alert(...)" text in a listing.
        assertThat(TextSanitizer.sanitize("<script>alert('xss')</script>Safe title"))
                .isEqualTo("Safe title");
    }

    @Test
    void stripsEventHandlerVectors() {
        assertThat(TextSanitizer.sanitize("<img src=x onerror=alert(1)>")).isEmpty();
        assertThat(TextSanitizer.sanitize("<svg onload=alert(1)>desc</svg>"))
                .isEqualTo("desc");
    }

    @Test
    void unescapesEntitiesSoStorageStaysPlainText() {
        // The DB stores "Tom & Jerry", never "Tom &amp; Jerry".
        assertThat(TextSanitizer.sanitize("Tom &amp; Jerry")).isEqualTo("Tom & Jerry");
        assertThat(TextSanitizer.sanitize("Tom & Jerry")).isEqualTo("Tom & Jerry");
        assertThat(TextSanitizer.sanitize("5 &lt; 6 &amp; 7 &gt; 4")).isEqualTo("5 < 6 & 7 > 4");
    }

    @Test
    void preservesInnerWhitespaceAndNewlines() {
        // prettyPrint(false) keeps long descriptions' line structure intact.
        assertThat(TextSanitizer.sanitize("line one\nline two"))
                .isEqualTo("line one\nline two");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(TextSanitizer.sanitize("  padded  ")).isEqualTo("padded");
    }

    @Test
    void outputNeverLongerThanInput() {
        // Column-length Bean Validation on the raw value must still hold
        // post-sanitization.
        String[] inputs = {
                "<b>abc</b>", "Tom &amp; Jerry", "plain", "<script>x()</script>hi",
                "5 &lt; 6", "  spaced  "
        };
        for (String input : inputs) {
            assertThat(TextSanitizer.sanitize(input).length())
                    .as("sanitize(%s)", input)
                    .isLessThanOrEqualTo(input.length());
        }
    }
}
