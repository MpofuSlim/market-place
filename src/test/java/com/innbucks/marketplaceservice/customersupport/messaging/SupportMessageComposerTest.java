package com.innbucks.marketplaceservice.customersupport.messaging;

import com.innbucks.marketplaceservice.api.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportMessageComposerTest {

    private static final String SIGNATURE = "\n- InnBucks Marketplace Support";

    private final SupportMessageComposer composer = new SupportMessageComposer(new SupportMessagingProperties());

    @Test
    @DisplayName("the signature goes on its own line; SMS gets the GSM-safe form, WhatsApp the original")
    void signedAndTransliterated() {
        var composed = composer.composeCustom("  Your parcel — ready: collect today!  ",
                MessageChannel.SMS_THEN_WHATSAPP);

        assertThat(composed.whatsappText()).isEqualTo("Your parcel — ready: collect today!" + SIGNATURE);
        assertThat(composed.smsText()).isEqualTo("Your parcel - ready collect today." + SIGNATURE);
        assertThat(composed.transliterated()).isTrue();
        assertThat(composer.composeCustom("Plain words.", MessageChannel.SMS).transliterated()).isFalse();
    }

    @Test
    @DisplayName("HTML is stripped, line endings unified, blank runs collapsed; nothing left is refused")
    void normalised() {
        assertThat(SupportMessageComposer.normalise("<b>Hello</b>\r\n\r\n\r\n\r\nthere  \n")).isEqualTo("Hello\n\nthere");
        assertThatThrownBy(() -> composer.composeCustom("<p> </p>", MessageChannel.SMS))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("message_required");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Pay at https://bit.ly/x", "see www.evil.example now", "go to evil.example",
            "email refunds@gmail.com", "http://10.0.0.1/pay", "open 10.0.0.1/pay",
            "https://innbucks.co.zw@evil.example/x", "innbucks.co.zw.evil.example/x",
            // A Cyrillic 'о' in "co": looks allowed, is not.
            "innbucks.cо.zw/track", "Thanks.Your order is ready",
            // Full-width letters and dot, folded by NFKC before matching.
            "visit \uff45\uff56\uff49\uff4c\uff0e\uff45\uff58\uff41\uff4d\uff50\uff4c\uff45"})
    @DisplayName("anything link-shaped must point at an allowed host")
    void linksElsewhereAreRefused(String text) {
        assertThatThrownBy(() -> composer.composeCustom(text, MessageChannel.WHATSAPP))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("link_not_allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Track at https://innbucks.co.zw/track", "See www.innbucks.co.zw.", "shop.innbucks.co.zw/deals",
            "Write to support@innbucks.co.zw", "Your order MKT-8B3E5D7F9A1C costs USD 25.99, e.g. today.",
            "Collect after 3.00pm", "It is 1.5kg"})
    @DisplayName("allowed hosts, subdomains and ordinary sentences pass")
    void allowedLinksAndProsePass(String text) {
        assertThat(composer.composeCustom(text, MessageChannel.WHATSAPP).whatsappText()).startsWith(text.strip());
    }

    @Test
    @DisplayName("lengths are measured on the final text, per channel the send may use")
    void lengthLimits() {
        String fits = "x".repeat(459 - SIGNATURE.length());
        assertThat(composer.composeCustom(fits, MessageChannel.SMS).smsText()).hasSize(459);
        assertThatThrownBy(() -> composer.composeCustom(fits + "x", MessageChannel.SMS))
                .extracting("code").isEqualTo("message_too_long");
        // WhatsApp alone allows more; with an SMS first, the SMS limit governs.
        assertThat(composer.composeCustom(fits + "x", MessageChannel.WHATSAPP).whatsappText()).hasSize(460);
        assertThatThrownBy(() -> composer.composeCustom(fits + "x", MessageChannel.SMS_THEN_WHATSAPP))
                .extracting("code").isEqualTo("message_too_long");
        assertThatThrownBy(() -> composer.composeCustom("x".repeat(1000), MessageChannel.WHATSAPP))
                .extracting("code").isEqualTo("message_too_long");
    }

    @Test
    @DisplayName("segments are GSM-7: 160 for one, then 153 each")
    void segments() {
        assertThat(SupportMessageComposer.smsSegments("x".repeat(160))).isEqualTo(1);
        assertThat(SupportMessageComposer.smsSegments("x".repeat(161))).isEqualTo(2);
        assertThat(SupportMessageComposer.smsSegments("x".repeat(306))).isEqualTo(2);
        assertThat(SupportMessageComposer.smsSegments("x".repeat(307))).isEqualTo(3);
        assertThat(SupportMessageComposer.smsSegments("x".repeat(459))).isEqualTo(3);
    }

    @Test
    @DisplayName("a WhatsApp limit above the gateway's own, or a non-ASCII allowed host, refuses to boot")
    void misconfigurationFailsAtBoot() {
        SupportMessagingProperties tooLong = new SupportMessagingProperties();
        tooLong.setWhatsappMaxCharacters(2000);
        assertThatThrownBy(() -> new SupportMessageComposer(tooLong)).isInstanceOf(IllegalStateException.class);

        SupportMessagingProperties lookalike = new SupportMessagingProperties();
        lookalike.setAllowedLinkHosts(List.of("innbucks.cо.zw"));
        assertThatThrownBy(() -> new SupportMessageComposer(lookalike)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("host extraction drops scheme, user info, port, path and trailing punctuation")
    void hostOf() {
        assertThat(SupportMessageComposer.hostOf("HTTPS://User@Shop.InnBucks.co.zw:8443/x?y")).isEqualTo("shop.innbucks.co.zw");
        assertThat(SupportMessageComposer.hostOf("innbucks.co.zw).")).isEqualTo("innbucks.co.zw");
    }
}
