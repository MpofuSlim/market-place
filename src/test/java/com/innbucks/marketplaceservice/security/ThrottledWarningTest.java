package com.innbucks.marketplaceservice.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** One admitted warning per interval, and an honest count of the held-back ones. */
class ThrottledWarningTest {

    private final AtomicLong nanos = new AtomicLong(-5_000L);   // negative on purpose: nanoTime may be
    private final ThrottledWarning warning =
            new ThrottledWarning(Duration.ofMinutes(1), nanos::get);

    @Test
    void theFirstOccurrenceIsAdmittedWithNothingHeldBack() {
        assertThat(warning.tryAcquire()).isZero();
    }

    @Test
    void occurrencesInsideTheIntervalAreHeldBackAndReportedByTheNextAdmittedOne() {
        assertThat(warning.tryAcquire()).isZero();
        nanos.addAndGet(Duration.ofSeconds(10).toNanos());
        assertThat(warning.tryAcquire()).isEqualTo(-1);
        assertThat(warning.tryAcquire()).isEqualTo(-1);
        nanos.addAndGet(Duration.ofSeconds(49).toNanos());
        assertThat(warning.tryAcquire()).isEqualTo(-1);

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());       // exactly one minute on
        assertThat(warning.tryAcquire()).isEqualTo(3);
        // The count restarts from the admitted one.
        assertThat(warning.tryAcquire()).isEqualTo(-1);
        nanos.addAndGet(Duration.ofMinutes(5).toNanos());
        assertThat(warning.tryAcquire()).isEqualTo(1);
    }
}
