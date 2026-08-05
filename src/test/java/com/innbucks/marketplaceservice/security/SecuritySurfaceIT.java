package com.innbucks.marketplaceservice.security;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the deployed security surface end to end — the real
 * {@code SecurityFilterChain}, {@code JwtFilter}, method security, the
 * internal-token trust boundary, and the metrics scrape gate — with SPECIFIC
 * status codes (fleet rule: never {@code .is4xxClientError()}, which would
 * pass for the wrong rejection layer).
 */
class SecuritySurfaceIT extends PostgresTestContainer {

    /** Valid per Bean Validation on purpose: argument resolution (and thus
     *  {@code @Valid}) runs BEFORE method security, so an invalid body would
     *  400 and mask the authorization outcome under test. */
    private static final String VALID_LISTING_BODY = """
            {
              "title": "Wireless Bluetooth Speaker",
              "description": "Portable speaker with 12h battery life.",
              "category": "electronics",
              "priceCents": 2599,
              "stockQty": 120
            }""";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Test
    void anonymousCatalogBrowseIsPublic() throws Exception {
        mockMvc.perform(get("/marketplace/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @Test
    void anonymousListingWriteIsUnauthorized() throws Exception {
        mockMvc.perform(post("/marketplace/listings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_LISTING_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void customerRoleCannotManageListings() throws Exception {
        // Authenticated but not MERCHANT_ADMIN (the only listing-admin role,
        // by owner decision): method security
        // refuses INSIDE handler invocation — must render the fleet 403
        // envelope, not fall into the Exception catch-all as a 500.
        String customerToken = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_LISTING_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void internalEndpointWithoutTokenIsIndistinguishableFromNotFound() throws Exception {
        // The fleet webhook convention: a failed trust-boundary check answers
        // the SAME 404 as an unknown ref, so a probe can't confirm the S2S
        // surface exists.
        mockMvc.perform(get("/marketplace/internal/orders/{ref}", "MKT-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("order_not_found"));
    }

    @Test
    void internalEndpointWithWrongTokenIsIndistinguishableFromNotFound() throws Exception {
        mockMvc.perform(get("/marketplace/internal/orders/{ref}", "MKT-000000000000")
                        .header("X-Internal-Token", "definitely-not-the-internal-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("order_not_found"));
    }

    @Test
    void prometheusWithoutScrapeTokenIsRejected() throws Exception {
        // monitoring.scrape-token is blank in application-test.yaml, so the
        // gate is fail-closed: nothing scrapes until a cell provisions the
        // token — never a 200.
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheusWithWrongScrapeTokenIsRejected() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header("X-Metrics-Token", "definitely-not-the-scrape-token"))
                .andExpect(status().isUnauthorized());
    }
}
