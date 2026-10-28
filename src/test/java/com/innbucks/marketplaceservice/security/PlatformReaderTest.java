package com.innbucks.marketplaceservice.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformReaderTest {

    private static final AuthenticatedUser READER = new AuthenticatedUser(
            "0b7f3c1e-2a4d-4e8f-9c1b-5d6e7f8a9b0c", Set.of("PRODUCT_OFFICER"), null, null, null, "ZW",
            null, "dev@innbucks.co.zw", Set.of(PlatformReader.REPORTS_READ));
    private static final AuthenticatedUser SUPER_ADMIN = new AuthenticatedUser(
            "1c8e4d2f-3b5e-4f90-8d2c-6e7f8091a1b2", Set.of("SUPER_ADMIN"), null, null, null, "ZW");
    private static final AuthenticatedUser SELLER = new AuthenticatedUser(
            "2d9f5e30-4c6f-4a01-9e3d-7f8091a2b3c4", Set.of("MERCHANT_ADMIN"),
            "3eaf6f41-5d70-4b12-8f4e-8091a2b3c4d5", null, null, "ZW");

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private static void request(String method) {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest(method, "/marketplace/x")));
    }

    @Test
    @DisplayName("the permission reads every seller on a GET or HEAD, and on no other verb")
    void permissionReadsOnGetOnly() {
        request("GET");
        assertThat(PlatformReader.readsEverySeller(READER)).isTrue();
        request("HEAD");
        assertThat(PlatformReader.readsEverySeller(READER)).isTrue();
        for (String verb : List.of("POST", "PUT", "PATCH", "DELETE")) {
            request(verb);
            assertThat(PlatformReader.readsEverySeller(READER)).as(verb).isFalse();
        }
    }

    @Test
    @DisplayName("outside a request the permission reads nothing: fail closed")
    void noRequestNoRead() {
        assertThat(PlatformReader.readsEverySeller(READER)).isFalse();
    }

    @Test
    @DisplayName("SUPER_ADMIN reads every seller on any verb; a seller never does")
    void rolesUnchanged() {
        request("POST");
        assertThat(PlatformReader.readsEverySeller(SUPER_ADMIN)).isTrue();
        request("GET");
        assertThat(PlatformReader.readsEverySeller(SELLER)).isFalse();
        assertThat(PlatformReader.readsEverySeller(null)).isFalse();
    }

    @Test
    @DisplayName("buyer contact is redacted for a reader, never for SUPER_ADMIN or a seller")
    void redactionFollowsThePrincipal() {
        assertThat(PlatformReader.redactsBuyerContact()).isFalse();
        authenticate(READER);
        assertThat(PlatformReader.redactsBuyerContact()).isTrue();
        authenticate(SUPER_ADMIN);
        assertThat(PlatformReader.redactsBuyerContact()).isFalse();
        authenticate(SELLER);
        assertThat(PlatformReader.redactsBuyerContact()).isFalse();
    }

    @Test
    void maskPhone() {
        assertThat(PlatformReader.maskPhone("+263771234567")).isEqualTo("****4567");
        assertThat(PlatformReader.maskPhone("123")).isEqualTo("****");
        assertThat(PlatformReader.maskPhone(null)).isNull();
    }

    private static void authenticate(AuthenticatedUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
