package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The seller portal's parcel cards and earnings rows over the real security
 * chain and a real Postgres: the one search box, the delivery-method filter,
 * the split counts, the buyer-notice outcome written back by the async
 * listener, and the earnings rows / summary / statement CSV.
 */
class PortalParcelsAndEarningsIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    private UUID merchantId;
    private String merchantToken;
    private String adminToken;
    private String deliveryBuyer;
    private String collectionBuyer;

    @BeforeEach
    void mintTokens() {
        merchantId = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        adminToken = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);
        deliveryBuyer = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);
        collectionBuyer = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263772222222").sign(jwtSecret);
    }

    @Test
    @DisplayName("One search box finds the buyer at the counter; filters and split counts agree")
    void searchFiltersAndCounts() throws Exception {
        String listingId = publishListing();
        Paid delivery = placePaidDelivery(listingId, "search-1");
        Paid collection = placePaidCollection(listingId, "search-2");

        // Delivery method filter.
        mockMvc.perform(get("/marketplace/fulfilments?deliveryMethod=COLLECTION")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].orderRef").value(collection.orderRef()));

        // The search box, by every shape it reads.
        search(delivery.orderRef().toLowerCase().replace("-", " "), delivery.orderRef());
        search("0772222222", collection.orderRef());
        search("+263 77 123 4567", delivery.orderRef());
        search("tariro", delivery.orderRef());
        search(collection.trackingCode(), collection.orderRef());
        mockMvc.perform(get("/marketplace/fulfilments?q=nobody-here")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
        // A filter value that is not one of its options is the client's typo: 400, not 500.
        mockMvc.perform(get("/marketplace/fulfilments?deliveryMethod=DRONE")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
        // Another seller's search finds none of it.
        mockMvc.perform(get("/marketplace/fulfilments?q={q}", delivery.orderRef())
                        .header("Authorization", "Bearer "
                                + TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret)))
                .andExpect(jsonPath("$.data.items").isEmpty());

        dispatch(delivery.fulfilmentId());
        dispatch(collection.fulfilmentId());

        // The listener runs after commit, on its own pool, and writes back
        // whether the buyer was told. This test cell has no SMS credentials,
        // so the honest answer is NOT_SENT — which is exactly what a seller
        // needs to see.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mockMvc.perform(get("/marketplace/fulfilments?q={q}", delivery.orderRef())
                                .header("Authorization", "Bearer " + merchantToken))
                        .andExpect(jsonPath("$.data.items[0].buyerNotice.kind").value("DISPATCHED"))
                        .andExpect(jsonPath("$.data.items[0].buyerNotice.outcome").value("NOT_SENT")));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mockMvc.perform(get("/marketplace/fulfilments?q={q}", collection.orderRef())
                                .header("Authorization", "Bearer " + merchantToken))
                        .andExpect(jsonPath("$.data.items[0].buyerNotice.kind")
                                .value("READY_TO_COLLECT")));

        mockMvc.perform(get("/marketplace/fulfilments/stats")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.inTransit").value(2))
                .andExpect(jsonPath("$.data.onTheWay").value(1))
                .andExpect(jsonPath("$.data.readyToCollect").value(1))
                .andExpect(jsonPath("$.data.readyToCollectOverdue").value(0))
                .andExpect(jsonPath("$.data.collectionOverdueDays").value(7));

        // The collection has been on the shelf for eight days.
        jdbc.update("UPDATE order_fulfilment SET dispatched_at = now() - interval '8 days' "
                + "WHERE id = ?::uuid", collection.fulfilmentId());
        mockMvc.perform(get("/marketplace/fulfilments/stats")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.readyToCollectOverdue").value(1));
    }

    @Test
    @DisplayName("Cards say when held money clears, why it is frozen, and how much code budget is left")
    void cardExplainsTheMoneyAndTheCode() throws Exception {
        String listingId = publishListing();
        Paid delivery = placePaidDelivery(listingId, "card-1");
        dispatch(delivery.fulfilmentId());
        mockMvc.perform(post("/marketplace/fulfilments/{id}/delivered", delivery.fulfilmentId())
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closedBy").value("SELLER_MARKED"))
                .andExpect(jsonPath("$.data.settlementStatus").value("HELD"))
                .andExpect(jsonPath("$.data.settlementClearsAt").isNotEmpty());

        // The buyer disputes: the card now says why the money is frozen.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/dispute",
                        delivery.orderId(), delivery.fulfilmentId())
                        .header("Authorization", "Bearer " + deliveryBuyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"NOT_RECEIVED\",\"detail\":\"Nothing came\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/fulfilments/tracking/{code}", delivery.trackingCode())
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.settlementStatus").value("DISPUTED"))
                .andExpect(jsonPath("$.data.settlementClearsAt").doesNotExist())
                .andExpect(jsonPath("$.data.dispute.status").value("OPEN"))
                .andExpect(jsonPath("$.data.dispute.reason").value("NOT_RECEIVED"))
                .andExpect(jsonPath("$.data.dispute.openedAt").isNotEmpty())
                // The buyer's own words are for the operator, not the seller.
                .andExpect(jsonPath("$.data.dispute.detail").doesNotExist());

        // A collection code: the seller sees the budget, never the code.
        Paid collection = placePaidCollection(listingId, "card-2");
        dispatch(collection.fulfilmentId());
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                        collection.orderId(), collection.fulfilmentId())
                        .header("Authorization", "Bearer " + collectionBuyer))
                .andExpect(status().isOk());
        // The test profile allows 3 wrong codes (production: 10).
        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", collection.fulfilmentId())
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AAAA-AAAA-AAAA\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/marketplace/fulfilments/tracking/{code}", collection.trackingCode())
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.collectCodeIssued").value(true))
                .andExpect(jsonPath("$.data.collectCodeLocked").value(false))
                .andExpect(jsonPath("$.data.collectCodeAttemptsLeft").value(2));
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", collection.fulfilmentId())
                            .header("Authorization", "Bearer " + merchantToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"AAAA-AAAA-AAAA\"}"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .isIn(409, 422));
        }
        // The seller learns the parcel is locked from the card, not by failing again.
        mockMvc.perform(get("/marketplace/fulfilments/tracking/{code}", collection.trackingCode())
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.collectCodeLocked").value(true))
                .andExpect(jsonPath("$.data.collectCodeAttemptsLeft").value(0));
    }

    @Test
    @DisplayName("Earnings rows name the order, items and close; summary and statement follow")
    void earningsRowsSummaryAndStatement() throws Exception {
        String listingId = publishListing();

        // A: the buyer confirms -> RELEASABLE -> paid out.
        Paid paidOut = placePaidDelivery(listingId, "earn-1");
        dispatch(paidOut.fulfilmentId());
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/received",
                        paidOut.orderId(), paidOut.fulfilmentId())
                        .header("Authorization", "Bearer " + deliveryBuyer))
                .andExpect(status().isOk());
        mockMvc.perform(post("/marketplace/settlements/pay-out")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"%s\",\"payoutReference\":\"PAYOUT-2026-09-30-01\"}"
                                .formatted(merchantId)))
                .andExpect(status().isOk());

        // B: seller-marked -> HELD on the dispute-window clock.
        Paid held = placePaidDelivery(listingId, "earn-2");
        dispatch(held.fulfilmentId());
        mockMvc.perform(post("/marketplace/fulfilments/{id}/delivered", held.fulfilmentId())
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());

        // C: the seller cannot supply -> REFUND_DUE with their reason.
        Paid declined = placePaidCollection(listingId, "earn-3");
        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", declined.fulfilmentId())
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Out of stock\"}"))
                .andExpect(status().isOk());

        String rows = mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(rows, "$.data.items[?(@.orderRef == '"
                + paidOut.orderRef() + "')].closedBy")).containsExactly("BUYER_CONFIRMED");
        assertThat(JsonPath.<List<String>>read(rows, "$.data.items[?(@.orderRef == '"
                + held.orderRef() + "')].closedBy")).containsExactly("SELLER_MARKED");
        assertThat(JsonPath.<List<String>>read(rows, "$.data.items[?(@.orderRef == '"
                + declined.orderRef() + "')].refundReason")).containsExactly("Out of stock");
        assertThat(JsonPath.<List<String>>read(rows, "$.data.items[?(@.orderRef == '"
                + declined.orderRef() + "')].closedBy")).containsExactly("CANNOT_SUPPLY");
        assertThat(JsonPath.<List<String>>read(rows, "$.data.items[*].itemSummary"))
                .containsOnly("1 x Solar Lantern 20W");

        // The wallet header.
        mockMvc.perform(get("/marketplace/settlements/summary")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.nextClearingAt").isNotEmpty())
                .andExpect(jsonPath("$.data.clearingNext7DaysCents").value(1550))
                .andExpect(jsonPath("$.data.lastPayout.payoutReference").value("PAYOUT-2026-09-30-01"))
                .andExpect(jsonPath("$.data.lastPayout.netCents").value(1550))
                .andExpect(jsonPath("$.data.lastPayout.parcels").value(1));

        // Date range: move the declined sale into January (Harare calendar).
        jdbc.update("UPDATE merchant_settlement SET created_at = '2026-01-15T10:00:00Z' "
                + "WHERE fulfilment_id = ?::uuid", declined.fulfilmentId());
        mockMvc.perform(get("/marketplace/settlements?from=2026-01-01&to=2026-01-31")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].orderRef").value(declined.orderRef()));
        mockMvc.perform(get("/marketplace/settlements?from=2026-02-01&to=2026-01-01")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_date_range"));

        // The statement, as a spreadsheet: same rows, oldest first, period in the filename.
        String csv = mockMvc.perform(get("/marketplace/settlements/statement?to=2026-12-31")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(
                                "marketplace-statement-start_to_2026-12-31.csv")))
                .andReturn().getResponse().getContentAsString();
        List<String> lines = csv.lines().toList();
        assertThat(lines.getFirst()).startsWith("date,orderRef,items,status,closedBy,");
        assertThat(lines).hasSize(4);
        // Oldest first: the January row leads, dated on the Harare calendar.
        assertThat(lines.get(1)).startsWith("2026-01-15," + declined.orderRef()
                + ",1 x Solar Lantern 20W,REFUND_DUE,CANNOT_SUPPLY,");
        assertThat(lines.get(1)).contains(",Out of stock,");
        assertThat(csv).contains("PAYOUT-2026-09-30-01").contains("+02:00");
        // An operator has to say whose statement.
        mockMvc.perform(get("/marketplace/settlements/statement")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("merchant_id_required"));
    }

    // ------------------------------------------------------------------

    private record Paid(String orderId, String orderRef, String fulfilmentId, String trackingCode) {
    }

    private void search(String q, String expectedRef) throws Exception {
        mockMvc.perform(get("/marketplace/fulfilments").param("q", q)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].orderRef").value(expectedRef));
    }

    private void dispatch(String fulfilmentId) throws Exception {
        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
    }

    private Paid placePaidDelivery(String listingId, String key) throws Exception {
        String address = mockMvc.perform(post("/marketplace/addresses")
                        .header("Authorization", "Bearer " + deliveryBuyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Home","recipientName":"Tariro Moyo",
                                 "recipientMsisdn":"0771234567","line1":"14 Samora Machel Ave",
                                 "townCode":"harare"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return placePaid(deliveryBuyer, key, """
                {"items":[{"listingId":"%s","quantity":1}],
                 "deliveryMethod":"DELIVERY","deliveryAddressId":"%s"}"""
                .formatted(listingId, JsonPath.<String>read(address, "$.data.id")));
    }

    private Paid placePaidCollection(String listingId, String key) throws Exception {
        return placePaid(collectionBuyer, key,
                "{\"items\":[{\"listingId\":\"%s\",\"quantity\":1}]}".formatted(listingId));
    }

    private Paid placePaid(String buyer, String key, String body) throws Exception {
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-%s\",\"amountCents\":1550}".formatted(key)))
                .andExpect(status().isOk());
        String paid = mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + buyer))
                .andReturn().getResponse().getContentAsString();
        return new Paid(orderId, orderRef, JsonPath.read(paid, "$.data.fulfilments[0].id"),
                JsonPath.read(paid, "$.data.fulfilments[0].trackingCode"));
    }

    private String publishListing() throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Solar Lantern 20W","categoryCode":"electronics",
                                 "priceCents":1550,"stockQty":20,
                                 "deliveryTowns":[{"townCode":"harare","feeCents":0}]}"""))
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
