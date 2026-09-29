package com.innbucks.marketplaceservice.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the branded-HTML rendering contract (see {@link BrandedEmailRenderer}):
 * the body is HTML-escaped (no injection), blank-line paragraphs become
 * {@code <p>}, the footer + disclaimer are always present, the logo is an
 * {@code <img>} when a URL is given / a CSS fallback when it isn't, and the
 * header row is brand navy so the white-lettered hosted logo is legible.
 *
 * <p>Mirrors the fleet copy's test in ticketing-system's {@code common}
 * module; keep the two in lock-step with the renderer.
 */
class BrandedEmailRendererTest {

    private static final String NAVY = "#0c2545";
    private static final String NAVY_HEADER_CELL =
            "<td bgcolor=\"" + NAVY + "\" style=\"background:" + NAVY + ";padding:26px 34px 20px;\">";

    @Test
    void rendersBodyParagraphsFooterAndDisclaimer() {
        String html = BrandedEmailRenderer.render(
                "Your order is on its way",
                "Hello,\n\nYour order \"MKT-1234\" has been dispatched.\n\nThank you for shopping!",
                "https://www.innbucks.co.zw/logo.png");

        // A fragment (bare table), not a full document — safe to inject into the
        // gateway's own HTML body without nesting an <html> element.
        assertThat(html).startsWith("<table");
        assertThat(html).doesNotContain("<html");
        assertThat(html).doesNotContain("<!doctype");
        // Body content survives, split into paragraphs.
        assertThat(html).contains("has been dispatched");
        assertThat(html).contains("<p style=");
        // Standard footer + statutory disclosure always present.
        assertThat(html).contains("The InnBucks Team");
        assertThat(html).contains("Deposit Protection Scheme");
        assertThat(html).contains("+263 (0) 8677 569 569");
    }

    @Test
    void usesHostedLogoImgWhenUrlProvided() {
        String html = BrandedEmailRenderer.render("s", "body", "https://cdn.innbucks.co.zw/logo.png");
        assertThat(html).contains("<img src=\"https://cdn.innbucks.co.zw/logo.png\"");
        assertThat(html).contains("alt=\"InnBucks\"");
    }

    @Test
    void headerIsNavySoTheWhiteLetteredHostedLogoIsLegible() {
        // The hosted logo's "InnBucks" / "MicroBank Limited" lettering is white;
        // on a white header only the four dots showed (Gmail, staging, 2026-09-29).
        String html = BrandedEmailRenderer.render("s", "body", "https://cdn.innbucks.co.zw/logo.png");

        // Both halves: inline style for Gmail/Apple Mail, bgcolor for Outlook.
        assertThat(html).contains(NAVY_HEADER_CELL);
        int headerCell = html.indexOf(NAVY_HEADER_CELL);
        int logoImg = html.indexOf("<img src=");
        assertThat(headerCell).isNotNegative();
        assertThat(logoImg).isGreaterThan(headerCell);
        // The logo sits directly inside the navy header cell — nothing between
        // them could reintroduce a white ground behind it.
        assertThat(html.substring(headerCell + NAVY_HEADER_CELL.length(), logoImg)).isEmpty();
        // And the header cell is the first cell of the white card, ahead of the
        // accent bar and the body.
        assertThat(headerCell).isLessThan(html.indexOf("background:#f5b71c"));
        assertThat(headerCell).isLessThan(html.indexOf("<p style="));
    }

    @Test
    void fallsBackToCssLogoWhenUrlBlank() {
        String html = BrandedEmailRenderer.render("s", "body", "");
        assertThat(html).doesNotContain("<img");
        // CSS brand lockup is rendered instead: wordmark + tagline.
        assertThat(html).contains(">InnBucks<");
        assertThat(html).contains(">MicroBank Limited<");
    }

    @Test
    void fallbackLockupIsDrawnForTheNavyHeader() {
        String html = BrandedEmailRenderer.render("s", "body", null);

        // The fallback sits on the same navy header cell as the hosted logo.
        int headerCell = html.indexOf(NAVY_HEADER_CELL);
        assertThat(headerCell).isNotNegative();
        assertThat(html.indexOf(">InnBucks<")).isGreaterThan(headerCell);
        // White wordmark, light-slate tagline — a navy wordmark would vanish.
        assertThat(html).contains("color:#ffffff;letter-spacing:-.5px;line-height:1;\">InnBucks</div>");
        assertThat(html).contains("color:#b9c6d8;letter-spacing:.4px;margin-top:3px;\">MicroBank Limited</div>");
        assertThat(html).doesNotContain("color:" + NAVY + ";letter-spacing:-.5px;");
        // The four brand dots keep their colours.
        assertThat(html).contains("background:#f5b71c;width:16px");
        assertThat(html).contains("background:#7a2e8f;width:16px");
        assertThat(html).contains("background:#17a98c;width:16px");
        assertThat(html).contains("background:#e11b22;width:16px");
    }

    @Test
    void bodyStaysWhiteAndFooterStaysNavy() {
        String html = BrandedEmailRenderer.render("s", "body", "https://cdn.innbucks.co.zw/logo.png");
        // Only the header changed: the card (and so the body) is still white,
        // and the footer is still the navy it always was.
        assertThat(html).contains("max-width:100%;background:#ffffff;");
        assertThat(html).contains("<tr><td style=\"background:" + NAVY + ";padding:26px 34px;");
    }

    @Test
    void escapesHtmlInBodySoContentCannotInjectMarkup() {
        String html = BrandedEmailRenderer.render(
                "s", "Body with <b>tags</b> & an ampersand and a <script>alert(1)</script>", "");
        assertThat(html).contains("&lt;b&gt;tags&lt;/b&gt;");
        assertThat(html).contains("&amp; an ampersand");
        // The raw script tag from message content must never appear unescaped.
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void escapesQuotesInLogoUrlAttribute() {
        String html = BrandedEmailRenderer.render("s", "b", "https://x/a\"onerror=alert(1)");
        assertThat(html).doesNotContain("\"onerror=alert(1)");
        assertThat(html).contains("&quot;onerror=alert(1)");
        // Still inside the navy header cell after escaping.
        assertThat(html).contains(NAVY_HEADER_CELL + "<img src=\"https://x/a&quot;onerror=alert(1)\"");
    }
}
