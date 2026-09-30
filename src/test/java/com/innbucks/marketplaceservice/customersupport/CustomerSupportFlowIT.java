package com.innbucks.marketplaceservice.customersupport;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The call center's marketplace surface end to end, over the real security
 * chain and a real Postgres: it is reachable ONLY through the support
 * permissions (never a role), every search and view leaves a row in the
 * activity log, the views show what an agent needs and mask what they must
 * not see, and notes cannot be rewritten.
 */
class CustomerSupportFlowIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};
    private static final String BUYER_PHONE = "+263771234567";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    private UUID merchantId;
    private UUID buyerUuid;
    private UUID agentUuid;
    private String merchantToken;
    private String customerToken;
    private String agentToken;
    private String readOnlyToken;
    private String supervisorToken;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        buyerUuid = UUID.randomUUID();
        agentUuid = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        customerToken = TestJwts.forUser(buyerUuid).role("CUSTOMER").phoneNumber(BUYER_PHONE).sign(jwtSecret);
        agentToken = TestJwts.forUser(agentUuid).loginIdentifier("tariro.moyo@innbucks.co.zw")
                .role("CALL_CENTER_AGENT")
                .permissions(SupportPermissions.READ, SupportPermissions.MANAGE, SupportPermissions.MESSAGES_SEND)
                .sign(jwtSecret);
        readOnlyToken = TestJwts.forUser(UUID.randomUUID()).loginIdentifier("viewer@innbucks.co.zw")
                .permissions(SupportPermissions.READ).sign(jwtSecret);
        supervisorToken = TestJwts.forUser(UUID.randomUUID()).loginIdentifier("chipo.supervisor@innbucks.co.zw")
                .role("CALL_CENTER_SUPERVISOR")
                .permissions(SupportPermissions.READ, SupportPermissions.MANAGE, SupportPermissions.SUPERVISE)
                .sign(jwtSecret);
    }

    @Test
    @DisplayName("support is reached through the permissions alone: SUPER_ADMIN's role without them is refused")
    void gatedOnPermissionsNotRoles() throws Exception {
        String superAdminRoleOnly = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);

        search(null, "MKT-0000").andExpect(status().isUnauthorized());
        search(customerToken, "MKT-0000").andExpect(status().isForbidden());
        search(superAdminRoleOnly, "MKT-0000").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        search(agentToken, "MKT-0000").andExpect(status().isOk());
        // Each tier gates its own endpoints.
        mockMvc.perform(post("/marketplace/support/notes").header("Authorization", "Bearer " + readOnlyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectKind\":\"BUYER\",\"subjectId\":\"%s\",\"body\":\"x\"}".formatted(buyerUuid)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/marketplace/support/activity").header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/marketplace/support/activity").header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a role-shaped entry in perms is dropped: it neither holds the role nor any support permission")
    void aRoleInThePermsClaimIsNothing() throws Exception {
        String forged = TestJwts.forUser(UUID.randomUUID())
                .permissions("ROLE_SUPER_ADMIN", "SUPER_ADMIN").sign(jwtSecret);

        mockMvc.perform(get("/marketplace/reports").header("Authorization", "Bearer " + forged))
                .andExpect(status().isForbidden());
        search(forged, "MKT-0000").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the search reads the query by shape, and every search is logged - a phone masked")
    void searchFindsByEveryShape() throws Exception {
        Map<String, String> order = placePaidOrder(publishListing("Wireless Earbuds"));
        String orderRef = order.get("orderRef");

        search(agentToken, "0771 234 567").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queryKind").value("PHONE"))
                .andExpect(jsonPath("$.data.buyers[0].buyerUuid").value(buyerUuid.toString()))
                .andExpect(jsonPath("$.data.buyers[0].matchedAs[0]").value("BUYER_PHONE"))
                .andExpect(jsonPath("$.data.orders[0].orderRef").value(orderRef));

        // Lower case and no dash: forgiven like the seller's own search box.
        search(agentToken, orderRef.toLowerCase().replace("-", "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queryKind").value("ORDER_REF"))
                .andExpect(jsonPath("$.data.orders[0].matchedAs[0]").value("ORDER_REF"))
                .andExpect(jsonPath("$.data.sellers[0].merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.data.sellers[0].matchedAs[0]").value("ORDER_SELLER"));

        String tracking = jdbc.queryForObject(
                "SELECT tracking_code FROM order_fulfilment WHERE order_id = ?::uuid", String.class, order.get("orderId"));
        search(agentToken, tracking).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queryKind").value("TRACKING_CODE"))
                .andExpect(jsonPath("$.data.orders[0].orderRef").value(orderRef));

        search(agentToken, buyerUuid.toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queryKind").value("ID"))
                .andExpect(jsonPath("$.data.buyers[0].matchedAs[0]").value("BUYER_ID"));
        search(agentToken, merchantId.toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellers[0].matchedAs[0]").value("SELLER_ID"));

        search(agentToken, "nobody by this name").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queryKind").value("NAME"))
                .andExpect(jsonPath("$.data.orders").isEmpty());
        search(agentToken, "TRK-!!!").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_search"));

        List<String> details = jdbc.queryForList(
                "SELECT detail FROM support_activity WHERE action = 'SEARCH' AND agent_uuid = ? ORDER BY created_at",
                String.class, agentUuid.toString());
        assertThat(details).hasSize(6);
        assertThat(details.getFirst()).contains("\"queryKind\":\"PHONE\"").contains("****4567")
                .doesNotContain("771234567");
        // A name is never logged.
        assertThat(details.getLast()).contains("\"queryKind\":\"NAME\"").doesNotContain("nobody");
        assertThat(jdbc.queryForObject("SELECT DISTINCT agent_login FROM support_activity WHERE agent_uuid = ?",
                String.class, agentUuid.toString())).isEqualTo("tariro.moyo@innbucks.co.zw");
    }

    @Test
    @DisplayName("the buyer 360 shows the whole customer, and a view is logged only once the buyer is known")
    void buyerProfile() throws Exception {
        Map<String, String> order = placePaidOrder(publishListing("Wireless Earbuds"));

        mockMvc.perform(get("/marketplace/support/buyers/{id}", buyerUuid)
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phones[0].msisdn").value(BUYER_PHONE))
                .andExpect(jsonPath("$.data.orders.total").value(1))
                .andExpect(jsonPath("$.data.recentOrders[0].orderRef").value(order.get("orderRef")))
                .andExpect(jsonPath("$.data.openParcels[0].status").value("PREPARING"))
                .andExpect(jsonPath("$.data.openParcels[0].settlementStatus").value("HELD"))
                .andExpect(jsonPath("$.data.engagement.basketLines").value(0))
                .andExpect(jsonPath("$.data.notes.total").value(0));

        mockMvc.perform(get("/marketplace/support/buyers/{id}/orders", buyerUuid)
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].orderRef").value(order.get("orderRef")))
                .andExpect(jsonPath("$.data.totalItems").value(1));

        UUID stranger = UUID.randomUUID();
        mockMvc.perform(get("/marketplace/support/buyers/{id}", stranger)
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("buyer_not_found"));

        assertThat(actions()).containsExactly("VIEW_BUYER", "VIEW_BUYER_ORDERS");
    }

    @Test
    @DisplayName("an order opens by ref or id: the buyer's view, each parcel, the money and the journal")
    void orderDetail() throws Exception {
        Map<String, String> order = placePaidOrder(publishListing("Wireless Earbuds"));

        for (String key : List.of(order.get("orderRef"), order.get("orderId"))) {
            mockMvc.perform(get("/marketplace/support/orders/{key}", key)
                            .header("Authorization", "Bearer " + agentToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.order.orderRef").value(order.get("orderRef")))
                    .andExpect(jsonPath("$.data.buyer.buyerUuid").value(buyerUuid.toString()))
                    .andExpect(jsonPath("$.data.buyer.msisdn").value(BUYER_PHONE))
                    .andExpect(jsonPath("$.data.sellers[0].merchantId").value(merchantId.toString()))
                    .andExpect(jsonPath("$.data.parcels[0].id").value(order.get("fulfilmentId")))
                    .andExpect(jsonPath("$.data.settlements[0].status").value("HELD"))
                    .andExpect(jsonPath("$.data.timeline[*].toStatus", hasItem("PAID")));
        }
        mockMvc.perform(get("/marketplace/support/orders/{key}", "not-an-order")
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_order_key"));
        mockMvc.perform(get("/marketplace/support/orders/{key}", "MKT-000000000000")
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("order_not_found"));

        assertThat(actions()).containsExactly("VIEW_ORDER", "VIEW_ORDER");
    }

    @Test
    @DisplayName("the seller 360 masks the payout destination and matches the seller's own figures")
    void sellerProfile() throws Exception {
        placePaidOrder(publishListing("Wireless Earbuds"));
        mockMvc.perform(put("/marketplace/sellers/me/payout-destination")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"MOBILE_MONEY","accountName":"R. Chikwanha","msisdn":"+263774954521"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/marketplace/support/sellers/{id}", merchantId)
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payout.configured").value(true))
                .andExpect(jsonPath("$.data.payout.method").value("MOBILE_MONEY"))
                .andExpect(jsonPath("$.data.payout.accountName").value("R. Chikwanha"))
                .andExpect(jsonPath("$.data.payout.destination").value("****4521"))
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .doesNotContain("774954521"))
                .andExpect(jsonPath("$.data.listings.ACTIVE").value(1))
                .andExpect(jsonPath("$.data.fulfilment.awaitingDispatch").value(1))
                .andExpect(jsonPath("$.data.money.totals[0].status").value("HELD"))
                .andExpect(jsonPath("$.data.collectionEnabled").value(true));

        mockMvc.perform(get("/marketplace/support/sellers/{id}/parcels", merchantId)
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1));
        mockMvc.perform(get("/marketplace/support/sellers/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("seller_not_found"));
    }

    @Test
    @DisplayName("notes are append-only: added, listed newest first, and never rewritable, even in SQL")
    void notesAreAppendOnly() throws Exception {
        Map<String, String> order = placePaidOrder(publishListing("Wireless Earbuds"));

        String added = note("ORDER", order.get("orderId"), "<b>Courier</b> missed the buyer; re-attempt tomorrow.")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.data.body").value("Courier missed the buyer; re-attempt tomorrow."))
                .andExpect(jsonPath("$.data.createdBy.login").value("tariro.moyo@innbucks.co.zw"))
                .andReturn().getResponse().getContentAsString();
        note("ORDER", order.get("orderId"), "Buyer confirmed the new time.").andExpect(status().isCreated());

        mockMvc.perform(get("/marketplace/support/notes")
                        .param("subjectKind", "ORDER").param("subjectId", order.get("orderId"))
                        .header("Authorization", "Bearer " + readOnlyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2))
                .andExpect(jsonPath("$.data.items[0].body").value("Buyer confirmed the new time."));

        note("ORDER", order.get("orderId"), "<i></i>").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("note_required"));
        note("ORDER", UUID.randomUUID().toString(), "About nothing").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("order_not_found"));

        String noteId = JsonPath.read(added, "$.data.id");
        assertThatThrownBy(() -> jdbc.update("UPDATE support_note SET body = 'rewritten' WHERE id = ?::uuid", noteId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM support_note WHERE id = ?::uuid", noteId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM support_activity"))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("supervisors read the activity log, filtered by agent, with ids and enums only")
    void activityFeed() throws Exception {
        Map<String, String> order = placePaidOrder(publishListing("Wireless Earbuds"));
        search(agentToken, BUYER_PHONE).andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/support/orders/{key}", order.get("orderRef"))
                        .header("Authorization", "Bearer " + agentToken)).andExpect(status().isOk());
        note("ORDER", order.get("orderId"), "Called back.").andExpect(status().isCreated());
        search(supervisorToken, "MKT-0000").andExpect(status().isOk());

        mockMvc.perform(get("/marketplace/support/activity").param("agentUuid", agentUuid.toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(3))
                .andExpect(jsonPath("$.data.items[0].action").value("NOTE_ADDED"))
                .andExpect(jsonPath("$.data.items[1].action").value("VIEW_ORDER"))
                .andExpect(jsonPath("$.data.items[1].detail.orderRef").value(order.get("orderRef")))
                .andExpect(jsonPath("$.data.items[2].action").value("SEARCH"))
                .andExpect(jsonPath("$.data.items[2].detail.query").value("****4567"))
                .andExpect(jsonPath("$.data.items[*].agent.login", not(hasItem("chipo.supervisor@innbucks.co.zw"))))
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .doesNotContain("Called back").doesNotContain("771234567"));

        mockMvc.perform(get("/marketplace/support/activity").param("action", "SEARCH")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2));
        mockMvc.perform(get("/marketplace/support/activity").param("subjectKind", "NOPE")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"))
                .andExpect(jsonPath("$.message", containsString("subjectKind")));
    }

    // ------------------------------------------------------------------

    private List<String> actions() {
        return jdbc.queryForList("SELECT action FROM support_activity WHERE agent_uuid = ? ORDER BY created_at",
                String.class, agentUuid.toString());
    }

    private ResultActions search(String token, String query) throws Exception {
        var request = post("/marketplace/support/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\"}".formatted(query));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    private ResultActions note(String kind, String subjectId, String body) throws Exception {
        return mockMvc.perform(post("/marketplace/support/notes")
                .header("Authorization", "Bearer " + agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectKind\":\"%s\",\"subjectId\":\"%s\",\"body\":\"%s\"}"
                        .formatted(kind, subjectId, body)));
    }

    private Map<String, String> placePaidOrder(String listingId) throws Exception {
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"listingId\":\"%s\",\"quantity\":1}]}".formatted(listingId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-%s\",\"amountCents\":1550}".formatted(orderRef)))
                .andExpect(status().isOk());
        String fulfilmentId = jdbc.queryForObject(
                "SELECT id::text FROM order_fulfilment WHERE order_id = ?::uuid", String.class, orderId);
        return Map.of("orderId", orderId, "orderRef", orderRef, "fulfilmentId", fulfilmentId);
    }

    private String publishListing(String title) throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","description":"Portable and sturdy","categoryCode":"electronics",
                                 "priceCents":1550,"stockQty":10}""".formatted(title)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.data.id");
        mockMvc.perform(multipart(HttpMethod.POST, "/marketplace/listings/{id}/image", listingId)
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
