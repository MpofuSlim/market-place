package com.innbucks.marketplaceservice.favorite;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingRestocked;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.notify.MarketplaceNotificationProperties;
import com.innbucks.marketplaceservice.notify.UserNotifyGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The restock-alert listener's delivery mechanics + guard rails: per-favoriter
 * delivery through {@link UserNotifyGateway}, the recipient cap with a metered
 * overflow, the disabled-flag no-op, and — after-commit path — never-throws
 * even when everything below it explodes.
 */
class RestockAlertListenerTest {

    private static final UUID LISTING_ID = UUID.randomUUID();

    private ListingFavoriteRepository favoriteRepository;
    private ListingRepository listingRepository;
    private UserNotifyGateway gateway;
    private MarketplaceNotificationProperties properties;
    private SimpleMeterRegistry registry;
    private RestockAlertListener listener;

    @BeforeEach
    void setUp() {
        favoriteRepository = mock(ListingFavoriteRepository.class);
        listingRepository = mock(ListingRepository.class);
        gateway = mock(UserNotifyGateway.class);
        properties = new MarketplaceNotificationProperties();
        registry = new SimpleMeterRegistry();
        listener = new RestockAlertListener(favoriteRepository, listingRepository, gateway,
                properties, new MarketplaceMetrics(registry));
        when(listingRepository.findById(LISTING_ID)).thenReturn(Optional.of(listing()));
    }

    private Listing listing() {
        Listing listing = new Listing();
        listing.setId(LISTING_ID);
        listing.setTitle("Solar Lantern 20W");
        listing.setPriceCents(1550);
        listing.setCurrency("USD");
        return listing;
    }

    private double outcome(String outcome) {
        var counter = registry.find("marketplace.notifications")
                .tag("type", "restock_alert").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("delivers the pinned subject+message to every favoriter via the user gateway")
    void deliversToEveryFavoriter() {
        UUID buyer1 = UUID.randomUUID();
        UUID buyer2 = UUID.randomUUID();
        when(favoriteRepository.countByIdListingId(LISTING_ID)).thenReturn(2L);
        when(favoriteRepository.findFavoriterUuids(eq(LISTING_ID), any(Pageable.class)))
                .thenReturn(List.of(buyer1, buyer2));
        when(gateway.notify(any(), anyString(), anyString())).thenReturn(true);

        listener.onRestock(new ListingRestocked(LISTING_ID));

        String subject = "Back in stock on InnBucks Marketplace";
        String message = "Back in stock. Solar Lantern 20W - USD 15.50 on InnBucks Marketplace";
        verify(gateway).notify(buyer1, subject, message);
        verify(gateway).notify(buyer2, subject, message);
        assertThat(outcome("sent")).isEqualTo(2.0);
        assertThat(registry.get("marketplace.restock_events").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recipient cap: only cap-many (oldest first) are fetched; overflow metered per skipped favoriter")
    void capsRecipientsAndMetersOverflow() {
        properties.getRestockAlerts().setMaxRecipientsPerEvent(3);
        when(favoriteRepository.countByIdListingId(LISTING_ID)).thenReturn(10L);
        List<UUID> capped = IntStream.range(0, 3).mapToObj(i -> UUID.randomUUID()).toList();
        when(favoriteRepository.findFavoriterUuids(eq(LISTING_ID), any(Pageable.class)))
                .thenReturn(capped);
        when(gateway.notify(any(), anyString(), anyString())).thenReturn(true);

        listener.onRestock(new ListingRestocked(LISTING_ID));

        // The page request IS the cap — the query never loads more than cap uuids.
        verify(favoriteRepository).findFavoriterUuids(LISTING_ID, PageRequest.of(0, 3));
        assertThat(outcome("sent")).isEqualTo(3.0);
        assertThat(outcome("overflow")).isEqualTo(7.0);
    }

    @Test
    @DisplayName("flag off: outcome=disabled, restock metric still counts, no lookups or sends")
    void disabledFlag_noOp() {
        properties.getRestockAlerts().setEnabled(false);

        listener.onRestock(new ListingRestocked(LISTING_ID));

        verifyNoInteractions(favoriteRepository, gateway);
        assertThat(outcome("disabled")).isEqualTo(1.0);
        assertThat(registry.get("marketplace.restock_events").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("zero favoriters: nothing sent, no outcome metric noise")
    void noFavoriters_noSends() {
        when(favoriteRepository.countByIdListingId(LISTING_ID)).thenReturn(0L);

        listener.onRestock(new ListingRestocked(LISTING_ID));

        verifyNoInteractions(gateway);
        assertThat(outcome("sent")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("vanished listing: quiet no-op (the restock beat a delete race)")
    void vanishedListing_noOp() {
        when(listingRepository.findById(LISTING_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> listener.onRestock(new ListingRestocked(LISTING_ID)))
                .doesNotThrowAnyException();
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("a refused notify counts outcome=failed; the rest still get theirs")
    void gatewayRefusal_countedAndContinues() {
        UUID buyer1 = UUID.randomUUID();
        UUID buyer2 = UUID.randomUUID();
        when(favoriteRepository.countByIdListingId(LISTING_ID)).thenReturn(2L);
        when(favoriteRepository.findFavoriterUuids(eq(LISTING_ID), any(Pageable.class)))
                .thenReturn(List.of(buyer1, buyer2));
        when(gateway.notify(eq(buyer1), anyString(), anyString())).thenReturn(false);
        when(gateway.notify(eq(buyer2), anyString(), anyString())).thenReturn(true);

        listener.onRestock(new ListingRestocked(LISTING_ID));

        assertThat(outcome("failed")).isEqualTo(1.0);
        assertThat(outcome("sent")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an exploding repository never escapes the after-commit listener")
    void repositoryExplosion_swallowed() {
        when(favoriteRepository.countByIdListingId(any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> listener.onRestock(new ListingRestocked(LISTING_ID)))
                .doesNotThrowAnyException();
    }
}
