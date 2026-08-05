package com.innbucks.marketplaceservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Verify-only JWT util for user-service-minted fleet tokens. This service
 * never mints — it holds no private key, and ideally (post RS256 flip) no
 * mint-capable secret at all. Ported from InnRewards (loyalty-service), the
 * fleet's proven standalone verifier.
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    // OWASP A02 stage-1 RS256/JWKS migration (dual-verify). Optional RSA public
    // key: when set, this service verifies BOTH HS256 and RS256 tokens (selected
    // by the token's own `alg` header). When unset it behaves exactly as before
    // (HS256 only). This is a verifier — it never mints, so no private key here.
    @Value("${jwt.public-key:}")
    private String publicKeyPem;

    private PublicKey rsaPublicKey;
    private Locator<Key> keyLocator;
    private volatile boolean keysReady;

    // @PostConstruct validates config at boot under Spring; ensureKeyMaterial()
    // also runs lazily on first use so plain unit tests that `new JwtUtil()` +
    // set fields via reflection (no Spring lifecycle) still work. Idempotent.
    @PostConstruct
    void initKeyMaterial() {
        ensureKeyMaterial();
    }

    private void ensureKeyMaterial() {
        if (keysReady) {
            return;
        }
        synchronized (this) {
            if (keysReady) {
                return;
            }
            SecretKey hmacKey = getSigningKey();
            if (publicKeyPem != null && !publicKeyPem.isBlank()) {
                this.rsaPublicKey = parseRsaPublicKey(publicKeyPem);
            }
            this.keyLocator = (Header header) -> {
                if (header instanceof ProtectedHeader ph) {
                    String alg = ph.getAlgorithm();
                    if ("RS256".equals(alg) || "RS384".equals(alg) || "RS512".equals(alg)) {
                        if (rsaPublicKey == null) {
                            throw new io.jsonwebtoken.security.SignatureException(
                                    "RS-signed token presented but no jwt.public-key is configured");
                        }
                        return rsaPublicKey;
                    }
                }
                return hmacKey;
            };
            this.keysReady = true;
        }
    }

    private static PublicKey parseRsaPublicKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(
                    pem.replaceAll("-----BEGIN [^-]+-----", "")
                            .replaceAll("-----END [^-]+-----", "")
                            .replaceAll("\\s", ""));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid jwt.public-key (expected PKCS#8/X.509 PEM)", e);
        }
    }

    /** Fixed JWT issuer (iss) required on every token this service verifies.
     *  Minted by user-service; a token lacking it (or with a different value)
     *  is rejected even when the shared HS256 signature checks out. */
    public static final String TOKEN_ISSUER = "innbucks-ticketing";

    /** Fixed JWT audience (aud) required on every token this service verifies. */
    public static final String TOKEN_AUDIENCE = "innbucks-app";

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** The token subject ({@code sub}) — the caller's stable user uuid. */
    /**
     * Stable cross-service user identifier for the principal. Fleet tokens
     * mint the user's uuid in the {@code userUuid} claim (the subject is the
     * LOGIN identifier — an email — not the uuid); legacy/test tokens may
     * carry the uuid directly as {@code sub}. Prefer the claim, fall back to
     * the subject — same resolution order as {@link #extractUserId}.
     */
    public String extractUserUuid(String token) {
        Object claim = getClaims(token).get("userUuid");
        if (claim != null && !claim.toString().isBlank()) {
            return claim.toString();
        }
        return getClaims(token).getSubject();
    }

    public Set<String> extractRoles(String token) {
        Object raw = getClaims(token).get("roles");
        if (raw instanceof Collection<?> c) {
            // LinkedHashSet keeps the mint order stable for logs/tests.
            Set<String> roles = new LinkedHashSet<>();
            for (Object role : c) {
                if (role != null) {
                    roles.add(role.toString());
                }
            }
            return roles;
        }
        return Set.of();
    }

    /** JWT {@code merchantId} claim (seller scoping — NEVER from a request
     *  body), canonicalised; null when absent or not a UUID. */
    public String extractMerchantId(String token) {
        return extractUuidClaimAsString(token, "merchantId");
    }

    /** JWT {@code shopId} claim, canonicalised; null when absent or not a UUID. */
    public String extractShopId(String token) {
        return extractUuidClaimAsString(token, "shopId");
    }

    public String extractPhoneNumber(String token) {
        return getClaims(token).get("phoneNumber", String.class);
    }

    /**
     * The {@code homeCountry} claim (ISO 3166-1 alpha-2, e.g. {@code ZW}) —
     * the customer's MSISDN-derived routing key set by user-service at mint
     * time. Returns null on any failure or when the claim is absent (legacy
     * tokens, staff tokens without an MSISDN).
     */
    public String extractCountry(String token) {
        try {
            String home = getClaims(token).get("homeCountry", String.class);
            return (home == null || home.isBlank()) ? null : home;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * UUID keying the shared token-version store ({@code auth:tokenver:<uuid>}).
     * Prefers the {@code userUuid} claim (the key user-service publishes under
     * on fleet tokens whose subject is not the uuid); falls back to the subject
     * when it parses as a UUID. Null when neither yields one — the filter then
     * has no version to enforce and fails open.
     */
    public UUID extractUserId(String token) {
        UUID fromClaim = extractUuidClaim(token, "userUuid");
        if (fromClaim != null) {
            return fromClaim;
        }
        return parseUuid(getClaims(token).getSubject());
    }

    /**
     * Per-user session epoch from the {@code tokenVersion} claim (OWASP A07 /
     * CWE-613). {@link JwtFilter} compares it against the fleet-current value
     * published to shared Redis ({@code auth:tokenver:<userUuid>}) to reject
     * tokens superseded by a newer login / password change. Returns
     * {@code null} when the claim is absent or unparseable — a legacy token
     * without the claim carries no version to enforce, so the filter fails
     * open rather than rejecting it.
     */
    public Long extractTokenVersion(String token) {
        try {
            Object raw = getClaims(token).get("tokenVersion");
            if (raw instanceof Number n) return n.longValue();
            if (raw == null) return null;
            return Long.parseLong(raw.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True when the JWT carries the {@code mustChangePassword} claim. The
     * filter uses this to gate every authenticated request — a user who
     * hasn't rotated their temp password may not call any endpoint in this
     * service. Returns false for absent / unparseable claims.
     */
    public boolean extractMustChangePassword(String token) {
        try {
            Boolean v = getClaims(token).get("mustChangePassword", Boolean.class);
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isTokenValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // jjwt raises IllegalArgumentException (not JwtException) for a
            // null/empty/whitespace token string. Map it to false too, so a
            // caller without JwtFilter's catch-all safety net still gets the
            // boolean contract this method advertises.
            return false;
        }
    }

    private String extractUuidClaimAsString(String token, String claim) {
        UUID parsed = extractUuidClaim(token, claim);
        return parsed == null ? null : parsed.toString();
    }

    private UUID extractUuidClaim(String token, String claim) {
        return parseUuid(getClaims(token).get(claim, String.class));
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Claims getClaims(String token) {
        ensureKeyMaterial();
        return Jwts.parser()
                .keyLocator(keyLocator)
                .requireIssuer(TOKEN_ISSUER)
                .requireAudience(TOKEN_AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
