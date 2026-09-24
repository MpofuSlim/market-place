package com.innbucks.marketplaceservice.catalog.dto;

import com.innbucks.marketplaceservice.catalog.ItemCondition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Full-replace update payload (PUT). Same field rules as
 * {@link ListingCreateRequest}: no currency, no merchant/shop scope — those are
 * server-owned. Status is NOT updatable here; use the status endpoint.
 */
@Schema(description = "Replace a listing's content (status changes go through PATCH /status)")
public record ListingUpdateRequest(

        @Schema(description = "Display title. HTML is stripped server-side.",
                example = "Wireless Bluetooth Speaker", maxLength = 160)
        @NotBlank
        @Size(max = 160)
        String title,

        @Schema(description = "Free-text description. HTML is stripped server-side.",
                example = "Portable speaker with 12h battery life.", maxLength = 4000)
        @Size(max = 4000)
        String description,

        @Schema(description = "Curated taxonomy code (GET /marketplace/categories lists valid values). "
                + "Omitted/blank defaults to 'other'; an unknown code is refused with 400 unknown_category.",
                example = "tv-audio", maxLength = 40, nullable = true)
        @Size(max = 40)
        String categoryCode,

        @Schema(description = "Item condition. Omitted defaults to NEW (full replace — send the "
                + "current value to keep it).",
                example = "USED_GOOD", nullable = true)
        ItemCondition condition,

        @Schema(description = "Seller's city (optional; HTML is stripped server-side).",
                example = "Harare", maxLength = 80, nullable = true)
        @Size(max = 80)
        String city,

        @Schema(description = "Neighbourhood/area within the city (optional; HTML is stripped "
                + "server-side).",
                example = "Avondale", maxLength = 120, nullable = true)
        @Size(max = 120)
        String area,

        @Schema(description = "Unit price in MINOR units (cents). Currency is always the cell currency.",
                example = "2399", minimum = "1", maximum = "100000000")
        @NotNull
        @Min(1)
        @Max(100_000_000)
        Long priceCents,

        @Schema(description = "Units in stock available for reservation. REQUIRED for a listing "
                + "without options (400 stock_required); ignored for one with options, whose "
                + "stock is per option.",
                example = "150", minimum = "0", maximum = "1000000")
        @Min(0)
        @Max(1_000_000)
        Integer stockQty,

        @Schema(description = "Towns this listing can be DELIVERED to, each with its fee. "
                + "**The one field this full replace leaves alone when omitted**: null keeps the "
                + "current towns, [] makes the listing collection only, a list replaces them. "
                + "It is a list a seller builds up town by town, and a client that predates it "
                + "must not wipe it on every edit.",
                nullable = true)
        @Valid
        @Size(max = 100)
        List<DeliveryTownFee> deliveryTowns,

        @Schema(description = "V19: the option AXES of a listing that sells options, e.g. "
                + "[\"Size\", \"Colour\"] (1-2 names, at most 30 characters, no comma). Send "
                + "only together with a non-empty `variants`.", nullable = true)
        @Size(max = 2)
        List<String> options,

        @Schema(description = "V19: the listing's OPTIONS. **Like deliveryTowns, left alone when "
                + "omitted**: null keeps the options (and `stockQty` is then ignored on a listing "
                + "with options), [] removes them all (the listing sells without options and "
                + "`stockQty` is required), a list REPLACES them - see each entry's `id` for how "
                + "existing options are kept. Converting a listing without options needs the "
                + "cell's options switch on (422 variants_disabled).",
                nullable = true)
        @Valid
        @Size(max = 50)
        List<VariantRequest> variants
) {

    /** Pre-V14 shape: delivery towns unchanged. */
    public ListingUpdateRequest(String title, String description, String categoryCode,
                                ItemCondition condition, String city, String area, Long priceCents,
                                Integer stockQty) {
        this(title, description, categoryCode, condition, city, area, priceCents, stockQty, null);
    }

    /** Pre-V19 shape: options unchanged. */
    public ListingUpdateRequest(String title, String description, String categoryCode,
                                ItemCondition condition, String city, String area, Long priceCents,
                                Integer stockQty, List<DeliveryTownFee> deliveryTowns) {
        this(title, description, categoryCode, condition, city, area, priceCents, stockQty,
                deliveryTowns, null, null);
    }
}
