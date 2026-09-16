package com.innbucks.marketplaceservice.fulfilment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The code the person at the counter presented, as the seller submits it. */
@Schema(description = "Redeem a collection code — hands the parcel over and closes it")
public record CollectRequest(

        @Schema(description = "Scanned from the QR or typed from what the collector read out. "
                + "Dashes, spaces and letter case are all ignored, and the characters people "
                + "confuse (I/L for 1, O for 0) are folded — so what someone reads off a screen "
                + "verifies even when they read it imperfectly.",
                example = "K7Q2-9XMF-3TRW")
        @NotBlank
        @Size(max = 32)
        String code) {
}
