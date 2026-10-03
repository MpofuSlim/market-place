package com.innbucks.marketplaceservice.notify;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * A bearer-token cache whose login never blocks a caller that already holds a
 * usable token, and that runs at most ONE login at a time.
 *
 * <p>It replaces a {@code synchronized} getter that held the client's monitor
 * across the upstream login: a slow login (about 19 s measured) made every
 * sender in the process queue behind it, including the ones whose cached token
 * was perfectly good. The rules, in the order a call meets them:
 *
 * <ol>
 *   <li><b>Fast path, no lock.</b> The cached token lives in an immutable
 *       {@link Token} behind a {@code volatile} field. Before its
 *       {@link Token#refreshAt() refreshAt} it is returned without touching
 *       the lock.</li>
 *   <li><b>Single flight.</b> At most one login is in flight, as a
 *       {@link CompletableFuture}. The lock is held only to start or join that
 *       future — never across the network call.</li>
 *   <li><b>Stale while refreshing.</b> Between {@code refreshAt} and
 *       {@link Token#expiresAt() expiresAt} the token is due but still valid:
 *       the caller that starts the refresh runs the login, and every other
 *       caller gets the current token at once.</li>
 *   <li><b>Bounded wait.</b> A caller with no usable token joins the in-flight
 *       login for at most {@code maxWait} (the client's connect + read timeout
 *       plus a margin), then fails with the client's own exception type.</li>
 *   <li><b>A rejection refreshes once.</b> {@link #refreshAfterRejection}
 *       logs in again only if the cached token is still the one that was
 *       rejected, so N concurrent 401s cause one login, not N.</li>
 *   <li><b>A failed login leaves nothing behind.</b> The future completes
 *       exceptionally, the in-flight slot is cleared, nothing is cached, and
 *       the next caller tries again. A token known to be rejected is dropped
 *       from the cache rather than handed out again.</li>
 * </ol>
 *
 * <p>The caller that starts a login runs it on its own thread, so its wait is
 * bounded by the client's HTTP timeouts rather than by {@code maxWait}. This
 * class never logs a token value or a credential; {@link Token#toString()}
 * omits the value.
 */
@Slf4j
public final class SingleFlightTokenCache {

    /**
     * One cached token. {@code refreshAt} is when a refresh becomes due;
     * {@code expiresAt} is the last instant the token may be handed out.
     */
    public record Token(String value, Instant refreshAt, Instant expiresAt) {
        public Token {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("token value is blank");
            }
            Objects.requireNonNull(refreshAt, "refreshAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (refreshAt.isAfter(expiresAt)) {
                refreshAt = expiresAt;
            }
        }

        /** Never prints the token itself. */
        @Override
        public String toString() {
            return "Token[refreshAt=" + refreshAt + ", expiresAt=" + expiresAt + "]";
        }
    }

    private final String name;
    private final Supplier<Token> login;
    private final Duration maxWait;
    private final BiFunction<String, Throwable, ? extends RuntimeException> failure;
    private final Clock clock;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile Token current;
    /** Guarded by {@link #lock}. Null when no login is running. */
    private CompletableFuture<Token> inFlight;

    /**
     * @param name    what the logs call the upstream, e.g. "Notification API"
     * @param login   performs one login; throws the client's own exception on failure
     * @param maxWait how long a caller with no usable token waits for someone else's login
     * @param failure builds the client's delivery exception (message, nullable cause)
     */
    public SingleFlightTokenCache(String name,
                                  Supplier<Token> login,
                                  Duration maxWait,
                                  BiFunction<String, Throwable, ? extends RuntimeException> failure) {
        this(name, login, maxWait, failure, Clock.systemUTC());
    }

    SingleFlightTokenCache(String name,
                           Supplier<Token> login,
                           Duration maxWait,
                           BiFunction<String, Throwable, ? extends RuntimeException> failure,
                           Clock clock) {
        this.name = Objects.requireNonNull(name, "name");
        this.login = Objects.requireNonNull(login, "login");
        this.maxWait = Objects.requireNonNull(maxWait, "maxWait");
        this.failure = Objects.requireNonNull(failure, "failure");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxWait.isNegative() || maxWait.isZero()) {
            throw new IllegalArgumentException("maxWait must be positive");
        }
    }

    /** A token to send now, logging in only when none is usable. */
    public String get() {
        return obtain(null);
    }

    /**
     * A token to replay with after {@code rejected} drew a 401. Logs in again
     * only if {@code rejected} is still the cached token; if another caller has
     * already replaced it, the replacement is returned without a login.
     */
    public String refreshAfterRejection(String rejected) {
        return obtain(Objects.requireNonNull(rejected, "rejected"));
    }

    private String obtain(String rejected) {
        Token seen = current;
        if (fresh(seen, rejected, clock.instant())) {
            return seen.value();
        }

        CompletableFuture<Token> flight;
        boolean starter = false;
        Instant now;
        lock.lock();
        try {
            seen = current;
            now = clock.instant();
            if (fresh(seen, rejected, now)) {
                return seen.value();
            }
            if (rejected != null && seen != null && seen.value().equals(rejected)) {
                // Known bad: nobody may be handed it again while we log in.
                current = null;
                seen = null;
            }
            flight = inFlight;
            if (flight == null) {
                flight = new CompletableFuture<>();
                inFlight = flight;
                starter = true;
            }
        } finally {
            lock.unlock();
        }

        if (starter) {
            return runLogin(flight, seen);
        }
        if (usable(seen, now)) {
            // Due but not expired, and someone else is already refreshing it.
            return seen.value();
        }
        return await(flight);
    }

    /** Usable without starting or joining a login. */
    private static boolean fresh(Token token, String rejected, Instant now) {
        return token != null
                && now.isBefore(token.refreshAt())
                && (rejected == null || !token.value().equals(rejected));
    }

    private static boolean usable(Token token, Instant now) {
        return token != null && now.isBefore(token.expiresAt());
    }

    private String runLogin(CompletableFuture<Token> flight, Token stale) {
        Token fresh;
        try {
            fresh = login.get();
            if (fresh == null) {
                throw failure.apply(name + " login returned no token", null);
            }
        } catch (Throwable t) {
            lock.lock();
            try {
                if (inFlight == flight) {
                    inFlight = null;
                }
            } finally {
                lock.unlock();
            }
            flight.completeExceptionally(t);
            if (!(t instanceof Error) && usable(stale, clock.instant())) {
                log.warn("{} token refresh failed; the current token is still valid until {} and stays in use: {}",
                        name, stale.expiresAt(), t.getMessage());
                return stale.value();
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error e) {
                throw e;
            }
            throw failure.apply(name + " login failed: " + t.getMessage(), t);
        }

        lock.lock();
        try {
            current = fresh;
            if (inFlight == flight) {
                inFlight = null;
            }
        } finally {
            lock.unlock();
        }
        flight.complete(fresh);
        return fresh.value();
    }

    private String await(CompletableFuture<Token> flight) {
        try {
            return flight.get(maxWait.toNanos(), TimeUnit.NANOSECONDS).value();
        } catch (TimeoutException e) {
            throw failure.apply(name + " login did not complete within " + maxWait.toMillis() + " ms", null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw failure.apply(cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure.apply("Interrupted while waiting for the " + name + " login", e);
        }
    }
}
