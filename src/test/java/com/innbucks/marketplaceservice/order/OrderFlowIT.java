package com.innbucks.marketplaceservice.order;

import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end order lifecycle over real HTTP semantics (MockMvc through the
 * real SecurityFilterChain) and a real Postgres: merchant lists, buyer orders
 * (idempotently), the payments service confirms over the internal S2S surface.
 * Pins the three money-safety invariants together: atomic stock reservation,
 * claim-row idempotent replay, and the confirm-amount 100x guard.
 */
class OrderFlowIT extends PostgresTestContainer {

    private static final String LISTING_BODY = """
            {
              "title": "Solar Lantern 20W",
              "description": "Portable solar lantern with 12h battery",
              "categoryCode": "electronics",
              "priceCents": 1550,
              "stockQty": 10
            }""";

    /** Real PNG signature + filler — the publish gate requires a primary
     *  image before a listing may go ACTIVE. */
    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private MarketOrderRepository orderRepository;

    private String merchantToken;
    private String customerToken;

    @BeforeEach
    void mintTokens() {
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        customerToken = TestJwts.customer(UUID.randomUUID(), jwtSecret);
    }

    @Test
    void fullLifecycle_listOrderReplayConfirmAndRefuseCancel() throws Exception {
        // --- Merchant creates a listing (DRAFT) and publishes it (ACTIVE) ----
        String createdListing = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LISTING_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(createdListing, "$.data.id");

        // Publish gate: activation needs a primary image first.
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // --- Buyer creates the order: 201 PENDING_PAYMENT, server-side total -
        String orderBody = """
                {"buyerMsisdn":"+263771234567","items":[{"listingId":"%s","quantity":2}]}"""
                .formatted(listingId);
        String createdOrder = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "order-flow-attempt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.totalCents").value(3100))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andExpect(jsonPath("$.data.items[0].unitPriceCents").value(1550))
                .andExpect(jsonPath("$.data.items[0].lineTotalCents").value(3100))
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(createdOrder, "$.data.id");
        String orderRef = JsonPath.read(createdOrder, "$.data.orderRef");
        assertThat(orderRef).startsWith("MKT-");

        // Stock reserved atomically: 10 - 2 = 8.
        assertThat(stockOf(listingId)).isEqualTo(8);

        // --- Replay with the SAME key: identical body, no second reservation -
        String replayedOrder = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "order-flow-attempt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(replayedOrder).isEqualTo(createdOrder);
        assertThat(stockOf(listingId)).isEqualTo(8);
        assertThat(orderRepository.count()).isEqualTo(1);

        // --- Payments reads the order over the internal S2S surface ----------
        mockMvc.perform(get("/marketplace/internal/orders/{ref}", orderRef)
                        .header("X-Internal-Token", internalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderRef").value(orderRef))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.totalCents").value(3100))
                .andExpect(jsonPath("$.data.buyerMsisdn").value("+263771234567"));

        // --- Confirm with the WRONG amount: 422, order untouched (100x guard)
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-0001\",\"amountCents\":310000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("amount_mismatch"));
        assertThat(orderRepository.findByOrderRef(orderRef).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);

        // --- Confirm with the EXACT amount: PAID -----------------------------
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-0001\",\"amountCents\":3100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

        // --- Replayed confirm, SAME paymentRef: idempotent 200 no-op ---------
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-0001\",\"amountCents\":3100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

        // --- Cancel after PAID: terminal-bound, refused with 409 -------------
        mockMvc.perform(post("/marketplace/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal_order_state"));

        // PAID never releases stock; the sold units stay gone.
        MarketOrder finalRow = orderRepository.findByOrderRef(orderRef).orElseThrow();
        assertThat(finalRow.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(finalRow.isStockReleased()).isFalse();
        assertThat(stockOf(listingId)).isEqualTo(8);
    }

    private int stockOf(String listingId) {
        return listingRepository.findById(UUID.fromString(listingId)).orElseThrow().getStockQty();
    }
}
