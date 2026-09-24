package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.catalog.ListingImageRepository.ImageMeta;
import com.innbucks.marketplaceservice.catalog.dto.DeliveryTownFeeResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import com.innbucks.marketplaceservice.catalog.variant.ListingVariant;
import com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository;
import com.innbucks.marketplaceservice.delivery.DeliveryTown;
import com.innbucks.marketplaceservice.delivery.DeliveryTownCatalog;
import com.innbucks.marketplaceservice.pickup.CollectionPointViews;
import com.innbucks.marketplaceservice.pickup.dto.CollectionTown;
import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import com.innbucks.marketplaceservice.seller.SellerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Assembles {@link ListingResponse}s with their gallery metadata and category
 * display name — the ONE place that fetches image metadata for responses, so
 * the two disciplines hold everywhere:
 *
 * <ul>
 *   <li><b>No N+1 on list endpoints</b>: a page of listings resolves its
 *       galleries with ONE grouped {@code listing_id IN (...)} query and its
 *       category names with ONE {@code findAllById}, regardless of page
 *       size.</li>
 *   <li><b>No image bytes on metadata paths</b>: only the bytes-free
 *       {@link ImageMeta} projection is ever queried here.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ListingViewAssembler {

    private final ListingImageRepository listingImageRepository;
    private final CategoryRepository categoryRepository;
    private final SellerService sellerService;
    private final ListingDeliveryTownRepository deliveryTownRepository;
    private final DeliveryTownCatalog deliveryTowns;
    private final CollectionPointViews collectionPoints;
    private final ListingVariantRepository variantRepository;

    /** Single-listing assembly: one image-metadata query + one category read +
     *  one seller read for the trust badge. */
    public ListingResponse toResponse(Listing listing) {
        List<ImageMeta> images = listingImageRepository
                .findByListingIdOrderByPrimaryImageDescPositionAscCreatedAtAsc(listing.getId());
        String categoryName = categoryRepository.findById(listing.getCategoryCode())
                .map(Category::getName)
                .orElse(null);
        List<UUID> merchantIds = List.of(listing.getMerchantId());
        Map<UUID, MarketplaceSeller> sellers = sellerService.findAllByMerchantIds(merchantIds);
        MarketplaceSeller seller = sellers.get(listing.getMerchantId());
        return ListingResponse.from(listing, images, categoryName, seller,
                sellerService.displayNames(merchantIds, sellers).get(listing.getMerchantId()),
                coverageOf(deliveryTownRepository.findByListingId(listing.getId())),
                collectionPoints.townsFor(merchantIds)
                        .getOrDefault(listing.getMerchantId(), List.of()),
                // V19: options only for a listing that sells them - no query otherwise.
                listing.isHasVariants()
                        ? variantRepository.findByListingIdOrderByPositionAsc(listing.getId())
                        : List.of());
    }

    /**
     * A listing's delivery coverage as the API shows it: display names from the
     * (cached) town list and the list's own display order — never the order
     * rows happened to be inserted in. A stored code the list no longer carries
     * is dropped rather than shown nameless: nobody can pick it at checkout.
     */
    public List<DeliveryTownFeeResponse> coverageOf(List<ListingDeliveryTown> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<String, Long> feeByTown = new HashMap<>();
        for (ListingDeliveryTown row : rows) {
            feeByTown.put(row.getTownCode(), row.getFeeCents());
        }
        List<DeliveryTownFeeResponse> coverage = new ArrayList<>(rows.size());
        for (DeliveryTown town : deliveryTowns.all()) {
            Long fee = feeByTown.get(town.getCode());
            if (fee != null) {
                coverage.add(new DeliveryTownFeeResponse(town.getCode(), town.getName(), fee));
            }
        }
        return coverage;
    }

    /** Page assembly: a fixed number of batch queries for the whole page —
     *  never one per row, regardless of page size (see {@link #assemble}). */
    public Page<ListingResponse> toResponsePage(Page<Listing> page) {
        Map<UUID, ListingResponse> assembled = assemble(page.getContent());
        return page.map(listing -> assembled.get(listing.getId()));
    }

    /**
     * Batch assembly for a caller that already HOLDS the listing rows — the
     * cart and the checkout quote, which resolve their lines through
     * {@code CheckoutPricer} and must not then pay a per-row
     * {@link #toResponse} (three queries each) to render them.
     *
     * <p>Keyed by listing id rather than returned as a list because callers
     * hold their own ordering (request order, cart order) and must not have it
     * silently replaced by ours.
     */
    public Map<UUID, ListingResponse> toResponsesById(List<Listing> listings) {
        return assemble(listings);
    }

    /** The one batch body: one query per related table for the whole
     *  collection — galleries, seller badges (and names for the gaps),
     *  category names, delivery coverage, collection towns and, only when the
     *  page holds a listing with options, the options (V19) — regardless of
     *  its size. */
    private Map<UUID, ListingResponse> assemble(List<Listing> content) {
        List<UUID> listingIds = content.stream().map(Listing::getId).toList();
        // groupingBy(LinkedHashMap) keeps the query's within-listing order
        // (primary first, then position) intact per key.
        Map<UUID, List<ImageMeta>> imagesByListing = listingIds.isEmpty()
                ? Map.of()
                : listingImageRepository
                        .findByListingIdInOrderByPrimaryImageDescPositionAscCreatedAtAsc(listingIds)
                        .stream()
                        .collect(Collectors.groupingBy(ImageMeta::getListingId,
                                LinkedHashMap::new, Collectors.toList()));
        // Third and last batch: seller trust records for the badge. Distinct
        // merchant ids so a page of one merchant's listings is a single-key
        // lookup, not one per row.
        List<UUID> merchantIds = content.stream()
                .map(Listing::getMerchantId).distinct().toList();
        Map<UUID, MarketplaceSeller> sellersByMerchant = sellerService.findAllByMerchantIds(merchantIds);
        // Fourth batch, and only for the merchants with no name of their own:
        // one call for the whole page, never one per listing.
        Map<UUID, String> merchantNames = sellerService.displayNames(merchantIds, sellersByMerchant);
        List<String> codes = content.stream()
                .map(Listing::getCategoryCode).distinct().toList();
        Map<String, String> categoryNames = codes.isEmpty()
                ? Map.of()
                : categoryRepository.findAllById(codes).stream()
                        .collect(Collectors.toMap(Category::getCode, Category::getName));
        // Fifth batch: delivery coverage for the whole page in one query.
        Map<UUID, List<ListingDeliveryTown>> coverageByListing = listingIds.isEmpty()
                ? Map.of()
                : deliveryTownRepository.findByListingIdIn(listingIds).stream()
                        .collect(Collectors.groupingBy(ListingDeliveryTown::getListingId));
        // Sixth batch: each seller's collection towns (V18), one query per page.
        Map<UUID, List<CollectionTown>> collectionTowns = collectionPoints.townsFor(merchantIds);
        // Seventh batch (V19): options, for the listings that sell them only — a
        // page of listings without options costs nothing extra.
        List<UUID> withOptions = content.stream()
                .filter(Listing::isHasVariants).map(Listing::getId).toList();
        Map<UUID, List<ListingVariant>> optionsByListing = withOptions.isEmpty()
                ? Map.of()
                : variantRepository.findByListingIdInOrderByListingIdAscPositionAsc(withOptions)
                        .stream()
                        .collect(Collectors.groupingBy(ListingVariant::getListingId,
                                LinkedHashMap::new, Collectors.toList()));
        Map<UUID, ListingResponse> byId = new LinkedHashMap<>();
        for (Listing listing : content) {
            byId.put(listing.getId(), ListingResponse.from(
                    listing,
                    imagesByListing.getOrDefault(listing.getId(), List.of()),
                    categoryNames.get(listing.getCategoryCode()),
                    sellersByMerchant.get(listing.getMerchantId()),
                    merchantNames.get(listing.getMerchantId()),
                    coverageOf(coverageByListing.getOrDefault(listing.getId(), List.of())),
                    collectionTowns.getOrDefault(listing.getMerchantId(), List.of()),
                    optionsByListing.getOrDefault(listing.getId(), List.of())));
        }
        return byId;
    }
}
