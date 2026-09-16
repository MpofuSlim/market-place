package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * THE one definition of "can this be bought, and what does it cost right now".
 *
 * <p>The cart read, the checkout quote and order creation all resolve their
 * lines through here, so the three can never disagree about whether something
 * is available — which matters because they are the three screens a shopper
 * sees in a row. A cart that says "in stock", a quote that prices it and an
 * order that then refuses it is the worst possible sequence, and it is exactly
 * what independent copies of this rule produce as they drift.
 *
 * <p>The issue vocabulary is {@link OrderLineRejection}'s, deliberately reused
 * rather than re-invented per surface: a client learns
 * {@code LISTING_UNAVAILABLE} / {@code INSUFFICIENT_STOCK} once and renders it
 * everywhere.
 *
 * <p><b>Advisory, always.</b> Stock read here can be stale the instant it is
 * taken. {@code ListingRepository.reserveStock}'s atomic UPDATE remains the
 * authoritative guard at order creation — this exists so the COMMON case
 * reports every problem at once, on the screen before the customer commits,
 * instead of one problem per attempt.
 */
@Component
public class CheckoutPricer {

    private final ListingRepository listingRepository;
    private final String currency;

    public CheckoutPricer(ListingRepository listingRepository,
                          @Value("${innbucks.currency}") String currency) {
        this.listingRepository = listingRepository;
        this.currency = currency;
    }

    /**
     * Prices a basket, preserving REQUEST order. Loads every listing in ONE
     * query regardless of basket size.
     *
     * @throws ApiException 422 {@code order_total_overflow} if the sellable
     *         lines cannot be summed — checked here rather than at each caller
     *         so no surface can present a total it cannot charge.
     */
    @Transactional(readOnly = true)
    public PricedBasket price(List<BasketLine> requested) {
        if (requested.isEmpty()) {
            return new PricedBasket(List.of(), 0L, List.of());
        }
        Map<UUID, Listing> listings = listingRepository
                .findAllById(requested.stream().map(BasketLine::listingId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Listing::getId, l -> l));

        List<PricedLine> lines = new ArrayList<>(requested.size());
        List<OrderLineRejection> issues = new ArrayList<>(0);
        long subtotal = 0L;
        for (BasketLine line : requested) {
            Listing listing = listings.get(line.listingId());
            OrderLineRejection issue = classify(listing, line);
            // An unavailable listing is handed on as null even when a row was
            // found: nothing downstream should price, name or attribute a
            // listing this surface has just declared unbuyable.
            PricedLine priced = new PricedLine(line.listingId(), line.quantity(),
                    issue != null && OrderLineRejection.REASON_UNAVAILABLE.equals(issue.reason())
                            ? null : listing,
                    issue);
            lines.add(priced);
            if (issue != null) {
                issues.add(issue);
            } else {
                try {
                    subtotal = Math.addExact(subtotal,
                            Math.multiplyExact(listing.getPriceCents(), (long) line.quantity()));
                } catch (ArithmeticException ex) {
                    throw ApiException.unprocessable("order_total_overflow",
                            "Order total exceeds the maximum representable amount");
                }
            }
        }
        return new PricedBasket(List.copyOf(lines), subtotal, List.copyOf(issues));
    }

    /**
     * One reason for missing / not ACTIVE / foreign currency: the client remedy
     * is identical (drop the line), and it leaks nothing the public catalog
     * does not already show.
     */
    private OrderLineRejection classify(Listing listing, BasketLine line) {
        if (listing == null
                || listing.getStatus() != ListingStatus.ACTIVE
                || !currency.equals(listing.getCurrency())) {
            return OrderLineRejection.unavailable(line.listingId(), line.quantity());
        }
        if (listing.getStockQty() < line.quantity()) {
            return OrderLineRejection.insufficientStock(line.listingId(), listing.getTitle(),
                    line.quantity(), listing.getStockQty(), listing.getPriceCents());
        }
        return null;
    }
}
