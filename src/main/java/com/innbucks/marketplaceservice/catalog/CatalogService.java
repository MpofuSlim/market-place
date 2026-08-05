package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.dto.ListingPageResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Public (unauthenticated) catalog reads. ACTIVE listings only — every other
 * status is invisible here, including on direct-id lookup, so a DRAFT or
 * ARCHIVED listing is indistinguishable from a nonexistent one (404).
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    /** Hard pagination cap (fleet input-hygiene rule). Oversized requests are
     *  clamped, never errored. */
    static final int MAX_PAGE_SIZE = 50;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ListingRepository listingRepository;

    @Transactional(readOnly = true)
    public ListingPageResponse browse(String q, String category, int page, int size) {
        String titleFilter = blankToNull(q);
        if (titleFilter != null) {
            titleFilter = escapeLike(titleFilter);
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE), NEWEST_FIRST);
        return ListingPageResponse.from(
                listingRepository.searchActive(titleFilter, blankToNull(category), pageable)
                        .map(ListingResponse::from));
    }

    @Transactional(readOnly = true)
    public ListingResponse getById(UUID id) {
        return listingRepository.findByIdAndStatus(id, ListingStatus.ACTIVE)
                .map(ListingResponse::from)
                .orElseThrow(() -> ApiException.notFound("listing_not_found", "Listing not found"));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Neutralises LIKE metacharacters in the user's search term using {@code !}
     * as the escape character (matching the {@code escape '!'} clause in
     * {@link ListingRepository#searchActive}) — a query of {@code 100%} must
     * match titles containing "100%", not act as a wildcard.
     */
    private static String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}
