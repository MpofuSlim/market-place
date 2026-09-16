package com.innbucks.marketplaceservice.fulfilment.collect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the handover code's two jobs: being readable by a person at a counter,
 * and being useless to anyone who did not receive it.
 */
class CollectCodesTest {

    @Test
    @DisplayName("A minted code is 12 Crockford characters — no I, L, O or U to misread")
    void mintedCodesAvoidTheConfusableLetters() {
        for (int i = 0; i < 200; i++) {
            String code = CollectCodes.mint();
            assertThat(code).hasSize(12).matches("[0-9A-HJKMNP-TV-Z]+");
        }
    }

    @Test
    @DisplayName("Codes do not repeat — 60 bits of SecureRandom is what makes an unkeyed hash safe")
    void codesAreDistinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            seen.add(CollectCodes.mint());
        }
        assertThat(seen).hasSize(1_000);
    }

    @Test
    @DisplayName("Grouping is cosmetic — both forms verify against the same hash")
    void groupedAndPlainAreTheSameCode() {
        String code = "K7Q29XMF3TRW";
        assertThat(CollectCodes.grouped(code)).isEqualTo("K7Q2-9XMF-3TRW");
        String hash = CollectCodes.hash(code);
        assertThat(CollectCodes.matches("K7Q2-9XMF-3TRW", hash)).isTrue();
        assertThat(CollectCodes.matches("k7q2 9xmf 3trw", hash)).isTrue();
    }

    @Test
    @DisplayName("What someone reads off a screen verifies: I and L are 1, O is 0")
    void confusablesAreFolded() {
        // The minted code cannot contain I/L/O, so a collector who reads a 1 as
        // an "l" or a 0 as an "O" must still get in — otherwise the alphabet
        // choice would be protecting nobody.
        String hash = CollectCodes.hash("10Q29XMF3TRW");
        assertThat(CollectCodes.matches("IOQ29XMF3TRW", hash)).isTrue();
        assertThat(CollectCodes.matches("lOq2-9xmf-3trw", hash)).isTrue();
    }

    @Test
    @DisplayName("A different code never matches, and a parcel with no code matches nothing")
    void wrongCodesAndAbsentHashesRefuse() {
        String hash = CollectCodes.hash("K7Q29XMF3TRW");
        assertThat(CollectCodes.matches("K7Q29XMF3TRX", hash)).isFalse();
        assertThat(CollectCodes.matches("", hash)).isFalse();
        assertThat(CollectCodes.matches(null, hash)).isFalse();
        // No code issued: nothing can ever redeem it, including an empty string.
        assertThat(CollectCodes.matches("K7Q29XMF3TRW", null)).isFalse();
        assertThat(CollectCodes.matches("", null)).isFalse();
    }

    @Test
    @DisplayName("The stored form is a 64-char hex digest — never the code itself")
    void hashIsHexAndNotThePlaintext() {
        String code = CollectCodes.mint();
        String hash = CollectCodes.hash(code);
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").doesNotContain(code.toLowerCase());
        assertThat(CollectCodes.hash(code)).isEqualTo(hash);
    }
}
