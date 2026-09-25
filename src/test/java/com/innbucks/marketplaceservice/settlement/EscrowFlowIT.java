package com.innbucks.marketplaceservice.settlement;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The escrow ("Buyer Protection") end to end, over the real security chain and
 * a real Postgres: money HELD at payment, released by the buyer's word or the
 * grace sweeper, frozen by a dispute, and closed by an operator's payout run
 * or refund — each leg read back through the surfaces the seller, buyer and
 * operator actually use.
 */
class EscrowFlowIT extends PostgresTestContainer {

    private static final String LISTING_BODY = """
            {
              "title": "Solar Lantern 20W",
              "description": "Portable solar lantern with 12h battery",
              "categoryCode": "electronics",
              "priceCents": 1550,
              "stockQty": 10,
              "deliveryTowns": [{ "townCode": "harare", "feeCents": 0 }]
            }""";

    private static final String ADDRESS_BODY = """
            {
              "label": "Home",
              "recipientName": "Tariro Moyo",
              "recipientMsisdn": "0771234567",
              "line1": "14 Samora Machel Ave",
              "city": "Harare"
            }""";

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    @Autowired
    private SettlementReleaseSweeper sweeper;

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
    @DisplayName("pay -> HELD; buyer confirms -> RELEASABLE now; payout run -> PAID_OUT, report emptied")
    void theHappyEscrowJourney() throws Exception {
        Map<String, String> order = placePaidOrder("escrow-happy-1");

        // Paying opened the escrow row: HELD, net = the seller's line total
        // (the test cell withholds no commission).
        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("HELD"))
                .andExpect(jsonPath("$.data.items[0].orderId").value(order.get("orderId")))
                .andExpect(jsonPath("$.data.items[0].grossCents").value(1550))
                .andExpect(jsonPath("$.data.items[0].commissionCents").value(0))
                .andExpect(jsonPath("$.data.items[0].netCents").value(1550));

        // "Where is my money" — the wallet header a seller refreshes.
        mockMvc.perform(get("/marketplace/settlements/summary")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.data.totals[0].status").value("HELD"))
                .andExpect(jsonPath("$.data.totals[0].parcels").value(1))
                .andExpect(jsonPath("$.data.totals[0].netCents").value(1550));

        // ANOTHER seller's view holds none of this money.
        String otherSeller = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + otherSeller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        // The merchant's own queue shows the parcel WITH its escrow state —
        // the answer to "why haven't I been paid" is on the queue itself.
        mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].settlementStatus").value("HELD"))
                .andExpect(jsonPath("$.data.items[0].settlementNetCents").value(1550));

        // Dispatch, then the BUYER confirms receipt: their own word releases
        // immediately — no grace clock.
        dispatch(order.get("fulfilmentId"));
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/received",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("RELEASABLE"))
                .andExpect(jsonPath("$.data.items[0].releasedAt").isNotEmpty());

        // The payout report lists this merchant, biggest owed first, dated in
        // the FILENAME (never a preamble row).
        String csv = mockMvc.perform(get("/marketplace/settlements/payout-report")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("marketplace-payout-report-")))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("merchantId,displayName,parcels,netCents,currency,"
                + "payoutMethod,payoutAccountName,payoutMsisdn,payoutBankName,"
                + "payoutAccountNumber,payoutChangedAt\n");
        assertThat(csv).contains(merchantId + ",", ",1,1550,USD");
        // This seller set no destination, so every destination column is empty
        // — and the row is still HERE. The money is genuinely owed, and a sheet
        // that dropped it would hide a seller who cannot be paid.
        assertThat(csv).contains(",1,1550,USD,,,,,,");

        // Finance makes ONE transfer per merchant, then records it here.
        mockMvc.perform(post("/marketplace/settlements/pay-out")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"%s\",\"payoutReference\":\"PAYOUT-2026-09-30-01\"}"
                                .formatted(merchantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parcels").value(1))
                .andExpect(jsonPath("$.data.totalNetCents").value(1550))
                .andExpect(jsonPath("$.data.payoutReference").value("PAYOUT-2026-09-30-01"));

        // The row now answers "where is my money" with the bank's reference...
        mockMvc.perform(get("/marketplace/settlements?status=PAID_OUT")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].payoutReference")
                        .value("PAYOUT-2026-09-30-01"));

        // ...and a second run over the same money is refused: a bank
        // reference over no money describes nothing.
        mockMvc.perform(post("/marketplace/settlements/pay-out")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"%s\",\"payoutReference\":\"PAYOUT-2026-09-30-02\"}"
                                .formatted(merchantId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("nothing_releasable"));
    }

    @Test
    @DisplayName("A seller's self-close waits out the whole dispute window; the sweeper releases it after")
    void sellerSelfCloseWaitsForTheSweeper() throws Exception {
        // A courier DELIVERY: the only kind a seller may close on their own word.
        Map<String, String> order = placePaidOrder("escrow-grace-1", true);
        dispatch(order.get("fulfilmentId"));

        // The SELLER closes the parcel themselves — weaker evidence, so the
        // money only starts the grace clock.
        mockMvc.perform(post("/marketplace/fulfilments/{id}/delivered", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        String held = mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("HELD"))
                .andExpect(jsonPath("$.data.items[0].releasableAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        // The grace covers the buyer's whole 7-day dispute window: the money
        // cannot become payable while the buyer can still object. (It was 48h,
        // which let a seller's word be paid out on day 3 of 7.)
        java.time.Instant releasableAt = java.time.OffsetDateTime.parse(
                JsonPath.<String>read(held, "$.data.items[0].releasableAt")).toInstant();
        assertThat(releasableAt).isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(7))
                .minus(java.time.Duration.ofMinutes(5)));

        // The sweeper runs — the clock has not lapsed, nothing moves.
        sweeper.sweep();
        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("HELD"));

        // The grace window passes (the clock is data, so the test moves it),
        // and the next sweep releases the money.
        jdbc.update("UPDATE merchant_settlement SET releasable_at = now() - interval '1 hour'");
        sweeper.sweep();
        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("RELEASABLE"))
                .andExpect(jsonPath("$.data.items[0].releasedAt").isNotEmpty());
    }

    @Test
    @DisplayName("A seller cannot close a COLLECTION on their own word: 409, parcel open, money untouched")
    void aCollectionCannotBeSelfClosed() throws Exception {
        Map<String, String> order = placePaidOrder("escrow-collection-self-close-1");
        dispatch(order.get("fulfilmentId"));

        mockMvc.perform(post("/marketplace/fulfilments/{id}/delivered", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("collect_code_required"));
        // An operator is refused too: a counter handover nobody verified is the
        // same unprovable claim whoever types it.
        mockMvc.perform(post("/marketplace/fulfilments/{id}/delivered", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("collect_code_required"));

        mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("DISPATCHED"))
                .andExpect(jsonPath("$.data.items[0].settlementStatus").value("HELD"));
        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("HELD"))
                .andExpect(jsonPath("$.data.items[0].releasableAt").doesNotExist());

        // The buyer's own "received" still closes it — and releases at once.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/received",
                                order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("RELEASABLE"));
    }

    @Test
    @DisplayName("A dispute freezes the money; REFUND resolution closes both sides of the record")
    void disputeFrozenThenRefunded() throws Exception {
        Map<String, String> order = placePaidOrder("escrow-dispute-1");

        // The parcel never arrives. Disputing an UNDELIVERED parcel is legal —
        // "it never arrived" IS the refund path.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/dispute",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"NOT_RECEIVED",
                                 "detail":"Paid five days ago, the seller has stopped answering."}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.reason").value("NOT_RECEIVED"));

        // Frozen: the seller sees WHY their money is not clearing...
        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("DISPUTED"));
        // ...and the buyer sees the dispute riding their own order.
        mockMvc.perform(get("/marketplace/orders/{id}", order.get("orderId"))
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.fulfilments[0].dispute.status").value("OPEN"))
                .andExpect(jsonPath("$.data.fulfilments[0].dispute.reason").value("NOT_RECEIVED"));

        // One dispute per parcel, EVER.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/dispute",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("dispute_already_raised"));

        // Someone else's order is the same 404 as a nonexistent one.
        String stranger = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263779999999").sign(jwtSecret);
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/dispute",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"NOT_RECEIVED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("order_not_found"));

        // The operator's FIFO queue carries it, with the buyer's words.
        String queue = mockMvc.perform(get("/marketplace/settlements/disputes")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].reason").value("NOT_RECEIVED"))
                .andExpect(jsonPath("$.data.items[0].detail")
                        .value("Paid five days ago, the seller has stopped answering."))
                .andReturn().getResponse().getContentAsString();
        String disputeId = JsonPath.read(queue, "$.data.items[0].id");

        // REFUND: recorded with the operator's transfer reference — the
        // transfer itself is theirs to execute on the rails.
        mockMvc.perform(patch("/marketplace/settlements/disputes/{id}", disputeId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"REFUND",
                                 "resolutionNote":"Seller unreachable for a week.",
                                 "refundReference":"RFND-2026-09-30-07"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDED"));

        // Both halves of the record agree, and no payout can touch the money.
        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.items[0].refundReference")
                        .value("RFND-2026-09-30-07"));
        mockMvc.perform(post("/marketplace/settlements/pay-out")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"%s\",\"payoutReference\":\"PAYOUT-X\"}"
                                .formatted(merchantId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("nothing_releasable"));

        // A decision is made exactly once.
        mockMvc.perform(patch("/marketplace/settlements/disputes/{id}", disputeId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RELEASE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("dispute_not_open"));
    }

    @Test
    @DisplayName("Confirming receipt does not sign away the dispute; RELEASE puts the money back on the run")
    void disputeAfterConfirmationResolvedForTheSeller() throws Exception {
        Map<String, String> order = placePaidOrder("escrow-release-1");
        dispatch(order.get("fulfilmentId"));
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/received",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());

        // RELEASABLE, then the buyer opens the box: still disputable until
        // the money is actually paid out.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/dispute",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"DAMAGED\",\"detail\":\"Cracked casing\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/marketplace/settlements/pay-out")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"%s\",\"payoutReference\":\"PAYOUT-EARLY\"}"
                                .formatted(merchantId)))
                .andExpect(status().isConflict());

        String queue = mockMvc.perform(get("/marketplace/settlements/disputes")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        String disputeId = JsonPath.read(queue, "$.data.items[0].id");

        // The operator sides with the seller: the money returns to
        // RELEASABLE and the next run covers it.
        mockMvc.perform(patch("/marketplace/settlements/disputes/{id}", disputeId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RELEASE\",\"resolutionNote\":\"Courier photo shows intact parcel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RELEASED"));
        mockMvc.perform(post("/marketplace/settlements/pay-out")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"%s\",\"payoutReference\":\"PAYOUT-AFTER\"}"
                                .formatted(merchantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parcels").value(1))
                .andExpect(jsonPath("$.data.totalNetCents").value(1550));
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /** Publish a listing, order one unit (COLLECTION), confirm payment over
     *  the S2S surface. Returns orderId / orderRef / fulfilmentId. */
    private Map<String, String> placePaidOrder(String idempotencyKey) throws Exception {
        return placePaidOrder(idempotencyKey, false);
    }

    /** {@link #placePaidOrder(String)}, as a courier DELIVERY to a saved
     *  address when {@code delivery} (the cell's delivery fee is zero, so the
     *  paid amount is unchanged). */
    private Map<String, String> placePaidOrder(String idempotencyKey, boolean delivery)
            throws Exception {
        String listingId = publishListing();
        String body = "{\"items\":[{\"listingId\":\"%s\",\"quantity\":1}]}".formatted(listingId);
        if (delivery) {
            String address = mockMvc.perform(post("/marketplace/addresses")
                            .header("Authorization", "Bearer " + customerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ADDRESS_BODY))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            body = ("{\"items\":[{\"listingId\":\"%s\",\"quantity\":1}],"
                    + "\"deliveryMethod\":\"DELIVERY\",\"deliveryAddressId\":\"%s\"}")
                    .formatted(listingId, JsonPath.<String>read(address, "$.data.id"));
        }
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");

        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-%s\",\"amountCents\":1550}"
                                .formatted(idempotencyKey)))
                .andExpect(status().isOk());

        String paid = mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andReturn().getResponse().getContentAsString();
        String fulfilmentId = JsonPath.read(paid, "$.data.fulfilments[0].id");
        return Map.of("orderId", orderId, "orderRef", orderRef, "fulfilmentId", fulfilmentId);
    }

    private void dispatch(String fulfilmentId) throws Exception {
        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
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
