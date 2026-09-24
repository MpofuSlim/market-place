package com.innbucks.marketplaceservice.gift;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Buying for someone else, end to end over the real security chain and a real
 * Postgres: the order names a recipient, the buyer mints a handover code, and
 * the seller redeems it at the counter — which closes the parcel on the
 * strongest evidence the platform records and releases their money on the spot.
 */
class GiftFlowIT extends PostgresTestContainer {

    private static final String LISTING_BODY = """
            {
              "title": "Mealie Meal 10kg",
              "description": "Roller meal, 10kg bag",
              "categoryCode": "groceries",
              "priceCents": 1550,
              "stockQty": 10,
              "deliveryTowns": [{ "townCode": "harare", "feeCents": 0 }]
            }""";

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    /** Matches marketplace.fulfilment.collect-code-max-attempts in the test profile. */
    private static final int MAX_ATTEMPTS = 3;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    private UUID merchantId;
    private String merchantToken;
    private String customerToken;

    @BeforeEach
    void mintTokens() {
        merchantId = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        customerToken = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);
    }

    @Test
    @DisplayName("order for Gogo -> pay -> mint code -> seller redeems -> money released on the spot")
    void theWholeGiftJourney() throws Exception {
        String listingId = publishListing();

        // --- The diaspora buyer orders for someone at home ------------------
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "gift-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"listingId":"%s","quantity":1}],
                                 "deliveryMethod":"COLLECTION",
                                 "recipient":{"name":"Gogo Chipo Moyo",
                                              "msisdn":"0772345678",
                                              "message":"Happy birthday Gogo, love from Tari"}}"""
                                .formatted(listingId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.recipient.name").value("Gogo Chipo Moyo"))
                // Normalised by the SAME rule as the payer's own number.
                .andExpect(jsonPath("$.data.recipient.msisdn").value("+263772345678"))
                .andExpect(jsonPath("$.data.recipient.message")
                        .value("Happy birthday Gogo, love from Tari"))
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");

        confirmPayment(orderRef, "gift-1");

        String paid = mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipient.name").value("Gogo Chipo Moyo"))
                // No code exists until somebody asks for one — minting on every
                // paid order would spend an SMS on parcels nobody collects.
                .andExpect(jsonPath("$.data.fulfilments[0].collectCodeIssuedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String fulfilmentId = JsonPath.read(paid, "$.data.fulfilments[0].id");

        // --- The seller sees WHO is coming, and no code ---------------------
        mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].collectorName").value("Gogo Chipo Moyo"))
                .andExpect(jsonPath("$.data.items[0].collectCodeIssued").value(false));

        // --- The buyer mints the code and forwards it -----------------------
        String minted = mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").isNotEmpty())
                .andExpect(jsonPath("$.data.groupedCode").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(minted, "$.data.code");
        String groupedCode = JsonPath.read(minted, "$.data.groupedCode");

        // The code is a credential: the parcel keeps only its hash, so no read
        // surface can hand it back — not the buyer's order, and above all not
        // the seller's queue.
        String sellerQueue = mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].collectCodeIssued").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(sellerQueue).doesNotContain(code);
        String buyerOrder = mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.fulfilments[0].collectCodeIssuedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(buyerOrder).doesNotContain(code);

        // --- Gogo walks in and reads the code out ---------------------------
        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        // The grouped form, typed as she read it — and in lower
                        // case, because that is what people type.
                        .content("{\"code\":\"%s\"}".formatted(groupedCode.toLowerCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.deliveredBy").value("RECIPIENT"))
                .andExpect(jsonPath("$.data.collectCodeRedeemedAt").isNotEmpty())
                // The whole point for the seller: paid out on the spot rather
                // than after the self-close grace window.
                .andExpect(jsonPath("$.data.settlementStatus").value("RELEASABLE"));

        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("RELEASABLE"))
                .andExpect(jsonPath("$.data.items[0].releasedAt").isNotEmpty());

        // The buyer's own view records the handover, still without the code.
        mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.fulfilmentStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.data.fulfilments[0].deliveredBy").value("RECIPIENT"))
                .andExpect(jsonPath("$.data.fulfilments[0].collectCodeRedeemedAt").isNotEmpty());

        // Redeeming again is a double-tap on a closed parcel, not a new handover.
        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal_fulfilment_state"));
    }

    @Test
    @DisplayName("Wrong codes lock the parcel, and only the BUYER can unlock it with a fresh one")
    void guessingIsBudgetedAndTheBuyerHoldsTheReset() throws Exception {
        String fulfilmentId = paidCollectionParcel("gift-lock");
        String orderId = orderIdOf(fulfilmentId);
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", fulfilmentId)
                            .header("Authorization", "Bearer " + merchantToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"AAAA-BBBB-CCC" + attempt + "\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("collect_code_invalid"));
        }
        // The budget survived three rolled-back refusals — which is the whole
        // reason it is counted in its own transaction.
        assertThat(jdbc.queryForObject(
                "SELECT collect_code_attempts FROM order_fulfilment WHERE id = ?::uuid",
                Integer.class, fulfilmentId)).isEqualTo(MAX_ATTEMPTS);

        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AAAA-BBBB-CCCC\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("collect_code_locked"));

        // Only the buyer can mint, so only the buyer can refill the budget.
        String fresh = mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}"
                                .formatted(JsonPath.read(fresh, "$.data.code").toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveredBy").value("RECIPIENT"));
    }

    @Test
    @DisplayName("Nobody else can mint or redeem, and a delivery order has nothing to collect")
    void theCodeSurfacesAreScoped() throws Exception {
        String fulfilmentId = paidCollectionParcel("gift-scope");
        String orderId = orderIdOf(fulfilmentId);

        // Another buyer's parcel is the same 404 as one that does not exist.
        String stranger = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263779999999").sign(jwtSecret);
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("fulfilment_not_found"));

        // A seller cannot mint a code for a parcel they hold — that would let
        // them redeem their own handover and take the instant payout.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden());

        // And a buyer cannot redeem one.
        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", fulfilmentId)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AAAA-BBBB-CCCC\"}"))
                .andExpect(status().isForbidden());

        // Another seller's attempt is a 404, not a wasted attempt on a parcel
        // they should not know exists.
        String otherSeller = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", fulfilmentId)
                        .header("Authorization", "Bearer " + otherSeller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AAAA-BBBB-CCCC\"}"))
                .andExpect(status().isNotFound());

        // Nothing has been spent by any of that.
        assertThat(jdbc.queryForObject(
                "SELECT collect_code_attempts FROM order_fulfilment WHERE id = ?::uuid",
                Integer.class, fulfilmentId)).isZero();
    }

    @Test
    @DisplayName("A DELIVERY order has nothing to collect in person")
    void deliveryOrdersHaveNoHandoverCode() throws Exception {
        String listingId = publishListing();
        mockMvc.perform(post("/marketplace/addresses")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Gogo","recipientName":"Gogo Chipo Moyo",
                                 "recipientMsisdn":"0772345678",
                                 "line1":"14 Samora Machel Ave","city":"Harare"}"""))
                .andExpect(status().isCreated());
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "gift-delivery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"listingId":"%s","quantity":1}],
                                 "deliveryMethod":"DELIVERY",
                                 "recipient":{"name":"Gogo Chipo Moyo"}}""".formatted(listingId)))
                .andExpect(status().isCreated())
                // A recipient with no number is still a gift — nobody is
                // messaged, and the parcel knows whose it is.
                .andExpect(jsonPath("$.data.recipient.name").value("Gogo Chipo Moyo"))
                .andExpect(jsonPath("$.data.recipient.msisdn").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        confirmPayment(JsonPath.read(created, "$.data.orderRef"), "gift-delivery");

        String paid = mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andReturn().getResponse().getContentAsString();
        String fulfilmentId = JsonPath.read(paid, "$.data.fulfilments[0].id");

        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("collect_code_not_applicable"));

        // The seller of a delivery parcel is told who the courier hands to via
        // the destination block, not as a "collector".
        mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].collectorName").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].destination.recipientName")
                        .value("Gogo Chipo Moyo"));
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /** A paid COLLECTION order for a named recipient; returns its parcel id. */
    private String paidCollectionParcel(String key) throws Exception {
        String listingId = publishListing();
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"listingId":"%s","quantity":1}],
                                 "deliveryMethod":"COLLECTION",
                                 "recipient":{"name":"Gogo Chipo Moyo","msisdn":"0772345678"}}"""
                                .formatted(listingId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        confirmPayment(JsonPath.read(created, "$.data.orderRef"), key);
        String paid = mockMvc.perform(get("/marketplace/orders/{id}",
                        JsonPath.read(created, "$.data.id").toString())
                        .header("Authorization", "Bearer " + customerToken))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(paid, "$.data.fulfilments[0].id");
    }

    private String orderIdOf(String fulfilmentId) {
        return jdbc.queryForObject(
                "SELECT order_id FROM order_fulfilment WHERE id = ?::uuid",
                String.class, fulfilmentId);
    }

    private void confirmPayment(String orderRef, String paymentRef) throws Exception {
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-%s\",\"amountCents\":1550}"
                                .formatted(paymentRef)))
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
