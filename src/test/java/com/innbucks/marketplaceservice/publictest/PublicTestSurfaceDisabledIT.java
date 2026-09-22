package com.innbucks.marketplaceservice.publictest;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DEFAULT posture: no {@code @TestPropertySource}, so this class runs on
 * the same configuration a cell gets when nobody opts in.
 *
 * <p>This is the more important of the two public-surface IT classes. The one
 * that proves the feature works only matters if this one proves it is absent
 * unless asked for — a cell that forgets the flag must serve nothing, and a
 * regression that flips the default would be invisible to every other test in
 * the suite.
 */
class PublicTestSurfaceDisabledIT extends PostgresTestContainer {

    @Test
    void everyPublicEndpointIsAbsentByDefault() throws Exception {
        UUID listingId = UUID.randomUUID();

        // 404 and not 403: "this endpoint does not exist here" is the honest
        // answer, and it tells a prober nothing about what the build can do.
        mockMvc.perform(get("/marketplace/public/buyers/{handle}/cart", "alice"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/marketplace/public/buyers/{handle}/cart/items", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"%s\",\"quantity\":1}".formatted(listingId)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/marketplace/public/buyers/{handle}/addresses", "alice"))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/marketplace/public/buyers/{handle}/favorites/{id}", "alice", listingId))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/marketplace/public/buyers/{handle}/favorites", "alice"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/marketplace/public/buyers/{handle}/checkout/quote", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/marketplace/public/checkout/options"))
                .andExpect(status().isNotFound());

        // The order half is absent on the default config for TWO independent
        // reasons — the surface is off AND no api-key is configured — and this
        // asserts the outcome, so it keeps holding if either one is ever
        // loosened by accident.
        mockMvc.perform(post("/marketplace/public/buyers/{handle}/orders", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCart":true,"buyerMsisdn":"0771234567"}"""))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/marketplace/public/buyers/{handle}/orders", "alice"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/marketplace/public/buyers/{handle}/orders/{o}/cancel", "alice", listingId))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/marketplace/public/buyers/{handle}/orders/{o}/fulfilments/{f}/received",
                        "alice", listingId, listingId))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/marketplace/public/buyers/{handle}/orders/{o}/fulfilments/{f}/collect-code",
                        "alice", listingId, listingId))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/marketplace/public/buyers/{handle}/listings/{id}/reviews", "alice", listingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theAuthenticatedSurfaceIsUntouchedByTheFeatureExisting() throws Exception {
        // Adding a permitAll matcher is exactly the kind of change that widens
        // something by accident, so the neighbours are re-pinned here.
        mockMvc.perform(get("/marketplace/cart"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/marketplace/addresses"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/marketplace/favorites"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/marketplace/checkout/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/marketplace/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/marketplace/orders/mine"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thePublicCatalogStillBrowsesAnonymously() throws Exception {
        mockMvc.perform(get("/marketplace/catalog"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/categories"))
                .andExpect(status().isOk());
    }
}
