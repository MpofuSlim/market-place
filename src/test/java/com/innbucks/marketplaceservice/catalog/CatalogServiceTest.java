package com.innbucks.marketplaceservice.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.dto.MerchantProfileResponse;
import com.innbucks.marketplaceservice.review.dto.MerchantRatingResponse;
import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import com.innbucks.marketplaceservice.seller.SellerStatus;
import com.innbucks.marketplaceservice.support.TestTowns;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    private com.innbucks.marketplaceservice.seller.SellerService sellerService;
    private com.innbucks.marketplaceservice.review.ReviewService reviewService;
    private com.innbucks.marketplaceservice.pickup.CollectionPointViews collectionPoints;
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
        sellerService = mock(com.innbucks.marketplaceservice.seller.SellerService.class);
        reviewService = mock(com.innbucks.marketplaceservice.review.ReviewService.class);
        collectionPoints = mock(com.innbucks.marketplaceservice.pickup.CollectionPointViews.class);
        catalogService = new CatalogService(listingRepository, listingImageRepository,
                categoryRepository,
                new ListingViewAssembler(listingImageRepository, categoryRepository, sellerService,
                        mock(ListingDeliveryTownRepository.class), TestTowns.zimbabwe(),
                        collectionPoints),
                sellerService, reviewService,
                mock(com.innbucks.marketplaceservice.fulfilment.SellerFulfilmentStatsService.class),
                TestTowns.zimbabwe(), collectionPoints);
    }

    private static CatalogService.BrowseQuery query(String q, String category,
                                                    String condition, String city) {
        return CatalogService.BrowseQuery.of(q, category, condition, city, 0, 20);
    }

    @SuppressWarnings("unchecked")
    private Specification<Listing> browseAndCaptureSpec(String q, String category,
                                                        String condition, String city) {
        return browseAndCaptureSpec(query(q, category, condition, city));
    }

    @SuppressWarnings("unchecked")
    private Specification<Listing> browseAndCaptureSpec(CatalogService.BrowseQuery request) {
        when(listingRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());
        catalogService.browse(request);
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
                () -> catalogService.browse(query(null, null, "MINT", null)));

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

        catalogService.browse(CatalogService.BrowseQuery.of(null, null, null, null, 0, 5000));

        assertThat(pageable.getValue().getPageSize()).isEqualTo(CatalogService.MAX_PAGE_SIZE);
    }

    // ------------------------------------------------------------------
    // Sort, price window, in-stock, seller filter
    // ------------------------------------------------------------------

    private CatalogService.BrowseQuery browseQuery(Long min, Long max, Boolean inStock,
                                                   ListingSort sort, UUID merchantId) {
        return new CatalogService.BrowseQuery(null, null, null, null, merchantId,
                min, max, inStock, sort, 0, 20);
    }

    @Test
    void defaultOrderIsNewestFirst() {
        Sort sort = capturePageable(browseQuery(null, null, null, null, null)).getSort();

        assertThat(sort.getOrderFor("createdAt")).isNotNull();
        assertThat(sort.getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void everySortEndsWithATotalOrderTiebreaker() {
        // The bug this prevents: a sort on a non-unique column is only a
        // PARTIAL order, so paging a catalogue where many items share a price
        // silently repeats some listings and skips others. Every ordering must
        // therefore end in something unique — the id.
        for (ListingSort candidate : ListingSort.values()) {
            List<Sort.Order> orders =
                    capturePageable(browseQuery(null, null, null, candidate, null))
                            .getSort().toList();

            assertThat(orders.getLast().getProperty())
                    .as("last order key of %s", candidate.wireName())
                    .isEqualTo("id");
            reset(listingRepository);
        }
    }

    @Test
    void priceAscSortsOnPriceCentsAscendingBeforeTheTiebreaker() {
        List<Sort.Order> orders =
                capturePageable(browseQuery(null, null, null, ListingSort.PRICE_ASC, null))
                        .getSort().toList();

        assertThat(orders.getFirst().getProperty()).isEqualTo("priceCents");
        assertThat(orders.getFirst().getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void unknownSortIs400NotASilentFallbackToNewest() {
        // Falling back would return a confidently wrong ORDER to a client that
        // believes it asked for cheapest-first.
        ApiException ex = assertThatApiException(() -> ListingSort.parse("cheapest"));

        assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.code()).isEqualTo("invalid_sort");
    }

    @Test
    void blankSortIsTheDefaultRatherThanAnError() {
        assertThat(ListingSort.parse(null)).isEqualTo(ListingSort.NEWEST);
        assertThat(ListingSort.parse("  ")).isEqualTo(ListingSort.NEWEST);
        assertThat(ListingSort.parse(" Price_Asc ")).isEqualTo(ListingSort.PRICE_ASC);
    }

    @Test
    @SuppressWarnings("unchecked")
    void priceBoundsBuildInclusiveComparisonsOnPriceCents() {
        Path<Long> pricePath = mock(Path.class);
        when(root.<Long>get("priceCents")).thenReturn(pricePath);

        Specification<Listing> spec =
                browseAndCaptureSpec(browseQuery(1000L, 5000L, null, null, null));
        spec.toPredicate(root, query, cb);

        verify(cb).greaterThanOrEqualTo(pricePath, 1000L);
        verify(cb).lessThanOrEqualTo(pricePath, 5000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onePriceBoundBuildsOnlyThatComparison() {
        Path<Long> pricePath = mock(Path.class);
        when(root.<Long>get("priceCents")).thenReturn(pricePath);

        Specification<Listing> spec =
                browseAndCaptureSpec(browseQuery(null, 5000L, null, null, null));
        spec.toPredicate(root, query, cb);

        verify(cb).lessThanOrEqualTo(pricePath, 5000L);
        // An absent lower bound must contribute NO predicate at all — not a
        // zero floor, and above all not a null bind (the bytea trap).
        verify(cb, never()).greaterThanOrEqualTo(any(Path.class), anyLong());
    }

    @Test
    void anInvertedPriceRangeIs400NotAnEmptyPage() {
        // An empty page is indistinguishable from "nothing is for sale in your
        // budget", which sends the client hunting for a data problem.
        ApiException ex = assertThatApiException(
                () -> catalogService.browse(browseQuery(5000L, 1000L, null, null, null)));

        assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.code()).isEqualTo("invalid_price_range");
        verifyNoInteractions(listingRepository);
    }

    @Test
    void aNegativePriceBoundIs400() {
        ApiException ex = assertThatApiException(
                () -> catalogService.browse(browseQuery(-1L, null, null, null, null)));

        assertThat(ex.code()).isEqualTo("invalid_price");
        verifyNoInteractions(listingRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inStockTrueHidesListingsSittingAtZeroStock() {
        Path<Integer> stockPath = mock(Path.class);
        when(root.<Integer>get("stockQty")).thenReturn(stockPath);

        Specification<Listing> spec =
                browseAndCaptureSpec(browseQuery(null, null, Boolean.TRUE, null, null));
        spec.toPredicate(root, query, cb);

        verify(cb).greaterThan(stockPath, 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inStockFalseIsNotAFilterForOutOfStockGoods() {
        // false means "don't filter", the same as absent — NOT "show me only
        // the sold-out ones", which no shopper ever wants.
        Path<Object> statusPath = mock(Path.class);
        when(root.get("status")).thenReturn(statusPath);

        Specification<Listing> spec =
                browseAndCaptureSpec(browseQuery(null, null, Boolean.FALSE, null, null));
        spec.toPredicate(root, query, cb);

        verify(cb).equal(statusPath, ListingStatus.ACTIVE);
        verifyNoMoreInteractions(cb);
    }

    @Test
    @SuppressWarnings("unchecked")
    void merchantFilterMatchesThatSellerExactly() {
        UUID merchantId = UUID.randomUUID();
        Path<Object> merchantPath = mock(Path.class);
        when(root.get("merchantId")).thenReturn(merchantPath);

        Specification<Listing> spec =
                browseAndCaptureSpec(browseQuery(null, null, null, null, merchantId));
        spec.toPredicate(root, query, cb);

        verify(cb).equal(merchantPath, merchantId);
    }

    private static CatalogService.BrowseQuery deliversTo(String town) {
        return new CatalogService.BrowseQuery(null, null, null, null, null, null, null, null,
                ListingSort.NEWEST, 0, 20, town);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deliversToIsAnExistsSubqueryOnThatTownsCoverage() {
        // "Delivers to my town" must be a correlated EXISTS over the listing's
        // own coverage rows — never a join, which would repeat a listing once
        // per town it covers and break paging.
        Path<Object> idPath = mock(Path.class);
        when(root.get("id")).thenReturn(idPath);
        jakarta.persistence.criteria.Subquery<Integer> covers =
                mock(jakarta.persistence.criteria.Subquery.class);
        Root<ListingDeliveryTown> row = mock(Root.class);
        Path<Object> rowListing = mock(Path.class);
        Path<Object> rowTown = mock(Path.class);
        when(query.subquery(Integer.class)).thenReturn(covers);
        when(covers.from(ListingDeliveryTown.class))
                .thenReturn(row);
        when(covers.select(any())).thenReturn(covers);
        when(row.get("listingId")).thenReturn(rowListing);
        when(row.get("townCode")).thenReturn(rowTown);

        // Spelled the way a person types it: normalised to the catalogue code.
        Specification<Listing> spec = browseAndCaptureSpec(deliversTo("  Harare "));
        spec.toPredicate(root, query, cb);

        verify(cb).equal(rowListing, idPath);
        verify(cb).equal(rowTown, "harare");
        verify(cb).exists(covers);
    }

    @Test
    void anUnknownDeliversToTownIs400NotAnEmptyPage() {
        ApiException ex = assertThatApiException(
                () -> catalogService.browse(deliversTo("atlantis")));

        assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.code()).isEqualTo("unknown_town");
        assertThat(ex.getMessage()).contains("deliversTo");
        verifyNoInteractions(listingRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBlankDeliversToIsNoFilter() {
        Path<Object> statusPath = mock(Path.class);
        when(root.get("status")).thenReturn(statusPath);

        Specification<Listing> spec = browseAndCaptureSpec(deliversTo("  "));
        spec.toPredicate(root, query, cb);

        verify(cb).equal(statusPath, ListingStatus.ACTIVE);
        verifyNoMoreInteractions(cb);
    }

    private static CatalogService.BrowseQuery townFilters(String collectsIn, String availableIn) {
        return new CatalogService.BrowseQuery(null, null, null, null, null, null, null, null,
                ListingSort.NEWEST, 0, 20, null, collectsIn, availableIn);
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectsInIsAnExistsOnTheSellersPointsCorrelatedOnTheMerchant() {
        // Points belong to the SELLER, so the correlation is the listing's
        // merchant, not the listing — and still an EXISTS, never a join.
        Path<Object> merchantPath = mock(Path.class);
        when(root.get("merchantId")).thenReturn(merchantPath);
        jakarta.persistence.criteria.Subquery<Integer> points =
                mock(jakarta.persistence.criteria.Subquery.class);
        Root<com.innbucks.marketplaceservice.pickup.CollectionPoint> point = mock(Root.class);
        Path<Object> pointMerchant = mock(Path.class);
        Path<Object> pointTown = mock(Path.class);
        when(query.subquery(Integer.class)).thenReturn(points);
        when(points.from(com.innbucks.marketplaceservice.pickup.CollectionPoint.class))
                .thenReturn(point);
        when(points.select(any())).thenReturn(points);
        when(point.get("merchantId")).thenReturn(pointMerchant);
        when(point.get("townCode")).thenReturn(pointTown);

        Specification<Listing> spec = browseAndCaptureSpec(townFilters(" BULAWAYO ", null));
        spec.toPredicate(root, query, cb);

        verify(cb).equal(pointMerchant, merchantPath);
        verify(cb).equal(pointTown, "bulawayo");
        verify(cb).exists(points);
    }

    @Test
    @SuppressWarnings("unchecked")
    void availableInIsOneOrOfDeliveredThereAndCollectableThere() {
        // One OR of the two EXISTS: a listing that is both delivered to and
        // collectable in the town appears once, and a seller who only offers
        // collection there is not hidden from the shopper.
        when(root.get(any(String.class))).thenReturn(mock(Path.class));
        jakarta.persistence.criteria.Subquery<Integer> covers =
                mock(jakarta.persistence.criteria.Subquery.class);
        jakarta.persistence.criteria.Subquery<Integer> points =
                mock(jakarta.persistence.criteria.Subquery.class);
        when(query.subquery(Integer.class)).thenReturn(covers, points);
        Root<ListingDeliveryTown> row = mock(Root.class);
        Root<com.innbucks.marketplaceservice.pickup.CollectionPoint> point = mock(Root.class);
        when(covers.from(ListingDeliveryTown.class)).thenReturn(row);
        when(points.from(com.innbucks.marketplaceservice.pickup.CollectionPoint.class))
                .thenReturn(point);
        when(covers.select(any())).thenReturn(covers);
        when(points.select(any())).thenReturn(points);
        Path<Object> rowTown = mock(Path.class);
        Path<Object> pointTown = mock(Path.class);
        when(row.get(any(String.class))).thenReturn(mock(Path.class));
        when(row.get("townCode")).thenReturn(rowTown);
        when(point.get(any(String.class))).thenReturn(mock(Path.class));
        when(point.get("townCode")).thenReturn(pointTown);
        jakarta.persistence.criteria.Predicate delivered =
                mock(jakarta.persistence.criteria.Predicate.class);
        jakarta.persistence.criteria.Predicate collected =
                mock(jakarta.persistence.criteria.Predicate.class);
        when(cb.exists(covers)).thenReturn(delivered);
        when(cb.exists(points)).thenReturn(collected);

        Specification<Listing> spec = browseAndCaptureSpec(townFilters(null, "mutare"));
        spec.toPredicate(root, query, cb);

        verify(cb).equal(rowTown, "mutare");
        verify(cb).equal(pointTown, "mutare");
        verify(cb).or(delivered, collected);
    }

    @Test
    void unknownCollectionTownsAre400NamingTheParameter() {
        ApiException collects = assertThatApiException(
                () -> catalogService.browse(townFilters("atlantis", null)));
        assertThat(collects.code()).isEqualTo("unknown_town");
        assertThat(collects.getMessage()).contains("collectsIn");

        ApiException available = assertThatApiException(
                () -> catalogService.browse(townFilters(null, "atlantis")));
        assertThat(available.code()).isEqualTo("unknown_town");
        assertThat(available.getMessage()).contains("availableIn");
        verifyNoInteractions(listingRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankCollectionTownsAreNoFilter() {
        Path<Object> statusPath = mock(Path.class);
        when(root.get("status")).thenReturn(statusPath);

        Specification<Listing> spec = browseAndCaptureSpec(townFilters(" ", ""));
        spec.toPredicate(root, query, cb);

        verify(cb).equal(statusPath, ListingStatus.ACTIVE);
        verifyNoMoreInteractions(cb);
    }

    @SuppressWarnings("unchecked")
    private Pageable capturePageable(CatalogService.BrowseQuery request) {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(listingRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());
        catalogService.browse(request);
        verify(listingRepository).findAll(any(Specification.class), pageable.capture());
        return pageable.getValue();
    }

    // ------------------------------------------------------------------
    // Public seller profile
    // ------------------------------------------------------------------

    @Test
    void merchantProfileCombinesBadgeRatingAndLiveListingCount() {
        UUID merchantId = UUID.randomUUID();
        Instant since = Instant.parse("2026-04-01T09:15:00Z");
        when(sellerService.findAllByMerchantIds(List.of(merchantId))).thenReturn(Map.of(merchantId,
                MarketplaceSeller.builder()
                        .merchantId(merchantId)
                        .status(SellerStatus.APPROVED)
                        .displayName("Rudo Traders")
                        .createdAt(since)
                        .build()));
        // The name now comes through SellerService, which layers the
        // operator-set name over the loyalty registry's — the profile reads
        // the resolved answer rather than the column, so a seller nobody has
        // approved is still named.
        when(sellerService.displayNames(eq(List.of(merchantId)), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(Map.of(merchantId, "Rudo Traders"));
        when(reviewService.merchantRating(merchantId))
                .thenReturn(new MerchantRatingResponse(merchantId, 4.5, 12));
        when(listingRepository.countByMerchantIdAndStatus(merchantId, ListingStatus.ACTIVE))
                .thenReturn(7L);

        MerchantProfileResponse profile = catalogService.merchantProfile(merchantId);

        assertThat(profile.displayName()).isEqualTo("Rudo Traders");
        assertThat(profile.verified()).isTrue();
        assertThat(profile.since()).isEqualTo(since);
        assertThat(profile.ratingAvg()).isEqualTo(4.5);
        assertThat(profile.reviewCount()).isEqualTo(12);
        assertThat(profile.activeListingCount()).isEqualTo(7);
    }

    @Test
    void merchantProfileCarriesTheSellersCollectionPoints() {
        UUID merchantId = UUID.randomUUID();
        when(sellerService.findAllByMerchantIds(List.of(merchantId))).thenReturn(Map.of());
        when(reviewService.merchantRating(merchantId))
                .thenReturn(new MerchantRatingResponse(merchantId, null, 0));
        com.innbucks.marketplaceservice.pickup.dto.CollectionPointResponse point =
                new com.innbucks.marketplaceservice.pickup.dto.CollectionPointResponse(
                        UUID.randomUUID(), "Avondale shop", "harare", "Harare",
                        "14 Samora Machel Ave", null, "Avondale", null, null, null, null, null,
                        null, null, null, true, Instant.parse("2026-09-24T08:10:22Z"));
        when(collectionPoints.forMerchant(merchantId)).thenReturn(List.of(point));

        MerchantProfileResponse profile = catalogService.merchantProfile(merchantId);

        assertThat(profile.collectionPoints()).containsExactly(point);
    }

    @Test
    void anUnknownMerchantIsAnEmptyProfileNotA404() {
        // A 404 would make the public catalogue an oracle for which merchant
        // ids exist, and a shopper on a stale link is better served by an
        // empty profile than an error page.
        UUID merchantId = UUID.randomUUID();
        when(sellerService.findAllByMerchantIds(List.of(merchantId))).thenReturn(Map.of());
        when(reviewService.merchantRating(merchantId))
                .thenReturn(new MerchantRatingResponse(merchantId, null, 0));
        when(listingRepository.countByMerchantIdAndStatus(merchantId, ListingStatus.ACTIVE))
                .thenReturn(0L);

        MerchantProfileResponse profile = catalogService.merchantProfile(merchantId);

        assertThat(profile.merchantId()).isEqualTo(merchantId);
        assertThat(profile.displayName()).isNull();
        assertThat(profile.verified()).isFalse();
        assertThat(profile.since()).isNull();
        assertThat(profile.ratingAvg()).isNull();
        assertThat(profile.activeListingCount()).isZero();
        assertThat(profile.collectionPoints()).isNotNull().isEmpty();
    }

    @Test
    void anUnratedSellerReportsNullRatherThanZeroStars() {
        UUID merchantId = UUID.randomUUID();
        when(sellerService.findAllByMerchantIds(List.of(merchantId))).thenReturn(Map.of(merchantId,
                MarketplaceSeller.builder()
                        .merchantId(merchantId)
                        .status(SellerStatus.PENDING)
                        .createdAt(Instant.now())
                        .build()));
        when(reviewService.merchantRating(merchantId))
                .thenReturn(new MerchantRatingResponse(merchantId, null, 0));
        when(listingRepository.countByMerchantIdAndStatus(merchantId, ListingStatus.ACTIVE))
                .thenReturn(3L);

        MerchantProfileResponse profile = catalogService.merchantProfile(merchantId);

        assertThat(profile.ratingAvg()).isNull();
        // PENDING can trade but is not vetted — verified is a claim the
        // platform makes on its own behalf, never inferred from existence.
        assertThat(profile.verified()).isFalse();
        assertThat(profile.activeListingCount()).isEqualTo(3);
    }

    @Test
    void theProfileCountsACTIVEListingsOnlyNotDraftsOrArchived() {
        UUID merchantId = UUID.randomUUID();
        when(sellerService.findAllByMerchantIds(List.of(merchantId))).thenReturn(Map.of());
        when(reviewService.merchantRating(merchantId))
                .thenReturn(new MerchantRatingResponse(merchantId, null, 0));

        catalogService.merchantProfile(merchantId);

        verify(listingRepository).countByMerchantIdAndStatus(merchantId, ListingStatus.ACTIVE);
        verify(listingRepository, never()).countByMerchantId(any(UUID.class));
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
