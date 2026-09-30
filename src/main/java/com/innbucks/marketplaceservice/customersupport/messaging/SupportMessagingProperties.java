package com.innbucks.marketplaceservice.customersupport.messaging;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code marketplace.support.messages.*} — what an agent may send a customer,
 * and how much.
 */
@Data
@ConfigurationProperties(prefix = "marketplace.support.messages")
public class SupportMessagingProperties {

    /**
     * Appended on its own line to every message an agent types, so a customer
     * can always tell a message from support apart from one from a stranger.
     * Counted in the length limits.
     */
    private String signature = "- InnBucks Marketplace Support";

    /**
     * Longest SMS, measured on the final text (after the signature and the
     * GSM-safe rewrite). 459 is three concatenated GSM-7 segments: a support
     * message can say something, but cannot quietly cost ten SMS.
     */
    private int smsMaxCharacters = 459;

    /** Longest WhatsApp message. The gateway itself refuses above 1600. */
    private int whatsappMaxCharacters = 1000;

    /**
     * Hosts a link in a typed message may point at (a subdomain of one counts).
     * Anything else is refused: a link in a message from "support" is exactly
     * what a phishing attempt from a compromised console account would send.
     */
    private List<String> allowedLinkHosts = new ArrayList<>(List.of("innbucks.co.zw"));

    /** Messages one agent may send in a rolling hour, every kind counted. */
    private int perAgentPerHour = 60;

    /** Messages one number may receive from support in a rolling 24 hours. */
    private int perRecipientPerDay = 5;
}
