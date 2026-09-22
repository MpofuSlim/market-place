package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.catalog.ListingImageRepository.ImageMeta;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import com.innbucks.marketplaceservice.seller.SellerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

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
                sellerService.displayNames(merchantIds, sellers).get(listing.getMerchantId()));
    }

    /** Page assembly: exactly THREE extra queries for the whole page —
     *  galleries, category names, and seller badges — regardless of page size. */
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

    /** The one batch body: three extra queries for the whole collection —
     *  galleries, category names, seller badges — regardless of its size. */
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
        Map<UUID, ListingResponse> byId = new LinkedHashMap<>();
        for (Listing listing : content) {
            byId.put(listing.getId(), ListingResponse.from(
                    listing,
                    imagesByListing.getOrDefault(listing.getId(), List.of()),
                    categoryNames.get(listing.getCategoryCode()),
                    sellersByMerchant.get(listing.getMerchantId()),
                    merchantNames.get(listing.getMerchantId())));
        }
        return byId;
    }
}
