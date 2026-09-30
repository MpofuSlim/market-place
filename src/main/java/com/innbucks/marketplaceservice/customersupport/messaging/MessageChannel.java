package com.innbucks.marketplaceservice.customersupport.messaging;

/**
 * How a support message should go out. {@link #SMS_THEN_WHATSAPP} tries SMS and
 * falls back to WhatsApp only when SMS fails (or is not set up on this cell) —
 * the same order every platform message here uses, so a customer hears from
 * support on the channel they already hear from the marketplace on.
 */
public enum MessageChannel {
    SMS,
    WHATSAPP,
    SMS_THEN_WHATSAPP;

    boolean usesSms() {
        return this != WHATSAPP;
    }

    boolean usesWhatsApp() {
        return this != SMS;
    }
}
