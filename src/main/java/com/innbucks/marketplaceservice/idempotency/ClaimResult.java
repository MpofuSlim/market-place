package com.innbucks.marketplaceservice.idempotency;

/**
 * Outcome of {@link IdempotencyService#claim(String, String)} — a closed set so
 * the order flow exhaustively switches over it:
 *
 * <ul>
 *   <li>{@link New} — this caller won the claim; run the work, then
 *       {@code complete(...)}.</li>
 *   <li>{@link Replay} — same key + same request seen before and finished;
 *       return the ORIGINAL stored status/body, run nothing.</li>
 *   <li>{@link InFlight} — a concurrent request with the same key is still
 *       executing (fresh claim); surface 409 and let the client retry.</li>
 *   <li>{@link Mismatch} — same key but a DIFFERENT request body; surface 422 —
 *       the client is misusing the key, never execute.</li>
 * </ul>
 */
public sealed interface ClaimResult {

    /** The claim row was inserted (or a stale claim taken over) — run the work. */
    record New() implements ClaimResult {}

    /** A finished record exists — replay its original status + body verbatim. */
    record Replay(int status, String responseBody) implements ClaimResult {}

    /** A live claim (status-0 sentinel younger than the grace) holds the key. */
    record InFlight() implements ClaimResult {}

    /** The key was reused with a different request fingerprint. */
    record Mismatch() implements ClaimResult {}
}
