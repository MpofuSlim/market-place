package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.settlement.StaleEscrowSweeper;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The V12 way out of a parcel that will never arrive, end to end over the real
 * security chain and a real Postgres.
 *
 * <p>Before this, a seller who could not supply what they had sold had NO
 * action available: the queue only offered dispatch and delivered, so the
 * honest answer was to do nothing — and doing nothing left the buyer's money
 * HELD with no timer able to reach it, because the release sweeper only sees a
 * {@code releasableAt} that a delivery sets. Two halves close it: a terminal
 * decline that hands back the stock and turns the money around, and a sweep
 * that at least NOTICES the money that got stuck anyway.
 */
class UnfulfilledParcelFlowIT extends PostgresTestContainer {

    private static final String LISTING_BODY = """
            {
              "title": "Solar Lantern 20W",
              "description": "Portable solar lantern with 12h battery",
              "categoryCode": "electronics",
              "priceCents": 1550,
              "stockQty": 10
            }""";

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    @Autowired
    private StaleEscrowSweeper staleEscrowSweeper;

    @Autowired
    private MeterRegistry meterRegistry;

    private UUID merchantId;
    private String merchantToken;
    private String customerToken;
    private String adminToken;

    @BeforeEach
    void mintTokens() {
        merchantId = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        customerToken = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);
        adminToken = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);
    }

    @Test
    @DisplayName("decline -> stock back, money REFUND_DUE, buyer told; operator records the refund")
    void theDeclineJourney() throws Exception {
        String listingId = publishListing();
        Map<String, String> order = placePaidOrder(listingId, "unfulfilled-happy-1", 2);

        // Two units left the shelf when the order was placed.
        assertThat(stockOf(listingId)).isEqualTo(8);

        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Supplier let us down, no stock until October\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNFULFILLED"))
                .andExpect(jsonPath("$.data.unfulfilledAt").isNotEmpty())
                .andExpect(jsonPath("$.data.unfulfilledReason")
                        .value("Supplier let us down, no stock until October"))
                // The seller sees immediately that the money went back, not to
                // them: the answer to "why wasn't I paid" is on the parcel.
                .andExpect(jsonPath("$.data.settlementStatus").value("REFUND_DUE"));

        // The goods are on sale again — a declined parcel that kept holding
        // stock would quietly shrink the catalogue every time this happened.
        assertThat(stockOf(listingId)).isEqualTo(10);

        // The buyer's own order says so, with the seller's words.
        mockMvc.perform(get("/marketplace/orders/{id}", order.get("orderId"))
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.fulfilments[0].status").value("UNFULFILLED"))
                .andExpect(jsonPath("$.data.fulfilments[0].unfulfilledReason")
                        .value("Supplier let us down, no stock until October"))
                // The order stays PAID: payment state and fulfilment state are
                // different questions, and PAID is what the review gate and
                // payment-service both read.
                .andExpect(jsonPath("$.data.status").value("PAID"))
                // Every parcel declined, so the order rolls up as unfulfilled.
                .andExpect(jsonPath("$.data.fulfilmentStatus").value("UNFULFILLED"));

        // Money queued, NOT moved: no reference yet, because nobody has sent
        // anything. A ledger that stamped one here would describe a transfer
        // that never happened.
        String settlements = mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("REFUND_DUE"))
                .andExpect(jsonPath("$.data.items[0].refundDueAt").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].refundedAt").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].refundReference").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String settlementId = JsonPath.read(settlements, "$.data.items[0].id");

        // No payout can reach it.
        mockMvc.perform(post("/marketplace/settlements/pay-out")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"%s\",\"payoutReference\":\"PAYOUT-X\"}"
                                .formatted(merchantId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("nothing_releasable"));

        // The operator makes the transfer on the rails, then records it here.
        mockMvc.perform(post("/marketplace/settlements/{id}/refund", settlementId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundReference\":\"RFND-2026-09-18-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.refundReference").value("RFND-2026-09-18-03"));

        // Recorded once. A second attempt is refused, not re-applied — the
        // money has already gone back.
        mockMvc.perform(post("/marketplace/settlements/{id}/refund", settlementId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundReference\":\"RFND-DUPLICATE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal_settlement_state"));

        // The decision is in the tamper-evident chain, and the parcel's whole
        // story is in the order's journal.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_type = 'SETTLEMENT_REFUNDED'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_type = 'FULFILMENT_UNFULFILLED'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT to_status FROM market_order_event WHERE kind = 'SETTLEMENT' ORDER BY id",
                String.class)).contains("REFUND_DUE", "REFUNDED");
    }

    @Test
    @DisplayName("A declined parcel is terminal and its stock is returned exactly once")
    void aDeclinedParcelIsTerminal() throws Exception {
        String listingId = publishListing();
        Map<String, String> order = placePaidOrder(listingId, "unfulfilled-terminal-1", 3);
        decline(order.get("fulfilmentId"), "Out of stock");
        assertThat(stockOf(listingId)).isEqualTo(10);

        // Declining twice is refused by the state machine — not silently
        // re-applied, which would restock the same three units again and
        // conjure inventory out of a double tap.
        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Out of stock\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal_fulfilment_state"));
        assertThat(stockOf(listingId)).isEqualTo(10);

        // And it can never be walked back into the delivery flow.
        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal_fulfilment_state"));
        mockMvc.perform(post("/marketplace/fulfilments/{id}/delivered", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("A dispatched parcel cannot be declined — by then it is a delivery failure")
    void aDispatchedParcelCannotBeDeclined() throws Exception {
        String listingId = publishListing();
        Map<String, String> order = placePaidOrder(listingId, "unfulfilled-dispatched-1", 1);
        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Actually out of stock\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal_fulfilment_state"));
        // Nothing touched: the goods are still committed and the money is
        // still held for the seller.
        assertThat(stockOf(listingId)).isEqualTo(9);
        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("HELD"));
    }

    @Test
    @DisplayName("Another seller cannot decline this parcel, and a reason is required")
    void scopeAndInputAreEnforced() throws Exception {
        String listingId = publishListing();
        Map<String, String> order = placePaidOrder(listingId, "unfulfilled-scope-1", 1);

        String otherSeller = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + otherSeller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Not mine to decline\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("fulfilment_not_found"));

        // A buyer has a dispute, not a decline.
        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Cancel this please\"}"))
                .andExpect(status().isForbidden());

        // "Why" is not optional: a refusal that does not say why is what makes
        // a buyer chase support instead of re-ordering elsewhere.
        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        // Still open, still holding the stock and the money.
        assertThat(stockOf(listingId)).isEqualTo(9);
        mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("PREPARING"));
    }

    @Test
    @DisplayName("A buyer who already disputed is told the refund is coming, not handed a 500")
    void disputingMoneyAlreadyTurnedAroundIsACleanRefusal() throws Exception {
        String listingId = publishListing();
        Map<String, String> order = placePaidOrder(listingId, "unfulfilled-dispute-1", 1);
        decline(order.get("fulfilmentId"), "Out of stock");

        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/dispute",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"NOT_RECEIVED\",\"detail\":\"Nothing arrived\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("refund_already_due"));
    }

    @Test
    @DisplayName("A parcel disputed FIRST keeps the buyer's dispute; the decline closes it cleanly")
    void decliningDisputedMoneyLeavesItAlone() throws Exception {
        String listingId = publishListing();
        Map<String, String> order = placePaidOrder(listingId, "unfulfilled-disputed-first-1", 1);
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/dispute",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"NOT_RECEIVED\",\"detail\":\"Nothing arrived\"}"))
                .andExpect(status().isOk());

        // The seller then admits they cannot supply it. The parcel still
        // closes — refusing would leave it open, which is the exact state
        // being fixed — but the money stays where the operator put it.
        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Out of stock\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNFULFILLED"))
                .andExpect(jsonPath("$.data.settlementStatus").value("DISPUTED"));
        // Stock still comes back — that half is about the catalogue, not the
        // money, and the goods are demonstrably still on the shelf.
        assertThat(stockOf(listingId)).isEqualTo(10);
    }

    @Test
    @DisplayName("The stale sweep NOTICES money no timer can release, and moves none of it")
    void staleEscrowIsReportedNeverDecided() throws Exception {
        String listingId = publishListing();
        placePaidOrder(listingId, "unfulfilled-stale-1", 1);

        // Fresh money is nobody's problem yet.
        staleEscrowSweeper.sweep();
        assertThat(staleGauge()).isZero();
        mockMvc.perform(get("/marketplace/settlements/stale")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        // A fortnight passes with the seller neither delivering nor declining.
        // Nothing set releasableAt, so the release sweeper cannot see this row
        // at all — which is precisely why the stale sweep exists.
        jdbc.update("UPDATE merchant_settlement SET created_at = now() - interval '30 days'");

        staleEscrowSweeper.sweep();
        assertThat(staleGauge()).isEqualTo(1.0);
        mockMvc.perform(get("/marketplace/settlements/stale")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("HELD"))
                .andExpect(jsonPath("$.data.items[0].netCents").value(1550));

        // Reported, not decided: the row is exactly where it was.
        assertThat(jdbc.queryForObject(
                "SELECT status FROM merchant_settlement", String.class)).isEqualTo("HELD");

        // A seller cannot read the fleet's stale ledger.
        mockMvc.perform(get("/marketplace/settlements/stale")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private double staleGauge() {
        var gauge = meterRegistry.find("marketplace.settlements.stale").gauge();
        return gauge == null ? -1 : gauge.value();
    }

    private int stockOf(String listingId) {
        return jdbc.queryForObject("SELECT stock_qty FROM listing WHERE id = ?::uuid",
                Integer.class, listingId);
    }

    private void decline(String fulfilmentId, String reason) throws Exception {
        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"%s\"}".formatted(reason)))
                .andExpect(status().isOk());
    }

    private Map<String, String> placePaidOrder(String listingId, String idempotencyKey,
                                               int quantity) throws Exception {
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"listingId\":\"%s\",\"quantity\":%d}]}"
                                .formatted(listingId, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");

        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-%s\",\"amountCents\":%d}"
                                .formatted(idempotencyKey, 1550L * quantity)))
                .andExpect(status().isOk());

        String paid = mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andReturn().getResponse().getContentAsString();
        String fulfilmentId = JsonPath.read(paid, "$.data.fulfilments[0].id");
        return Map.of("orderId", orderId, "orderRef", orderRef, "fulfilmentId", fulfilmentId);
    }

    private String publishListing() throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LISTING_BODY))
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
