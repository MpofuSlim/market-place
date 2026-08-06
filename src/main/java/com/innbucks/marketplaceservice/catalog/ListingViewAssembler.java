package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.catalog.ListingImageRepository.ImageMeta;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
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

    /** Single-listing assembly: one image-metadata query + one category read. */
    public ListingResponse toResponse(Listing listing) {
        List<ImageMeta> images = listingImageRepository
                .findByListingIdOrderByPrimaryImageDescPositionAscCreatedAtAsc(listing.getId());
        String categoryName = categoryRepository.findById(listing.getCategoryCode())
                .map(Category::getName)
                .orElse(null);
        return ListingResponse.from(listing, images, categoryName);
    }

    /** Page assembly: exactly two extra queries for the whole page. */
    public Page<ListingResponse> toResponsePage(Page<Listing> page) {
        List<UUID> listingIds = page.getContent().stream().map(Listing::getId).toList();
        // groupingBy(LinkedHashMap) keeps the query's within-listing order
        // (primary first, then position) intact per key.
        Map<UUID, List<ImageMeta>> imagesByListing = listingIds.isEmpty()
                ? Map.of()
                : listingImageRepository
                        .findByListingIdInOrderByPrimaryImageDescPositionAscCreatedAtAsc(listingIds)
                        .stream()
                        .collect(Collectors.groupingBy(ImageMeta::getListingId,
                                LinkedHashMap::new, Collectors.toList()));
        List<String> codes = page.getContent().stream()
                .map(Listing::getCategoryCode).distinct().toList();
        Map<String, String> categoryNames = codes.isEmpty()
                ? Map.of()
                : categoryRepository.findAllById(codes).stream()
                        .collect(Collectors.toMap(Category::getCode, Category::getName));
        return page.map(listing -> ListingResponse.from(
                listing,
                imagesByListing.getOrDefault(listing.getId(), List.of()),
                categoryNames.get(listing.getCategoryCode())));
    }
}
