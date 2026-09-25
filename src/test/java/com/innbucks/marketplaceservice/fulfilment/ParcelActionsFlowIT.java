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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The buyer's parcel actions end to end, over the real security chain and a
 * real Postgres: at every step of a parcel's life the flags the order and the
 * tracking screen advertise are exactly what the action endpoints then accept
 * or refuse — the one promise the flags exist to keep.
 */
class ParcelActionsFlowIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    private UUID merchantId;
    private String merchantToken;
    private String customerToken;
    private String driverToken;
    private int orders;

    @BeforeEach
    void mintTokens() {
        merchantId = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        customerToken = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);
        driverToken = TestJwts.forUser(UUID.randomUUID())
                .organization(merchantId, "STAFF", List.of("marketplace")).sign(jwtSecret);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("COLLECTION: open parcel offers all four -> code redeemed at the counter -> "
            + "finished, received, released; only a dispute is left")
    void aCollectionHandover() throws Exception {
        String listing = publishListing("[]");
        Placed order = placeAndPay(collectionBody(listing));

        order(order.id())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.data.actions.canCancel").value(false))
                .andExpect(actions(true, true, true, true))
                .andExpect(jsonPath("$.data.fulfilments[0].closedAt").doesNotExist())
                .andExpect(jsonPath("$.data.fulfilments[0].closedBy").doesNotExist())
                .andExpect(jsonPath("$.data.fulfilments[0].receivedAt").doesNotExist())
                // Undelivered: disputable with no deadline, no release clock yet.
                .andExpect(jsonPath("$.data.fulfilments[0].disputableUntil").doesNotExist())
                .andExpect(jsonPath("$.data.fulfilments[0].paymentReleasesAt").doesNotExist());

        String code = JsonPath.read(mockMvc.perform(post(
                                "/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                                order.id(), order.parcel())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.code");
        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", order.parcel())
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());

        String after = order(order.id())
                .andExpect(actions(false, false, false, true))
                .andExpect(jsonPath("$.data.fulfilments[0].closedBy").value("COLLECTION_CODE"))
                .andExpect(jsonPath("$.data.fulfilments[0].deliveredBy").value("RECIPIENT"))
                .andExpect(jsonPath("$.data.fulfilments[0].disputableUntil").exists())
                // Released at the counter: no clock left.
                .andExpect(jsonPath("$.data.fulfilments[0].paymentReleasesAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String deliveredAt = JsonPath.read(after, "$.data.fulfilments[0].deliveredAt");
        assertThat(Instant.parse(JsonPath.read(after, "$.data.fulfilments[0].receivedAt")))
                .isEqualTo(Instant.parse(deliveredAt));
        assertThat(Instant.parse(JsonPath.read(after, "$.data.fulfilments[0].closedAt")))
                .isEqualTo(Instant.parse(deliveredAt));
        assertThat(Instant.parse(JsonPath.read(after, "$.data.fulfilments[0].disputableUntil")))
                .isEqualTo(Instant.parse(deliveredAt).plus(java.time.Duration.ofDays(7)));

        // The tracking screen says the same, from the same rules.
        tracking(order)
                .andExpect(jsonPath("$.data.actions.canConfirmReceipt").value(false))
                .andExpect(jsonPath("$.data.actions.canDispute").value(true))
                .andExpect(jsonPath("$.data.closedBy").value("COLLECTION_CODE"))
                .andExpect(jsonPath("$.data.receivedAt").exists());

        // And the endpoints agree with the flags that said no.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/received",
                        order.id(), order.parcel())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                        order.id(), order.parcel())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("DELIVERY closed by the seller: not received, a release clock and a dispute "
            + "deadline; past the window both the flag and the endpoint say no")
    void aSellerMarkedDelivery() throws Exception {
        String listing = publishListing("[{\"townCode\":\"harare\",\"feeCents\":300}]");
        String address = saveAddress();
        Placed order = placeAndPay("""
                {"items":[{"listingId":"%s","quantity":1}],"deliveryMethod":"DELIVERY",
                 "deliveryAddressId":"%s"}""".formatted(listing, address));

        // The buyer's copy names the address-book entry; the seller's does not.
        order(order.id())
                .andExpect(jsonPath("$.data.deliveryAddress.addressId").value(address))
                .andExpect(actions(true, false, true, true));
        tracking(order).andExpect(jsonPath("$.data.destination.addressId").value(address));
        mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].destination.line1").exists())
                .andExpect(jsonPath("$.data.items[0].destination.addressId").doesNotExist());

        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", order.parcel())
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        order(order.id()).andExpect(actions(true, false, false, true));
        mockMvc.perform(get("/marketplace/deliveries")
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(jsonPath("$.data[0].destination.line1").exists())
                .andExpect(jsonPath("$.data[0].destination.addressId").doesNotExist());

        mockMvc.perform(post("/marketplace/fulfilments/{id}/delivered", order.parcel())
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        String closed = order(order.id())
                .andExpect(actions(false, false, false, true))
                .andExpect(jsonPath("$.data.fulfilments[0].closedBy").value("SELLER_MARKED"))
                // The seller's word is not a receipt.
                .andExpect(jsonPath("$.data.fulfilments[0].receivedAt").doesNotExist())
                .andExpect(jsonPath("$.data.fulfilments[0].closedAt").exists())
                .andReturn().getResponse().getContentAsString();
        Instant deliveredAt = Instant.parse(JsonPath.read(closed, "$.data.fulfilments[0].deliveredAt"));
        Instant releasesAt = Instant.parse(
                JsonPath.read(closed, "$.data.fulfilments[0].paymentReleasesAt"));
        Instant until = Instant.parse(JsonPath.read(closed, "$.data.fulfilments[0].disputableUntil"));
        assertThat(until).isEqualTo(deliveredAt.plus(java.time.Duration.ofDays(7)));
        // The release never comes before the buyer's right to object ends.
        assertThat(releasesAt).isAfterOrEqualTo(until.minusSeconds(5));

        // The buyer cannot "upgrade" a seller's close: flag false, endpoint 409.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/received",
                        order.id(), order.parcel())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal_fulfilment_state"));

        // Eight days later the window has closed: flag, deadline and endpoint agree.
        jdbc.update("UPDATE order_fulfilment SET delivered_at = now() - interval '8 days' "
                + "WHERE id = ?::uuid", order.parcel());
        order(order.id())
                .andExpect(actions(false, false, false, false))
                .andExpect(jsonPath("$.data.fulfilments[0].disputableUntil").doesNotExist());
        dispute(order).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("dispute_window_closed"));
    }

    @Test
    @DisplayName("Disputed: cancel and dispute both switch off, and the endpoints agree")
    void aDisputeSwitchesTheRestOff() throws Exception {
        String listing = publishListing("[]");
        Placed order = placeAndPay(collectionBody(listing));

        dispute(order).andExpect(status().isOk());

        order(order.id())
                .andExpect(jsonPath("$.data.fulfilments[0].actions.canCancel").value(false))
                .andExpect(jsonPath("$.data.fulfilments[0].actions.canDispute").value(false))
                // The money froze: no release clock.
                .andExpect(jsonPath("$.data.fulfilments[0].paymentReleasesAt").doesNotExist());
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/cancel",
                        order.id(), order.parcel())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("parcel_disputed"));
        dispute(order).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("dispute_already_raised"));
    }

    @Test
    @DisplayName("A buyer cancel: every action switches off and the parcel says who ended it")
    void aBuyerCancel() throws Exception {
        String listing = publishListing("[]");
        Placed order = placeAndPay(collectionBody(listing));

        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/cancel",
                        order.id(), order.parcel())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fulfilments[0].actions.canCancel").value(false))
                .andExpect(jsonPath("$.data.fulfilments[0].actions.canRequestCollectCode")
                        .value(false))
                .andExpect(jsonPath("$.data.fulfilments[0].actions.canConfirmReceipt").value(false))
                // The money is already on its way back.
                .andExpect(jsonPath("$.data.fulfilments[0].actions.canDispute").value(false))
                .andExpect(jsonPath("$.data.fulfilments[0].closedBy").value("BUYER_CANCELLED"))
                .andExpect(jsonPath("$.data.fulfilments[0].closedAt").exists());

        // A cancelled collection gets no code (and texts nobody).
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                        order.id(), order.parcel())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("This parcel was cancelled - there is nothing to collect"));
    }

    @Test
    @DisplayName("An unpaid order can be cancelled as a whole - and then it cannot")
    void theOrderLevelCancel() throws Exception {
        String listing = publishListing("[]");
        String orderId = JsonPath.read(place(collectionBody(listing)), "$.data.id");

        order(orderId).andExpect(jsonPath("$.data.actions.canCancel").value(true))
                .andExpect(jsonPath("$.data.fulfilments").isEmpty());
        mockMvc.perform(post("/marketplace/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actions.canCancel").value(false));
    }

    @Test
    @DisplayName("Confirming receipt with another order's parcel is refused before anything "
            + "closes - no parcel moves, no audit row is left behind")
    void aReceiptForAnotherOrdersParcel() throws Exception {
        String listing = publishListing("[]");
        Placed first = placeAndPay(collectionBody(listing));
        Placed second = placeAndPay(collectionBody(listing));

        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/received",
                        first.id(), second.parcel())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("fulfilment_not_found"));

        order(second.id()).andExpect(jsonPath("$.data.fulfilments[0].status").value("PREPARING"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_type = 'FULFILMENT_DELIVERED'",
                Integer.class)).isZero();
    }

    // ------------------------------------------------------------------

    private record Placed(String id, String parcel) {
    }

    private org.springframework.test.web.servlet.ResultMatcher actions(boolean receipt,
                                                                      boolean code,
                                                                      boolean cancel,
                                                                      boolean dispute) {
        return result -> {
            jsonPath("$.data.fulfilments[0].actions.canConfirmReceipt").value(receipt).match(result);
            jsonPath("$.data.fulfilments[0].actions.canRequestCollectCode").value(code).match(result);
            jsonPath("$.data.fulfilments[0].actions.canCancel").value(cancel).match(result);
            jsonPath("$.data.fulfilments[0].actions.canDispute").value(dispute).match(result);
        };
    }

    private ResultActions order(String orderId) throws Exception {
        return mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
    }

    private ResultActions tracking(Placed order) throws Exception {
        return mockMvc.perform(get("/marketplace/orders/{id}/fulfilments/{fid}/tracking",
                        order.id(), order.parcel())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
    }

    private ResultActions dispute(Placed order) throws Exception {
        return mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/dispute",
                order.id(), order.parcel())
                .header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"NOT_RECEIVED\"}"));
    }

    private static String collectionBody(String listingId) {
        return """
                {"items":[{"listingId":"%s","quantity":1}],"deliveryMethod":"COLLECTION"}"""
                .formatted(listingId);
    }

    private String place(String body) throws Exception {
        return mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "parcel-actions-" + (++orders))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private Placed placeAndPay(String body) throws Exception {
        String created = place(body);
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");
        Number total = JsonPath.read(created, "$.data.totalCents");
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-ACT-" + orders + "\",\"amountCents\":"
                                + total.longValue() + "}"))
                .andExpect(status().isOk());
        String parcel = JsonPath.read(order(orderId).andReturn().getResponse().getContentAsString(),
                "$.data.fulfilments[0].id");
        return new Placed(orderId, parcel);
    }

    private String saveAddress() throws Exception {
        String saved = mockMvc.perform(post("/marketplace/addresses")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Home","recipientName":"Tariro Moyo",
                                 "recipientMsisdn":"0771234567","line1":"14 Samora Machel Ave",
                                 "townCode":"harare"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(saved, "$.data.id");
    }

    private String publishListing(String deliveryTowns) throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Solar Lantern 20W","categoryCode":"electronics",
                                 "priceCents":1550,"stockQty":10,"deliveryTowns":%s}"""
                                .formatted(deliveryTowns)))
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
