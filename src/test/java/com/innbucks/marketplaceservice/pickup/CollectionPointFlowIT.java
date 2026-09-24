package com.innbucks.marketplaceservice.pickup;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Seller collection points (V18) end to end, over the real security chain and a
 * real Postgres: a seller says where buyers collect, the catalogue shows and
 * filters on it, checkout resolves a point per seller, the order SNAPSHOTS it,
 * and every later view reads that snapshot — so a seller moving or removing a
 * point never moves a collection already arranged.
 */
class CollectionPointFlowIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    private static final String AVONDALE = """
            {"name":"Avondale shop","townCode":"harare","line1":"14 Samora Machel Ave",
             "line2":"Shop 3, Avondale Shopping Centre","area":"Avondale",
             "landmark":"Next to the pharmacy","phone":"0242123456",
             "hoursNote":"Closed on public holidays","latitude":-17.7985,"longitude":31.0452,
             "openingHours":[
               {"day":"MONDAY","opens":"08:00","closes":"17:00"},
               {"day":"TUESDAY","opens":"08:00","closes":"17:00"},
               {"day":"WEDNESDAY","opens":"08:00","closes":"17:00"},
               {"day":"THURSDAY","opens":"08:00","closes":"17:00"},
               {"day":"FRIDAY","opens":"08:00","closes":"17:00"},
               {"day":"SATURDAY","opens":"08:00","closes":"13:00"}]}""";

    private static final String BULAWAYO_DEPOT = """
            {"name":"Bulawayo depot","townCode":"bulawayo","line1":"22 Fife St"}""";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

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

    // ------------------------------------------------------------------
    // The seller's own points
    // ------------------------------------------------------------------

    @Test
    @DisplayName("add -> first is the default -> add another -> switch default -> remove the "
            + "default promotes the survivor")
    void aSellerManagesTheirPoints() throws Exception {
        String first = JsonPath.read(addPoint(merchantToken, AVONDALE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.defaultPoint").value(true))
                .andExpect(jsonPath("$.data.townName").value("Harare"))
                .andExpect(jsonPath("$.data.phone").value("+263242123456"))
                .andExpect(jsonPath("$.data.latitude").value(-17.7985))
                .andExpect(jsonPath("$.data.openingHoursSummary")
                        .value("Mon-Fri 08:00-17:00, Sat 08:00-13:00"))
                .andExpect(jsonPath("$.data.openingHours", hasSize(6)))
                .andExpect(jsonPath("$.data.openNow").isBoolean())
                .andReturn().getResponse().getContentAsString(), "$.data.id");

        String second = JsonPath.read(addPoint(merchantToken, BULAWAYO_DEPOT)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.defaultPoint").value(false))
                // No hours given: say nothing, rather than "closed".
                .andExpect(jsonPath("$.data.openingHoursSummary").doesNotExist())
                .andExpect(jsonPath("$.data.openNow").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(get("/marketplace/sellers/me/collection-points")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(first));

        mockMvc.perform(put("/marketplace/sellers/me/collection-points/{id}/default", second)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultPoint").value(true));
        mockMvc.perform(get("/marketplace/sellers/me/collection-points")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data[0].id").value(second))
                .andExpect(jsonPath("$.data[1].defaultPoint").value(false));

        mockMvc.perform(delete("/marketplace/sellers/me/collection-points/{id}", second)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/sellers/me/collection-points")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(first))
                .andExpect(jsonPath("$.data[0].defaultPoint").value(true));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM seller_collection_point
                 WHERE merchant_id = ?::uuid AND is_default""", Integer.class, merchantId.toString()))
                .isEqualTo(1);

        // The audit trail names who and where, never the address typed.
        List<Map<String, Object>> audits = jdbc.queryForList("""
                SELECT event_type, metadata FROM audit_events
                 WHERE event_type LIKE 'COLLECTION_POINT_%' ORDER BY id""");
        assertThat(audits).extracting(r -> r.get("event_type")).containsExactly(
                "COLLECTION_POINT_CREATED", "COLLECTION_POINT_CREATED",
                "COLLECTION_POINT_DEFAULT_CHANGED", "COLLECTION_POINT_DELETED");
        assertThat(audits).allSatisfy(r -> assertThat(String.valueOf(r.get("metadata")))
                .doesNotContain("Samora").doesNotContain("Fife").contains("\"bySeller\":true"));
    }

    @Test
    @DisplayName("Replacing a point redefines it whole, hours included")
    void updateReplacesWhole() throws Exception {
        String id = JsonPath.read(addPoint(merchantToken, AVONDALE)
                .andReturn().getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(put("/marketplace/sellers/me/collection-points/{id}", id)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Avondale shop (upstairs)","townCode":"harare",
                                 "line1":"14 Samora Machel Ave",
                                 "openingHours":[{"day":"SUNDAY","opens":"10:00","closes":"12:00"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Avondale shop (upstairs)"))
                .andExpect(jsonPath("$.data.landmark").doesNotExist())
                .andExpect(jsonPath("$.data.latitude").doesNotExist())
                .andExpect(jsonPath("$.data.openingHoursSummary").value("Sun 10:00-12:00"))
                .andExpect(jsonPath("$.data.defaultPoint").value(true));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM seller_collection_point_hours WHERE point_id = ?::uuid",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("Refusals: unknown town, bad hours, half a pin, the cap, and another seller's point")
    void refusals() throws Exception {
        addPoint(merchantToken, """
                {"name":"X","townCode":"johannesburg","line1":"1 Main"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown_town"));
        addPoint(merchantToken, """
                {"name":"X","townCode":"harare","line1":"1 Main",
                 "openingHours":[{"day":"MONDAY","opens":"17:00","closes":"08:00"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_opening_hours"))
                .andExpect(jsonPath("$.message").value("Mon closes before it opens"));
        addPoint(merchantToken, """
                {"name":"X","townCode":"harare","line1":"1 Main",
                 "openingHours":[{"day":"MONDAY","opens":"8am","closes":"17:00"}]}""")
                .andExpect(status().isBadRequest());
        addPoint(merchantToken, """
                {"name":"X","townCode":"harare","line1":"1 Main","latitude":-17.8}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("pin_incomplete"));
        addPoint(merchantToken, """
                {"name":"X","townCode":"harare","line1":"1 Main","latitude":0,"longitude":0}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("location_out_of_bounds"));

        for (int i = 0; i < CollectionPointService.MAX_POINTS_PER_SELLER; i++) {
            addPoint(merchantToken, """
                    {"name":"Counter %d","townCode":"harare","line1":"%d Main"}""".formatted(i, i))
                    .andExpect(status().isCreated());
        }
        addPoint(merchantToken, BULAWAYO_DEPOT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("collection_point_limit_reached"));
        String mine = JsonPath.read(mockMvc.perform(get("/marketplace/sellers/me/collection-points")
                        .header("Authorization", "Bearer " + merchantToken))
                .andReturn().getResponse().getContentAsString(), "$.data[0].id");

        // Another seller: the same 404 as a point that does not exist.
        String otherSeller = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(put("/marketplace/sellers/me/collection-points/{id}", mine)
                        .header("Authorization", "Bearer " + otherSeller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BULAWAYO_DEPOT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("collection_point_not_found"));
        mockMvc.perform(delete("/marketplace/sellers/me/collection-points/{id}", mine)
                        .header("Authorization", "Bearer " + otherSeller))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/marketplace/sellers/me/collection-points/{id}", "not-a-uuid")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_collection_point_id"));
    }

    @Test
    @DisplayName("Only a seller manages their own points; an operator uses the admin override")
    void whoMayManagePoints() throws Exception {
        mockMvc.perform(get("/marketplace/sellers/me/collection-points"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/marketplace/sellers/me/collection-points")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/marketplace/admin/sellers/{m}/collection-points", merchantId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/marketplace/admin/sellers/{m}/collection-points", merchantId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(AVONDALE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.defaultPoint").value(true));
        // The seller sees what the operator set up for them.
        mockMvc.perform(get("/marketplace/sellers/me/collection-points")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data[0].name").value("Avondale shop"));
        assertThat(jdbc.queryForObject("""
                SELECT metadata FROM audit_events WHERE event_type = 'COLLECTION_POINT_CREATED'""",
                String.class)).contains("\"bySeller\":false");
    }

    // ------------------------------------------------------------------
    // The catalogue
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The public profile lists points; cards carry collection towns; an unknown "
            + "merchant is an empty list, never a 404")
    void catalogueShowsPoints() throws Exception {
        String listingId = publishListing(merchantToken, "[]");
        addPoint(merchantToken, AVONDALE).andExpect(status().isCreated());
        addPoint(merchantToken, BULAWAYO_DEPOT).andExpect(status().isCreated());
        addPoint(merchantToken, """
                {"name":"Second Harare counter","townCode":"harare","line1":"5 Julius Nyerere Way"}""")
                .andExpect(status().isCreated());

        mockMvc.perform(get("/marketplace/catalog/merchants/{id}", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionPoints", hasSize(3)))
                .andExpect(jsonPath("$.data.collectionPoints[0].name").value("Avondale shop"))
                .andExpect(jsonPath("$.data.collectionPoints[0].defaultPoint").value(true));
        mockMvc.perform(get("/marketplace/catalog/merchants/{id}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionPoints", hasSize(0)));

        // Distinct towns, in the town list's order (Harare before Bulawayo).
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(jsonPath("$.data.deliverable").value(false))
                .andExpect(jsonPath("$.data.collectionTowns", hasSize(2)))
                .andExpect(jsonPath("$.data.collectionTowns[0].townCode").value("harare"))
                .andExpect(jsonPath("$.data.collectionTowns[1].townName").value("Bulawayo"));
    }

    @Test
    @DisplayName("collectsIn / availableIn / deliversTo against real SQL: EXISTS, never a join")
    void browseByTown() throws Exception {
        // A delivers to Harare and has no points.
        String sellerA = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        String a = publishListing(sellerA, "[{\"townCode\":\"harare\",\"feeCents\":300}]");
        // B does not deliver and can be collected in Bulawayo — at TWO counters,
        // which must not make it appear twice.
        String sellerB = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        String b = publishListing(sellerB, "[]");
        addPoint(sellerB, BULAWAYO_DEPOT).andExpect(status().isCreated());
        addPoint(sellerB, """
                {"name":"Bulawayo second","townCode":"bulawayo","line1":"9 Main St"}""")
                .andExpect(status().isCreated());
        // C both delivers to Bulawayo and can be collected there.
        String sellerC = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        String c = publishListing(sellerC, "[{\"townCode\":\"bulawayo\",\"feeCents\":500}]");
        addPoint(sellerC, BULAWAYO_DEPOT).andExpect(status().isCreated());

        browse("collectsIn", "Bulawayo")
                .andExpect(jsonPath("$.data.totalItems").value(2))
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(b, c)));
        browse("availableIn", "bulawayo")
                .andExpect(jsonPath("$.data.totalItems").value(2))
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(b, c)));
        browse("deliversTo", "bulawayo")
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(c));
        browse("availableIn", "harare")
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(a));
        browse("collectsIn", "mutare")
                .andExpect(jsonPath("$.data.totalItems").value(0));
        browse("availableIn", "atlantis")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown_town"));
        browse("collectIn", "harare")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown_parameter"));
    }

    // ------------------------------------------------------------------
    // Checkout -> order -> parcel
    // ------------------------------------------------------------------

    @Test
    @DisplayName("quote -> choose a point -> order snapshots it -> pay -> every view shows it -> "
            + "the seller moves and removes the point -> the order still says where to go")
    void theCollectionJourney() throws Exception {
        String listingId = publishListing(merchantToken, "[]");
        String avondale = JsonPath.read(addPoint(merchantToken, AVONDALE)
                .andReturn().getResponse().getContentAsString(), "$.data.id");
        String depot = JsonPath.read(addPoint(merchantToken, BULAWAYO_DEPOT)
                .andReturn().getResponse().getContentAsString(), "$.data.id");

        // Unchosen: the seller's default.
        quote("""
                {"items":[{"listingId":"%s","quantity":1}],"deliveryMethod":"COLLECTION"}"""
                .formatted(listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionPoints", hasSize(1)))
                .andExpect(jsonPath("$.data.collectionPoints[0].merchantId")
                        .value(merchantId.toString()))
                .andExpect(jsonPath("$.data.collectionPoints[0].collectionPoint.id").value(avondale));
        // Delivery quotes carry no collection points at all.
        mockMvc.perform(post("/marketplace/checkout/quote")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"listingId":"%s","quantity":1}],"deliveryMethod":"DELIVERY",
                                 "deliveryAddressId":"%s"}""".formatted(listingId,
                                saveAddress("harare"))))
                .andExpect(jsonPath("$.data.collectionPoints").doesNotExist());
        // Somebody else's point is not one of this seller's.
        String otherSeller = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        String foreign = JsonPath.read(addPoint(otherSeller, AVONDALE)
                .andReturn().getResponse().getContentAsString(), "$.data.id");
        quote(collectionBody(listingId, foreign))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown_collection_point"));

        String body = collectionBody(listingId, depot);
        quote(body)
                .andExpect(jsonPath("$.data.collectionPoints[0].collectionPoint.name")
                        .value("Bulawayo depot"));
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "collection-journey-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.collectionPoints[0].collectionPoint.id").value(depot))
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");
        assertThat(jdbc.queryForObject("""
                SELECT name FROM market_order_collection_point
                 WHERE order_id = ?::uuid AND merchant_id = ?::uuid""",
                String.class, orderId, merchantId.toString())).isEqualTo("Bulawayo depot");

        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-CP-1\",\"amountCents\":1550}"))
                .andExpect(status().isOk());

        String paid = mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.collectionPoints[0].collectionPoint.name")
                        .value("Bulawayo depot"))
                .andExpect(jsonPath("$.data.fulfilments[0].collectionPoint.line1").value("22 Fife St"))
                .andReturn().getResponse().getContentAsString();
        String fulfilmentId = JsonPath.read(paid, "$.data.fulfilments[0].id");

        // The seller's card says which counter to have the parcel at.
        mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].collectionPoint.name").value("Bulawayo depot"));
        mockMvc.perform(get("/marketplace/orders/{id}/fulfilments/{fid}/tracking",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.collectionPoint.townName").value("Bulawayo"));
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionPoint.name").value("Bulawayo depot"));

        // The seller renames and MOVES the point, and gives it hours: the
        // address on the order does not move, the hours are read live.
        mockMvc.perform(put("/marketplace/sellers/me/collection-points/{id}", depot)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"New depot","townCode":"mutare","line1":"1 Elsewhere Rd",
                                 "openingHours":[{"day":"MONDAY","opens":"09:00","closes":"10:00"}]}"""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.fulfilments[0].collectionPoint.name").value("Bulawayo depot"))
                .andExpect(jsonPath("$.data.fulfilments[0].collectionPoint.townCode").value("bulawayo"))
                .andExpect(jsonPath("$.data.fulfilments[0].collectionPoint.openingHoursSummary")
                        .value("Mon 09:00-10:00"));

        // Removed altogether: still there on the order, just without hours.
        mockMvc.perform(delete("/marketplace/sellers/me/collection-points/{id}", depot)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.fulfilments[0].collectionPoint.name").value("Bulawayo depot"))
                .andExpect(jsonPath("$.data.fulfilments[0].collectionPoint.openingHoursSummary")
                        .doesNotExist());
        mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].collectionPoint.line1").value("22 Fife St"));
    }

    @Test
    @DisplayName("A seller with no points still sells for collection: arranged directly, as before")
    void noPointsNeverBlocksASale() throws Exception {
        String listingId = publishListing(merchantToken, "[]");

        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "collection-no-points-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"listingId":"%s","quantity":1}],"deliveryMethod":"COLLECTION"}"""
                                .formatted(listingId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.collectionPoints[0].merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.data.collectionPoints[0].collectionPoint").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM market_order_collection_point WHERE order_id = ?::uuid",
                Integer.class, JsonPath.<String>read(created, "$.data.id"))).isZero();
    }

    // ------------------------------------------------------------------

    private ResultActions addPoint(String token, String body) throws Exception {
        return mockMvc.perform(post("/marketplace/sellers/me/collection-points")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions browse(String param, String value) throws Exception {
        return mockMvc.perform(get("/marketplace/catalog").param(param, value));
    }

    private ResultActions quote(String body) throws Exception {
        return mockMvc.perform(post("/marketplace/checkout/quote")
                .header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String collectionBody(String listingId, String pointId) {
        return """
                {"items":[{"listingId":"%s","quantity":1}],"deliveryMethod":"COLLECTION",
                 "collectionPoints":[{"merchantId":"%s","collectionPointId":"%s"}]}"""
                .formatted(listingId, merchantId, pointId);
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
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(saved, "$.data.id");
    }

    private String publishListing(String token, String deliveryTowns) throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + token)
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
