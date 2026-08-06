package com.innbucks.marketplaceservice.favorite;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Favorites end to end on real Postgres: idempotent add (the ON CONFLICT
 * DO NOTHING path needs real SQL), newest-favorited-first listing with the
 * listing's CURRENT status on every row, idempotent remove, and the
 * restock-alert foundation (0 -> >0 stock via merchant update publishes the
 * AFTER_COMMIT event and bumps marketplace.restock_events).
 */
class FavoritesFlowIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private MeterRegistry meterRegistry;

    private String merchantToken;
    private String buyerToken;

    @BeforeEach
    void mintTokens() {
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        buyerToken = TestJwts.customer(UUID.randomUUID(), jwtSecret);
    }

    @Test
    void favoriteLifecycle_idempotentAddListWithStatusAndIdempotentRemove() throws Exception {
        String listingA = createActiveListing("Solar Lantern 20W");
        String listingB = createActiveListing("USB-C Charging Cable 2m");

        // Unknown listing: 404 (favoriting requires the row to exist).
        mockMvc.perform(put("/marketplace/favorites/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("listing_not_found"));

        // Add A, then B; repeat-add A is a 200 no-op (and keeps its position).
        mockMvc.perform(put("/marketplace/favorites/{id}", listingA)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Favorited"));
        mockMvc.perform(put("/marketplace/favorites/{id}", listingB)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk());
        mockMvc.perform(put("/marketplace/favorites/{id}", listingA)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk());

        // Newest-favorited first: B then A (the repeat add did not bump A).
        mockMvc.perform(get("/marketplace/favorites")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(listingB))
                .andExpect(jsonPath("$.data.items[1].id").value(listingA))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"));

        // Merchant deactivates A — the favorites list surfaces the CURRENT
        // status so the FE can render "no longer available".
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingA)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/favorites")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[1].id").value(listingA))
                .andExpect(jsonPath("$.data.items[1].status").value("INACTIVE"));

        // Another buyer's favorites are their own — empty list here.
        String otherBuyer = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(get("/marketplace/favorites")
                        .header("Authorization", "Bearer " + otherBuyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(0));

        // Idempotent remove: first removes, second is the same 200 no-op.
        mockMvc.perform(delete("/marketplace/favorites/{id}", listingA)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Unfavorited"));
        mockMvc.perform(delete("/marketplace/favorites/{id}", listingA)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/favorites")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(listingB));
    }

    @Test
    void merchantRestockFromZeroFiresTheRestockFoundationEvent() throws Exception {
        String listingId = createActiveListing("Camp Stove", 0);
        mockMvc.perform(put("/marketplace/favorites/{id}", listingId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk());

        double before = meterRegistry.counter("marketplace.restock_events").count();

        // 0 -> 25 via the merchant update path: the AFTER_COMMIT listener runs
        // (log + metric) once the update transaction commits.
        mockMvc.perform(put("/marketplace/listings/{id}", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Camp Stove","categoryCode":"camping-hiking",
                                 "priceCents":4500,"stockQty":25}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockQty").value(25));
        assertThat(meterRegistry.counter("marketplace.restock_events").count())
                .isEqualTo(before + 1);

        // A non-zero -> non-zero update is NOT a restock.
        mockMvc.perform(put("/marketplace/listings/{id}", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Camp Stove","categoryCode":"camping-hiking",
                                 "priceCents":4500,"stockQty":30}"""))
                .andExpect(status().isOk());
        assertThat(meterRegistry.counter("marketplace.restock_events").count())
                .isEqualTo(before + 1);
    }

    @Test
    void orderCancelReturningTheLastUnitsFiresTheRestockFoundationEvent() throws Exception {
        String listingId = createActiveListing("Solar Lantern 20W", 2);
        double before = meterRegistry.counter("marketplace.restock_events").count();

        // Buyer takes the ENTIRE stock (2 -> 0), then cancels (0 -> 2).
        String orderBody = """
                {"buyerMsisdn":"+263771234567","items":[{"listingId":"%s","quantity":2}]}"""
                .formatted(listingId);
        String createdOrder = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .header("Idempotency-Key", "favorites-restock-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(createdOrder, "$.data.id");
        assertThat(meterRegistry.counter("marketplace.restock_events").count())
                .isEqualTo(before); // reserving to zero is not a restock

        mockMvc.perform(post("/marketplace/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(meterRegistry.counter("marketplace.restock_events").count())
                .isEqualTo(before + 1);
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private String createActiveListing(String title) throws Exception {
        return createActiveListing(title, 10);
    }

    private String createActiveListing(String title, int stockQty) throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","categoryCode":"electronics",
                                 "priceCents":1550,"stockQty":%d}""".formatted(title, stockQty)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.data.id");
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        return listingId;
    }
}
