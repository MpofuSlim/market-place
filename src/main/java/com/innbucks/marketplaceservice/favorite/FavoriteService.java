package com.innbucks.marketplaceservice.favorite;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingViewAssembler;
import com.innbucks.marketplaceservice.catalog.dto.ListingPageResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Buyer favorites (V6). Both mutations are IDEMPOTENT — a repeat add or
 * remove is a 200 no-op (the composite-PK {@code ON CONFLICT DO NOTHING} /
 * 0-row DELETE), so the FE's heart-toggle can retry blindly. Adding requires
 * the listing to EXIST but not to be ACTIVE (any status is favoritable —
 * matching the review/image stance); the list read reuses
 * {@link ListingViewAssembler} so rows carry the full listing summary
 * including CURRENT status, letting the FE render "no longer available".
 *
 * <p>Deliberately NO audit rows and NO public favorite counts — see CLAUDE.md.
 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    /** Same hard pagination cap as the public catalog. */
    static final int MAX_PAGE_SIZE = 50;

    private final ListingFavoriteRepository favoriteRepository;
    private final ListingRepository listingRepository;
    private final ListingViewAssembler assembler;

    @Transactional
    public void add(AuthenticatedUser caller, UUID listingId) {
        if (!listingRepository.existsById(listingId)) {
            throw ApiException.notFound("listing_not_found", "Listing not found");
        }
        favoriteRepository.insertIfAbsent(UUID.fromString(caller.uuid()), listingId, Instant.now());
    }

    /** No listing-existence check: removing a favorite of a listing that never
     *  existed and removing an absent favorite are the same no-op — the end
     *  state ("not favorited") is identical either way. */
    @Transactional
    public void remove(AuthenticatedUser caller, UUID listingId) {
        favoriteRepository.remove(UUID.fromString(caller.uuid()), listingId);
    }

    @Transactional(readOnly = true)
    public ListingPageResponse listMine(AuthenticatedUser caller, int page, int size) {
        // UNSORTED pageable — the newest-favorited-first ordering lives in the
        // repository query (sorting by a Listing property here would fight it).
        PageRequest pageable = PageRequest.of(Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE));
        return ListingPageResponse.from(assembler.toResponsePage(
                favoriteRepository.findFavoriteListings(UUID.fromString(caller.uuid()), pageable)));
    }
}
