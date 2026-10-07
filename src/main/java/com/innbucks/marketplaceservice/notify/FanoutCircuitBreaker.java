package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.notify.UserNotifyGateway.Delivery;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A circuit breaker for a notification FAN-OUT: one task that makes many
 * sequential calls to the same downstream (the restock alert's up-to-200
 * {@code POST /users/internal/{uuid}/notify} calls). Without it, a user-service
 * that is down or hung costs every remaining recipient its full connect + read
 * timeout in turn — 200 x 7 s is 23 minutes of a bulk-pool thread and a pooled
 * connection, per event, with two such threads.
 *
 * <p>Built on resilience4j's {@link CircuitBreaker}, driven explicitly
 * (acquire, call, record) because the outcome to record is a {@link Delivery},
 * not an exception:
 * <ul>
 *   <li>{@link Delivery#UNAVAILABLE} (no answer, 5xx, 429) is a FAILURE;</li>
 *   <li>{@link Delivery#ACCEPTED} and {@link Delivery#REFUSED} are successes —
 *       a 404 for one vanished user is user-service answering — and are timed,
 *       so a user-service that answers but slowly opens the breaker through
 *       the slow-call rate;</li>
 *   <li>{@link Delivery#SKIPPED} (nothing sent) releases the permission
 *       unrecorded.</li>
 * </ul>
 *
 * <p>While OPEN, {@link #call} returns {@link Delivery#SHORT_CIRCUITED} without
 * invoking the sender. After the wait it lets a few trial calls through
 * (HALF_OPEN; the transition happens on the next call, no timer thread) and
 * closes or re-opens on their result.
 *
 * <p><b>Scoped to fan-outs on purpose.</b> The per-order notices and the
 * payout-destination warning (the anti-redirect control, "told on EVERY
 * change") make one call each and do NOT go through a breaker: an open breaker
 * would drop them for up to the wait even after user-service recovered.
 *
 * <p>Metrics, all present from boot: {@code marketplace.notifications.breaker_state{breaker}}
 * (0 closed, 1 open, 2 half-open, 3 disabled/other) and
 * {@code marketplace.notifications.breaker_transitions{breaker,to}}
 * ({@code to=open|half_open|closed}). Every transition is a WARN.
 */
@Slf4j
public final class FanoutCircuitBreaker {

    /** Recorded as the failure cause; carries no stack trace (it is not thrown). */
    private static final RuntimeException DOWNSTREAM_UNAVAILABLE = new DownstreamUnavailable();

    private final String name;
    private final boolean enabled;
    private final CircuitBreaker breaker;

    public FanoutCircuitBreaker(String name, Settings settings, Clock clock, MeterRegistry registry) {
        this.name = name;
        this.enabled = settings.isEnabled();
        this.breaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.getSlidingWindowSize())
                .minimumNumberOfCalls(settings.getMinimumNumberOfCalls())
                .failureRateThreshold(settings.getFailureRateThreshold())
                .slowCallDurationThreshold(settings.getSlowCallDurationThreshold())
                .slowCallRateThreshold(settings.getSlowCallRateThreshold())
                .waitDurationInOpenState(settings.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.getPermittedNumberOfCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .clock(clock)
                .build());

        Gauge.builder("marketplace.notifications.breaker_state", this, FanoutCircuitBreaker::stateValue)
                .description("Fan-out circuit breaker state: 0 closed, 1 open, 2 half-open, 3 disabled")
                .tag("breaker", name)
                .register(registry);
        Map<CircuitBreaker.State, Counter> transitions = new EnumMap<>(CircuitBreaker.State.class);
        for (CircuitBreaker.State to : new CircuitBreaker.State[] {
                CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN, CircuitBreaker.State.CLOSED}) {
            transitions.put(to, Counter.builder("marketplace.notifications.breaker_transitions")
                    .description("Fan-out circuit breaker state transitions, by target state")
                    .tag("breaker", name)
                    .tag("to", to.name().toLowerCase(Locale.ROOT))
                    .register(registry));
        }
        breaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State to = event.getStateTransition().getToState();
            Counter counter = transitions.get(to);
            if (counter != null) {
                counter.increment();
            }
            log.warn("Notification fan-out breaker {} {} -> {}", name,
                    event.getStateTransition().getFromState(), to);
        });
    }

    /**
     * Runs {@code send} unless the breaker is open, and records its outcome.
     * Returns {@link Delivery#SHORT_CIRCUITED} — without calling {@code send} —
     * while the breaker refuses calls. A null from {@code send} is returned as
     * is and recorded as nothing.
     */
    public Delivery call(Supplier<Delivery> send) {
        if (!enabled) {
            return send.get();
        }
        if (!breaker.tryAcquirePermission()) {
            return Delivery.SHORT_CIRCUITED;
        }
        long start = System.nanoTime();
        Delivery delivery;
        try {
            delivery = send.get();
        } catch (RuntimeException e) {
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            throw e;
        }
        long elapsed = System.nanoTime() - start;
        if (delivery == Delivery.UNAVAILABLE) {
            breaker.onError(elapsed, TimeUnit.NANOSECONDS, DOWNSTREAM_UNAVAILABLE);
        } else if (delivery == Delivery.ACCEPTED || delivery == Delivery.REFUSED) {
            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
        } else {
            breaker.releasePermission();
        }
        return delivery;
    }

    public String name() {
        return name;
    }

    public CircuitBreaker.State state() {
        return enabled ? breaker.getState() : CircuitBreaker.State.DISABLED;
    }

    private double stateValue() {
        return switch (state()) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
            default -> 3;
        };
    }

    /** The failure resilience4j records for an UNAVAILABLE delivery; never thrown. */
    private static final class DownstreamUnavailable extends RuntimeException {
        private DownstreamUnavailable() {
            super("downstream unavailable", null, false, false);
        }
    }

    /**
     * Thresholds, bound from configuration (see
     * {@link MarketplaceNotificationProperties.RestockAlerts#getBreaker()}).
     * resilience4j validates them; a nonsensical value fails the boot.
     */
    @lombok.Data
    public static class Settings {
        /** Off = every call goes through, as before the breaker existed. */
        private boolean enabled = true;
        /** The last N calls the failure and slow-call rates are computed over. */
        private int slidingWindowSize = 10;
        /** No verdict before this many calls have been recorded. */
        private int minimumNumberOfCalls = 5;
        /** Percentage of UNAVAILABLE outcomes in the window that opens it. */
        private float failureRateThreshold = 50;
        /** A call slower than this counts as slow, whatever its answer. */
        private Duration slowCallDurationThreshold = Duration.ofSeconds(2);
        /** Percentage of slow calls in the window that opens it. */
        private float slowCallRateThreshold = 50;
        /** How long it stays open before letting trial calls through. */
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);
        /** Trial calls in HALF_OPEN; their failure rate decides close or re-open. */
        private int permittedNumberOfCallsInHalfOpenState = 2;
    }
}
