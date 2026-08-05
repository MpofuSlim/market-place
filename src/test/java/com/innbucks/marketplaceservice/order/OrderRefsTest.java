package com.innbucks.marketplaceservice.order;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the payments-facing order-reference contract: {@code MKT-} + 12
 * UPPERCASE hex characters, drawn from a {@link SecureRandom} (the ref is the
 * S2S lookup key, so it must be unguessable), with no collisions across a
 * realistic sample.
 */
class OrderRefsTest {

    private static final Pattern REF_SHAPE = Pattern.compile("^MKT-[0-9A-F]{12}$");

    @Test
    void refsAreMktPrefixedTwelveUppercaseHexChars() {
        for (int i = 0; i < 1_000; i++) {
            String ref = OrderRefs.newRef();
            assertTrue(REF_SHAPE.matcher(ref).matches(), "bad ref shape: " + ref);
            assertEquals(16, ref.length(), ref);
        }
    }

    @Test
    void tenThousandDrawsProduceNoCollisions() {
        Set<String> seen = new HashSet<>(20_000);
        for (int i = 0; i < 10_000; i++) {
            String ref = OrderRefs.newRef();
            assertTrue(seen.add(ref), "collision at draw " + i + ": " + ref);
        }
        assertEquals(10_000, seen.size());
    }

    /**
     * The ref is the S2S surface's lookup key — a predictable PRNG would make
     * it an enumeration oracle. Pin the generator to {@link SecureRandom} so a
     * refactor to {@code java.util.Random}/{@code ThreadLocalRandom} fails
     * the build.
     */
    @Test
    void generatorIsASecureRandom() throws Exception {
        Field random = OrderRefs.class.getDeclaredField("RANDOM");
        random.setAccessible(true);
        assertTrue(Modifier.isStatic(random.getModifiers()));
        assertInstanceOf(SecureRandom.class, random.get(null));
    }
}
