package com.innbucks.marketplaceservice.seller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/** Page envelope for the seller queue — mirrors ReportPageResponse. */
@Schema(description = "A page of seller records")
public record SellerPageResponse(
        List<SellerResponse> items,
        @Schema(example = "0") int page,
        @Schema(example = "20") int size,
        @Schema(example = "37") long totalElements,
        @Schema(example = "2") int totalPages
) {
    public static SellerPageResponse from(Page<SellerResponse> p) {
        return new SellerPageResponse(p.getContent(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }
}
