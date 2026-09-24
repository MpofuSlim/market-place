package com.innbucks.marketplaceservice.fulfilment.tracking;

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

import java.util.List;
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
 * Delivery towns, per-seller fees and parcel tracking end to end, over the real
 * security chain and a real Postgres: a seller lists the towns they deliver to,
 * a buyer in a covered town pays that seller's fee, the fee rides the parcel
 * into escrow, a STAFF driver reports positions, and the buyer and the portal
 * both see where the parcel is.
 */
class DeliveryTrackingFlowIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    private UUID merchantId;
    private String merchantToken;
    private String driverToken;
    private String customerToken;

    @BeforeEach
    void mintTokens() {
        merchantId = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        // A driver is STAFF of the selling business: no seller authority, only
        // the courier surface.
        driverToken = TestJwts.forUser(UUID.randomUUID())
                .organization(merchantId, "STAFF", List.of("marketplace")).sign(jwtSecret);
        customerToken = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);
    }

    @Test
    @DisplayName("The town list is public reference data")
    void townsArePublic() throws Exception {
        mockMvc.perform(get("/marketplace/delivery-towns"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("max-age=3600")))
                .andExpect(jsonPath("$.data[0].code").value("harare"))
                .andExpect(jsonPath("$.data[?(@.code == 'victoria-falls')].name")
                        .value("Victoria Falls"));
    }

    @Test
    @DisplayName("list towns -> quote the town's fee -> order -> escrow holds goods + fee -> "
            + "driver reports -> buyer and portal track it -> delivered hides the pin")
    void theDeliveryJourney() throws Exception {
        String listingId = publishListing("""
                [{ "townCode": "harare", "feeCents": 800 },
                 { "townCode": "bulawayo", "feeCents": 1500 }]""");

        // The listing says where it goes and what it costs — to anyone.
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliverable").value(true))
                .andExpect(jsonPath("$.data.deliveryTowns[0].townCode").value("harare"))
                .andExpect(jsonPath("$.data.deliveryTowns[0].townName").value("Harare"))
                .andExpect(jsonPath("$.data.deliveryTowns[1].feeCents").value(1500));

        String bulawayo = saveAddress("bulawayo");
        String quote = """
                {"items":[{"listingId":"%s","quantity":2}],
                 "deliveryMethod":"DELIVERY","deliveryAddressId":"%s"}"""
                .formatted(listingId, bulawayo);
        mockMvc.perform(post("/marketplace/checkout/quote")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quote))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subtotalCents").value(3100))
                .andExpect(jsonPath("$.data.deliveryFeeCents").value(1500))
                .andExpect(jsonPath("$.data.totalCents").value(4600))
                .andExpect(jsonPath("$.data.deliveryFees[0].merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.data.deliveryFees[0].feeCents").value(1500))
                .andExpect(jsonPath("$.data.checkoutReady").value(true));

        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "delivery-journey-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quote))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deliveryFeeCents").value(1500))
                .andExpect(jsonPath("$.data.totalCents").value(4600))
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");

        // The seller REPRICES Bulawayo before the buyer pays: the order keeps
        // the fee it was quoted.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/marketplace/listings/{id}", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Solar Lantern 20W","categoryCode":"electronics",
                                 "priceCents":1550,"stockQty":8,
                                 "deliveryTowns":[{"townCode":"bulawayo","feeCents":9999}]}"""))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-DLV-1\",\"amountCents\":4600}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

        // The parcel carries a tracking code and the fee it was sold with.
        String paid = mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.fulfilments[0].trackingStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.data.fulfilments[0].deliveryFeeCents").value(1500))
                .andReturn().getResponse().getContentAsString();
        String fulfilmentId = JsonPath.read(paid, "$.data.fulfilments[0].id");
        String trackingCode = JsonPath.read(paid, "$.data.fulfilments[0].trackingCode");
        assertThat(trackingCode).matches("TRK-[0-9A-HJKMNP-TV-Z]{10}");

        // Escrow holds goods + delivery for the seller; commission (none on
        // this cell) could only ever touch the goods.
        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].grossCents").value(4600))
                .andExpect(jsonPath("$.data.items[0].deliveryFeeCents").value(1500))
                .andExpect(jsonPath("$.data.items[0].netCents").value(4600));

        // Before dispatch the driver has nothing to report on.
        ping(fulfilmentId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("parcel_not_in_transit"));

        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());

        // The driver's run: where to go, what to hand over — and no money.
        String run = mockMvc.perform(get("/marketplace/deliveries")
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data[0].trackingCode").value(trackingCode))
                .andExpect(jsonPath("$.data[0].destination.city").value("Bulawayo"))
                .andExpect(jsonPath("$.data[0].items[0].quantity").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(run).doesNotContain("Cents");

        ping(fulfilmentId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true));
        // A second fix a moment later is inside the throttle window: fine, ignored.
        ping(fulfilmentId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(false));

        // The buyer's map...
        mockMvc.perform(get("/marketplace/orders/{id}/fulfilments/{fid}/tracking",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trackingStatus").value("DISPATCHED"))
                .andExpect(jsonPath("$.data.timeline[1].status").value("DISPATCHED"))
                .andExpect(jsonPath("$.data.liveLocation.latitude").value(-20.15))
                .andExpect(jsonPath("$.data.liveLocation.longitude").value(28.58));

        // ...and the portal's search box, forgiving how the code was typed.
        mockMvc.perform(get("/marketplace/fulfilments/tracking/{code}",
                        trackingCode.toLowerCase().replace("-", " "))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(fulfilmentId))
                .andExpect(jsonPath("$.data.lastLocation.latitude").value(-20.15));
        // Another seller cannot probe codes.
        mockMvc.perform(get("/marketplace/fulfilments/tracking/{code}", trackingCode)
                        .header("Authorization", "Bearer "
                                + TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret)))
                .andExpect(status().isNotFound());

        // Delivered: the pin disappears, and the driver's reports stop landing.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/received",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/orders/{id}/fulfilments/{fid}/tracking",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.trackingStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.data.liveLocation").doesNotExist());
        ping(fulfilmentId).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("A town the seller does not cover is named on the quote and refused on the order")
    void anUncoveredTownIsRefused() throws Exception {
        String listingId = publishListing("""
                [{ "townCode": "harare", "feeCents": 800 }]""");
        String mutare = saveAddress("mutare");
        String body = """
                {"items":[{"listingId":"%s","quantity":1}],
                 "deliveryMethod":"DELIVERY","deliveryAddressId":"%s"}"""
                .formatted(listingId, mutare);

        mockMvc.perform(post("/marketplace/checkout/quote")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutReady").value(false))
                .andExpect(jsonPath("$.data.rejections[0].reason").value("NOT_DELIVERED_TO_TOWN"))
                .andExpect(jsonPath("$.data.rejections[0].message")
                        .value("Solar Lantern 20W is not delivered to Mutare"));

        mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "uncovered-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("not_delivered_to_town"))
                .andExpect(jsonPath("$.data.rejections[0].reason").value("NOT_DELIVERED_TO_TOWN"));
        // Nothing reserved.
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(jsonPath("$.data.stockQty").value(10));
    }

    @Test
    @DisplayName("A customer, and a seller of another business, cannot use the courier surface")
    void courierSurfaceIsScoped() throws Exception {
        mockMvc.perform(get("/marketplace/deliveries")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/marketplace/deliveries"))
                .andExpect(status().isUnauthorized());

        String listingId = publishListing("""
                [{ "townCode": "harare", "feeCents": 0 }]""");
        String harare = saveAddress("harare");
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "scope-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"listingId":"%s","quantity":1}],
                                 "deliveryMethod":"DELIVERY","deliveryAddressId":"%s"}"""
                                .formatted(listingId, harare)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment",
                        JsonPath.<String>read(created, "$.data.orderRef"))
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-SCOPE-1\",\"amountCents\":1550}"))
                .andExpect(status().isOk());
        String fulfilmentId = JsonPath.read(mockMvc.perform(
                        get("/marketplace/orders/{id}", JsonPath.<String>read(created, "$.data.id"))
                                .header("Authorization", "Bearer " + customerToken))
                .andReturn().getResponse().getContentAsString(), "$.data.fulfilments[0].id");
        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());

        // STAFF of ANOTHER marketplace business: same 404 as a missing parcel.
        String foreignDriver = TestJwts.forUser(UUID.randomUUID())
                .organization(UUID.randomUUID(), "STAFF", List.of("marketplace")).sign(jwtSecret);
        mockMvc.perform(post("/marketplace/deliveries/{id}/location", fulfilmentId)
                        .header("Authorization", "Bearer " + foreignDriver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":-17.8292,\"longitude\":31.0539}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("fulfilment_not_found"));
        // Outside the market: a phone with no fix yet.
        mockMvc.perform(post("/marketplace/deliveries/{id}/location", fulfilmentId)
                        .header("Authorization", "Bearer " + driverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":0,\"longitude\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("location_out_of_bounds"));
    }

    // ------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions ping(String fulfilmentId)
            throws Exception {
        return mockMvc.perform(post("/marketplace/deliveries/{id}/location", fulfilmentId)
                .header("Authorization", "Bearer " + driverToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":-20.15,\"longitude\":28.58,\"accuracyMeters\":15}"));
    }

    private String saveAddress(String townCode) throws Exception {
        String saved = mockMvc.perform(post("/marketplace/addresses")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Home","recipientName":"Tariro Moyo",
                                 "recipientMsisdn":"0771234567","line1":"14 Samora Machel Ave",
                                 "townCode":"%s"}""".formatted(townCode)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.townCode").value(townCode))
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
