package com.innbucks.marketplaceservice.customersupport.messaging;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.notify.SmsTextSanitizer;
import com.innbucks.marketplaceservice.notify.WhatsAppNotificationClient;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what an agent typed into what the customer will receive, and refuses
 * what must not be sent.
 *
 * <p>Three rules, each checked on the FINAL text — the one the customer will
 * actually read:
 * <ul>
 *   <li><b>A signature on every message</b>, on its own line, so a customer can
 *       always tell a message from support from one sent by a stranger.</li>
 *   <li><b>Links only to allowed hosts.</b> A typed message from "support"
 *       carrying a link is exactly what someone with a stolen console login
 *       would send, so any URL-like token must point at an allowed host (or a
 *       subdomain of one). Bare domains count ({@code evil.example} is linked
 *       by every phone), and so do addresses written with non-Latin letters,
 *       which is how a lookalike of an allowed host is spelled.</li>
 *   <li><b>A length cap per channel</b>: an SMS is measured AFTER the GSM-safe
 *       rewrite the gateway requires (an em-dash becomes a hyphen, an emoji a
 *       space), because that is the text that is billed.</li>
 * </ul>
 *
 * <p>SMS text is rewritten by the same {@link SmsTextSanitizer} the gateway
 * client applies — it rejects {@code : / ? ! " * ;} — so a link survives only
 * on WhatsApp. The preview says when the rewrite changed anything.
 */
@Component
public class SupportMessageComposer {

    /** Characters per segment of a single / concatenated GSM-7 SMS. The
     *  sanitised text holds nothing outside GSM-7's basic set, so every
     *  character is one septet. */
    static final int SMS_SINGLE_SEGMENT = 160;
    static final int SMS_CONCATENATED_SEGMENT = 153;

    /**
     * Anything a phone or WhatsApp would turn into a link: an explicit scheme,
     * a {@code www.} prefix, a dotted IPv4 address, or a bare domain (labels of
     * letters, digits and hyphens — ANY script's letters — ending in a
     * letters-only TLD). {@code \p{L}} rather than {@code [a-z]} is deliberate:
     * an ASCII-only pattern would not see a lookalike domain at all, and so
     * would let it through.
     */
    private static final Pattern LINK = Pattern.compile(
            "(?iU)(?:[a-z][a-z0-9+.-]*://\\S+"
                    + "|www\\.\\S+"
                    + "|\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b\\S*"
                    + "|[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?"
                    + "(?:\\.[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?)*"
                    + "\\.\\p{L}{2,}\\b\\S*)");

    private final SupportMessagingProperties properties;

    public SupportMessageComposer(SupportMessagingProperties properties) {
        if (properties.getWhatsappMaxCharacters() > WhatsAppNotificationClient.MAX_MESSAGE_LENGTH) {
            // The gateway refuses above its cap: a limit above it would let the
            // console accept messages that can never be delivered.
            throw new IllegalStateException("marketplace.support.messages.whatsapp-max-characters ("
                    + properties.getWhatsappMaxCharacters() + ") is above the WhatsApp gateway's limit of "
                    + WhatsAppNotificationClient.MAX_MESSAGE_LENGTH);
        }
        if (properties.getSmsMaxCharacters() < 1 || properties.getWhatsappMaxCharacters() < 1) {
            throw new IllegalStateException("marketplace.support.messages length limits must be positive");
        }
        this.properties = properties;
        properties.getAllowedLinkHosts().forEach(SupportMessageComposer::requireAsciiHost);
    }

    /** The texts one typed message becomes, one per channel. */
    public record Composed(String smsText, String whatsappText, boolean transliterated) {

        String textFor(DeliveredVia via) {
            return via == DeliveredVia.SMS ? smsText : whatsappText;
        }
    }

    /**
     * @throws ApiException 400 {@code message_required}, {@code link_not_allowed}
     *                      or {@code message_too_long}
     */
    public Composed composeCustom(String typed, MessageChannel channel) {
        Composed composed = composeUnchecked(typed);
        String sms = composed.smsText();
        String full = composed.whatsappText();
        if (channel.usesSms() && sms.length() > properties.getSmsMaxCharacters()) {
            throw ApiException.badRequest("message_too_long", "An SMS from support is at most "
                    + properties.getSmsMaxCharacters() + " characters including the signature - this one is "
                    + sms.length());
        }
        if (channel.usesWhatsApp() && full.length() > properties.getWhatsappMaxCharacters()) {
            throw ApiException.badRequest("message_too_long", "A WhatsApp message from support is at most "
                    + properties.getWhatsappMaxCharacters() + " characters including the signature - this one is "
                    + full.length());
        }
        return composed;
    }

    /**
     * The same texts WITHOUT the length check, for a preview: the console shows
     * a live count against the limit while the agent types, so an over-long
     * draft is something to report, not refuse. The send still refuses it.
     *
     * @throws ApiException 400 {@code message_required} or {@code link_not_allowed}
     */
    public Composed composeUnchecked(String typed) {
        String text = normalise(typed);
        if (text.isEmpty()) {
            throw ApiException.badRequest("message_required", "Write the message to send");
        }
        requireAllowedLinks(text);
        String full = text + "\n" + properties.getSignature();
        String sms = SmsTextSanitizer.toGsmSafe(full);
        return new Composed(sms, full, !sms.equals(full));
    }

    /** A platform template: already GSM-safe by construction (its own tests
     *  pin that), so the one text serves both channels, with no signature. */
    public Composed template(String text) {
        return new Composed(SmsTextSanitizer.toGsmSafe(text), text, false);
    }

    public int maxCharacters(MessageChannel channel) {
        return channel == MessageChannel.WHATSAPP
                ? properties.getWhatsappMaxCharacters() : properties.getSmsMaxCharacters();
    }

    /** GSM-7 segments the SMS will be billed as. */
    public static int smsSegments(String smsText) {
        int length = smsText.length();
        if (length <= SMS_SINGLE_SEGMENT) {
            return 1;
        }
        return (length + SMS_CONCATENATED_SEGMENT - 1) / SMS_CONCATENATED_SEGMENT;
    }

    /**
     * HTML stripped (the console renders history, and a stored {@code <script>}
     * must not become its problem), line endings unified, trailing space and
     * runs of blank lines removed.
     */
    static String normalise(String typed) {
        String text = TextSanitizer.sanitize(typed);
        if (text == null) {
            return "";
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("(?U)[\\t\\x0B\\f ]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n");
        return text.strip();
    }

    private void requireAllowedLinks(String text) {
        // NFKC first: full-width letters and dots (ｅｖｉｌ．ｅｘａｍｐｌｅ) fold to
        // ASCII, so a link cannot dress itself up as plain text.
        Matcher matcher = LINK.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        while (matcher.find()) {
            String host = hostOf(matcher.group());
            if (!allowed(host)) {
                throw ApiException.badRequest("link_not_allowed", "Links in a support message may only point at "
                        + String.join(", ", properties.getAllowedLinkHosts()) + " - '" + host
                        + "' is not one of them. If it is not meant as a link, add a space after the full stop.")
                        .withDetails(Map.of("host", host));
            }
        }
    }

    boolean allowed(String host) {
        List<String> allowedHosts = properties.getAllowedLinkHosts();
        for (String allowedHost : allowedHosts) {
            String a = allowedHost.toLowerCase(Locale.ROOT);
            if (host.equals(a) || host.endsWith("." + a)) {
                return true;
            }
        }
        return false;
    }

    /** The host part of a link-shaped token, lower-cased: scheme, user info,
     *  port, path and trailing punctuation removed. */
    static String hostOf(String token) {
        String t = token;
        int scheme = t.indexOf("://");
        if (scheme >= 0) {
            t = t.substring(scheme + 3);
        }
        int end = t.length();
        for (char stop : new char[]{'/', '?', '#', '\\'}) {
            int i = t.indexOf(stop);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        t = t.substring(0, end);
        int at = t.lastIndexOf('@');
        if (at >= 0) {
            t = t.substring(at + 1);
        }
        int colon = t.indexOf(':');
        if (colon >= 0) {
            t = t.substring(0, colon);
        }
        t = t.replaceAll("[.,;!)\\]}'\"]+$", "");
        return t.toLowerCase(Locale.ROOT);
    }

    private static void requireAsciiHost(String host) {
        if (host == null || host.isBlank() || !host.equals(IDN.toASCII(host)) || host.contains("/")) {
            throw new IllegalStateException("marketplace.support.messages.allowed-link-hosts holds '" + host
                    + "' - name plain ASCII hosts only, e.g. innbucks.co.zw");
        }
    }
}
