package com.innbucks.marketplaceservice.notify;

/**
 * Wraps a plain-text email body in the InnBucks branded HTML shell — logo
 * header, colour accent bar, the message as formatted paragraphs, and the
 * standard contact + Deposit-Protection footer.
 *
 * <p>Used only when {@code innbucks-notify.html-enabled=true}; otherwise
 * {@link EmailSignature} (plain text) is the wire format. The HTML is
 * deliberately email-client-robust: a single centred table, all styles inline,
 * web-safe fonts, no external CSS/JS — the lowest-common-denominator that
 * Gmail / Outlook / Apple Mail all render. The logo is an {@code <img>} at a
 * hosted URL (clients block {@code data:} URIs); when no URL is configured it
 * falls back to a CSS-drawn four-dot roundel + wordmark so the header is still
 * branded without a hosted asset.
 *
 * <p>The caller's body is plain text (the same string the plain-text path
 * sends), so it is HTML-escaped here and its blank-line-separated paragraphs
 * become {@code <p>} blocks — no HTML is ever taken from the message content,
 * which keeps injected markup impossible.
 *
 * <p>Local copy of the fleet renderer (InnRewards / ticketing-system) — the
 * marketplace depends on no fleet module. Keep it in lock-step.
 */
public final class BrandedEmailRenderer {

    private BrandedEmailRenderer() {
    }

    private static final String NAVY = "#0c2545";
    private static final String TEAL = "#17a98c";

    /**
     * Render {@code plainBody} into a full branded HTML document.
     *
     * @param subject  used as the visually-hidden preheader / heading
     * @param plainBody the plain-text message (escaped + paragraphed here)
     * @param logoUrl  hosted logo image URL; blank → CSS roundel fallback
     */
    public static String render(String subject, String plainBody, String logoUrl) {
        String paragraphs = toParagraphs(plainBody);
        String logo = (logoUrl == null || logoUrl.isBlank())
                ? cssRoundel()
                : "<img src=\"" + escapeAttr(logoUrl) + "\" width=\"180\" alt=\"InnBucks\" "
                    + "style=\"display:block;border:0;outline:none;text-decoration:none;height:auto;\">";

        // A FRAGMENT, not a full <html> document: the notification gateway
        // renders the message field as HTML and (per the payment-rail template)
        // may inject it into its own <html><body>. Emitting a bare centred table
        // renders correctly both when the gateway wraps it and when it doesn't —
        // a nested <html> would be invalid. All styles inline, web-safe fonts,
        // no external CSS/JS: the lowest common denominator Gmail/Outlook render.
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            +   "style=\"background:#eef1f5;margin:0;\"><tr><td align=\"center\" style=\"padding:24px 12px;\">"
            + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" "
            +   "style=\"width:600px;max-width:100%;background:#ffffff;border-radius:12px;overflow:hidden;"
            +   "font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\">"
            // header
            + "<tr><td style=\"padding:26px 34px 20px;\">" + logo + "</td></tr>"
            // accent bar
            + "<tr><td style=\"font-size:0;line-height:0;\">"
            +   "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
            +   "<td width=\"25%\" height=\"4\" style=\"background:#f5b71c;\"></td>"
            +   "<td width=\"25%\" height=\"4\" style=\"background:#7a2e8f;\"></td>"
            +   "<td width=\"25%\" height=\"4\" style=\"background:" + TEAL + ";\"></td>"
            +   "<td width=\"25%\" height=\"4\" style=\"background:#e11b22;\"></td>"
            +   "</tr></table></td></tr>"
            // body
            + "<tr><td style=\"padding:30px 34px 8px;color:#2b3a4b;font-size:15px;line-height:1.6;\">"
            +   paragraphs + "</td></tr>"
            // footer
            + "<tr><td style=\"background:" + NAVY + ";padding:26px 34px;color:#b9c6d8;font-size:13px;"
            +   "line-height:1.55;\">"
            +   "<div style=\"color:#ffffff;font-weight:700;font-size:15px;margin-bottom:8px;\">"
            +     "The InnBucks Team</div>"
            +   "InnBucks Microfinance Bank<br>"
            +   "+263 (0) 8677 569 569 &nbsp;&middot;&nbsp; "
            +     "<a href=\"https://www.innbucks.co.zw\" style=\"color:#9fd9cc;text-decoration:none;\">"
            +     "www.innbucks.co.zw</a> &nbsp;&middot;&nbsp; Dial *569#<br>"
            +   "2 Northridge Close, Northridge Park, Harare"
            +   "<div style=\"margin-top:16px;padding-top:14px;border-top:1px solid rgba(255,255,255,.14);"
            +     "font-size:11.5px;color:#8496ad;\">"
            +     "InnBucks MicroBank Ltd is a registered Deposit-Taking Microfinance Bank and a member "
            +     "of the Deposit Protection Scheme.</div>"
            + "</td></tr>"
            + "</table></td></tr></table>";
    }

    /**
     * CSS/table-drawn brand lockup — the four-dot roundel + "InnBucks" wordmark
     * + "MicroBank Limited" tagline, all in solid brand colours. Used as the
     * header when no hosted logo URL is set: it renders crisply in every client
     * with no image to load (or wash out), unlike a hosted PNG that can proxy
     * faintly on a white ground.
     */
    private static String cssRoundel() {
        String dot = "width:16px;height:16px;border-radius:50%;font-size:0;line-height:0;"
                + "mso-line-height-rule:exactly;";
        String face = "font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;";
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
            + "<td style=\"padding-right:12px;vertical-align:middle;\">"
            +   "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"3\">"
            +   "<tr><td style=\"background:#f5b71c;" + dot + "\"></td>"
            +     "<td style=\"background:#7a2e8f;" + dot + "\"></td></tr>"
            +   "<tr><td style=\"background:" + TEAL + ";" + dot + "\"></td>"
            +     "<td style=\"background:#e11b22;" + dot + "\"></td></tr></table></td>"
            + "<td style=\"vertical-align:middle;" + face + "\">"
            +   "<div style=\"font-size:28px;font-weight:800;color:" + NAVY + ";letter-spacing:-.5px;"
            +     "line-height:1;\">InnBucks</div>"
            +   "<div style=\"font-size:12px;font-weight:600;color:#5d6b7b;letter-spacing:.4px;"
            +     "margin-top:3px;\">MicroBank Limited</div>"
            + "</td></tr></table>";
    }

    /** Split on blank lines into &lt;p&gt; blocks; single newlines become &lt;br&gt;. */
    private static String toParagraphs(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String[] blocks = body.trim().split("\\n\\s*\\n");
        StringBuilder sb = new StringBuilder();
        for (String block : blocks) {
            sb.append("<p style=\"margin:0 0 16px;\">")
              .append(escape(block).replace("\n", "<br>"))
              .append("</p>");
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String s) {
        return escape(s).replace("\"", "&quot;");
    }
}
