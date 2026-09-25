package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * THE one place stock moves (V19): reserve, the compensation of a failed
 * reserve, the return of an order or a single parcel, and the seller's sets.
 * It runs every stock statement and is the only publisher of
 * {@link ListingRestocked}, so "what counts as back in stock" has one
 * definition.
 *
 * <h2>Where stock lives</h2>
 * A listing WITHOUT variants keeps its stock on {@code listing.stock_qty},
 * moved by guarded deltas exactly as before V19. A listing WITH variants keeps
 * it per option on {@code listing_variant.stock_qty}; its
 * {@code listing.stock_qty} is a DERIVED total, recomputed (never moved by a
 * delta) after the transaction's variant statements. The guarded variant
 * UPDATE is the only oversell guard; a drifted total can at worst mislead a
 * card until the next movement heals it.
 *
 * <h2>The lock rule</h2>
 * <ol>
 *   <li>A listing's row before any of its variant rows. A variant row is
 *       touched only while its listing's row lock is held — taken by the plain
 *       reserve UPDATE or by {@link ListingRepository#lockForStock}.</li>
 *   <li>Across listings, in {@code java.util.UUID.compareTo} order ({@link
 *       TreeMap}), one statement at a time. Never an SQL {@code ORDER BY ...
 *       FOR UPDATE}: Java orders UUIDs signed and Postgres unsigned.</li>
 *   <li>{@code FOR NO KEY UPDATE}, never {@code FOR UPDATE}.</li>
 * </ol>
 * So two orders on different options of one listing serialise on the listing
 * row, and orders across listings can never form a cycle. Returns iterate in
 * the same order as reserves — before V19 they did not (order-item order vs
 * the reserve's sort), which was a latent deadlock between a release and a
 * reservation.
 *
 * <p>Every method is {@code MANDATORY}: a stock movement belongs to the
 * transaction of the business fact it serves (an order, a cancel, a decline),
 * never to one of its own.
 */
@Slf4j
@Component
public class ListingStock {

    private static final Comparator<StockLine> BY_VARIANT =
            Comparator.comparing(StockLine::variantId, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final ListingRepository listings;
    private final ListingVariantRepository variants;
    private final ApplicationEventPublisher eventPublisher;
    private final MarketplaceMetrics metrics;

    public ListingStock(ListingRepository listings, ListingVariantRepository variants,
                        ApplicationEventPublisher eventPublisher, MarketplaceMetrics metrics) {
        this.listings = listings;
        this.variants = variants;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------
    // Reserve
    // ------------------------------------------------------------------

    /**
     * Reserves every line, or none. Lines are taken per listing in UUID order
     * and, within a listing, by option. A listing without variants runs
     * exactly the pre-V19 statement per line; a listing with variants is
     * locked first, then each option is reserved by its guarded UPDATE, then
     * the total is recomputed.
     *
     * @return the FIRST line that could not be reserved (after un-reserving
     *         everything taken before it), or null when every line was
     *         reserved. The caller turns a refusal into its 409.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockLine reserveAll(Collection<StockLine> lines) {
        List<StockLine> taken = new ArrayList<>(lines.size());
        for (Map.Entry<UUID, List<StockLine>> group : byListing(lines).entrySet()) {
            UUID listingId = group.getKey();
            List<StockLine> groupLines = group.getValue();
            boolean anyPlain = groupLines.stream().anyMatch(l -> l.variantId() == null);
            boolean allPlain = groupLines.stream().allMatch(l -> l.variantId() == null);
            if (allPlain) {
                for (StockLine line : groupLines) {
                    if (listings.reserveStock(listingId, line.quantity()) == 0) {
                        undo(taken);
                        return line;
                    }
                    taken.add(line);
                }
                continue;
            }
            if (anyPlain) {
                // A plain line and an option line of ONE listing cannot both be
                // sellable after pricing; refuse rather than guess, never 500.
                undo(taken);
                return groupLines.get(0);
            }
            StockRow row = listings.lockForStock(listingId);
            if (row == null || !"ACTIVE".equals(row.getStatus())
                    || !Boolean.TRUE.equals(row.getHasVariants())) {
                undo(taken);
                return groupLines.get(0);
            }
            for (StockLine line : groupLines) {
                if (variants.reserve(line.variantId(), listingId, line.quantity()) == 0) {
                    undo(taken);
                    return line;
                }
                taken.add(line);
            }
            listings.recomputeStockTotal(listingId);
        }
        return null;
    }

    /** Un-reserves what a failed reserve had taken. Publishes nothing: units
     *  that were never really out cannot be "back in stock". The rollback
     *  would undo them too; this keeps the guarantee if the loop ever runs
     *  outside the creating transaction. */
    private void undo(List<StockLine> taken) {
        Set<UUID> variantListings = new LinkedHashSet<>();
        for (StockLine line : taken) {
            if (line.variantId() == null) {
                listings.restock(line.listingId(), line.quantity());
            } else {
                variants.restock(line.variantId(), line.listingId(), line.quantity());
                variantListings.add(line.listingId());
            }
        }
        variantListings.forEach(listings::recomputeStockTotal);
    }

    // ------------------------------------------------------------------
    // Return
    // ------------------------------------------------------------------

    /**
     * Returns reserved units — an order's (cancel/expiry) or one parcel's
     * (decline/buyer cancel). Exactly-once is the caller's flag
     * ({@code market_order.stock_released}, {@code order_fulfilment.stock_returned}).
     *
     * <p><b>Never throws for a data condition</b>: a throw here would roll back
     * the EXPIRED / CANCELLED / UNFULFILLED transition it rides and wedge the
     * order. A return with nowhere to go (the listing was converted between
     * plain and variants, the option was deleted, the row is gone) is metered
     * {@code marketplace.stock.returns_dropped{reason}} and skipped. No status
     * guard: held units always go back, whatever the listing's status now.
     *
     * <p>Publishes {@link ListingRestocked} once per listing whose stock (the
     * total, for a variant listing) moved 0 → &gt;0, reading "before" under the
     * listing lock so the check is exact.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void returnAll(Collection<StockLine> lines) {
        for (Map.Entry<UUID, List<StockLine>> group : byListing(lines).entrySet()) {
            UUID listingId = group.getKey();
            StockRow before = listings.lockForStock(listingId);
            int beforeQty = before == null || before.getStockQty() == null ? -1 : before.getStockQty();
            boolean credited = false;
            boolean variantCredited = false;
            for (StockLine line : group.getValue()) {
                if (line.quantity() <= 0) {
                    continue;
                }
                if (line.variantId() == null) {
                    if (listings.restock(listingId, line.quantity()) == 1) {
                        credited = true;
                    } else {
                        drop(before == null ? "listing_missing" : "listing_converted", line);
                    }
                    continue;
                }
                if (before == null) {
                    drop("listing_missing", line);
                } else if (!Boolean.TRUE.equals(before.getHasVariants())) {
                    drop("listing_converted", line);
                } else if (variants.restock(line.variantId(), listingId, line.quantity()) == 0) {
                    drop("variant_removed", line);
                } else {
                    variantCredited = true;
                }
            }
            if (variantCredited) {
                if (listings.recomputeStockTotal(listingId) == 0) {
                    log.error("stock total not recomputed after a return listingId={}", listingId);
                    metrics.stockReturnDropped("invariant_broken");
                    continue;
                }
                credited = true;
            }
            if (credited && beforeQty == 0) {
                Integer after = listings.stockQtyOf(listingId);
                if (after != null && after > 0) {
                    eventPublisher.publishEvent(new ListingRestocked(listingId));
                }
            }
        }
    }

    private void drop(String reason, StockLine line) {
        log.warn("stock return dropped reason={} listingId={} variantId={} qty={}",
                reason, line.listingId(), line.variantId(), line.quantity());
        metrics.stockReturnDropped(reason);
    }

    // ------------------------------------------------------------------
    // Seller writes
    // ------------------------------------------------------------------

    /**
     * Locks one listing's row for a seller write and returns what it held
     * before (null = no such listing). The first statement of every editor
     * transaction, so an edit and an order on the same listing serialise.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockRow lock(UUID listingId) {
        return listings.lockForStock(listingId);
    }

    /** The seller's absolute stock on a listing WITHOUT variants. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setPlain(UUID listingId, int quantity) {
        if (listings.setPlainStock(listingId, quantity) != 1) {
            throw new IllegalStateException("plain stock set did not apply to listing " + listingId);
        }
    }

    /** The seller's absolute stock on one option; false when it is not an
     *  option of that listing. The caller then {@link #settle}s. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean setVariantStock(UUID listingId, UUID variantId, int quantity, Instant now) {
        return variants.setStock(variantId, listingId, quantity, now) == 1;
    }

    /**
     * Ends a seller write: recomputes a variant listing's total, reads the
     * stock after, and publishes {@link ListingRestocked} when it moved
     * 0 → &gt;0 from {@code before} (read under the lock at the start).
     *
     * @return the stock after the write (the total, for a variant listing)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int settle(UUID listingId, int before, boolean variantListing) {
        if (variantListing && listings.recomputeStockTotal(listingId) != 1) {
            throw new IllegalStateException("stock total not recomputed for listing " + listingId);
        }
        Integer after = listings.stockQtyOf(listingId);
        int afterQty = after == null ? 0 : after;
        if (before == 0 && afterQty > 0) {
            eventPublisher.publishEvent(new ListingRestocked(listingId));
        }
        return afterQty;
    }

    /** Lines grouped by listing in UUID order, each group ordered by option
     *  (plain first) — the lock order both reserve and return follow. */
    private static TreeMap<UUID, List<StockLine>> byListing(Collection<StockLine> lines) {
        TreeMap<UUID, List<StockLine>> groups = new TreeMap<>();
        for (StockLine line : lines) {
            groups.computeIfAbsent(line.listingId(), id -> new ArrayList<>()).add(line);
        }
        groups.values().forEach(group -> group.sort(BY_VARIANT));
        return groups;
    }
}
