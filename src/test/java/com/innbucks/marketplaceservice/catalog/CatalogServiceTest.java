package com.innbucks.marketplaceservice.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import com.innbucks.marketplaceservice.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/**
 * Pins the browse-query selection: one explicit repository query per filter
 * combination, NEVER a nullable-param query. The single
 * "(:q is null or lower(...) ...)" query this replaced died on real Postgres
 * with "function lower(bytea) does not exist" for the no-filter browse —
 * caught by SecuritySurfaceIT in CI, invisible to mocked-repo tests. These
 * tests keep the branch structure from regressing back to one query.
 */
@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private ListingRepository listingRepository;

    @InjectMocks
    private CatalogService catalogService;

    // Each test stubs only the branch it expects; verifyNoMoreInteractions
    // proves no other query variant was touched.

    @Test
    void noFiltersUsesTheStatusOnlyQuery() {
        when(listingRepository.findByStatus(eq(ListingStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(Page.empty());

        catalogService.browse(null, null, 0, 20);

        verify(listingRepository).findByStatus(eq(ListingStatus.ACTIVE), any(Pageable.class));
        verifyNoMoreInteractions(listingRepository);
    }

    @Test
    void blankFiltersAreTreatedAsAbsent() {
        when(listingRepository.findByStatus(eq(ListingStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(Page.empty());

        catalogService.browse("   ", "", 0, 20);

        verify(listingRepository).findByStatus(eq(ListingStatus.ACTIVE), any(Pageable.class));
        verifyNoMoreInteractions(listingRepository);
    }

    @Test
    void categoryOnlyUsesTheCategoryQuery() {
        when(listingRepository.findByStatusAndCategory(
                eq(ListingStatus.ACTIVE), eq("electronics"), any(Pageable.class)))
                .thenReturn(Page.empty());

        catalogService.browse(null, "electronics", 0, 20);

        verify(listingRepository).findByStatusAndCategory(
                eq(ListingStatus.ACTIVE), eq("electronics"), any(Pageable.class));
        verifyNoMoreInteractions(listingRepository);
    }

    @Test
    void titleOnlyUsesTheTitleQueryWithLikeWildcardsEscaped() {
        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        when(listingRepository.findByStatusAndTitleLike(
                eq(ListingStatus.ACTIVE), q.capture(), any(Pageable.class)))
                .thenReturn(Page.empty());

        catalogService.browse("50%_off!", null, 0, 20);

        // escapeLike: ! -> !!, % -> !%, _ -> !_ so client text never acts as
        // a LIKE wildcard.
        assertThat(q.getValue()).isEqualTo("50!%!_off!!");
        verifyNoMoreInteractions(listingRepository);
    }

    @Test
    void bothFiltersUseTheCombinedQuery() {
        when(listingRepository.findByStatusAndCategoryAndTitleLike(
                eq(ListingStatus.ACTIVE), eq("shoes"), eq("runner"), any(Pageable.class)))
                .thenReturn(Page.empty());

        catalogService.browse("runner", "shoes", 0, 20);

        verify(listingRepository).findByStatusAndCategoryAndTitleLike(
                eq(ListingStatus.ACTIVE), eq("shoes"), eq("runner"), any(Pageable.class));
        verifyNoMoreInteractions(listingRepository);
    }

    @Test
    void pageSizeIsClampedToTheCatalogCap() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(listingRepository.findByStatus(eq(ListingStatus.ACTIVE), pageable.capture()))
                .thenReturn(Page.empty());

        catalogService.browse(null, null, 0, 5000);

        assertThat(pageable.getValue().getPageSize()).isEqualTo(CatalogService.MAX_PAGE_SIZE);
    }

    // ------------------------------------------------------------------
    // Image serving: status-independent, missing-listing and missing-image
    // are the same 404
    // ------------------------------------------------------------------

    private static final byte[] PNG =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    @Test
    void imageOfAnUnknownListingIs404ImageNotFound() {
        UUID id = UUID.randomUUID();
        when(listingRepository.findById(id)).thenReturn(Optional.empty());

        ApiException ex = assertThatApiException(() -> catalogService.getImage(id));

        assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.code()).isEqualTo("image_not_found");
    }

    @Test
    void listingWithoutAnImageIs404ImageNotFound() {
        UUID id = UUID.randomUUID();
        when(listingRepository.findById(id))
                .thenReturn(Optional.of(listingWithStatus(id, ListingStatus.ACTIVE, null)));

        ApiException ex = assertThatApiException(() -> catalogService.getImage(id));

        assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.code()).isEqualTo("image_not_found");
    }

    @Test
    void imageIsServedRegardlessOfListingStatus() {
        // Deliberate: DRAFT owners need the preview URL, ids are unguessable
        // UUIDs — unlike getById, which hides non-ACTIVE listings.
        UUID id = UUID.randomUUID();
        when(listingRepository.findById(id))
                .thenReturn(Optional.of(listingWithStatus(id, ListingStatus.DRAFT, PNG)));

        CatalogService.ListingImage image = catalogService.getImage(id);

        assertThat(image.bytes()).isEqualTo(PNG);
        assertThat(image.contentType()).isEqualTo("image/png");
    }

    private static Listing listingWithStatus(UUID id, ListingStatus status, byte[] image) {
        return Listing.builder()
                .id(id).merchantId(UUID.randomUUID()).title("Solar Lantern")
                .priceCents(1000).currency("USD").stockQty(10).status(status)
                .imageBytes(image)
                .imageContentType(image == null ? null : "image/png")
                .createdAt(java.time.Instant.now()).updatedAt(java.time.Instant.now())
                .build();
    }

    private static ApiException assertThatApiException(Runnable call) {
        try {
            call.run();
        } catch (ApiException ex) {
            return ex;
        }
        throw new AssertionError("Expected an ApiException");
    }
}
