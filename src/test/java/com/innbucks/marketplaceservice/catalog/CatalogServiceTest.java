package com.innbucks.marketplaceservice.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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
}
