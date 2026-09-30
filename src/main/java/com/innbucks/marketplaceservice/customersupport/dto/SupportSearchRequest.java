package com.innbucks.marketplaceservice.customersupport.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A support search. POST, not GET, so a phone number or a name never lands in
 * a URL — and therefore never in the gateway's or nginx's access logs.
 */
@Schema(description = "What the agent typed. Read by shape: MKT-... is an order ref, TRK-... a tracking "
        + "code, a UUID an id (buyer, order, parcel, seller or listing), anything dialable a phone "
        + "number, anything else a name.")
public record SupportSearchRequest(
        @NotBlank
        @Size(max = 80)
        @Schema(example = "0772 123 456")
        String query) {
}
