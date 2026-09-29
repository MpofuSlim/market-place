package com.innbucks.marketplaceservice.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two fail-open revocation lookups on the authentication path
 * ({@link RevokedTokenDenylist}, {@link TokenVersionStore}): during a Redis
 * outage they must still let the request through exactly as before, but every
 * failure is COUNTED ({@code marketplace.auth.revocation_check_failed{store}})
 * and the WARN is throttled to one per store per minute — class and message,
 * no stack trace, carrying how many were held back. They used to log a full
 * stack trace each, twice per authenticated request.
 */
class RevocationFailOpenSignalTest {

    private static final String TOKEN = "header.payload.signature";
    private static final String USER_UUID = "0b7c3f5e-1d2a-4c6b-9e8f-7a6b5c4d3e2f";

    private final AtomicLong nanos = new AtomicLong();
    private SimpleMeterRegistry registry;
    private MarketplaceMetrics metrics;
    private StringRedisTemplate redis;
    private ObjectProvider<StringRedisTemplate> provider;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MarketplaceMetrics(registry);
        redis = mock(StringRedisTemplate.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        appender.start();
        logger(RevokedTokenDenylist.class).addAppender(appender);
        logger(TokenVersionStore.class).addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger(RevokedTokenDenylist.class).detachAppender(appender);
        logger(TokenVersionStore.class).detachAppender(appender);
    }

    private static Logger logger(Class<?> type) {
        return (Logger) LoggerFactory.getLogger(type);
    }

    private ThrottledWarning throttle() {
        return new ThrottledWarning(Duration.ofMinutes(1), nanos::get);
    }

    private double failures(String store) {
        // Pre-registered at construction: a missing series is itself a bug.
        return registry.get("marketplace.auth.revocation_check_failed")
                .tag("store", store).counter().count();
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    @DisplayName("Denylist outage: still fails open, counted every time, one WARN a minute without a stack trace")
    void denylistOutageIsCountedAndTheWarningThrottled() {
        when(redis.hasKey(anyString()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        RevokedTokenDenylist denylist = new RevokedTokenDenylist(provider, metrics, throttle());

        for (int i = 0; i < 5; i++) {
            assertThat(denylist.isRevoked(TOKEN)).isFalse();
        }
        assertThat(failures("denylist")).isEqualTo(5.0);
        assertThat(failures("token_version")).isZero();
        assertThat(warnings()).hasSize(1);
        ILoggingEvent first = warnings().getFirst();
        assertThat(first.getThrowableProxy()).isNull();
        assertThat(first.getFormattedMessage())
                .contains(RedisConnectionFailureException.class.getName())
                .contains("Unable to connect to Redis")
                .contains("0 further failure(s)");

        nanos.addAndGet(Duration.ofMinutes(1).toNanos());
        assertThat(denylist.isRevoked(TOKEN)).isFalse();

        assertThat(failures("denylist")).isEqualTo(6.0);
        assertThat(warnings()).hasSize(2);
        assertThat(warnings().get(1).getFormattedMessage()).contains("4 further failure(s)");
    }

    @Test
    @DisplayName("Token-version outage: still fails open (null), counted under its own store tag, throttled")
    @SuppressWarnings("unchecked")
    void tokenVersionOutageIsCountedAndTheWarningThrottled() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        TokenVersionStore store = new TokenVersionStore(provider, metrics, throttle());

        for (int i = 0; i < 3; i++) {
            assertThat(store.currentVersion(USER_UUID)).isNull();
        }
        assertThat(failures("token_version")).isEqualTo(3.0);
        assertThat(failures("denylist")).isZero();
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().getFirst().getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("Each store throttles independently: one store's warning never silences the other's")
    @SuppressWarnings("unchecked")
    void theTwoStoresThrottleIndependently() {
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        RevokedTokenDenylist denylist = new RevokedTokenDenylist(provider, metrics, throttle());
        TokenVersionStore store = new TokenVersionStore(provider, metrics, throttle());

        denylist.isRevoked(TOKEN);
        store.currentVersion(USER_UUID);
        denylist.isRevoked(TOKEN);
        store.currentVersion(USER_UUID);

        assertThat(warnings()).hasSize(2);
        assertThat(warnings()).extracting(ILoggingEvent::getLoggerName).containsExactlyInAnyOrder(
                RevokedTokenDenylist.class.getName(), TokenVersionStore.class.getName());
    }

    @Test
    @DisplayName("A healthy lookup - hit, miss or absent key - counts and logs nothing")
    @SuppressWarnings("unchecked")
    void healthyLookupsCountNothing() {
        when(redis.hasKey(anyString())).thenReturn(true, false);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn("3", (String) null);
        RevokedTokenDenylist denylist = new RevokedTokenDenylist(provider, metrics, throttle());
        TokenVersionStore store = new TokenVersionStore(provider, metrics, throttle());

        assertThat(denylist.isRevoked(TOKEN)).isTrue();
        assertThat(denylist.isRevoked(TOKEN)).isFalse();
        assertThat(store.currentVersion(USER_UUID)).isEqualTo(3L);
        assertThat(store.currentVersion(USER_UUID)).isNull();

        assertThat(failures("denylist")).isZero();
        assertThat(failures("token_version")).isZero();
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("No Redis template at all (test profiles) stays a silent fail-open, not an outage")
    void noTemplateIsNotCountedAsAnOutage() {
        when(provider.getIfAvailable()).thenReturn(null);
        RevokedTokenDenylist denylist = new RevokedTokenDenylist(provider, metrics, throttle());
        TokenVersionStore store = new TokenVersionStore(provider, metrics, throttle());

        assertThat(denylist.isRevoked(TOKEN)).isFalse();
        assertThat(store.currentVersion(USER_UUID)).isNull();

        assertThat(failures("denylist")).isZero();
        assertThat(failures("token_version")).isZero();
    }
}
