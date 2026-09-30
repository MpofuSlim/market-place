package com.innbucks.marketplaceservice.customersupport.messaging;

/**
 * What a support message was. Stored by name in a VARCHAR, not a CHECKed
 * enum column, so a release adding a kind never leaves an older image unable
 * to read the table.
 */
public enum SupportMessageKind {
    /** Typed by the agent. */
    CUSTOM(false),
    /** The buyer's order-paid confirmation, sent again. */
    ORDER_CONFIRMATION(false),
    /** The seller's last move on a parcel (on its way / ready to collect /
     *  marked delivered), sent again. */
    PARCEL_UPDATE(false),
    /** A FRESH collection code, minted for the collector. The code is never
     *  shown to the agent and never stored, so the body is not kept. */
    COLLECT_CODE(true);

    private final boolean secret;

    SupportMessageKind(boolean secret) {
        this.secret = secret;
    }

    /** A kind whose text carries a secret: its body is never stored or shown. */
    public boolean secret() {
        return secret;
    }
}
