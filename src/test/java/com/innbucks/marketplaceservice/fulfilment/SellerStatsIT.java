package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the trust-stat SQL against REAL Postgres — the {@code percentile_cont}
 * median, the {@code FILTER} counts and the native-query projections are
 * exactly the pieces a mocked repository cannot vouch for — and the two
 * surfaces that serve the figures (the public profile, the seller's own view)
 * over the real security chain.
 *
 * <p>Seeded directly with JDBC rather than driven through the API: the median
 * is only checkable when the test CONTROLS the paid/dispatched timestamps,
 * which no HTTP flow can.
 */
class SellerStatsIT extends PostgresTestContainer {

    private static final UUID MERCHANT_A = UUID.randomUUID();
    private static final UUID MERCHANT_B = UUID.randomUUID();

    @Value("${jwt.secret}")
    private String jwtSecret;

    private String sellerAToken;

    @BeforeEach
    void seed() {
        sellerAToken = TestJwts.merchantAdmin(UUID.randomUUID(), MERCHANT_A, jwtSecret);

        Instant base = Instant.now().minus(30, ChronoUnit.DAYS);
        // Merchant A, six cleanly-dispatched delivered parcels, all
        // buyer-confirmed, with payment->dispatch delays of 10..60 hours.
        for (int i = 1; i <= 6; i++) {
            seedParcel(MERCHANT_A, base.plus(i, ChronoUnit.DAYS), i * 10L,
                    "DELIVERED", "BUYER");
        }
        // One delivered parcel whose dispatch stamp PRECEDES the payment (a
        // corrected/backfilled row): counts as completed, excluded from the
        // median. Seller-closed, so it also drags the confirmed rate to 6/7.
        seedParcel(MERCHANT_A, base.plus(7, ChronoUnit.DAYS), -5L, "DELIVERED", "MERCHANT");
        // One parcel still open on the queue, one dispatched 5h after payment
        // and still in transit — an open dispatch has a REAL dispatch time and
        // counts toward the median (7 samples: 5,10..60 -> median 30h).
        seedParcel(MERCHANT_A, base.plus(8, ChronoUnit.DAYS), null, "PREPARING", null);
        seedParcel(MERCHANT_A, base.plus(9, ChronoUnit.DAYS), 5L, "DISPATCHED", null);

        // Merchant B: two completed parcels — real history, below the sample
        // floor of 5.
        seedParcel(MERCHANT_B, base.plus(1, ChronoUnit.DAYS), 24L, "DELIVERED", "BUYER");
        seedParcel(MERCHANT_B, base.plus(2, ChronoUnit.DAYS), 24L, "DELIVERED", "BUYER");
    }

    /** One PAID order + one parcel for {@code merchantId}. {@code dispatchDelayHours}
     *  null = never dispatched; negative = dispatched "before" payment. */
    private void seedParcel(UUID merchantId, Instant paidAt, Long dispatchDelayHours,
                            String status, String deliveredBy) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO market_order (id, order_ref, buyer_uuid, buyer_msisdn, status,
                    subtotal_cents, delivery_fee_cents, total_cents, currency, delivery_method,
                    paid_at, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, '+263771234567', 'PAID', 1000, 0, 1000, 'USD', 'COLLECTION',
                    ?, ?, ?, ?)
                """,
                orderId, "MKT-" + orderId.toString().substring(0, 12).replace("-", "").toUpperCase(),
                UUID.randomUUID(), java.sql.Timestamp.from(paidAt),
                java.sql.Timestamp.from(paidAt.plus(1, ChronoUnit.HOURS)),
                java.sql.Timestamp.from(paidAt.minus(1, ChronoUnit.HOURS)),
                java.sql.Timestamp.from(paidAt));
        Instant dispatchedAt = dispatchDelayHours == null ? null
                : paidAt.plus(dispatchDelayHours, ChronoUnit.HOURS);
        Instant deliveredAt = "DELIVERED".equals(status)
                ? paidAt.plus(5, ChronoUnit.DAYS) : null;
        jdbc.update("""
                INSERT INTO order_fulfilment (id, order_id, merchant_id, status,
                    dispatched_at, delivered_at, delivered_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), orderId, merchantId, status,
                dispatchedAt == null ? null : java.sql.Timestamp.from(dispatchedAt),
                deliveredAt == null ? null : java.sql.Timestamp.from(deliveredAt),
                deliveredBy,
                java.sql.Timestamp.from(paidAt), java.sql.Timestamp.from(paidAt));
    }

    @Test
    @DisplayName("The public profile carries the computed figures: median 30h, 86% confirmed, 7 completed")
    void publicProfileCarriesComputedStats() throws Exception {
        mockMvc.perform(get("/marketplace/catalog/merchants/{id}", MERCHANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fulfilment.completedOrders").value(7))
                // 7 timing samples (5,10,20,30,40,50,60h — the clock-skew row
                // is excluded, the open DISPATCHED one included) -> median 30.
                .andExpect(jsonPath("$.data.fulfilment.medianDispatchHours").value(30))
                // 6 of 7 completed parcels buyer-confirmed -> 85.7 -> 86.
                .andExpect(jsonPath("$.data.fulfilment.buyerConfirmedPercent").value(86));
    }

    @Test
    @DisplayName("Below the sample floor: the count shows, the figures stay absent")
    void belowTheFloorShowsCountOnly() throws Exception {
        mockMvc.perform(get("/marketplace/catalog/merchants/{id}", MERCHANT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fulfilment.completedOrders").value(2))
                .andExpect(jsonPath("$.data.fulfilment.medianDispatchHours").doesNotExist())
                .andExpect(jsonPath("$.data.fulfilment.buyerConfirmedPercent").doesNotExist());
    }

    @Test
    @DisplayName("A merchant with no history has NO fulfilment block — and the profile still never 404s")
    void unknownMerchantHasNoBlock() throws Exception {
        mockMvc.perform(get("/marketplace/catalog/merchants/{id}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fulfilment").doesNotExist());
    }

    @Test
    @DisplayName("The seller's own view: same public figures plus the live queue")
    void sellerSeesTheirOwnQueueAndTheSamePublicFigures() throws Exception {
        mockMvc.perform(get("/marketplace/fulfilments/stats")
                        .header("Authorization", "Bearer " + sellerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicStats.completedOrders").value(7))
                .andExpect(jsonPath("$.data.publicStats.medianDispatchHours").value(30))
                .andExpect(jsonPath("$.data.publicStats.buyerConfirmedPercent").value(86))
                .andExpect(jsonPath("$.data.awaitingDispatch").value(1))
                .andExpect(jsonPath("$.data.inTransit").value(1))
                .andExpect(jsonPath("$.data.completedOrders").value(7));
    }

    @Test
    @DisplayName("A seller cannot read another seller's private view by passing their id")
    void merchantIdIsIgnoredForSellers() throws Exception {
        mockMvc.perform(get("/marketplace/fulfilments/stats")
                        .param("merchantId", MERCHANT_B.toString())
                        .header("Authorization", "Bearer " + sellerAToken))
                .andExpect(status().isOk())
                // Still merchant A's numbers, whatever the parameter said.
                .andExpect(jsonPath("$.data.completedOrders").value(7));
    }

    @Test
    @DisplayName("SUPER_ADMIN reads any merchant's stats, but must name one")
    void adminReadsAnyMerchantByName() throws Exception {
        String admin = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);

        mockMvc.perform(get("/marketplace/fulfilments/stats")
                        .param("merchantId", MERCHANT_B.toString())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedOrders").value(2));

        mockMvc.perform(get("/marketplace/fulfilments/stats")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("merchant_id_required"));
    }

    @Test
    @DisplayName("The private view is not a buyer surface")
    void customersCannotReadTheSellerView() throws Exception {
        mockMvc.perform(get("/marketplace/fulfilments/stats")
                        .header("Authorization", "Bearer "
                                + TestJwts.customer(UUID.randomUUID(), jwtSecret)))
                .andExpect(status().isForbidden());
    }
}
