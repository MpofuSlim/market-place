package com.innbucks.marketplaceservice.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.innbucks.marketplaceservice.api.ApiException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

/**
 * Pins the browse-query construction rule: one predicate PER PRESENT FILTER,
 * built conditionally — NEVER a nullable-param bind. The single
 * "(:q is null or lower(...) ...)" query this design replaced died on real
 * Postgres with "function lower(bytea) does not exist" for the no-filter
 * browse (an untyped null bind is inferred as bytea) — caught by
 * SecuritySurfaceIT in CI, invisible to mocked-repo tests. These tests keep
 * the structure from regressing: each case captures the {@link Specification}
 * handed to the repository and renders it against a mocked Criteria API,
 * asserting exactly which predicates (and which VALUES — all non-null) were
 * built. SecuritySurfaceIT's anonymous no-filter browse remains the
 * real-SQL-semantics proof, and CatalogTaxonomyBrowseIT exercises every
 * filter against real Postgres.
 */
class CatalogServiceTest {

    private ListingRepository listingRepository;
    private ListingImageRepository listingImageRepository;
    private CategoryRepository categoryRepository;
    private CatalogService catalogService;

    // Mocked Criteria API used to render captured Specifications.
    @SuppressWarnings("unchecked")
    private final Root<Listing> root = mock(Root.class);
    @SuppressWarnings("unchecked")
    private final CriteriaQuery<Object> query = mock(CriteriaQuery.class);
    private final CriteriaBuilder cb = mock(CriteriaBuilder.class);

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        listingImageRepository = mock(ListingImageRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        catalogService = new CatalogService(listingRepository, listingImageRepository,
                categoryRepository,
                new ListingViewAssembler(listingImageRepository, categoryRepository));
    }

    @SuppressWarnings("unchecked")
    private Specification<Listing> browseAndCaptureSpec(String q, String category,
                                                        String condition, String city) {
        when(listingRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());
        catalogService.browse(q, category, condition, city, 0, 20);
        ArgumentCaptor<Specification<Listing>> spec = ArgumentCaptor.forClass(Specification.class);
        verify(listingRepository).findAll(spec.capture(), any(Pageable.class));
        return spec.getValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void noFilterBrowseBuildsOnlyTheStatusPredicate() {
        // The regression this pins: with no filters, the rendered query must
        // contain NOTHING beyond status = ACTIVE — in particular no bind for
        // q/category/condition/city (a null bind is what broke on Postgres).
        Path<Object> statusPath = mock(Path.class);
        when(root.get("status")).thenReturn(statusPath);

        Specification<Listing> spec = browseAndCaptureSpec(null, null, null, null);
        spec.toPredicate(root, query, cb);

        verify(cb).equal(statusPath, ListingStatus.ACTIVE);
        // No like/in/lower/equal for the absent filters — no other predicate,
        // no other bind, nothing that could carry a null.
        verifyNoMoreInteractions(cb);
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankFiltersAreTreatedAsAbsent() {
        Path<Object> statusPath = mock(Path.class);
        when(root.get("status")).thenReturn(statusPath);

        Specification<Listing> spec = browseAndCaptureSpec("   ", "", " ", "");
        spec.toPredicate(root, query, cb);

        verify(cb).equal(statusPath, ListingStatus.ACTIVE);
        verifyNoMoreInteractions(cb);
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void titleFilterLowercasesAndEscapesLikeWildcards() {
        Specification<Listing> spec = browseAndCaptureSpec("50%_off!", null, null, null);
        spec.toPredicate(root, query, cb);

        // escapeLike: ! -> !!, % -> !%, _ -> !_ so client text never acts as
        // a LIKE wildcard; '!' is the declared escape char.
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(cb).like(any(), pattern.capture(), eq('!'));
        assertThat(pattern.getValue()).isEqualTo("%50!%!_off!!%");
    }

    @Test
    @SuppressWarnings("unchecked")
    void parentCategoryFilterExpandsToItselfPlusChildren() {
        when(categoryRepository.findByParentCode("electronics")).thenReturn(List.of(
                new Category("phones-tablets", "Phones & Tablets", "electronics"),
                new Category("tv-audio", "TV & Audio", "electronics")));
        Path<Object> categoryPath = mock(Path.class);
        when(root.get("categoryCode")).thenReturn(categoryPath);

        // Normalization: trims + lowercases before the expansion lookup.
        Specification<Listing> spec = browseAndCaptureSpec(null, "  Electronics ", null, null);
        spec.toPredicate(root, query, cb);

        ArgumentCaptor<Collection<String>> codes = ArgumentCaptor.forClass(Collection.class);
        verify(categoryPath).in(codes.capture());
        assertThat(codes.getValue())
                .containsExactly("electronics", "phones-tablets", "tv-audio");
    }

    @Test
    @SuppressWarnings("unchecked")
    void leafCategoryFilterMatchesJustThatCode() {
        when(categoryRepository.findByParentCode("shoes")).thenReturn(List.of());
        Path<Object> categoryPath = mock(Path.class);
        when(root.get("categoryCode")).thenReturn(categoryPath);

        Specification<Listing> spec = browseAndCaptureSpec(null, "shoes", null, null);
        spec.toPredicate(root, query, cb);

        ArgumentCaptor<Collection<String>> codes = ArgumentCaptor.forClass(Collection.class);
        verify(categoryPath).in(codes.capture());
        assertThat(codes.getValue()).containsExactly("shoes");
    }

    @Test
    @SuppressWarnings("unchecked")
    void conditionFilterParsesCaseInsensitivelyIntoTheEnum() {
        Path<Object> conditionPath = mock(Path.class);
        when(root.get("condition")).thenReturn(conditionPath);

        Specification<Listing> spec = browseAndCaptureSpec(null, null, "used_good", null);
        spec.toPredicate(root, query, cb);

        verify(cb).equal(conditionPath, ItemCondition.USED_GOOD);
    }

    @Test
    void invalidConditionIs400NotASilentlyUnfilteredBrowse() {
        ApiException ex = assertThatApiException(
                () -> catalogService.browse(null, null, "MINT", null, 0, 20));

        assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.code()).isEqualTo("invalid_condition");
        verifyNoInteractions(listingRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cityFilterComparesLowercasedExact() {
        Path<Object> cityPath = mock(Path.class);
        when(root.get("city")).thenReturn(cityPath);
        var lowered = mock(jakarta.persistence.criteria.Expression.class);
        when(cb.lower(any())).thenReturn(lowered);

        Specification<Listing> spec = browseAndCaptureSpec(null, null, null, "  Harare ");
        spec.toPredicate(root, query, cb);

        // lower(city) = 'harare' — exact but case-insensitive, matching the
        // functional index in V4.
        verify(cb).equal(lowered, "harare");
    }

    @Test
    void pageSizeIsClampedToTheCatalogCap() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(listingRepository.findAll(any(Specification.class), pageable.capture()))
                .thenReturn(Page.empty());

        catalogService.browse(null, null, null, null, 0, 5000);

        assertThat(pageable.getValue().getPageSize()).isEqualTo(CatalogService.MAX_PAGE_SIZE);
    }

    // ------------------------------------------------------------------
    // Image serving: primary via /image, any gallery image via
    // /images/{imageId}; every miss is the same 404
    // ------------------------------------------------------------------

    private static final byte[] PNG =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    private static ListingImage image(UUID listingId, boolean primary) {
        return ListingImage.builder()
                .id(UUID.randomUUID()).listingId(listingId)
                .imageBytes(PNG).contentType("image/png")
                .primaryImage(primary).position(0)
                .createdAt(java.time.Instant.now())
                .build();
    }

    @Test
    void primaryImageOfAnUnknownListingIs404ImageNotFound() {
        UUID id = UUID.randomUUID();
        when(listingImageRepository.findByListingIdAndPrimaryImageTrue(id))
                .thenReturn(Optional.empty());

        ApiException ex = assertThatApiException(() -> catalogService.getImage(id));

        assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.code()).isEqualTo("image_not_found");
    }

    @Test
    void primaryImageIsServedFromTheGallery() {
        // Status-independent by design: the query never touches the listing
        // row, so a DRAFT owner's preview URL works — unlike getById, which
        // hides non-ACTIVE listings.
        UUID id = UUID.randomUUID();
        when(listingImageRepository.findByListingIdAndPrimaryImageTrue(id))
                .thenReturn(Optional.of(image(id, true)));

        CatalogService.ListingImageView view = catalogService.getImage(id);

        assertThat(view.bytes()).isEqualTo(PNG);
        assertThat(view.contentType()).isEqualTo("image/png");
    }

    @Test
    void galleryImageIsServedOnlyThroughItsOwnListingsUrl() {
        UUID listingId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        when(listingImageRepository.findByIdAndListingId(imageId, listingId))
                .thenReturn(Optional.of(image(listingId, false)));

        CatalogService.ListingImageView view = catalogService.getImageById(listingId, imageId);
        assertThat(view.contentType()).isEqualTo("image/png");

        // The (listingId, imageId) pair must match — a valid imageId under a
        // DIFFERENT listing id is the same indistinguishable 404.
        UUID otherListing = UUID.randomUUID();
        when(listingImageRepository.findByIdAndListingId(imageId, otherListing))
                .thenReturn(Optional.empty());
        ApiException ex = assertThatApiException(
                () -> catalogService.getImageById(otherListing, imageId));
        assertThat(ex.code()).isEqualTo("image_not_found");
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
