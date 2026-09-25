package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.catalog.ListingViewAssembler;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingVariantResponse;
import com.innbucks.marketplaceservice.checkout.dto.PricedLineResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Renders a {@link PricedBasket} into the wire shape the cart and the checkout
 * quote both serve.
 *
 * <p>Shared so those two screens cannot drift: they are the same list of the
 * same goods a tap apart, and a shopper who sees one price on the cart and
 * another on the checkout will not trust either.
 *
 * <p>Resolves every line's catalogue summary in ONE batch — three queries for
 * the whole basket regardless of how many lines it has, never a round per row.
 */
@Component
@RequiredArgsConstructor
public class BasketViewAssembler {

    private final ListingViewAssembler listingViewAssembler;

    /**
     * @param addedAt when each line entered the cart, or an empty map for a
     *                quote built from explicit items — the field is
     *                {@code NON_NULL} and simply drops out then
     */
    public List<PricedLineResponse> toLines(PricedBasket basket, Map<LineKey, Instant> addedAt) {
        Map<UUID, ListingResponse> views = listingViewAssembler.toResponsesById(
                basket.lines().stream()
                        .map(PricedLine::listing)
                        .filter(Objects::nonNull)
                        .toList());
        List<PricedLineResponse> lines = new ArrayList<>(basket.lines().size());
        for (PricedLine line : basket.lines()) {
            lines.add(new PricedLineResponse(
                    line.listingId(),
                    // Null when the pricer declared the line unbuyable: nothing
                    // should price or name a listing it has just refused.
                    line.listing() == null ? null : views.get(line.listingId()),
                    line.quantity(),
                    line.lineTotalCents(),
                    line.issue(),
                    addedAt.get(line.key()),
                    line.variantId(),
                    line.variant() == null || line.listing() == null ? null
                            : ListingVariantResponse.from(line.variant(),
                                    line.listing().getPriceCents()),
                    line.listing() == null ? null : line.unitPriceCents()));
        }
        return List.copyOf(lines);
    }

    /** Total units across the basket — the number on the cart badge. */
    public static int totalQuantity(PricedBasket basket) {
        return basket.lines().stream().mapToInt(PricedLine::quantity).sum();
    }
}
