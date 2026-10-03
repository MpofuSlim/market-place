package com.innbucks.marketplaceservice.notify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Pure JUnit: the single-flight token cache that replaced a {@code synchronized}
 * getter held across the upstream login.
 */
class SingleFlightTokenCacheTest {

    private static final Instant T0 = Instant.parse("2026-10-03T08:00:00Z");
    private static final int CALLERS = 16;

    private final MutableClock clock = new MutableClock(T0);
    private final ExecutorService pool = Executors.newFixedThreadPool(CALLERS + 2);
    /** Released in teardown so no test leaves a thread parked in a fake login. */
    private final List<CountDownLatch> gates = new ArrayList<>();

    @AfterEach
    void tearDown() {
        gates.forEach(CountDownLatch::countDown);
        pool.shutdownNow();
    }

    private CountDownLatch gate() {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        return gate;
    }

    private SingleFlightTokenCache cache(Supplier<SingleFlightTokenCache.Token> login, Duration maxWait) {
        return new SingleFlightTokenCache("Test API", login, maxWait, NotificationDeliveryException::new, clock);
    }

    private SingleFlightTokenCache.Token token(String value) {
        // Refresh due in 60 s, usable for 120 s, from the clock's current instant.
        Instant now = clock.instant();
        return new SingleFlightTokenCache.Token(value, now.plusSeconds(60), now.plusSeconds(120));
    }

    /** A login that announces it has started, then blocks until {@code release} opens. */
    private Supplier<SingleFlightTokenCache.Token> blockingLogin(AtomicInteger logins, CountDownLatch entered,
                                                                CountDownLatch release, Supplier<String> value) {
        return () -> {
            logins.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test login was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return token(value.get());
        };
    }

    private List<String> joinAll(List<Future<String>> futures) throws Exception {
        List<String> out = new ArrayList<>();
        for (Future<String> f : futures) {
            out.add(f.get(10, TimeUnit.SECONDS));
        }
        return out;
    }

    @Test
    @DisplayName("callers holding a still-valid token never wait while a refresh is blocked")
    void validTokenCallersNeverWaitDuringBlockedRefresh() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger seq = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        // The first login returns at once; the second blocks.
        SingleFlightTokenCache cache = cache(() -> {
            if (seq.incrementAndGet() == 1) {
                logins.incrementAndGet();
                return token("tok-1");
            }
            return blockingLogin(logins, entered, release, () -> "tok-2").get();
        }, Duration.ofSeconds(5));

        assertThat(cache.get()).isEqualTo("tok-1");

        // Refresh is due (past refreshAt) but tok-1 has not expired.
        clock.advance(Duration.ofSeconds(90));
        Future<String> refresher = pool.submit(cache::get);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // The refresh is parked in the upstream. Everyone else gets tok-1 now.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int i = 0; i < 100; i++) {
                assertThat(cache.get()).isEqualTo("tok-1");
            }
        });
        List<Future<String>> concurrent = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
            concurrent.add(pool.submit(cache::get));
        }
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(joinAll(concurrent)).containsOnly("tok-1");
        });

        release.countDown();
        assertThat(refresher.get(5, TimeUnit.SECONDS)).isEqualTo("tok-2");
        assertThat(cache.get()).isEqualTo("tok-2");
        assertThat(logins.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("N cold callers cause ONE login and all receive its token")
    void coldCallersShareOneLogin() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        SingleFlightTokenCache cache = cache(blockingLogin(logins, entered, release, () -> "tok-1"),
                Duration.ofSeconds(10));

        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> callers = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
            callers.add(pool.submit(() -> {
                start.await();
                return cache.get();
            }));
        }
        start.countDown();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200); // let the rest pile up on the in-flight login
        release.countDown();

        assertThat(joinAll(callers)).hasSize(CALLERS).containsOnly("tok-1");
        assertThat(logins.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("N concurrent 401s on the same token cause ONE re-login")
    void concurrentRejectionsShareOneLogin() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger seq = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        SingleFlightTokenCache cache = cache(() -> {
            if (seq.incrementAndGet() == 1) {
                logins.incrementAndGet();
                return token("tok-1");
            }
            return blockingLogin(logins, entered, release, () -> "tok-2").get();
        }, Duration.ofSeconds(10));
        String rejected = cache.get();
        assertThat(rejected).isEqualTo("tok-1");

        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> callers = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
            callers.add(pool.submit(() -> {
                start.await();
                return cache.refreshAfterRejection(rejected);
            }));
        }
        start.countDown();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);
        release.countDown();

        assertThat(joinAll(callers)).hasSize(CALLERS).containsOnly("tok-2");
        assertThat(logins.get()).isEqualTo(2); // the first login + ONE refresh

        // A late 401 for the old token is answered from the cache.
        assertThat(cache.refreshAfterRejection("tok-1")).isEqualTo("tok-2");
        assertThat(logins.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a rejected token is never handed out again while its replacement is being fetched")
    void rejectedTokenIsDroppedFromTheCache() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger seq = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        SingleFlightTokenCache cache = cache(() -> {
            if (seq.incrementAndGet() == 1) {
                logins.incrementAndGet();
                return token("tok-1");
            }
            return blockingLogin(logins, entered, release, () -> "tok-2").get();
        }, Duration.ofSeconds(10));
        cache.get();

        Future<String> rejector = pool.submit(() -> cache.refreshAfterRejection("tok-1"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        Future<String> plain = pool.submit(cache::get);
        Thread.sleep(100);
        assertThat(plain.isDone()).isFalse(); // waiting for tok-2, not given tok-1

        release.countDown();
        assertThat(rejector.get(5, TimeUnit.SECONDS)).isEqualTo("tok-2");
        assertThat(plain.get(5, TimeUnit.SECONDS)).isEqualTo("tok-2");
        assertThat(logins.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a cold caller on a hung login fails within the bound with the client's exception")
    void coldCallerOnHungLoginFailsWithinTheBound() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch neverReleased = gate();
        Duration bound = Duration.ofMillis(300);
        SingleFlightTokenCache cache = cache(blockingLogin(logins, entered, neverReleased, () -> "tok-1"), bound);

        pool.submit(cache::get); // starts the login, which hangs
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThatThrownBy(cache::get)
                        .isInstanceOf(NotificationDeliveryException.class)
                        .hasMessageContaining("did not complete within 300 ms"));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(waitedMs).isGreaterThanOrEqualTo(bound.toMillis() - 20).isLessThan(3000);
        assertThat(logins.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed login caches nothing and the next caller logs in again")
    void failedLoginIsRetriedByTheNextCaller() {
        AtomicInteger logins = new AtomicInteger();
        NotificationDeliveryException boom = new NotificationDeliveryException("Test API login failed: HTTP 503");
        SingleFlightTokenCache cache = cache(() -> {
            if (logins.incrementAndGet() == 1) {
                throw boom;
            }
            return token("tok-1");
        }, Duration.ofSeconds(5));

        // The caller that ran the login sees exactly what the login threw.
        assertThatThrownBy(cache::get).isSameAs(boom);
        assertThat(cache.get()).isEqualTo("tok-1");
        assertThat(cache.get()).isEqualTo("tok-1");
        assertThat(logins.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("callers that joined a failed login get the client's exception, not a hang")
    void joinersOfAFailedLoginFailFast() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = gate();
        SingleFlightTokenCache cache = cache(() -> {
            blockingLogin(logins, entered, release, () -> "unused").get();
            throw new NotificationDeliveryException("Test API login failed: HTTP 401");
        }, Duration.ofSeconds(10));

        Future<String> starter = pool.submit(cache::get);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        Future<String> joiner = pool.submit(cache::get);
        Thread.sleep(100);
        release.countDown();

        assertThatThrownBy(() -> starter.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> joiner.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("login failed");
        assertThat(logins.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed refresh of a still-valid token keeps serving that token")
    void failedRefreshOfAValidTokenKeepsIt() {
        AtomicInteger logins = new AtomicInteger();
        SingleFlightTokenCache cache = cache(() -> {
            if (logins.incrementAndGet() == 1) {
                return token("tok-1");
            }
            throw new NotificationDeliveryException("Test API login failed: HTTP 503");
        }, Duration.ofSeconds(5));
        cache.get();
        clock.advance(Duration.ofSeconds(90)); // due, not expired

        assertThat(cache.get()).isEqualTo("tok-1");
        assertThat(cache.get()).isEqualTo("tok-1");
        assertThat(logins.get()).isEqualTo(3); // each due caller retries the refresh

        clock.advance(Duration.ofSeconds(60)); // now expired: no stale fallback
        assertThatThrownBy(cache::get).isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    @DisplayName("a token's toString never prints its value")
    void tokenToStringHidesTheValue() {
        assertThat(token("secret-bearer").toString()).doesNotContain("secret-bearer");
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
