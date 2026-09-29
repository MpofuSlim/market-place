package com.innbucks.marketplaceservice.security;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Admits at most one warning per interval and counts the ones it holds back.
 *
 * <p>Exists for the fail-open Redis lookups on the authentication path
 * ({@link RevokedTokenDenylist}, {@link TokenVersionStore}): both run on EVERY
 * authenticated request, so an outage used to log two full stack traces per
 * request — a flood that buried the useful lines. The outage itself is now a
 * counter ({@code marketplace.auth.revocation_check_failed}); the log only has
 * to say "still happening, N more since I last said so".
 *
 * <p>Lock-free: one CAS decides which caller logs; everyone else increments the
 * suppressed count. The first call is always admitted.
 */
final class ThrottledWarning {

    static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);

    private final long intervalNanos;
    private final LongSupplier nanoClock;
    private final AtomicLong nextAllowedAt;
    private final AtomicLong suppressed = new AtomicLong();

    ThrottledWarning() {
        this(DEFAULT_INTERVAL, System::nanoTime);
    }

    ThrottledWarning(Duration interval, LongSupplier nanoClock) {
        this.intervalNanos = interval.toNanos();
        this.nanoClock = nanoClock;
        this.nextAllowedAt = new AtomicLong(nanoClock.getAsLong());
    }

    /**
     * @return {@code -1} when this occurrence must stay quiet; otherwise the
     *         caller should log now, and the value is how many occurrences were
     *         held back since the previous admitted one.
     */
    long tryAcquire() {
        long now = nanoClock.getAsLong();
        long next = nextAllowedAt.get();
        // Subtraction, not comparison: nanoTime may be negative or wrap.
        if (now - next >= 0 && nextAllowedAt.compareAndSet(next, now + intervalNanos)) {
            return suppressed.getAndSet(0);
        }
        suppressed.incrementAndGet();
        return -1;
    }
}
