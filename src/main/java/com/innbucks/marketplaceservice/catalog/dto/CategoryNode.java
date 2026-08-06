package com.innbucks.marketplaceservice.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One node of the public category tree (GET /marketplace/categories).
 * Two levels only: top-level nodes carry their children; children carry an
 * empty list (kept as a list, not null, so FE mapping stays uniform).
 */
@Schema(description = "A category taxonomy node")
public record CategoryNode(

        @Schema(description = "Stable machine code — the value listings store and the catalog "
                + "?category= filter matches (a parent code also matches its children's listings)",
                example = "electronics")
        String code,

        @Schema(description = "Display name", example = "Electronics")
        String name,

        @Schema(description = "Child categories (empty for leaf nodes)")
        List<CategoryNode> children
) {
}
