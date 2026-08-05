package com.innbucks.marketplaceservice.support;

import com.innbucks.marketplaceservice.security.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints HS256 JWTs this service's {@code JwtFilter}/{@code JwtUtil} accept —
 * the InnRewards {@code TestJwtFactory} pattern, adapted to marketplace's
 * claim shape: issuer/audience are {@link JwtUtil#TOKEN_ISSUER}/
 * {@link JwtUtil#TOKEN_AUDIENCE} (both REQUIRED by the verifier), and the
 * subject is the caller's cross-service user UUID — {@code OrderService} does
 * {@code UUID.fromString(buyer.uuid())} on it, so an email-style subject
 * would 500 the order flow.
 *
 * <p>Sign with the same secret the context runs with
 * ({@code jwt.secret} from {@code application-test.yaml}).
 */
public final class TestJwts {

    private TestJwts() {
    }

    /** CUSTOMER (buyer) token. */
    public static String customer(UUID userUuid, String secret) {
        return forUser(userUuid).role("CUSTOMER").sign(secret);
    }

    /** MERCHANT_ADMIN token scoped to the given merchant — the merchantId
     *  claim is the ONLY place listing writes take their merchant scope from. */
    public static String merchantAdmin(UUID userUuid, UUID merchantId, String secret) {
        return forUser(userUuid).role("MERCHANT_ADMIN").merchantId(merchantId).sign(secret);
    }

    /** SUPER_ADMIN (fleet oversight) token — deliberately carries NO
     *  merchantId claim, exactly like the real admin tokens user-service
     *  mints; the service layer must not require one for admins. */
    public static String superAdmin(UUID userUuid, String secret) {
        return forUser(userUuid).role("SUPER_ADMIN").sign(secret);
    }

    public static Builder forUser(UUID userUuid) {
        return new Builder(userUuid);
    }

    public static final class Builder {
        private final UUID subject;
        private List<String> roles = List.of();
        private UUID merchantId;
        private UUID shopId;
        // Default: valid for 1 hour from now.
        private long ttlMillis = 3_600_000L;

        private Builder(UUID subject) {
            this.subject = subject;
        }

        public Builder role(String role) {
            this.roles = role == null ? List.of() : List.of(role);
            return this;
        }

        public Builder roles(List<String> roles) {
            this.roles = roles == null ? List.of() : List.copyOf(roles);
            return this;
        }

        public Builder merchantId(UUID merchantId) {
            this.merchantId = merchantId;
            return this;
        }

        public Builder shopId(UUID shopId) {
            this.shopId = shopId;
            return this;
        }

        /** Issued and already expired — for expired-token tests. */
        public Builder expired() {
            this.ttlMillis = -60_000L;
            return this;
        }

        public String sign(String secret) {
            SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            var builder = Jwts.builder()
                    .subject(subject.toString())
                    .issuer(JwtUtil.TOKEN_ISSUER)
                    .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                    .claim("roles", roles);
            if (merchantId != null) {
                builder.claim("merchantId", merchantId.toString());
            }
            if (shopId != null) {
                builder.claim("shopId", shopId.toString());
            }
            long now = System.currentTimeMillis();
            return builder
                    .issuedAt(new Date(now - 1_000L))
                    .expiration(new Date(now + ttlMillis))
                    .signWith(key, Jwts.SIG.HS256)
                    .compact();
        }
    }
}
