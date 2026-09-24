package com.innbucks.marketplaceservice.catalog.variant;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.ListingStock;
import com.innbucks.marketplaceservice.catalog.dto.VariantRequest;
import com.innbucks.marketplaceservice.catalog.variant.VariantSetResolver.Draft;
import com.innbucks.marketplaceservice.catalog.variant.VariantSetResolver.VariantPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The seller editor's side of options (V19): plans a write through
 * {@link VariantSetResolver} (every 400, and the 422 of the per-cell switch,
 * BEFORE anything is written) and applies it.
 *
 * <p><b>{@code marketplace.listing.variants-enabled}</b> gates only the move
 * INTO options — creating a listing with them, or converting a listing without
 * them. While it is off no listing with options can exist, which is what keeps
 * a rolling restart and a rollback to an image that has never heard of options
 * safe. Editing an existing listing's options, and removing them, always work.
 */
@Component
public class ListingVariantService {

    private final ListingVariantRepository variants;
    private final ListingStock listingStock;
    private final boolean enabled;
    private final int maxVariants;

    public ListingVariantService(ListingVariantRepository variants, ListingStock listingStock,
                                 @Value("${marketplace.listing.variants-enabled:false}") boolean enabled,
                                 @Value("${marketplace.listing.max-variants:50}") int maxVariants) {
        this.variants = variants;
        this.listingStock = listingStock;
        this.enabled = enabled;
        this.maxVariants = maxVariants;
    }

    /**
     * Plans a non-empty {@code variants} list.
     *
     * @param alreadyHasOptions whether the listing sells options today (false
     *                          on create) — only the move INTO options is gated
     * @throws ApiException 422 {@code variants_disabled} when the move into
     *         options is switched off on this cell; every 400 of
     *         {@link VariantSetResolver}
     */
    public VariantPlan plan(boolean alreadyHasOptions, List<String> options,
                            List<VariantRequest> requested, long listingPriceCents,
                            List<ListingVariant> existing) {
        if (!enabled && !alreadyHasOptions) {
            throw ApiException.unprocessable("variants_disabled",
                    "Product options are not available on this marketplace yet");
        }
        return VariantSetResolver.resolve(options, requested, listingPriceCents, existing,
                maxVariants);
    }

    /** The listing's options in the seller's order. */
    public List<ListingVariant> optionsOf(UUID listingId) {
        return variants.findByListingIdOrderByPositionAsc(listingId);
    }

    /** Create: inserts every drafted option. The listing's total was inserted
     *  with the listing (the plan's sum), so no recompute is needed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertAll(UUID listingId, VariantPlan plan, Instant now) {
        variants.saveAll(plan.drafts().stream().map(d -> newRow(listingId, d, now)).toList());
    }

    /**
     * Update: removes what the plan dropped, rewrites what it kept (values,
     * own price, position — and stock only where the seller sent one, as an
     * absolute set through {@link ListingStock}) and inserts what is new. The
     * caller holds the listing row lock and {@link ListingStock#settle}s after,
     * which recomputes the total.
     *
     * <p>Renames and swaps (M→L while L→M) are safe: the option-key uniqueness
     * is checked at commit ({@code DEFERRABLE INITIALLY DEFERRED}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(UUID listingId, VariantPlan plan, Instant now) {
        if (!plan.removed().isEmpty()) {
            variants.deleteAll(plan.removed());
        }
        List<ListingVariant> added = new ArrayList<>();
        for (Draft draft : plan.drafts()) {
            if (draft.isNew()) {
                added.add(newRow(listingId, draft, now));
                continue;
            }
            ListingVariant row = draft.existing();
            row.setValues(draft.value1(), draft.value2());
            row.setPriceCents(draft.override());
            row.setPosition(draft.position());
            row.setUpdatedAt(now);
            if (draft.stockQty() != null) {
                if (!listingStock.setVariantStock(listingId, row.getId(), draft.stockQty(), now)) {
                    throw new IllegalStateException("option " + row.getId()
                            + " vanished under the listing lock");
                }
                // In memory only: the column is not updatable through the entity.
                row.setStockQty(draft.stockQty());
            }
        }
        if (!added.isEmpty()) {
            variants.saveAll(added);
        }
    }

    /** Removes every option — the move back to a listing without them. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeAll(List<ListingVariant> existing) {
        if (!existing.isEmpty()) {
            variants.deleteAll(existing);
        }
    }

    private static ListingVariant newRow(UUID listingId, Draft draft, Instant now) {
        ListingVariant row = ListingVariant.builder()
                .id(UUID.randomUUID())
                .listingId(listingId)
                .priceCents(draft.override())
                .stockQty(draft.stockQty())
                .position(draft.position())
                .createdAt(now)
                .updatedAt(now)
                .build();
        row.setValues(draft.value1(), draft.value2());
        return row;
    }
}
