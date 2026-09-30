package com.innbucks.marketplaceservice.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The {@code perms} claim is how the customer-support surface is authorized,
 * so what it can and cannot turn into is pinned here: permission-shaped codes
 * become BARE authorities; anything else — above all a role-shaped entry — is
 * dropped, so this claim can never be a way to hold a role.
 */
class PermissionsClaimTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-unit-test-secret-0123";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("permission-shaped codes survive; role-shaped, service-shaped and malformed entries are dropped")
    void onlyPermissionShapedCodesSurvive() {
        String token = token(Map.of("roles", List.of("CALL_CENTER_AGENT"), "perms", Arrays.asList(
                "marketplace-support:read", "users:roles:write", "ROLE_SUPER_ADMIN", "SERVICE_LOYALTY-OTP",
                "SUPER_ADMIN", "Marketplace-Support:read", "marketplace-support", ":read", "a:", "*", "",
                42, null)));

        assertThat(jwtUtil().extractPermissions(token))
                .containsExactly("marketplace-support:read", "users:roles:write");
    }

    @Test
    @DisplayName("no claim, or a claim that is not a list, is no permissions at all")
    void absentOrMalformedIsEmpty() {
        assertThat(jwtUtil().extractPermissions(token(Map.of("roles", List.of("CUSTOMER"))))).isEmpty();
        assertThat(jwtUtil().extractPermissions(token(Map.of("perms", "marketplace-support:read")))).isEmpty();
    }

    @Test
    @DisplayName("the filter grants permissions BARE, beside ROLE_ roles — never as a role")
    void theFilterGrantsThemBare() throws Exception {
        String token = token(Map.of("roles", List.of("CALL_CENTER_AGENT"),
                "perms", List.of("marketplace-support:read", "ROLE_SUPER_ADMIN"),
                "userUuid", "9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86"));

        JwtFilter filter = new JwtFilter(jwtUtil(), mock(RevokedTokenDenylist.class), mock(TokenVersionStore.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/marketplace/support/buyers/x");
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_CALL_CENTER_AGENT", "marketplace-support:read");
        AuthenticatedUser user = (AuthenticatedUser) auth.getPrincipal();
        assertThat(user.permissions()).containsExactly("marketplace-support:read");
        assertThat(user.login()).isEqualTo("agent@innbucks.co.zw");
        assertThat(user.isSuperAdmin()).isFalse();
    }

    private static JwtUtil jwtUtil() {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "secret", SECRET);
        ReflectionTestUtils.setField(util, "publicKeyPem", "");
        return util;
    }

    private static String token(Map<String, ?> claims) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject("agent@innbucks.co.zw")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claims(claims)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now - 1_000L))
                .expiration(new Date(now + 60_000L))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }
}
