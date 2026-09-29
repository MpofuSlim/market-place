package com.innbucks.marketplaceservice.fulfilment;

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
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * A MIXED order end to end over real Postgres and the real security chain
 * (V21): seller A delivers, seller B's parcel is collected, and the order's
 * summary says DELIVERY. Every parcel-level surface must read the PARCEL's
 * method - the self-close rule, collection codes, the courier run and its
 * positions, the seller card and its destination, the queue filter and search,
 * the stats split, the overdue sweep, tracking, the buyer's parcels and the
 * earnings row.
 *
 * <p>The API that lets a buyer choose a method per seller is the next stage, so
 * the mix is made the way V21 permits it to exist: the order is placed through
 * the API as a uniform DELIVERY order, and before payment seller B's
 * {@code market_order_seller} row is rewritten to COLLECTION (B delivers
 * Harare for free, so no money moves). Payment then opens each parcel from its
 * own seller row.
 */
class MixedParcelFlowIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};
    private static final String DELIVERY_RECIPIENT_PHONE = "+263772000111";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    @Autowired
    private OrderFulfilmentRepository fulfilments;

    private UUID sellerA;
    private UUID sellerB;
    private String tokenA;
    private String tokenB;
    private String driverA;
    private String driverB;
    private String customer;

    private String orderId;
    private String orderRef;
    private String parcelA;
    private String parcelB;

    @BeforeEach
    void placeAndPayAMixedOrder() throws Exception {
        sellerA = UUID.randomUUID();
        sellerB = UUID.randomUUID();
        tokenA = TestJwts.merchantAdmin(UUID.randomUUID(), sellerA, jwtSecret);
        tokenB = TestJwts.merchantAdmin(UUID.randomUUID(), sellerB, jwtSecret);
        driverA = TestJwts.forUser(UUID.randomUUID())
                .organization(sellerA, "STAFF", List.of("marketplace")).sign(jwtSecret);
        driverB = TestJwts.forUser(UUID.randomUUID())
                .organization(sellerB, "STAFF", List.of("marketplace")).sign(jwtSecret);
        customer = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);

        String lantern = publishListing(tokenA, "Solar Lantern 20W", 800);
        String hose = publishListing(tokenB, "Garden Hose", 0);
        String address = saveAddress();

        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customer)
                        .header("Idempotency-Key", "mixed-parcels-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"listingId":"%s","quantity":1},
                                          {"listingId":"%s","quantity":1}],
                                 "deliveryMethod":"DELIVERY","deliveryAddressId":"%s"}"""
                                .formatted(lantern, hose, address)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.totalCents").value(3900))
                .andReturn().getResponse().getContentAsString();
        orderId = JsonPath.read(created, "$.data.id");
        orderRef = JsonPath.read(created, "$.data.orderRef");

        // Seller B's share is collected (the per-seller API is the next stage).
        // B delivers Harare free, so the order's money is unchanged, and a
        // collecting seller carries no fee row.
        assertThat(jdbc.update("""
                UPDATE market_order_seller SET delivery_method = 'COLLECTION'
                 WHERE order_id = ?::uuid AND merchant_id = ?""", orderId, sellerB)).isEqualTo(1);
        jdbc.update("DELETE FROM market_order_delivery_fee WHERE order_id = ?::uuid AND merchant_id = ?",
                orderId, sellerB);

        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-MIXED-1\",\"amountCents\":3900}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

        parcelA = parcelOf(sellerA);
        parcelB = parcelOf(sellerB);
    }

    @Test
    @DisplayName("Each parcel is opened with ITS seller's method and fee; the buyer's order says so per parcel under a DELIVERY summary")
    void parcelsCarryTheirOwnMethod() throws Exception {
        Map<String, Object> a = jdbc.queryForMap(
                "SELECT delivery_method, delivery_fee_cents FROM order_fulfilment WHERE id = ?::uuid",
                parcelA);
        Map<String, Object> b = jdbc.queryForMap(
                "SELECT delivery_method, delivery_fee_cents FROM order_fulfilment WHERE id = ?::uuid",
                parcelB);
        assertThat(a).containsEntry("delivery_method", "DELIVERY").containsEntry("delivery_fee_cents", 800L);
        assertThat(b).containsEntry("delivery_method", "COLLECTION").containsEntry("delivery_fee_cents", 0L);
        // The seller of the collected parcel is owed its goods only.
        assertThat(jdbc.queryForObject(
                "SELECT gross_cents FROM merchant_settlement WHERE fulfilment_id = ?::uuid",
                Long.class, parcelB)).isEqualTo(1550L);
        assertThat(jdbc.queryForObject(
                "SELECT gross_cents FROM merchant_settlement WHERE fulfilment_id = ?::uuid",
                Long.class, parcelA)).isEqualTo(2350L);

        mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryMethod").value("DELIVERY"))
                .andExpect(jsonPath("$.data.fulfilments[?(@.id == '%s')].deliveryMethod", parcelA)
                        .value("DELIVERY"))
                .andExpect(jsonPath("$.data.fulfilments[?(@.id == '%s')].deliveryMethod", parcelB)
                        .value("COLLECTION"))
                // The flags follow the parcel: a code for the collected one only.
                .andExpect(jsonPath("$.data.fulfilments[?(@.id == '%s')].actions.canRequestCollectCode",
                        parcelB).value(true))
                .andExpect(jsonPath("$.data.fulfilments[?(@.id == '%s')].actions.canRequestCollectCode",
                        parcelA).value(false));
    }

    @Test
    @DisplayName("Collection rules follow the parcel: B cannot self-close and mints a code, A mints none and cannot be 'collected'")
    void collectionRulesFollowTheParcel() throws Exception {
        mockMvc.perform(post("/marketplace/fulfilments/{id}/delivered", parcelB)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("collect_code_required"));

        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code", orderId, parcelA)
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("collect_code_not_applicable"))
                .andExpect(jsonPath("$.message")
                        .value("This parcel is being delivered - there is nothing to collect in person"));
        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", parcelA)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AAAA-BBBB-CCCC\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("collect_code_not_applicable"));

        String minted = mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                                orderId, parcelB)
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", parcelB)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(
                                JsonPath.<String>read(minted, "$.data.code"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closedBy").value("COLLECTION_CODE"));

        // A courier delivery is the seller's to close.
        mockMvc.perform(post("/marketplace/fulfilments/{id}/delivered", parcelA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closedBy").value("SELLER_MARKED"));
    }

    @Test
    @DisplayName("The seller card: B sees no destination but its queue filter finds it as a collection; A sees where it goes")
    void sellerCardsAndQueueFilterReadTheParcel() throws Exception {
        mockMvc.perform(get("/marketplace/fulfilments").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(parcelB))
                .andExpect(jsonPath("$.data.items[0].deliveryMethod").value("COLLECTION"))
                .andExpect(jsonPath("$.data.items[0].destination").doesNotExist());
        mockMvc.perform(get("/marketplace/fulfilments").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.data.items[0].deliveryMethod").value("DELIVERY"))
                .andExpect(jsonPath("$.data.items[0].destination.line1").value("14 Samora Machel Ave"));

        queue(tokenB, "deliveryMethod", "COLLECTION").andExpect(jsonPath("$.data.totalItems").value(1));
        queue(tokenB, "deliveryMethod", "DELIVERY").andExpect(jsonPath("$.data.totalItems").value(0));
        queue(tokenA, "deliveryMethod", "DELIVERY").andExpect(jsonPath("$.data.totalItems").value(1));
        queue(tokenA, "deliveryMethod", "COLLECTION").andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    @DisplayName("C7: the collecting seller's search cannot test the delivery recipient's phone or name; the buyer's phone still finds it")
    void deliveryRecipientIsNotSearchableByTheCollectingSeller() throws Exception {
        queue(tokenB, "q", "0772000111").andExpect(jsonPath("$.data.totalItems").value(0));
        queue(tokenB, "q", "Nyasha").andExpect(jsonPath("$.data.totalItems").value(0));
        queue(tokenB, "q", "0771234567").andExpect(jsonPath("$.data.totalItems").value(1));
        queue(tokenB, "q", orderRef).andExpect(jsonPath("$.data.totalItems").value(1));

        // The delivering seller, whose card shows that destination, finds it.
        queue(tokenA, "q", "0772000111").andExpect(jsonPath("$.data.totalItems").value(1));
        queue(tokenA, "q", "Nyasha").andExpect(jsonPath("$.data.totalItems").value(1));
    }

    @Test
    @DisplayName("Courier run, positions, stats, tracking and the overdue sweep all split on the parcel")
    void transitSurfacesSplitOnTheParcel() throws Exception {
        for (String[] dispatch : new String[][]{{parcelA, tokenA}, {parcelB, tokenB}}) {
            mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", dispatch[0])
                            .header("Authorization", "Bearer " + dispatch[1]))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/marketplace/deliveries").header("Authorization", "Bearer " + driverA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fulfilmentId").value(parcelA))
                .andExpect(jsonPath("$.data[0].destination.line1").value("14 Samora Machel Ave"));
        mockMvc.perform(get("/marketplace/deliveries").header("Authorization", "Bearer " + driverB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        ping(driverB, parcelB).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("parcel_not_in_transit"));
        ping(driverA, parcelA).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true));

        mockMvc.perform(get("/marketplace/fulfilments/stats").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.data.onTheWay").value(1))
                .andExpect(jsonPath("$.data.readyToCollect").value(0));
        mockMvc.perform(get("/marketplace/fulfilments/stats").header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$.data.onTheWay").value(0))
                .andExpect(jsonPath("$.data.readyToCollect").value(1));

        mockMvc.perform(get("/marketplace/orders/{id}/fulfilments/{fid}/tracking", orderId, parcelB)
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryMethod").value("COLLECTION"))
                .andExpect(jsonPath("$.data.destination").doesNotExist())
                .andExpect(jsonPath("$.data.liveLocation").doesNotExist());
        mockMvc.perform(get("/marketplace/orders/{id}/fulfilments/{fid}/tracking", orderId, parcelA)
                        .header("Authorization", "Bearer " + customer))
                .andExpect(jsonPath("$.data.deliveryMethod").value("DELIVERY"))
                .andExpect(jsonPath("$.data.destination.line1").value("14 Samora Machel Ave"))
                .andExpect(jsonPath("$.data.liveLocation.latitude").value(-17.8292));

        // Both set aside / sent ten days ago: only the COLLECTION is overdue.
        jdbc.update("UPDATE order_fulfilment SET dispatched_at = ? WHERE order_id = ?::uuid",
                Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS)), orderId);
        assertThat(fulfilments.findOverdueCollections(Instant.now().minus(7, ChronoUnit.DAYS), 200))
                .extracting(OrderFulfilmentRepository.OverdueCollection::getFulfilmentId)
                .containsExactly(UUID.fromString(parcelB));
        assertThat(fulfilments.claimOverdueCollectionAlert(UUID.fromString(parcelA), Instant.now()))
                .as("a delivery is never claimed as an overdue collection").isZero();
    }

    @Test
    @DisplayName("The no-show exit is B's alone: B closes NOT_COLLECTED on its card and earnings row, A cannot decline once sent")
    void noShowExitFollowsTheParcel() throws Exception {
        for (String[] dispatch : new String[][]{{parcelA, tokenA}, {parcelB, tokenB}}) {
            mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", dispatch[0])
                            .header("Authorization", "Bearer " + dispatch[1]))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", parcelA)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Courier lost it\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal_fulfilment_state"));

        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", parcelB)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Waited a week, never collected\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNFULFILLED"))
                .andExpect(jsonPath("$.data.closedBy").value("NOT_COLLECTED"));

        mockMvc.perform(get("/marketplace/settlements").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("REFUND_DUE"))
                .andExpect(jsonPath("$.data.items[0].closedBy").value("NOT_COLLECTED"));
    }

    // ------------------------------------------------------------------

    private String parcelOf(UUID merchantId) {
        return jdbc.queryForObject(
                "SELECT id::text FROM order_fulfilment WHERE order_id = ?::uuid AND merchant_id = ?",
                String.class, orderId, merchantId);
    }

    private ResultActions queue(String token, String param, String value) throws Exception {
        return mockMvc.perform(get("/marketplace/fulfilments")
                        .param(param, value)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private ResultActions ping(String driver, String fulfilmentId) throws Exception {
        return mockMvc.perform(post("/marketplace/deliveries/{id}/location", fulfilmentId)
                .header("Authorization", "Bearer " + driver)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":-17.8292,\"longitude\":31.0539,\"accuracyMeters\":15}"));
    }

    /** A Harare address whose recipient is NOT the buyer - so a phone or name
     *  search can tell the delivery recipient from the payer. */
    private String saveAddress() throws Exception {
        String saved = mockMvc.perform(post("/marketplace/addresses")
                        .header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Mum's","recipientName":"Nyasha Moyo",
                                 "recipientMsisdn":"%s","line1":"14 Samora Machel Ave",
                                 "townCode":"harare"}""".formatted(DELIVERY_RECIPIENT_PHONE)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(saved, "$.data.id");
    }

    private String publishListing(String token, String title, long harareFee) throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","categoryCode":"electronics",
                                 "priceCents":1550,"stockQty":10,
                                 "deliveryTowns":[{"townCode":"harare","feeCents":%d}]}"""
                                .formatted(title, harareFee)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.data.id");
        mockMvc.perform(multipart(HttpMethod.POST, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        return listingId;
    }
}
