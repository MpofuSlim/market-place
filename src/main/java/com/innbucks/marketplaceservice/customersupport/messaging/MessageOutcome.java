package com.innbucks.marketplaceservice.customersupport.messaging;

/**
 * PENDING from the moment the row claims its rate-limit slot until the
 * gateway answers; then SENT or FAILED, once, for good (the V23 trigger
 * refuses any later change). A row left PENDING means the service stopped
 * between the two — the message may or may not have gone out.
 */
public enum MessageOutcome {
    PENDING,
    SENT,
    FAILED
}
