package com.innbucks.marketplaceservice.favorite;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingRestocked;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.notify.FanoutCircuitBreaker;
import com.innbucks.marketplaceservice.notify.MarketplaceNotificationProperties;
import com.innbucks.marketplaceservice.notify.UserNotifyGateway;
import com.innbucks.marketplaceservice.notify.UserNotifyGateway.Delivery;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The restock-alert listener's delivery mechanics + guard rails: per-favoriter
 * delivery through {@link UserNotifyGateway}, the recipient cap with a metered
 * overflow, the disabled-flag no-op, the circuit breaker that stops a fan-out
 * against a failing user-service, and — after-commit path — never-throws even
 * when everything below it explodes.
 */
class RestockAlertListenerTest {

    private static final UUID LISTING_ID = UUID.randomUUID();

    private ListingFavoriteRepository favoriteRepository;
    private ListingRepository listingRepository;
    private UserNotifyGateway gateway;
    private MarketplaceNotificationProperties properties;
    private SimpleMeterRegistry registry;
    private MutableClock clock;
    private FanoutCircuitBreaker breaker;
    private RestockAlertListener listener;

    @BeforeEach
    void setUp() {
        favoriteRepository = mock(ListingFavoriteRepository.class);
        listingRepository = mock(ListingRepository.class);
        gateway = mock(UserNotifyGateway.class);
        properties = new MarketplaceNotificationProperties();
        registry = new SimpleMeterRegistry();
        clock = new MutableClock();
        // The production defaults: window 10, verdict after 5, 50% failures.
        breaker = new FanoutCircuitBreaker("restock_alert_user_notify",
                properties.getRestockAlerts().getBreaker(), clock, registry);
        listener = new RestockAlertListener(favoriteRepository, listingRepository, gateway,
                properties, new MarketplaceMetrics(registry), breaker);
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
        when(gateway.deliver(any(), anyString(), anyString())).thenReturn(Delivery.ACCEPTED);

        listener.onRestock(new ListingRestocked(LISTING_ID));

        String subject = "Back in stock on InnBucks Marketplace";
        String message = "Back in stock. Solar Lantern 20W - USD 15.50 on InnBucks Marketplace";
        verify(gateway).deliver(buyer1, subject, message);
        verify(gateway).deliver(buyer2, subject, message);
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
        when(gateway.deliver(any(), anyString(), anyString())).thenReturn(Delivery.ACCEPTED);

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
        when(gateway.deliver(eq(buyer1), anyString(), anyString())).thenReturn(Delivery.REFUSED);
        when(gateway.deliver(eq(buyer2), anyString(), anyString())).thenReturn(Delivery.ACCEPTED);

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

    private List<UUID> favoriters(int count) {
        List<UUID> recipients = IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
        when(favoriteRepository.countByIdListingId(LISTING_ID)).thenReturn((long) count);
        when(favoriteRepository.findFavoriterUuids(eq(LISTING_ID), any(Pageable.class)))
                .thenReturn(recipients);
        return recipients;
    }

    @Test
    @DisplayName("breaker_open is registered at 0 from boot, before any restock")
    void breakerOpenSeriesExistsAtZero() {
        assertThat(registry.find("marketplace.notifications")
                .tag("type", "restock_alert").tag("outcome", "breaker_open").counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isZero());
        assertThat(registry.get("marketplace.notifications.breaker_state")
                .tag("breaker", "restock_alert_user_notify").gauge().value()).isZero();
    }

    @Test
    @DisplayName("user-service failing: after 5 UNAVAILABLE the breaker opens and the other 195 "
            + "favoriters are skipped at once — counted, never called")
    void failingUserService_opensTheBreaker_andSkipsTheRest() {
        favoriters(200);
        when(gateway.deliver(any(), anyString(), anyString())).thenReturn(Delivery.UNAVAILABLE);

        listener.onRestock(new ListingRestocked(LISTING_ID));

        verify(gateway, times(5)).deliver(any(), anyString(), anyString());
        assertThat(outcome("failed")).isEqualTo(5.0);
        assertThat(outcome("breaker_open")).isEqualTo(195.0);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(registry.get("marketplace.notifications.breaker_transitions")
                .tag("to", "open").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an event arriving while the breaker is open sends nothing; after the wait a trial "
            + "call goes through and a healthy answer closes it again")
    void openBreaker_skipsWholeEvent_thenHalfOpensAndRecovers() {
        favoriters(10);
        when(gateway.deliver(any(), anyString(), anyString())).thenReturn(Delivery.UNAVAILABLE);
        listener.onRestock(new ListingRestocked(LISTING_ID));
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        // Still inside the 30 s wait: a second restock event never reaches user-service.
        clock.advance(Duration.ofSeconds(10));
        listener.onRestock(new ListingRestocked(LISTING_ID));
        verify(gateway, times(5)).deliver(any(), anyString(), anyString());
        assertThat(outcome("breaker_open")).isEqualTo(5.0 + 10.0);

        // Past the wait, user-service is back: two trial calls close it and the
        // rest of the event goes out normally.
        clock.advance(Duration.ofSeconds(25));
        when(gateway.deliver(any(), anyString(), anyString())).thenReturn(Delivery.ACCEPTED);
        listener.onRestock(new ListingRestocked(LISTING_ID));
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(outcome("sent")).isEqualTo(10.0);
        assertThat(registry.get("marketplace.notifications.breaker_transitions")
                .tag("to", "half_open").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("marketplace.notifications.breaker_transitions")
                .tag("to", "closed").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("404s for vanished users are user-service answering: they never open the breaker")
    void refusals_doNotOpenTheBreaker() {
        favoriters(50);
        when(gateway.deliver(any(), anyString(), anyString())).thenReturn(Delivery.REFUSED);

        listener.onRestock(new ListingRestocked(LISTING_ID));

        verify(gateway, times(50)).deliver(any(), anyString(), anyString());
        assertThat(outcome("failed")).isEqualTo(50.0);
        assertThat(outcome("breaker_open")).isZero();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("breaker disabled: every favoriter is tried, as before the breaker existed")
    void disabledBreaker_triesEveryone() {
        properties.getRestockAlerts().getBreaker().setEnabled(false);
        FanoutCircuitBreaker off = new FanoutCircuitBreaker("off",
                properties.getRestockAlerts().getBreaker(), clock, registry);
        RestockAlertListener noBreaker = new RestockAlertListener(favoriteRepository, listingRepository,
                gateway, properties, new MarketplaceMetrics(registry), off);
        favoriters(20);
        when(gateway.deliver(any(), anyString(), anyString())).thenReturn(Delivery.UNAVAILABLE);

        noBreaker.onRestock(new ListingRestocked(LISTING_ID));

        verify(gateway, times(20)).deliver(any(), anyString(), anyString());
        verify(gateway, never()).notify(any(), anyString(), anyString());
        assertThat(off.state()).isEqualTo(CircuitBreaker.State.DISABLED);
    }

    /** A clock the test moves by hand, so the open-state wait is deterministic. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T08:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
