package com.innbucks.marketplaceservice.security;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link JwtUtil}'s verify-only contract: HS256 via the shared secret,
 * RS256 dual-verify selected by the token's own {@code alg} header when a
 * public key is configured, and rejection of everything else (expired, garbage,
 * alg=none, wrong key, wrong iss/aud). Tokens are minted inside the test with
 * jjwt — this service never mints in production.
 */
class JwtUtilTest {

    private static final String SECRET =
            "unit-test-jwt-secret-0123456789abcdefghijklmnopqrstuv";
    private static final String OTHER_SECRET =
            "unit-test-other-secret-0123456789abcdefghijklmnopqrs";

    private static final String USER_UUID = "0b9f4a6e-1c2d-4e5f-8a7b-9c0d1e2f3a4b";
    private static final String MERCHANT_ID = "11111111-2222-3333-4444-555555555555";
    private static final String SHOP_ID = "66666666-7777-8888-9999-aaaaaaaaaaaa";

    private static KeyPair rsaKeyPair;

    @BeforeAll
    static void generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        rsaKeyPair = generator.generateKeyPair();
    }

    // JwtUtil supports plain `new` + reflection-set fields (no Spring
    // lifecycle) by design — key material initialises lazily on first use.
    private static JwtUtil jwtUtil(String secret, String publicKeyPem) {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "secret", secret);
        ReflectionTestUtils.setField(util, "publicKeyPem", publicKeyPem);
        return util;
    }

    private static JwtUtil hs256OnlyUtil() {
        return jwtUtil(SECRET, "");
    }

    private static JwtUtil dualVerifyUtil() {
        return jwtUtil(SECRET, toPem(rsaKeyPair.getPublic()));
    }

    private static String toPem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static SecretKey hmacKey(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Builder pre-loaded with everything the verifier requires (iss, aud,
     *  future exp) plus the fleet claims this service extracts. */
    private static JwtBuilder validClaims() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .subject(USER_UUID)
                .claim("roles", List.of("MERCHANT_ADMIN", "SHOP_ADMIN"))
                .claim("merchantId", MERCHANT_ID)
                .claim("shopId", SHOP_ID)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)));
    }

    private static String validHs256Token() {
        return validClaims().signWith(hmacKey(SECRET)).compact();
    }

    private static String validRs256Token() {
        return validClaims().signWith(rsaKeyPair.getPrivate(), Jwts.SIG.RS256).compact();
    }

    @Test
    void validHs256TokenIsAcceptedAndClaimsExtracted() {
        JwtUtil util = hs256OnlyUtil();
        String token = validHs256Token();

        assertThat(util.isTokenValid(token)).isTrue();
        assertThat(util.extractUserUuid(token)).isEqualTo(USER_UUID);
        // LinkedHashSet keeps mint order.
        assertThat(util.extractRoles(token)).containsExactly("MERCHANT_ADMIN", "SHOP_ADMIN");
        assertThat(util.extractMerchantId(token)).isEqualTo(MERCHANT_ID);
        assertThat(util.extractShopId(token)).isEqualTo(SHOP_ID);
    }

    @Test
    void userUuidClaimIsPreferredOverSubject() {
        // Real fleet tokens carry the LOGIN identifier (an email) as sub and
        // the user's uuid in the userUuid claim — the principal uuid must be
        // the claim, or downstream UUID columns would receive an email.
        JwtUtil util = hs256OnlyUtil();
        String token = validClaims()
                .subject("merchant@example.com")
                .claim("userUuid", USER_UUID)
                .signWith(hmacKey(SECRET))
                .compact();

        assertThat(util.extractUserUuid(token)).isEqualTo(USER_UUID);
    }

    @Test
    void subjectIsTheFallbackWhenNoUserUuidClaim() {
        // Legacy/test tokens mint the uuid directly as sub (validClaims()
        // default) — the fallback keeps them working.
        JwtUtil util = hs256OnlyUtil();

        assertThat(util.extractUserUuid(validHs256Token())).isEqualTo(USER_UUID);
    }

    @Test
    void rs256TokenIsAcceptedWhenPublicKeyConfigured() {
        JwtUtil util = dualVerifyUtil();
        String token = validRs256Token();

        assertThat(util.isTokenValid(token)).isTrue();
        assertThat(util.extractUserUuid(token)).isEqualTo(USER_UUID);
        assertThat(util.extractMerchantId(token)).isEqualTo(MERCHANT_ID);
    }

    @Test
    void hs256TokenStillAcceptedWhenPublicKeyConfigured() {
        // Dual-verify: configuring the RS public key must not break HS256
        // verification during the migration window.
        assertThat(dualVerifyUtil().isTokenValid(validHs256Token())).isTrue();
    }

    @Test
    void rs256TokenRejectedWhenNoPublicKeyConfigured() {
        // Without jwt.public-key the locator refuses RS-alg tokens outright —
        // they can never fall through to the HMAC key.
        assertThat(hs256OnlyUtil().isTokenValid(validRs256Token())).isFalse();
    }

    @Test
    void expiredTokenRejected() {
        Instant past = Instant.now().minusSeconds(600);
        String expired = validClaims()
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plusSeconds(60)))
                .signWith(hmacKey(SECRET))
                .compact();

        assertThat(hs256OnlyUtil().isTokenValid(expired)).isFalse();
    }

    @Test
    void garbageTokensRejected() {
        JwtUtil util = hs256OnlyUtil();
        assertThat(util.isTokenValid("not-a-jwt")).isFalse();
        assertThat(util.isTokenValid("a.b.c")).isFalse();
        // Hardened sharp edge: jjwt raises IllegalArgumentException (not
        // JwtException) for an empty/null token string; isTokenValid now maps
        // that to false as well, so callers get the boolean contract without
        // needing JwtFilter's catch (Exception) safety net.
        assertThat(util.isTokenValid("")).isFalse();
        assertThat(util.isTokenValid(null)).isFalse();
    }

    @Test
    void unsignedAlgNoneTokenRejected() {
        // jjwt-built unsecured JWT: header {"alg":"none"}, no signature part.
        String unsigned = validClaims().compact();
        assertThat(hs256OnlyUtil().isTokenValid(unsigned)).isFalse();
        assertThat(dualVerifyUtil().isTokenValid(unsigned)).isFalse();
    }

    @Test
    void signatureStrippedAlgNoneForgeryRejected() {
        // The classic downgrade attack: take a validly-signed token, swap the
        // header to alg=none and drop the signature. Must never verify.
        String[] parts = validHs256Token().split("\\.");
        String noneHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String forged = noneHeader + "." + parts[1] + ".";

        assertThat(hs256OnlyUtil().isTokenValid(forged)).isFalse();
    }

    @Test
    void tokenSignedWithDifferentSecretRejected() {
        String foreign = validClaims().signWith(hmacKey(OTHER_SECRET)).compact();
        assertThat(hs256OnlyUtil().isTokenValid(foreign)).isFalse();
    }

    @Test
    void wrongIssuerOrAudienceRejected() {
        JwtUtil util = hs256OnlyUtil();

        String wrongIssuer = validClaims().issuer("someone-else")
                .signWith(hmacKey(SECRET)).compact();
        assertThat(util.isTokenValid(wrongIssuer)).isFalse();

        String wrongAudience = Jwts.builder()
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add("other-app").and()
                .subject(USER_UUID)
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(hmacKey(SECRET))
                .compact();
        assertThat(util.isTokenValid(wrongAudience)).isFalse();
    }

    @Test
    void nonUuidOrAbsentScopeClaimsYieldNull() {
        JwtUtil util = hs256OnlyUtil();
        // merchantId/shopId are canonicalised to UUIDs — anything else is null,
        // so a mangled claim can never widen a merchant's scope.
        String token = validClaims()
                .claim("merchantId", "not-a-uuid")
                .claim("shopId", null)
                .signWith(hmacKey(SECRET))
                .compact();

        assertThat(util.extractMerchantId(token)).isNull();
        assertThat(util.extractShopId(token)).isNull();
    }

    @Test
    void missingRolesClaimYieldsEmptySet() {
        String token = validClaims().claim("roles", null)
                .signWith(hmacKey(SECRET)).compact();
        assertThat(hs256OnlyUtil().extractRoles(token)).isEqualTo(Set.of());
    }
}
