package com.innbucks.marketplaceservice.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A freshly minted collection code, returned to the BUYER once.
 *
 * <p>This response is the only read surface the plaintext ever appears on.
 * It is not stored, not readable back from the order, and never present on any
 * seller-facing shape — so an app that drops it must mint a new one, which is a
 * single call and invalidates the one it lost.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A collection code for one parcel — show it, send it, or render it as a QR")
public record CollectCodeResponse(

        @Schema(description = "The parcel this code collects",
                example = "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31")
        UUID fulfilmentId,

        @Schema(description = "The code itself. Encode THIS verbatim in a QR — the seller's "
                + "scanner submits it unchanged.", example = "K7Q29XMF3TRW")
        String code,

        @Schema(description = "The same code in reading groups, for saying out loud or typing at "
                + "a counter. Either form verifies.", example = "K7Q2-9XMF-3TRW")
        String groupedCode,

        @Schema(example = "2026-09-16T14:05:00Z")
        Instant issuedAt,

        @Schema(description = "The number the code was also sent to, masked — the recipient's if "
                + "the order named one with a number, otherwise the buyer's. Absent when no "
                + "message channel is configured on this cell.",
                example = "****5678", nullable = true)
        String sentTo) {
}
