package com.innbucks.marketplaceservice.idempotency;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit-testable slices of {@link IdempotencyService}: the {@code namespaced}
 * key derivation (pure function) and the {@code complete} sentinel guard,
 * which both run before any SQL. The claim/replay/in-flight/takeover/mismatch
 * semantics live in Postgres-specific SQL ({@code ON CONFLICT}, guarded
 * UPDATE) — mocking JdbcTemplate there would only re-assert the code's own
 * structure, so those are pinned against a real Postgres in
 * {@code IdempotencyServiceIT} instead.
 */
class IdempotencyServiceTest {

    @Test
    void namespacedIsDeterministic64LowercaseHex() {
        String first = IdempotencyService.namespaced("buyer-uuid", "client-key-1");
        String second = IdempotencyService.namespaced("buyer-uuid", "client-key-1");

        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void namespacedMatchesSha256OverScopeSeparatorKey() throws Exception {
        // Independent recompute pins the documented canonical form:
        // SHA-256(scope ‖ US ‖ rawKey), hex.
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(("buyer-uuid" + "\u001F" + "client-key-1").getBytes(StandardCharsets.UTF_8)));

        assertThat(IdempotencyService.namespaced("buyer-uuid", "client-key-1"))
                .isEqualTo(expected);
    }

    @Test
    void namespacedSeparatesScopes() {
        // Same raw key under two buyers never collides.
        assertThat(IdempotencyService.namespaced("buyer-a", "key"))
                .isNotEqualTo(IdempotencyService.namespaced("buyer-b", "key"));
    }

    @Test
    void namespacedBoundaryCannotBeForgedAcrossParts() {
        // Without the unit separator ("ab","c") and ("a","bc") would collide.
        assertThat(IdempotencyService.namespaced("ab", "c"))
                .isNotEqualTo(IdempotencyService.namespaced("a", "bc"));
    }

    @Test
    void namespacedRejectsBlankParts() {
        assertThatThrownBy(() -> IdempotencyService.namespaced(null, "key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyService.namespaced(" ", "key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyService.namespaced("scope", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyService.namespaced("scope", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completeRejectsTheInProgressSentinelStatus() {
        // Storing status 0 would turn a finished record back into a live claim.
        // The guard fires before any SQL, so a mock DataSource is never touched.
        IdempotencyService service = new IdempotencyService(mock(DataSource.class));

        assertThatThrownBy(() -> service.complete("a".repeat(64), 0, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }
}
